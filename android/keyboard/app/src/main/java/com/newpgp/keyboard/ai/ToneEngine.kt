// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
/**
 * Public API for the offline English tone/grammar rewrite engine.
 *
 * ```
 * val engine = ToneEngine(modelRoot = File(filesDir, "models").absolutePath)
 * // Instant, always available, safe on the UI thread:
 * val fixed = engine.rewrite(text, Tone.GRAMMAR_ONLY)
 * // Model-backed, on a background thread (never block the IME main thread):
 * engine.rewriteAsync(text, Tone.PROFESSIONAL) { result -> ... }
 * // Or from a coroutine: engine.rewriteSuspend(text, Tone.FRIENDLY)
 * engine.unload() // release the interpreter when idle
 * ```
 *
 * Privacy: no network permission, no analytics, no text logging. [AiLogger] receives
 * fixed event codes only. Model files are read through [ModelSource]; the default is
 * local files, and the IME may supply an assets/filesDir backed implementation.
 *
 * Threading: [rewrite] with [Tone.GRAMMAR_ONLY] is pure Kotlin and returns in
 * microseconds. Model-backed [rewrite] calls the interpreter on the calling thread —
 * call it from a background thread only. [rewriteAsync], [rewriteSuspend], [loadAsync]
 * and [loadSuspend] run on an internal single daemon thread. [load] itself is a
 * blocking call intended for that background thread or a coroutine's IO dispatcher.
 * Model work is lazy: the first model-backed request triggers a load; while it is
 * loading, callers receive the conservative rule-based rewrite instantly.
 */
package com.newpgp.keyboard.ai

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private fun defaultExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "newpgp-tone-engine").apply { isDaemon = true }
}

class ToneEngine(
    /** Root directory holding converted model files. Pass an absolute filesDir path. */
    private val modelRoot: String = "models",
    private val registry: ModelRegistry = ModelRegistry(),
    private val modelSource: ModelSource = FileModelSource(),
    private val backendFactory: (ModelInfo, String) -> RewriteBackend = { info, path ->
        LiteRtRewriteBackend(info, path, modelSource = modelSource)
    },
    private val rules: GrammarRules = GrammarRules(),
    private val logger: AiLogger = NoOpAiLogger,
    private val executor: ExecutorService = defaultExecutor()
) : AutoCloseable {

    @Volatile
    private var backend: RewriteBackend? = null

    @Volatile
    private var activeModel: ModelInfo = registry.default()


    private val lock = Any()
    private var loadScheduled = false

    /** True when a model interpreter is ready for inference. */
    fun isLoaded(): Boolean = backend?.isReady() == true

    /** Model currently selected (not necessarily loaded). */
    fun activeModel(): ModelInfo = activeModel

    /** Models the user may choose from. */
    fun availableModels(): List<ModelInfo> = registry.all()

    /**
     * Load the active model, blocking the calling thread. Call from a background
     * thread or via [loadSuspend]/[loadAsync]. [modelPath] may be an explicit file
     * path, or the id of a registered model; null uses the active model's file.
     */
    fun load(modelPath: String? = null): Result<Unit> {
        logger.event("load.start")
        return try {
            val path = resolve(modelPath)
            if (!modelSource.exists(path)) throw ModelUnavailableException("model.missing")
            val info = activeModel
            synchronized(lock) {
                backend?.close()
                backend = null
                val created = backendFactory(info, path)
                if (!created.isReady()) {
                    created.close()
                    throw ModelUnavailableException("model.not_ready")
                }
                backend = created
            }
            logger.event("load.ok")
            Result.success(Unit)
        } catch (t: Throwable) {
            logger.error("load.fail")
            Result.failure(t)
        }
    }

    /** Non-blocking load with a callback on the internal background thread. */
    fun loadAsync(modelPath: String? = null, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            try {
                callback(load(modelPath))
            } catch (t: Throwable) {
                logger.error("callback.fail")
            }
        }
    }

    /** Coroutine-friendly load. Uses only kotlin-stdlib's [suspendCoroutine]. */
    suspend fun loadSuspend(modelPath: String? = null): Result<Unit> =
        suspendCoroutine { continuation ->
            loadAsync(modelPath) { continuation.resume(it) }
        }

    /**
     * Rewrite [text] with [tone].
     *
     * Grammar-only always uses the instant rule path. Model tones use the loaded
     * interpreter (call from a background thread); if no model is loaded yet, a lazy
     * load is scheduled and the instant rule-based result is returned immediately.
     * Prefer [rewriteAsync]/[rewriteSuspend] for model tones on the IME thread.
     */
    fun rewrite(text: String, tone: Tone): Result<String> {
        if (text.isEmpty()) return Result.success(text)
        if (tone == Tone.GRAMMAR_ONLY) return Result.success(rules.rewrite(text))

        val current = backend
        if (current?.isReady() == true) {
            return try {
                Result.success(sanitize(current.rewrite(rules.rewrite(text), tone), text, tone))
            } catch (t: Throwable) {
                logger.error("rewrite.fail")
                Result.failure(t)
            }
        }
        logger.event("rewrite.fallback")
        scheduleLazyLoad()
        return Result.success(rules.rewriteWithTone(text, tone))
    }

    /** Background rewrite; callback receives the final result on the internal thread. */
    fun rewriteAsync(text: String, tone: Tone, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                callback(rewriteBlocking(text, tone))
            } catch (t: Throwable) {
                logger.error("callback.fail")
            }
        }
    }

    /** Coroutine-friendly background rewrite. */
    suspend fun rewriteSuspend(text: String, tone: Tone): Result<String> =
        suspendCoroutine { continuation ->
            rewriteAsync(text, tone) { continuation.resume(it) }
        }

    /**
     * Select a different model by id (see [availableModels]). Unloads the current
     * interpreter; the new model loads lazily on the next model-backed request.
     */
    fun setModel(id: String): Result<Unit> = try {
        val model = registry.find(id)
            ?: throw ModelUnavailableException("model.unknown")
        synchronized(lock) {
            backend?.close()
            backend = null
            activeModel = model
        }
        logger.event("model.set")
        Result.success(Unit)
    } catch (t: Throwable) {
        logger.error("model.set_fail")
        Result.failure(t)
    }

    /** Register a user-supplied model definition for later selection. */
    fun registerModel(model: ModelInfo) = registry.register(model)

    /** Release the interpreter and its native memory. Safe to call repeatedly. */
    fun unload() {
        synchronized(lock) {
            backend?.close()
            backend = null
        }
        logger.event("unload")
    }

    /** Shut down the internal thread as well; use when the IME is destroyed. */
    override fun close() {
        unload()
        executor.shutdownNow()
    }

    private fun resolve(modelPath: String?): String {
        if (modelPath.isNullOrEmpty()) return "$modelRoot/${activeModel.fileName}"
        registry.find(modelPath)?.let { model ->
            activeModel = model
            return "$modelRoot/${model.fileName}"
        }
        return modelPath
    }

    private fun scheduleLazyLoad() {
        synchronized(lock) {
            if (loadScheduled || backend != null) return
            loadScheduled = true
        }
        executor.execute {
            try {
                load(null)
            } finally {
                synchronized(lock) { loadScheduled = false }
            }
        }
    }

    private fun rewriteBlocking(text: String, tone: Tone): Result<String> {
        if (text.isEmpty()) return Result.success(text)
        if (tone == Tone.GRAMMAR_ONLY) return Result.success(rules.rewrite(text))

        if (!isLoaded()) {
            val loaded = load(null)
            if (loaded.isFailure) logger.event("rewrite.fallback")
        }
        val current = backend
        if (current?.isReady() != true) {
            return Result.success(rules.rewriteWithTone(text, tone))
        }
        return try {
            Result.success(sanitize(current.rewrite(rules.rewrite(text), tone), text, tone))
        } catch (t: Throwable) {
            // Never fall back to sending the original text through a broken model path
            // silently: log a code, keep the user's text unchanged in the result.
            logger.error("rewrite.fail")
            Result.failure(t)
        }
    }

    /** Guard against empty or runaway generations; never logs either string. */
    private fun sanitize(output: String, input: String, tone: Tone): String {
        val trimmed = output.trim()
        val limit = maxOf(input.length * 4, input.length + 160)
        if (trimmed.isEmpty() || trimmed.length > limit || trimmed.any { it == '\u0000' }) {
            logger.event("rewrite.guard")
            return rules.rewriteWithTone(input, tone)
        }
        return trimmed
    }
}
