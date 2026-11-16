package com.jondude.anzu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitTest {
    @Test
    fun splitsOnTerminatorsFollowedBySpace() {
        assertEquals(listOf("Hi there.", "How are you?", "Great!"), CloudVoice.splitSentences("Hi there. How are you? Great!"))
    }

    @Test
    fun keepsTrailingTextWithoutTerminator() {
        assertEquals(listOf("One.", "two and a bit"), CloudVoice.splitSentences("One. two and a bit"))
    }

    @Test
    fun doesNotSplitInsideNumbers() {
        assertEquals(listOf("It is 3.5 degrees."), CloudVoice.splitSentences("It is 3.5 degrees."))
    }

    @Test
    fun neverEmptyChunks() {
        assertTrue(CloudVoice.splitSentences("").isEmpty())
        assertTrue(CloudVoice.splitSentences("   \n ").isEmpty())
        assertEquals(listOf("Wait...", "Ok."), CloudVoice.splitSentences("  Wait...   Ok.  "))
    }

    @Test
    fun longSentenceStaysOneChunk() {
        val long = "word ".repeat(200).trim() + "."
        assertEquals(listOf(long), CloudVoice.splitSentences(long))
    }
}
