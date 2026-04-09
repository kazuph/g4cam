package com.kazuph.g4cam.ai

import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import com.kazuph.g4cam.model.LocalModelSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

private const val LIQUID_TAG = "LiquidOnnxInference"
private const val PATCH_SIZE = 16
private const val DOWNSAMPLE_FACTOR = 2
private const val MAX_IMAGE_TOKENS = 256
private const val MIN_IMAGE_TOKENS = 64
private const val MAX_PATCHES = MAX_IMAGE_TOKENS * DOWNSAMPLE_FACTOR * DOWNSAMPLE_FACTOR
private const val PATCH_VECTOR_SIZE = PATCH_SIZE * PATCH_SIZE * 3
private const val SYSTEM_PROMPT = "You are a helpful multimodal assistant by Liquid AI."

enum class OnnxExecutionProvider(val displayName: String) {
    CPU("CPU"),
    XNNPACK("XNNPACK"),
    NNAPI("NNAPI"),
}

class LiquidOnnxInference {
    private var env: OrtEnvironment? = null
    private var embedSession: OrtSession? = null
    private var visionSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: BytePairTokenizer? = null
    private var initializedRoot: String? = null
    private var activeProvider: OnnxExecutionProvider? = null

    suspend fun initialize(
        modelRoot: File,
        provider: OnnxExecutionProvider,
    ): ModelStatus =
        withContext(Dispatchers.IO) {
            try {
                val spec = LocalModelSpecs.liquidOnnx
                val embedFile = File(modelRoot, "onnx/embed_tokens.onnx")
                val visionFile = File(modelRoot, "onnx/vision_encoder_q4.onnx")
                val decoderFile = File(modelRoot, "onnx/decoder_model_merged_q4.onnx")
                val tokenizerFile = File(modelRoot, "tokenizer.json")

                val requiredFiles = listOf(embedFile, visionFile, decoderFile, tokenizerFile)
                if (requiredFiles.any { !it.exists() }) {
                    return@withContext ModelStatus.NeedsFallbackDownload("${spec.title} のファイルが不足しています\n再ダウンロードが必要です")
                }

                if (initializedRoot == modelRoot.absolutePath &&
                    env != null &&
                    embedSession != null &&
                    visionSession != null &&
                    decoderSession != null &&
                    tokenizer != null &&
                    activeProvider == provider
                ) {
                    return@withContext ModelStatus.Ready(InferenceBackend.LIQUID_ONNX, hardwareBackend = provider.displayName)
                }

                release()
                val environment = OrtEnvironment.getEnvironment()
                val availableProviders = OrtEnvironment.getAvailableProviders()
                Log.i(LIQUID_TAG, "Available ORT providers: ${availableProviders.joinToString()}")
                env = environment
                tokenizer = BytePairTokenizer.fromFile(tokenizerFile)
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
                    options.setInterOpNumThreads(1)
                    when (provider) {
                        OnnxExecutionProvider.CPU -> Unit
                        OnnxExecutionProvider.XNNPACK -> {
                            if (!availableProviders.contains(OrtProvider.XNNPACK)) {
                                return@withContext ModelStatus.Unavailable("XNNPACK が利用できません")
                            }
                            options.addXnnpack(emptyMap())
                        }
                        OnnxExecutionProvider.NNAPI -> {
                            if (!availableProviders.contains(OrtProvider.NNAPI)) {
                                return@withContext ModelStatus.Unavailable("NNAPI が利用できません")
                            }
                            options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                        }
                    }
                    embedSession = environment.createSession(embedFile.absolutePath, options)
                    visionSession = environment.createSession(visionFile.absolutePath, options)
                    decoderSession = environment.createSession(decoderFile.absolutePath, options)
                }
                initializedRoot = modelRoot.absolutePath
                activeProvider = provider
                Log.i(LIQUID_TAG, "Liquid ONNX sessions initialized at ${modelRoot.absolutePath} with ${provider.displayName}")
                ModelStatus.Ready(InferenceBackend.LIQUID_ONNX, hardwareBackend = provider.displayName)
            } catch (e: Exception) {
                Log.e(LIQUID_TAG, "Liquid ONNX init failed", e)
                ModelStatus.Unavailable("Liquid ONNX 初期化失敗: ${e.message}")
            }
        }

    fun analyze(bitmap: Bitmap, prompt: String): InferenceState {
        val localEnv = env ?: return InferenceState.Error("ONNX Runtime が未初期化です")
        val localEmbedSession = embedSession ?: return InferenceState.Error("埋め込みモデルが未初期化です")
        val localVisionSession = visionSession ?: return InferenceState.Error("Vision モデルが未初期化です")
        val localDecoderSession = decoderSession ?: return InferenceState.Error("Decoder モデルが未初期化です")
        val localTokenizer = tokenizer ?: return InferenceState.Error("Tokenizer が未初期化です")

        return try {
            val imageInputs = preprocess(bitmap)
            val promptText = buildPrompt(prompt, imageInputs.imageTokenCount)
            val inputIds = localTokenizer.encode(promptText)
            val tokenEmbeddings = runEmbed(localEnv, localEmbedSession, inputIds)
            val imageEmbeddings =
                runVision(
                    localEnv = localEnv,
                    session = localVisionSession,
                    pixelValues = imageInputs.pixelValues,
                    pixelAttentionMask = imageInputs.pixelAttentionMask,
                    spatialShapes = imageInputs.spatialShapes,
                )
            val mergedEmbeddings = mergeImageEmbeddings(tokenEmbeddings, inputIds, imageEmbeddings, localTokenizer.imageTokenId)
            val generated = runDecoder(localEnv, localDecoderSession, localTokenizer, mergedEmbeddings, maxNewTokens = 96)
            val text = localTokenizer.decode(generated.toIntArray()).trim()
            InferenceState.Done(text.ifEmpty { "結果が空でした" })
        } catch (e: Exception) {
            Log.e(LIQUID_TAG, "Liquid ONNX inference failed", e)
            InferenceState.Error("Liquid ONNX 推論失敗: ${e.message}")
        }
    }

    fun release() {
        try {
            embedSession?.close()
        } catch (_: Exception) {
        }
        try {
            visionSession?.close()
        } catch (_: Exception) {
        }
        try {
            decoderSession?.close()
        } catch (_: Exception) {
        }
        embedSession = null
        visionSession = null
        decoderSession = null
        tokenizer = null
        initializedRoot = null
        activeProvider = null
    }

    private fun buildPrompt(prompt: String, imageTokenCount: Int): String {
        val imageTokens = "<image>".repeat(imageTokenCount)
        return buildString {
            append("<|startoftext|>")
            append("<|im_start|>system\n")
            append(SYSTEM_PROMPT)
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append("<|image_start|>")
            append(imageTokens)
            append("<|image_end|>")
            append(prompt)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun runEmbed(localEnv: OrtEnvironment, session: OrtSession, inputIds: IntArray): FloatArray {
        val shape = longArrayOf(1, inputIds.size.toLong())
        val idsTensor = createLongTensor(localEnv, inputIds.map(Int::toLong).toLongArray(), shape)
        idsTensor.use {
            session.run(mapOf("input_ids" to idsTensor)).use { result ->
                return extractFloatData(result.get(0))
            }
        }
    }

    private fun runVision(
        localEnv: OrtEnvironment,
        session: OrtSession,
        pixelValues: FloatArray,
        pixelAttentionMask: LongArray,
        spatialShapes: LongArray,
    ): FloatArray {
        val pixelValuesTensor = createFloatTensor(localEnv, pixelValues, longArrayOf(1, MAX_PATCHES.toLong(), PATCH_VECTOR_SIZE.toLong()))
        val attentionMaskTensor = createLongTensor(localEnv, pixelAttentionMask, longArrayOf(1, MAX_PATCHES.toLong()))
        val spatialShapesTensor = createLongTensor(localEnv, spatialShapes, longArrayOf(1, 2))
        pixelValuesTensor.use {
            attentionMaskTensor.use {
                spatialShapesTensor.use {
                    session.run(
                        mapOf(
                            "pixel_values" to pixelValuesTensor,
                            "pixel_attention_mask" to attentionMaskTensor,
                            "spatial_shapes" to spatialShapesTensor,
                        ),
                    ).use { result ->
                        return extractFloatData(result.get(0))
                    }
                }
            }
        }
    }

    private fun mergeImageEmbeddings(
        tokenEmbeddings: FloatArray,
        inputIds: IntArray,
        imageEmbeddings: FloatArray,
        imageTokenId: Int,
    ): FloatArray {
        val hiddenSize = tokenEmbeddings.size / inputIds.size
        val imageTokenPositions = inputIds.withIndex().filter { it.value == imageTokenId }.map { it.index }
        val expectedImageValues = imageTokenPositions.size * hiddenSize
        check(expectedImageValues == imageEmbeddings.size) {
            "画像埋め込み長が一致しません: tokens=${imageTokenPositions.size}, hidden=$hiddenSize, embeds=${imageEmbeddings.size}"
        }

        val merged = tokenEmbeddings.copyOf()
        imageTokenPositions.forEachIndexed { imageIndex, tokenIndex ->
            val tokenOffset = tokenIndex * hiddenSize
            val imageOffset = imageIndex * hiddenSize
            System.arraycopy(imageEmbeddings, imageOffset, merged, tokenOffset, hiddenSize)
        }
        return merged
    }

    private fun runDecoder(
        localEnv: OrtEnvironment,
        session: OrtSession,
        tokenizer: BytePairTokenizer,
        promptEmbeddings: FloatArray,
        maxNewTokens: Int,
    ): MutableList<Int> {
        val inputInfo = session.inputInfo
        val promptLength = inferSequenceLength(promptEmbeddings)
        val hiddenSize = (promptEmbeddings.size / promptLength).toLong()
        val generated = mutableListOf<Int>()
        val cache = initializeCache(inputInfo)
        var currentEmbeddings = promptEmbeddings
        var currentLength = promptLength

        repeat(maxNewTokens) { step ->
            val tensors = LinkedHashMap<String, OnnxTensor>()
            try {
                val embedLength = if (step == 0) promptLength else 1
                tensors["inputs_embeds"] =
                    createFloatTensor(localEnv, currentEmbeddings, longArrayOf(1, embedLength.toLong(), hiddenSize))
                tensors["attention_mask"] =
                    createLongTensor(localEnv, LongArray(currentLength) { 1L }, longArrayOf(1, currentLength.toLong()))

                if (inputInfo.containsKey("position_ids")) {
                    val positionStart = currentLength - if (step == 0) currentLength else 1
                    val positionIds =
                        LongArray(if (step == 0) currentLength else 1) { offset ->
                            (positionStart + offset).toLong()
                        }
                    tensors["position_ids"] = createLongTensor(localEnv, positionIds, longArrayOf(1, positionIds.size.toLong()))
                }

                cache.forEach { (name, value) ->
                    tensors[name] = createFloatTensor(localEnv, value.data, value.shape)
                }

                session.run(tensors).use { result ->
                    val logitsValue = result.get(0)
                    val nextToken = argmaxLastToken(logitsValue)
                    if (nextToken == tokenizer.eosTokenId) {
                        return generated
                    }
                    generated += nextToken
                    updateCache(result, cache)
                    currentEmbeddings = runEmbed(localEnv, embedSession!!, intArrayOf(nextToken))
                    currentLength += 1
                }
            } finally {
                tensors.values.forEach {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return generated
    }

    private fun initializeCache(inputInfo: Map<String, NodeInfo>): MutableMap<String, CacheTensor> {
        val cache = LinkedHashMap<String, CacheTensor>()
        inputInfo.forEach { (name, info) ->
            if (name == "inputs_embeds" || name == "attention_mask" || name == "position_ids") {
                return@forEach
            }
            val tensorInfo = info.info as? ai.onnxruntime.TensorInfo ?: return@forEach
            val shape =
                tensorInfo.shape.mapIndexed { index, dim ->
                    when {
                        dim >= 0 -> dim
                        index == 0 -> 1L
                        else -> 0L
                    }
                }.toLongArray()
            cache[name] = CacheTensor(FloatArray(shape.fold(1L) { acc, dim -> acc * max(1L, dim) }.toInt()), shape)
            if (shape.any { it == 0L }) {
                cache[name] = CacheTensor(FloatArray(0), shape)
            }
        }
        return cache
    }

    private fun updateCache(result: OrtSession.Result, cache: MutableMap<String, CacheTensor>) {
        result.forEachIndexed { index, entry ->
            if (index == 0) {
                return@forEachIndexed
            }
            val output = entry.value
            val outputName = entry.key
            val cacheName =
                outputName
                    .replace("present_conv", "past_conv")
                    .replace("present.", "past_key_values.")
            if (cacheName in cache) {
                cache[cacheName] = CacheTensor(extractFloatData(output), extractShape(output))
            }
        }
    }

    private fun extractShape(output: OnnxValue): LongArray {
        val info = output.info as? ai.onnxruntime.TensorInfo
        return info?.shape ?: inferShape(output.value)
    }

    private fun extractFloatData(output: OnnxValue): FloatArray {
        val tensor = output as? OnnxTensor
        if (tensor != null) {
            return tensor.floatBuffer.copyToArray()
        }
        val flattened = ArrayList<Float>()
        flattenFloatValues(output.value, flattened)
        return flattened.toFloatArray()
    }

    private fun inferShape(value: Any?): LongArray =
        when (value) {
            is FloatArray -> longArrayOf(value.size.toLong())
            is Array<*> -> {
                val childShape = inferShape(value.firstOrNull())
                longArrayOf(value.size.toLong(), *childShape)
            }
            else -> longArrayOf()
        }

    private fun flattenFloatValues(value: Any?, sink: MutableList<Float>) {
        when (value) {
            is FloatArray -> value.forEach(sink::add)
            is Array<*> -> value.forEach { flattenFloatValues(it, sink) }
            is Number -> sink += value.toFloat()
            null -> return
            else -> error("Unsupported ONNX output type: ${value::class.java.name}")
        }
    }

    private fun argmaxLastToken(logits: OnnxValue): Int {
        val shape = extractShape(logits)
        val vocabSize = shape.lastOrNull()?.toInt() ?: error("logits shape が取得できません")
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        val tensor = logits as? OnnxTensor
        if (tensor != null) {
            val buffer = tensor.floatBuffer.duplicate()
            buffer.position(buffer.limit() - vocabSize)
            repeat(vocabSize) { index ->
                val value = buffer.get()
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = index
                }
            }
            return bestIndex
        }

        val lastTokenValues = extractLastTokenValues(logits.value)
        require(lastTokenValues.size == vocabSize) {
            "logits の語彙次元が一致しません: expected=$vocabSize actual=${lastTokenValues.size}"
        }
        for (i in 0 until vocabSize) {
            val value = lastTokenValues[i]
            if (value > bestValue) {
                bestValue = value
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun extractLastTokenValues(value: Any?): FloatArray =
        when (value) {
            is FloatArray -> value
            is Array<*> -> extractLastTokenValues(value.lastOrNull())
            else -> error("Unsupported logits output type: ${value?.javaClass?.name}")
        }

    private fun inferSequenceLength(promptEmbeddings: FloatArray): Int {
        return promptEmbeddings.size / 1024
    }

    private fun preprocess(bitmap: Bitmap): LiquidImageInputs {
        val resized = smartResize(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, resized.width, resized.height, true)
        val patchesWidth = resized.width / PATCH_SIZE
        val patchesHeight = resized.height / PATCH_SIZE
        val patchCount = patchesWidth * patchesHeight
        val imageTokenCount = (patchesWidth / DOWNSAMPLE_FACTOR) * (patchesHeight / DOWNSAMPLE_FACTOR)

        val pixels = IntArray(resized.width * resized.height)
        scaled.getPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val pixelValues = FloatArray(MAX_PATCHES * PATCH_VECTOR_SIZE)
        var patchIndex = 0
        for (patchY in 0 until patchesHeight) {
            for (patchX in 0 until patchesWidth) {
                val base = patchIndex * PATCH_VECTOR_SIZE
                writePatch(
                    pixels = pixels,
                    imageWidth = resized.width,
                    patchX = patchX * PATCH_SIZE,
                    patchY = patchY * PATCH_SIZE,
                    out = pixelValues,
                    offset = base,
                )
                patchIndex += 1
            }
        }

        val pixelAttentionMask = LongArray(MAX_PATCHES) { index -> if (index < patchCount) 1L else 0L }
        val spatialShapes = longArrayOf(patchesHeight.toLong(), patchesWidth.toLong())
        return LiquidImageInputs(pixelValues, pixelAttentionMask, spatialShapes, imageTokenCount)
    }

    private fun writePatch(
        pixels: IntArray,
        imageWidth: Int,
        patchX: Int,
        patchY: Int,
        out: FloatArray,
        offset: Int,
    ) {
        var cursor = offset
        for (dy in 0 until PATCH_SIZE) {
            for (dx in 0 until PATCH_SIZE) {
                val pixel = pixels[(patchY + dy) * imageWidth + patchX + dx]
                for (channel in 0..2) {
                    val component =
                        when (channel) {
                            0 -> (pixel shr 16) and 0xFF
                            1 -> (pixel shr 8) and 0xFF
                            else -> pixel and 0xFF
                        }
                    out[cursor++] = (component / 255f - 0.5f) / 0.5f
                }
            }
        }
    }

    private fun smartResize(width: Int, height: Int): ResizedImage {
        val totalFactor = PATCH_SIZE * DOWNSAMPLE_FACTOR
        val minPixels = MIN_IMAGE_TOKENS * PATCH_SIZE * PATCH_SIZE * DOWNSAMPLE_FACTOR * DOWNSAMPLE_FACTOR
        val maxPixels = MAX_IMAGE_TOKENS * PATCH_SIZE * PATCH_SIZE * DOWNSAMPLE_FACTOR * DOWNSAMPLE_FACTOR

        var resizedWidth = max(totalFactor, roundToFactor(width, totalFactor))
        var resizedHeight = max(totalFactor, roundToFactor(height, totalFactor))

        if (resizedWidth * resizedHeight > maxPixels) {
            val beta = sqrt(width.toDouble() * height.toDouble() / maxPixels.toDouble())
            resizedWidth = max(totalFactor, floor(width / beta / totalFactor).toInt() * totalFactor)
            resizedHeight = max(totalFactor, floor(height / beta / totalFactor).toInt() * totalFactor)
        } else if (resizedWidth * resizedHeight < minPixels) {
            val beta = sqrt(minPixels.toDouble() / (width.toDouble() * height.toDouble()))
            resizedWidth = ceil(width * beta / totalFactor).toInt() * totalFactor
            resizedHeight = ceil(height * beta / totalFactor).toInt() * totalFactor
        }

        return ResizedImage(width = resizedWidth, height = resizedHeight)
    }

    private fun roundToFactor(value: Int, factor: Int): Int = round(value.toDouble() / factor).toInt() * factor

    private fun createFloatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun createLongTensor(env: OrtEnvironment, data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun FloatBuffer.copyToArray(): FloatArray {
        val duplicate = duplicate()
        val array = FloatArray(duplicate.remaining())
        duplicate.get(array)
        return array
    }

    private data class LiquidImageInputs(
        val pixelValues: FloatArray,
        val pixelAttentionMask: LongArray,
        val spatialShapes: LongArray,
        val imageTokenCount: Int,
    )

    private data class CacheTensor(
        val data: FloatArray,
        val shape: LongArray,
    )

    private data class ResizedImage(
        val width: Int,
        val height: Int,
    )
}
