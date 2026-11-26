// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

/**
 * Greedy T5-style encoder/decoder loop over two LiteRT interpreters.
 *
 * Expected exported signatures (produced by `tools/convert_grammar_model.py`):
 * - encoder inputs  `input_ids` int32 [1,N], `attention_mask` int32 [1,N]
 *   encoder output  `hidden_states` float32 [1,N,H]
 * - decoder inputs  `input_ids` int32 [1,T], `attention_mask` int32 [1,T],
 *                   `encoder_hidden_states` float32 [1,N,H]  (no KV cache)
 *   decoder output  `logits` float32 [1,T,V]
 *
 * The decoder is re-run over the whole generated prefix (T is resized each step).
 * This is slower than a cached decode but avoids cache-shape mismatches across
 * devices, and for short inline rewrites (<= [maxOutputTokens]) it is acceptable.
 * Any signature mismatch throws a fixed code; no prompt or generated text is logged.
 */
class T5Seq2SeqRunner(
    private val encoder: LiteRtInterpreter,
    private val decoder: LiteRtInterpreter,
    private val tokenizer: AiTokenizer,
    private val logger: AiLogger = NoOpAiLogger,
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    private val maxInputTokens: Int = DEFAULT_MAX_INPUT_TOKENS
) {

    fun generate(prompt: String): String {
        val sourceIds = tokenizer.encode(prompt, maxInputTokens)
        val sourceLength = sourceIds.size
        if (sourceLength == 0) throw ModelUnavailableException("tokenize.empty")

        val encoderInputs = findOne(encoder.inputNames(), *INPUT_IDS) to
            findOne(encoder.inputNames(), *ATTENTION_MASK)
        val encoderHiddenIndex = findOne(encoder.outputNames(), *HIDDEN_STATES)
        val decoderIdsIndex = findOne(decoder.inputNames(), *DECODER_INPUT_IDS)
        val decoderMaskIndex = findOne(decoder.inputNames(), *ATTENTION_MASK)
        val decoderHiddenIndex = findOne(decoder.inputNames(), *HIDDEN_STATES, *ENCODER_HIDDEN)
        val logitsIndex = findOne(decoder.outputNames(), *LOGITS)

        encoder.resizeInput(encoderInputs.first, intArrayOf(1, sourceLength))
        encoder.resizeInput(encoderInputs.second, intArrayOf(1, sourceLength))
        val encoderFeeds = mutableMapOf<Int, Any>(
            encoderInputs.first to sourceIds,
            encoderInputs.second to IntArray(sourceLength) { 1 }
        )
        val encoderResults = mutableMapOf<Int, Any>(
            encoderHiddenIndex to allocate(encoder.outputShape(encoderHiddenIndex), sourceLength)
        )
        encoder.run(encoderFeeds, encoderResults)
        val hidden = encoderResults[encoderHiddenIndex] as? FloatArray
            ?: throw ModelUnavailableException("encoder.no_hidden")

        val generated = IntArray(maxOutputTokens + 1)
        generated[0] = tokenizer.padId
        var length = 1
        var sawEos = false

        while (length <= maxOutputTokens) {
            decoder.resizeInput(decoderIdsIndex, intArrayOf(1, length))
            decoder.resizeInput(decoderMaskIndex, intArrayOf(1, length))
            val hiddenShape = decoder.inputShape(decoderHiddenIndex)
            val hiddenDim = if (hiddenShape.size >= 3 && hiddenShape[2] > 0) hiddenShape[2] else
                if (sourceLength > 0) hidden.size / sourceLength else hidden.size
            decoder.resizeInput(decoderHiddenIndex, intArrayOf(1, sourceLength, hiddenDim))

            val feeds = mutableMapOf<Int, Any>(
                decoderIdsIndex to generated.copyOf(length),
                decoderMaskIndex to IntArray(length) { 1 },
                decoderHiddenIndex to hidden
            )
            val outputs = mutableMapOf<Int, Any>(
                logitsIndex to allocate(decoder.outputShape(logitsIndex), length)
            )
            decoder.run(feeds, outputs)
            val logits = outputs[logitsIndex] as? FloatArray
                ?: throw ModelUnavailableException("decoder.no_logits")

            val logitsShape = decoder.outputShape(logitsIndex)
            val vocabSize = when {
                logitsShape.isNotEmpty() && logitsShape.last() > 0 -> logitsShape.last()
                length > 0 -> logits.size / length
                else -> logits.size
            }
            val token = argmaxLastPosition(logits, length, vocabSize)
            if (token == tokenizer.eosId || token == tokenizer.padId) {
                sawEos = true
                break
            }
            generated[length] = token
            length++
        }

        if (!sawEos) logger.event("decode.max_tokens")
        return tokenizer.decode(generated.copyOf(length))
    }

    private fun argmaxLastPosition(logits: FloatArray, length: Int, vocabSize: Int): Int {
        if (vocabSize <= 0 || logits.isEmpty()) throw ModelUnavailableException("decoder.bad_logits")
        val offset = (length - 1) * vocabSize
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < vocabSize && offset + i < logits.size) {
            val value = logits[offset + i]
            if (value > bestValue) {
                bestValue = value
                best = i
            }
            i++
        }
        return best
    }

    /** Allocate an output buffer, substituting declared -1 dims with [sequenceLength]. */
    private fun allocate(shape: IntArray, sequenceLength: Int): FloatArray {
        var count = 1
        for (index in shape.indices) {
            val dim = shape[index]
            count *= when {
                dim > 0 -> dim
                dim == -1 && index == 1 -> maxOf(1, sequenceLength)
                else -> 1
            }
        }
        return FloatArray(maxOf(1, count))
    }

    private fun findOne(names: List<String>, vararg aliases: String): Int {
        for (alias in aliases) {
            val index = names.indexOfFirst { it.equals(alias, ignoreCase = true) }
            if (index >= 0) return index
        }
        throw ModelUnavailableException("signature.unsupported")
    }

    private companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 64
        const val DEFAULT_MAX_INPUT_TOKENS = 128
        val INPUT_IDS = arrayOf("input_ids")
        val DECODER_INPUT_IDS = arrayOf("decoder_input_ids", "input_ids")
        val ATTENTION_MASK = arrayOf("attention_mask")
        val HIDDEN_STATES = arrayOf("hidden_states", "last_hidden_state", "encoder_hidden_states")
        val ENCODER_HIDDEN = arrayOf("encoder_hidden_states", "hidden_states")
        val LOGITS = arrayOf("logits")
    }
}
