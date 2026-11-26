// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import java.io.File

/**
 * Real on-device backend: a quantized T5 encoder + decoder pair and a local
 * tokenizer. All file reads go through [ModelSource], all inference through
 * [LiteRtInterpreter]. There are no network calls, analytics or text logs here.
 *
 * Expected files next to each other (see `assets/models/README.md`):
 * - `grammar_small_q8.tflite` (encoder)
 * - `grammar_small_q8_decoder.tflite` (decoder)
 * - `tokenizer_vocab.tsv`, `tokenizer_metadata.txt`
 */
class LiteRtRewriteBackend(
    private val modelInfo: ModelInfo,
    private val modelPath: String,
    private val modelSource: ModelSource = FileModelSource(),
    private val interpreterFactory: LiteRtInterpreterFactory = DefaultLiteRtInterpreterFactory(),
    private val tokenizerDirectory: String? = null,
    private val tokenizerLoader: (String) -> AiTokenizer = { dir ->
        ApproxSentencePieceTokenizer.fromDirectory(File(dir))
    },
    private val logger: AiLogger = NoOpAiLogger,
    private val maxOutputTokens: Int = 64
) : RewriteBackend {

    override val id: String get() = modelInfo.id

    private var encoder: LiteRtInterpreter? = null
    private var decoder: LiteRtInterpreter? = null
    private var runner: T5Seq2SeqRunner? = null

    @Volatile
    private var ready = false

    init {
        try {
            val decoderName = File(modelPath).name.removeSuffix(".tflite") + "_decoder.tflite"
            val decoderPath = modelSource.sibling(modelPath, decoderName)
            if (!modelSource.exists(decoderPath)) throw ModelUnavailableException("decoder.missing")

            val directory = tokenizerDirectory
                ?: File(modelPath).absoluteFile.parent
                ?: throw ModelUnavailableException("tokenizer.missing")
            val tokenizer = try {
                tokenizerLoader(directory)
            } catch (t: Throwable) {
                throw ModelUnavailableException("tokenizer.missing")
            }

            encoder = interpreterFactory.create(modelSource, modelPath)
            decoder = interpreterFactory.create(modelSource, decoderPath)
            runner = T5Seq2SeqRunner(
                encoder = encoder!!,
                decoder = decoder!!,
                tokenizer = tokenizer,
                logger = logger,
                maxOutputTokens = maxOutputTokens
            )
            ready = true
            logger.event("backend.ready")
        } catch (t: Throwable) {
            close()
            if (t is ModelUnavailableException) throw t
            throw ModelUnavailableException("backend.init")
        }
    }

    override fun isReady(): Boolean = ready

    override fun rewrite(text: String, tone: Tone): String {
        if (!ready) throw ModelUnavailableException("backend.closed")
        val current = runner ?: throw ModelUnavailableException("backend.closed")
        // Model prefixes are short style hints; the bundled model was trained for
        // grammar correction, so a future fine-tune can make these more meaningful.
        return current.generate(tone.modelPrompt + text.trim())
    }

    override fun close() {
        ready = false
        runner = null
        val e = encoder
        val d = decoder
        encoder = null
        decoder = null
        try {
            d?.close()
        } catch (t: Throwable) {
            logger.error("decoder.close_fail")
        }
        try {
            e?.close()
        } catch (t: Throwable) {
            logger.error("encoder.close_fail")
        }
    }
}
