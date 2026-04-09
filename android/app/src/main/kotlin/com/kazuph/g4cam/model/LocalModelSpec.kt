package com.kazuph.g4cam.model

enum class ModelId {
    GEMMA_LITERT_E2B,
    LIQUID_LFM2_5_VL_450M_ONNX_Q4,
}

data class RemoteModelFile(
    val relativePath: String,
    val url: String,
    val minBytes: Long,
    val expectedBytes: Long = minBytes,
)

data class LocalModelSpec(
    val id: ModelId,
    val title: String,
    val downloadDescription: String,
    val initDescription: String,
    val storageDir: String,
    val primaryFile: String,
    val files: List<RemoteModelFile>,
)

object LocalModelSpecs {
    val gemmaLiteRt =
        LocalModelSpec(
            id = ModelId.GEMMA_LITERT_E2B,
            title = "Gemma 4 E2B",
            downloadDescription = "AIモデルのダウンロードが必要です\nWi-Fi接続を推奨します",
            initDescription = "エンジンの初期化に\n30〜60秒かかります",
            storageDir = "gemma-4-e2b-litert",
            primaryFile = "gemma-4-E2B-it.litertlm",
            files =
                listOf(
                    RemoteModelFile(
                        relativePath = "gemma-4-E2B-it.litertlm",
                        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                        minBytes = 2_500_000_000L,
                        expectedBytes = 2_580_000_000L,
                    ),
                ),
        )

    val liquidOnnx =
        LocalModelSpec(
            id = ModelId.LIQUID_LFM2_5_VL_450M_ONNX_Q4,
            title = "Liquid LFM2.5-VL-450M",
            downloadDescription = "ONNXモデル一式のダウンロードが必要です\nWi-Fi接続を推奨します",
            initDescription = "ONNX Runtime の初期化に\n60〜90秒かかります",
            storageDir = "liquid-lfm2-5-vl-450m-onnx-q4",
            primaryFile = "onnx/decoder_model_merged_q4.onnx",
            files =
                listOf(
                    RemoteModelFile(
                        relativePath = "onnx/embed_tokens.onnx",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/onnx/embed_tokens.onnx",
                        minBytes = 200_000_000L,
                        expectedBytes = 268_435_456L,
                    ),
                    RemoteModelFile(
                        relativePath = "onnx/vision_encoder_q4.onnx",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/onnx/vision_encoder_q4.onnx",
                        minBytes = 100_000L,
                        expectedBytes = 146_157L,
                    ),
                    RemoteModelFile(
                        relativePath = "onnx/vision_encoder_q4.onnx_data",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/onnx/vision_encoder_q4.onnx_data",
                        minBytes = 50_000_000L,
                        expectedBytes = 59_982_848L,
                    ),
                    RemoteModelFile(
                        relativePath = "onnx/decoder_model_merged_q4.onnx",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/onnx/decoder_model_merged_q4.onnx",
                        minBytes = 100_000L,
                        expectedBytes = 171_898L,
                    ),
                    RemoteModelFile(
                        relativePath = "onnx/decoder_model_merged_q4.onnx_data",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/onnx/decoder_model_merged_q4.onnx_data",
                        minBytes = 450_000_000L,
                        expectedBytes = 481_030_144L,
                    ),
                    RemoteModelFile(
                        relativePath = "tokenizer.json",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/tokenizer.json",
                        minBytes = 4_000_000L,
                        expectedBytes = 4_000_000L,
                    ),
                    RemoteModelFile(
                        relativePath = "tokenizer_config.json",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/tokenizer_config.json",
                        minBytes = 1_000L,
                        expectedBytes = 5_000L,
                    ),
                    RemoteModelFile(
                        relativePath = "config.json",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/config.json",
                        minBytes = 1_000L,
                        expectedBytes = 5_000L,
                    ),
                    RemoteModelFile(
                        relativePath = "generation_config.json",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/generation_config.json",
                        minBytes = 50L,
                        expectedBytes = 200L,
                    ),
                    RemoteModelFile(
                        relativePath = "processor_config.json",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/processor_config.json",
                        minBytes = 500L,
                        expectedBytes = 2_000L,
                    ),
                    RemoteModelFile(
                        relativePath = "preprocessor_config.json",
                        url = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-ONNX/resolve/main/preprocessor_config.json",
                        minBytes = 500L,
                        expectedBytes = 2_000L,
                    ),
                ),
        )

    fun fromId(id: String?): LocalModelSpec? =
        when (id) {
            ModelId.GEMMA_LITERT_E2B.name -> gemmaLiteRt
            ModelId.LIQUID_LFM2_5_VL_450M_ONNX_Q4.name -> liquidOnnx
            else -> null
        }
}
