// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ToneEngineTest {

    private fun engine(
        fake: FakeToneEngine = FakeToneEngine(),
        registry: ModelRegistry = ModelRegistry(),
        logger: AiLogger = NoOpAiLogger,
        factory: (ModelInfo, String) -> RewriteBackend = { _, _ -> fake }
    ): ToneEngine = ToneEngine(
        modelRoot = "/models",
        registry = registry,
        modelSource = FakeModelSource(),
        backendFactory = factory,
        logger = logger
    )

    @Test
    fun grammarOnlyPathNeverLoadsAModel() {
        val created = ArrayList<FakeToneEngine>()
        val engine = engine(factory = { _, _ -> FakeToneEngine().also { created.add(it) } })
        try {
            val result = engine.rewrite("i recieve teh apropriate adress", Tone.GRAMMAR_ONLY)
            assertEquals("I receive the appropriate address.", result.getOrThrow())
            assertTrue(created.isEmpty())
            assertFalse(engine.isLoaded())
        } finally {
            engine.close()
        }
    }

    @Test
    fun modelToneUsesLoadedBackendAndPassesRulesOutput() {
        val fake = FakeToneEngine()
        val engine = engine(fake)
        try {
            assertTrue(engine.load().isSuccess)
            assertTrue(engine.isLoaded())
            val result = engine.rewrite("i recieve teh adress", Tone.PROFESSIONAL).getOrThrow()
            assertEquals("PROFESSIONAL:I receive the address.", result)
            assertEquals(listOf("I receive the address." to Tone.PROFESSIONAL), fake.calls)
        } finally {
            engine.close()
        }
    }

    @Test
    fun asyncRewriteDeliversModelResult() {
        val fake = FakeToneEngine(transform = { text, tone -> "$tone($text)" })
        val engine = engine(fake)
        try {
            val latch = CountDownLatch(1)
            val holder = AtomicReference<Result<String>>()
            engine.rewriteAsync("i recieve teh adress", Tone.FRIENDLY) { result ->
                holder.set(result)
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("FRIENDLY(I receive the address.)", holder.get().getOrThrow())
        } finally {
            engine.close()
        }
    }

    @Test
    fun modelSwitchingSelectsNewModelAndLazyLoadsIt() {
        val alternate = ModelInfo(
            id = "alt",
            displayName = "Alternate (offline)",
            fileName = "alt_q8.tflite",
            approxSizeBytes = 1024,
            description = "test only",
            license = "test"
        )
        val registry = ModelRegistry()
        registry.register(alternate)
        val seen = ArrayList<Pair<String, String>>()
        val engine = engine(registry = registry, factory = { info, path ->
            seen.add(info.id to path)
            FakeToneEngine(id = info.id)
        })
        try {
            assertEquals("grammar-small", engine.activeModel().id)
            assertTrue(engine.setModel("alt").isSuccess)
            assertEquals("alt", engine.activeModel().id)
            assertTrue(engine.availableModels().any { it.id == "alt" })
            assertTrue(engine.setModel("does-not-exist").isFailure)
            assertFalse(engine.isLoaded())

            assertTrue(engine.load().isSuccess)
            assertEquals("alt" to "/models/alt_q8.tflite", seen.last())
            assertTrue(engine.isLoaded())
        } finally {
            engine.close()
        }
    }

    @Test
    fun unloadThenReloadCreatesFreshBackend() {
        val created = ArrayList<FakeToneEngine>()
        val engine = engine(factory = { _, _ -> FakeToneEngine().also { created.add(it) } })
        try {
            assertTrue(engine.load().isSuccess)
            assertTrue(engine.isLoaded())
            engine.unload()
            assertFalse(engine.isLoaded())
            assertEquals(1, created[0].closeCalls)

            assertTrue(engine.load().isSuccess)
            assertEquals(2, created.size)
            assertEquals(1, created[0].closeCalls)
            assertEquals(0, created[1].closeCalls)
            assertTrue(engine.isLoaded())
        } finally {
            engine.close()
        }
    }

    @Test
    fun modelToneFallsBackInstantlyBeforeLoad() {
        val fake = FakeToneEngine()
        val engine = engine(fake)
        try {
            val result = engine.rewrite("i really like it", Tone.CONCISE).getOrThrow()
            assertTrue(result.contains("like"))
            assertFalse(result.contains("really"))
            assertEquals(0, fake.calls.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun loadFailureIsReportedWithoutThrowing() {
        val fake = FakeToneEngine(initiallyReady = false)
        val engine = engine(fake)
        try {
            assertTrue(engine.load().isFailure)
            assertFalse(engine.isLoaded())
            assertEquals(1, fake.closeCalls)
        } finally {
            engine.close()
        }
    }
}
