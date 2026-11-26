// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import java.io.File
import java.util.Locale

/**
 * Small, dependency-free approximation of the T5 SentencePiece tokenizer.
 *
 * The conversion script writes `tokenizer_vocab.tsv` ("token<TAB>id") and
 * `tokenizer_metadata.txt` ("key=value") beside the model. This implementation
 * uses longest-match over that vocabulary plus `<0xHH>` byte fallback. It is
 * deterministic, offline and adequate for the bundled grammar model; it is not a
 * byte-perfect SentencePiece implementation, so unusual Unicode may degrade
 * quality. Replace [AiTokenizer] if exact parity is needed later.
 */
class ApproxSentencePieceTokenizer(
    private val vocab: Map<String, Int>,
    override val eosId: Int = EOS_ID,
    override val padId: Int = PAD_ID,
    private val unkId: Int = UNK_ID,
    private val maxInputTokens: Int = DEFAULT_MAX_TOKENS
) : AiTokenizer {

    private val idToToken: Map<Int, String> = vocab.entries.associate { (token, id) -> id to token }
    private val longestFirst: List<String> = vocab.keys.sortedByDescending { it.length }
    private val byteTokens: Array<String?> = Array(256) { idx ->
        val hex = String.format(Locale.ROOT, "<0x%02X>", idx)
        vocab[hex]?.let { hex }
    }

    override fun encode(text: String, maxTokens: Int): IntArray {
        val limit = if (maxTokens <= 0) maxInputTokens else minOf(maxTokens, maxInputTokens)
        val cleaned = WHITESPACE.replace(text.trim(), " ")
        val sentencePieces = buildString(cleaned.length + 2) {
            append(SPACE_MARKER)
            for (ch in cleaned) append(if (ch == ' ') SPACE_MARKER else ch)
        }

        val ids = ArrayList<Int>(minOf(sentencePieces.length, limit))
        var index = 0
        while (index < sentencePieces.length && ids.size < limit - 1) {
            var matched: String? = null
            for (token in longestFirst) {
                if (sentencePieces.startsWith(token, index)) {
                    matched = token
                    break
                }
            }
            if (matched != null) {
                vocab[matched]?.let { ids.add(it) }
                index += matched.length
            } else {
                // Byte fallback: UTF-8 bytes for this character, matching T5's <0xHH>
                // tokens. Each byte is appended separately (byte-level BPE style).
                val ch = sentencePieces[index]
                val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                for (byte in bytes) {
                    if (ids.size >= limit - 1) break
                    val token = byteTokens[byte.toInt() and 0xFF]
                    ids.add(if (token != null) vocab[token]!! else unkId)
                }
                index += 1
            }
        }
        ids.add(eosId)
        return ids.toIntArray()
    }

    override fun decode(ids: IntArray): String {
        val out = StringBuilder(ids.size * 4)
        for (id in ids) {
            if (id == eosId || id == padId) continue
            val token = idToToken[id] ?: continue
            if (token.startsWith("<0x")) {
                val value = token.removePrefix("<0x").removeSuffix(">").toIntOrNull(16)
                if (value != null) out.append(value.toChar())
            } else {
                out.append(token)
            }
        }
        return WHITESPACE.replace(out.toString().replace(SPACE_MARKER, ' '), " ").trim()
    }

    companion object {
        private const val SPACE_MARKER = '\u2581' // U+2581 LOWER ONE EIGHTH BLOCK, T5's "▁"
        private const val PAD_ID = 0
        private const val EOS_ID = 1
        private const val UNK_ID = 2
        private const val DEFAULT_MAX_TOKENS = 128
        private val WHITESPACE = Regex("""[ \t\r\n\u00A0]+""")

        /** Load the files written by `tools/convert_grammar_model.py`. */
        fun fromDirectory(directory: File): ApproxSentencePieceTokenizer {
            val vocab = LinkedHashMap<String, Int>()
            File(directory, "tokenizer_vocab.tsv").forEachLine { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2) {
                    val id = parts[1].trim().toIntOrNull()
                    if (id != null) vocab[parts[0]] = id
                }
            }
            require(vocab.isNotEmpty()) { "AIE_EMPTY_VOCAB" }

            val metadata = HashMap<String, String>()
            val metaFile = File(directory, "tokenizer_metadata.txt")
            if (metaFile.isFile) {
                metaFile.forEachLine { line ->
                    val parts = line.split('=', limit = 2)
                    if (parts.size == 2) metadata[parts[0].trim()] = parts[1].trim()
                }
            }
            return ApproxSentencePieceTokenizer(
                vocab = vocab,
                eosId = metadata["eos_id"]?.toIntOrNull() ?: EOS_ID,
                padId = metadata["pad_id"]?.toIntOrNull() ?: PAD_ID,
                unkId = metadata["unk_id"]?.toIntOrNull() ?: UNK_ID,
                maxInputTokens = metadata["max_input_tokens"]?.toIntOrNull() ?: DEFAULT_MAX_TOKENS
            )
        }
    }
}
