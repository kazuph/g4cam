package com.kazuph.g4cam.ai

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets

private val TOKEN_PATTERN =
    Regex("(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+")

class BytePairTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val reverseVocab: Map<Int, String>,
    mergeList: JsonArray,
    addedTokens: JsonArray,
) {
    private val mergeRanks =
        buildMap<Pair<String, String>, Int> {
            mergeList.forEachIndexed { index, element ->
                val pair =
                    when {
                        element.isJsonArray -> {
                            val items = element.asJsonArray
                            if (items.size() == 2) {
                                listOf(items[0].asString, items[1].asString)
                            } else {
                                emptyList()
                            }
                        }
                        element.isJsonPrimitive -> element.asString.split(" ")
                        else -> emptyList()
                    }
                if (pair.size == 2) {
                    put(pair[0] to pair[1], index)
                }
            }
        }
    private val specialTokens =
        addedTokens
            .map { it.asJsonObject.get("content").asString }
            .sortedByDescending { it.length }
    private val byteToUnicode: Map<Int, Char>
    private val unicodeToByte: Map<Char, Int>
    private val bpeCache = HashMap<String, List<String>>()

    init {
        val (encoder, decoder) = buildByteLookup()
        byteToUnicode = encoder
        unicodeToByte = decoder
    }

    val imageTokenId: Int = vocab.getValue("<image>")
    val eosTokenId: Int = vocab.getValue("<|im_end|>")

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(text.length)
        var cursor = 0
        while (cursor < text.length) {
            val special = specialTokens.firstOrNull { text.startsWith(it, cursor) }
            if (special != null) {
                ids += vocab.getValue(special)
                cursor += special.length
                continue
            }

            val nextSpecial =
                specialTokens
                    .map { token ->
                        val idx = text.indexOf(token, cursor)
                        if (idx >= 0) idx else Int.MAX_VALUE
                    }.minOrNull() ?: Int.MAX_VALUE
            val plainText = text.substring(cursor, minOf(nextSpecial, text.length))
            tokenizePlainSegment(plainText, ids)
            cursor = minOf(nextSpecial, text.length)
        }
        return ids.toIntArray()
    }

    fun decode(tokenIds: IntArray, skipSpecialTokens: Boolean = true): String {
        val builder = StringBuilder()
        val byteBuffer = ArrayList<Byte>()
        tokenIds.forEach { tokenId ->
            val piece = reverseVocab[tokenId] ?: return@forEach
            if (skipSpecialTokens && piece.startsWith("<|") && piece.endsWith("|>")) {
                return@forEach
            }
            if (piece == "<image>") {
                return@forEach
            }
            piece.forEach { ch ->
                val byte = unicodeToByte[ch]
                if (byte != null) {
                    byteBuffer += byte.toByte()
                } else {
                    flushBytes(byteBuffer, builder)
                    builder.append(ch)
                }
            }
        }
        flushBytes(byteBuffer, builder)
        return builder.toString()
    }

    private fun tokenizePlainSegment(segment: String, sink: MutableList<Int>) {
        TOKEN_PATTERN.findAll(segment).forEach { match ->
            val encoded =
                match.value
                    .toByteArray(StandardCharsets.UTF_8)
                    .joinToString(separator = "") { byte ->
                        byteToUnicode.getValue(byte.toInt() and 0xFF).toString()
                    }
            bpe(encoded).forEach { piece ->
                sink += vocab[piece] ?: error("Unknown token piece: $piece")
            }
        }
    }

    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }
        if (token.length <= 1) {
            return listOf(token).also { bpeCache[token] = it }
        }

        var word = token.map { it.toString() }
        while (true) {
            val pairs = getPairs(word)
            val candidate =
                pairs.minByOrNull { pair -> mergeRanks[pair] ?: Int.MAX_VALUE }
                    ?: break
            if (mergeRanks[candidate] == null) break

            val merged = ArrayList<String>(word.size)
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex &&
                    word[index] == candidate.first &&
                    word[index + 1] == candidate.second
                ) {
                    merged += candidate.first + candidate.second
                    index += 2
                } else {
                    merged += word[index]
                    index += 1
                }
            }
            word = merged
            if (word.size == 1) break
        }
        return word.also { bpeCache[token] = it }
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until word.lastIndex) {
            pairs += word[i] to word[i + 1]
        }
        return pairs
    }

    private fun flushBytes(bytes: MutableList<Byte>, builder: StringBuilder) {
        if (bytes.isEmpty()) return
        builder.append(bytes.toByteArray().toString(StandardCharsets.UTF_8))
        bytes.clear()
    }

    companion object {
        fun fromFile(file: File): BytePairTokenizer {
            val root = JsonParser.parseReader(file.reader()).asJsonObject
            val model = root.getAsJsonObject("model")
            val vocabObject = model.getAsJsonObject("vocab")
            val vocab =
                buildMap<String, Int>(vocabObject.size()) {
                    vocabObject.entrySet().forEach { (token, id) -> put(token, id.asInt) }
                }
            val reverseVocab = vocab.entries.associate { (token, id) -> id to token }
            return BytePairTokenizer(
                vocab = vocab,
                reverseVocab = reverseVocab,
                mergeList = model.getAsJsonArray("merges"),
                addedTokens = root.getAsJsonArray("added_tokens"),
            )
        }

        private fun buildByteLookup(): Pair<Map<Int, Char>, Map<Char, Int>> {
            val byteList = ArrayList<Int>(256)
            for (value in 33..126) byteList += value
            for (value in 161..172) byteList += value
            for (value in 174..255) byteList += value

            val codePoints = byteList.toMutableList()
            var nextCodePoint = 256
            for (value in 0..255) {
                if (value !in byteList) {
                    byteList += value
                    codePoints += nextCodePoint++
                }
            }

            val encoder = LinkedHashMap<Int, Char>(256)
            byteList.zip(codePoints).forEach { (byte, codePoint) ->
                encoder[byte] = codePoint.toChar()
            }
            val decoder = encoder.entries.associate { (key, value) -> value to key }
            return encoder to decoder
        }
    }
}
