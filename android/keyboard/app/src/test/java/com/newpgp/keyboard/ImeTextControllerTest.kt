// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

import com.newpgp.keyboard.autocorrect.AutocorrectEngine
import com.newpgp.keyboard.autocorrect.LearnedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the editing flows that used to duplicate or leak plaintext. */
class ImeTextControllerTest {

    private fun controller(
        autocorrect: Boolean = true,
        learnedWords: LearnedWords = LearnedWords()
    ): ImeTextController = ImeTextController(
        engine = AutocorrectEngine(learnedWords = learnedWords),
        autocorrectEnabled = { autocorrect }
    )

    private fun ImeTextController.type(ic: FakeTextEditConnection, word: String) {
        word.forEach { typeCharacter(ic, it.toString(), false) }
    }

    @Test
    fun shieldReplacesComposingSoOnlyArmorRemains() {
        val ic = FakeTextEditConnection()
        val subject = controller(autocorrect = false)
        subject.type(ic, "secret")
        assertEquals("secret", ic.text())

        subject.replaceComposingWithArmored(ic, "ARMOR")

        assertEquals("ARMOR", ic.text())
        assertEquals("", subject.composingText())
        assertEquals(-1, ic.composingStart)
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun shieldKeepsUnrelatedTextButRemovesThePlaintextWord() {
        val ic = FakeTextEditConnection()
        ic.setText("Hello ")
        val subject = controller(autocorrect = false)
        subject.type(ic, "secret")

        subject.replaceComposingWithArmored(ic, "ARMOR")

        assertEquals("Hello ARMOR", ic.text())
        assertFalse(ic.text().contains("secret"))
    }

    @Test
    fun shieldWithNoComposingReplacesTheWholeField() {
        val ic = FakeTextEditConnection()
        ic.setText("whole field plaintext", 9)
        val subject = controller()
        val snapshot = subject.wholeFieldSnapshot(ic)

        subject.replaceAllWithArmored(ic, "ARMOR", snapshot.fieldLength)

        assertEquals("ARMOR", ic.text())
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun spaceCommitsComposingOnceWithoutDuplication() {
        val ic = FakeTextEditConnection()
        val subject = controller(autocorrect = false)
        subject.type(ic, "teh")

        subject.commitWordAndText(ic, " ")

        assertEquals("teh ", ic.text())
        assertEquals("", subject.composingText())
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun spaceCommitsCorrectionOnceWithoutDuplication() {
        val ic = FakeTextEditConnection()
        ic.setText("Hi ")
        val subject = controller()
        subject.type(ic, "teh")

        subject.commitWordAndText(ic, " ")

        assertEquals("Hi the ", ic.text())
        assertFalse(ic.text().contains("tehthe"))
    }

    @Test
    fun separatorIsPrependedBeforeTheWordNotAfterIt() {
        val ic = FakeTextEditConnection()
        val subject = controller(autocorrect = false)
        subject.type(ic, "Hi")
        subject.commitWordAndText(ic, ".")
        assertEquals("Hi.", ic.text())

        subject.type(ic, "there")
        subject.commitWordAndText(ic, " ")

        assertEquals("Hi. there ", ic.text())
        assertFalse(ic.text().contains("  "))
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun sentenceStartCapitalizesAnAlreadyCorrectWord() {
        val ic = FakeTextEditConnection()
        val subject = controller()
        subject.type(ic, "hello")

        subject.commitWordAndText(ic, " ")

        assertEquals("Hello ", ic.text())
    }

    @Test
    fun cursorCapsModeCapitalizesOutsideSentenceStarts() {
        val ic = FakeTextEditConnection()
        ic.setText("Mr. ")
        val subject = controller()
        subject.type(ic, "hello")

        subject.commitWordAndText(ic, "", capsMode = 1)

        assertEquals("Mr. Hello", ic.text())
    }

    @Test
    fun tappingTheTypedWordDoesNotLearnIt() {
        var saveCount = 0
        val persistence = object : LearnedWords.Persistence {
            override fun load(): Map<String, Int> = emptyMap()
            override fun save(words: Map<String, Int>) {
                saveCount++
            }
        }
        val learned = LearnedWords(persistence)
        val engine = AutocorrectEngine(learnedWords = learned)
        val subject = ImeTextController(engine, autocorrectEnabled = { true })
        val ic = FakeTextEditConnection()

        subject.type(ic, "teh")
        assertFalse(subject.selectSuggestion(ic, "teh"))
        assertTrue(learned.words().isEmpty())

        subject.type(ic, "teh")
        assertTrue(subject.selectSuggestion(ic, "the"))
        assertEquals(1, learned.frequency("the"))
        subject.selectSuggestion(ic, "the")
        engine.flushLearnedWords()
        assertTrue(saveCount > 0)
    }

    @Test
    fun generationAndAutoAppendGuardArePerSession() {
        val subject = controller()
        val first = subject.startInput()
        assertTrue(subject.claimAutoAppend())
        assertFalse(subject.claimAutoAppend())

        subject.finishInput()
        assertFalse(subject.isCurrentGeneration(first))

        val second = subject.startInput()
        assertTrue(subject.isCurrentGeneration(second))
        assertTrue(subject.claimAutoAppend())
    }

    @Test
    fun appendUsesWholeFieldAndPutsCursorAtTrueEndOfOriginal() {
        val ic = FakeTextEditConnection()
        ic.setText("Hello world", 5) // cursor deliberately in the middle
        val subject = controller()
        val snapshot = subject.wholeFieldSnapshot(ic)
        assertEquals("Hello world", snapshot.text)
        assertEquals(11, snapshot.fieldLength)

        val cursor = subject.appendArmored(ic, snapshot, "ARMOR")

        assertEquals("Hello world\n\nARMOR\n", ic.text())
        assertEquals("Hello world".length, cursor)
        assertEquals(cursor, ic.cursor)
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun wholeFieldSnapshotPrefersExplicitSelectionAndRejectsBlankInput() {
        val ic = FakeTextEditConnection()
        ic.setText("secret", 4)
        ic.setSelection(1, 4)

        val snapshot = controller().wholeFieldSnapshot(ic)

        assertEquals("ecr", snapshot.text)
        assertTrue(snapshot.fromSelection)
        assertEquals(6, snapshot.fieldLength)

        ic.setText("   ")
        assertTrue(controller().wholeFieldSnapshot(ic).text.isBlank())
    }

    @Test
    fun backspaceDeletesSelectionBeforeTouchingComposing() {
        val ic = FakeTextEditConnection()
        ic.setText("abcdef")
        ic.setSelection(1, 4)

        assertTrue(controller().backspace(ic))

        assertEquals("aef", ic.text())
        assertEquals(0, ic.batchDepth)
    }

    @Test
    fun backspaceOutsideComposingDeletesOneCodePoint() {
        val ic = FakeTextEditConnection()
        ic.setText("abc", 3)

        assertFalse(controller().backspace(ic))

        assertEquals("ab", ic.text())
    }
}
