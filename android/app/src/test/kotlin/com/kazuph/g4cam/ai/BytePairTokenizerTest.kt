package com.kazuph.g4cam.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BytePairTokenizerTest {
    @Test
    fun encodeTreatsSpecialTokensAtomically() {
        val tokenizer = createTokenizer()

        val tokenIds = tokenizer.encode("<image>cat<|im_end|>")

        assertArrayEquals(
            intArrayOf(100, 1, 2, 3, 101),
            tokenIds,
        )
    }

    @Test
    fun decodeRoundTripsPlainAsciiText() {
        val tokenizer = createTokenizer()

        val decoded = tokenizer.decode(intArrayOf(1, 2, 3))

        assertEquals("cat", decoded)
    }

    @Test
    fun decodeSkipsImageAndSpecialTokensByDefault() {
        val tokenizer = createTokenizer()

        val decoded = tokenizer.decode(intArrayOf(100, 1, 2, 3, 101))

        assertEquals("cat", decoded)
    }

    @Test
    fun supportsTokenizerJsonWithArrayStyleMerges() {
        val file = File.createTempFile("tokenizer-array-merges", ".json")
        file.writeText(
            """
            {
              "added_tokens": [
                { "content": "<image>" },
                { "content": "<|im_end|>" }
              ],
              "model": {
                "type": "BPE",
                "vocab": {
                  "c": 1,
                  "a": 2,
                  "t": 3,
                  "ca": 4,
                  "cat": 5,
                  "<image>": 100,
                  "<|im_end|>": 101
                },
                "merges": [
                  ["c", "a"],
                  ["ca", "t"]
                ]
              }
            }
            """.trimIndent(),
        )

        val tokenizer =
            try {
                BytePairTokenizer.fromFile(file)
            } finally {
                file.delete()
            }

        assertArrayEquals(intArrayOf(5), tokenizer.encode("cat"))
        assertEquals("cat", tokenizer.decode(intArrayOf(5)))
    }

    private fun createTokenizer(): BytePairTokenizer {
        val file = File.createTempFile("tokenizer", ".json")
        file.writeText(
            """
            {
              "added_tokens": [
                { "content": "<image>" },
                { "content": "<|im_end|>" }
              ],
              "model": {
                "type": "BPE",
                "vocab": {
                  "c": 1,
                  "a": 2,
                  "t": 3,
                  "<image>": 100,
                  "<|im_end|>": 101
                },
                "merges": []
              }
            }
            """.trimIndent(),
        )
        return file.useTokenizer()
    }

    private fun File.useTokenizer(): BytePairTokenizer =
        try {
            BytePairTokenizer.fromFile(this)
        } finally {
            delete()
        }
}
