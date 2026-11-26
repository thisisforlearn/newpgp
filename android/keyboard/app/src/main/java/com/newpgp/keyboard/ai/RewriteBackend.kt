// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A model-backed rewrite implementation. Everything behind this interface is
 * replaceable in tests; the real implementation is [LiteRtRewriteBackend].
 *
 * Methods throw [ModelUnavailableException] (or a plain exception carrying only a
 * machine-readable code) on failure. Never include user text in exception messages.
 */
interface RewriteBackend : AutoCloseable {
    val id: String
    fun isReady(): Boolean
    fun rewrite(text: String, tone: Tone): String
    override fun close()
}

/** Thrown for model/signature failures. [code] is a fixed code, never user text. */
class ModelUnavailableException(val code: String) : Exception(code)

/**
 * Where model files are read from. The default reads ordinary files; an IME can pass
 * an asset-backed implementation that copies `assets/models/*` into `filesDir` on
 * first use, then delegates to the same interface. No network implementation exists.
 */
interface ModelSource {
    fun open(path: String): InputStream
    fun exists(path: String): Boolean
    /** Absolute-or-relative path of [name] next to [path]. */
    fun sibling(path: String, name: String): String
}

/** Default [ModelSource] backed by the device filesystem. */
class FileModelSource : ModelSource {
    override fun open(path: String): InputStream = FileInputStream(path)
    override fun exists(path: String): Boolean = File(path).isFile
    override fun sibling(path: String, name: String): String =
        File(path).absoluteFile.parentFile?.resolve(name)?.path ?: name
}

/**
 * Minimal abstraction over a LiteRT interpreter so JVM tests and future runtime
 * migrations do not depend on the concrete LiteRT classes.
 */
interface LiteRtInterpreter : AutoCloseable {
    fun inputNames(): List<String>
    fun outputNames(): List<String>
    fun inputShape(index: Int): IntArray
    fun outputShape(index: Int): IntArray
    fun resizeInput(index: Int, shape: IntArray)
    fun run(inputs: Map<Int, Any>, outputs: MutableMap<Int, Any>)
    override fun close()
}

/** Creates interpreters from model bytes. */
interface LiteRtInterpreterFactory {
    fun create(source: ModelSource, path: String): LiteRtInterpreter
}

/** Tokenizer contract. Implementations are deterministic and entirely local. */
interface AiTokenizer {
    val eosId: Int
    val padId: Int
    fun encode(text: String, maxTokens: Int): IntArray
    fun decode(ids: IntArray): String
}
