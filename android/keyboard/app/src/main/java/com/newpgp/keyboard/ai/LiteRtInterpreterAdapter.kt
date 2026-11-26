// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The one file that touches the LiteRT (formerly TensorFlow Lite) runtime.
 *
 * Required Gradle dependency (to be added by the build owner, not this package):
 * `implementation("org.tensorflow:tensorflow-lite:2.17.0")`
 *
 * All interpreter access goes through [LiteRtInterpreter], so tests use a fake and a
 * future move to the `CompiledModel` API only needs a new adapter. Model bytes are
 * read through [ModelSource]; there is no network code and no text is logged.
 */
class DefaultLiteRtInterpreterFactory : LiteRtInterpreterFactory {

    override fun create(source: ModelSource, path: String): LiteRtInterpreter {
        val bytes = source.open(path).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
        buffer.rewind()
        return WrappedInterpreter(
            Interpreter(buffer, Interpreter.Options().setNumThreads(NUM_THREADS))
        )
    }

    private class WrappedInterpreter(
        private val delegate: Interpreter
    ) : LiteRtInterpreter {

        override fun inputNames(): List<String> =
            (0 until delegate.inputTensorCount).map { delegate.getInputTensor(it).name() }

        override fun outputNames(): List<String> =
            (0 until delegate.outputTensorCount).map { delegate.getOutputTensor(it).name() }

        override fun inputShape(index: Int): IntArray =
            delegate.getInputTensor(index).shape().copyOf()

        override fun outputShape(index: Int): IntArray =
            delegate.getOutputTensor(index).shape().copyOf()

        override fun resizeInput(index: Int, shape: IntArray) {
            delegate.resizeInput(index, shape, false)
        }

        override fun run(inputs: Map<Int, Any>, outputs: MutableMap<Int, Any>) {
            // Use the multi-input overload; Interpreter.run(Object, Object) would
            // treat the whole Map as one input and fail once a model is loaded.
            delegate.runForMultipleInputsOutputs(
                inputs.toSortedMap().values.toTypedArray(),
                outputs,
            )
        }

        override fun close() = delegate.close()
    }

    private companion object {
        const val NUM_THREADS = 1
    }
}
