package com.jondude.anzu

import com.jondude.anzu.assist.Hermes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesTest {
    @Test
    fun parseReplyReturnsTrimmedContent() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"  Hello Jon.\n"}}]}"""
        assertEquals("Hello Jon.", Hermes.parseReply(body))
    }

    @Test
    fun parseReplyEmptyOrMissingChoicesIsBlank() {
        assertEquals("", Hermes.parseReply("""{"choices":[]}"""))
        assertEquals("", Hermes.parseReply("""{"id":"x"}"""))
    }

    @Test
    fun parseReplyMalformedJsonIsBlank() {
        assertEquals("", Hermes.parseReply("not json {"))
        assertEquals("", Hermes.parseReply(""))
    }

    @Test
    fun requestBodyPutsSystemFirstThenPairs() {
        val body = JSONObject(Hermes.requestBody("be brief", listOf("user" to "hi", "assistant" to "hey", "user" to "now?"), "m1"))
        assertEquals("m1", body.getString("model"))
        val msgs = body.getJSONArray("messages")
        assertEquals(4, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("be brief", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        assertEquals("assistant", msgs.getJSONObject(2).getString("role"))
        assertEquals("now?", msgs.getJSONObject(3).getString("content"))
    }

    @Test
    fun requestBodyDefaultsBlankModelAndOmitsNullSystem() {
        val body = JSONObject(Hermes.requestBody(null, listOf("user" to "do it"), "  "))
        assertEquals("hermes-agent", body.getString("model"))
        val msgs = body.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }
}
