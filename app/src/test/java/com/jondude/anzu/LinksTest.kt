package com.jondude.anzu

import com.jondude.anzu.assist.Links
import com.jondude.anzu.assist.Links.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    private fun r(name: String, s: State) = Links.Result(Links.Target(name, name, "x"), s, 5, "")

    private fun round(vararg s: State) = s.mapIndexed { i, st -> r(listOf("Home Assistant", "Obelisk hub", "Ollama", "Athena", "Helios relay")[i], st) }

    @Test
    fun allDownIsTailscaleVerdict() {
        val d = round(State.UNREACHABLE, State.UNREACHABLE, State.UNREACHABLE, State.UNREACHABLE, State.UNREACHABLE)
        assertEquals(Links.TAILSCALE_DOWN, Links.verdict(d))
        assertTrue(Links.verdict(d).startsWith("Tailscale looks down."))
        assertTrue(Links.allDown(d))
    }

    @Test
    fun someDownNamesThem() {
        val one = round(State.UNREACHABLE, State.REACHABLE, State.REACHABLE, State.REACHABLE, State.REACHABLE)
        assertEquals("Home Assistant is not answering; the rest are up.", Links.verdict(one))
        assertFalse(Links.allDown(one))
        val two = round(State.UNREACHABLE, State.REACHABLE, State.UNREACHABLE, State.REACHABLE, State.REACHABLE)
        assertEquals("Home Assistant and Ollama are not answering; the rest are up.", Links.verdict(two))
        val three = round(State.UNREACHABLE, State.UNREACHABLE, State.UNREACHABLE, State.REACHABLE, State.REACHABLE)
        assertEquals("Home Assistant, Obelisk hub and Ollama are not answering; the rest are up.", Links.verdict(three))
    }

    @Test
    fun allUp() {
        val u = round(State.REACHABLE, State.REACHABLE, State.REACHABLE, State.REACHABLE, State.REACHABLE)
        assertEquals("All links up.", Links.verdict(u))
        assertFalse(Links.allDown(u))
    }

    @Test
    fun httpErrorCountsAsLinkUp() {
        val e = round(State.HTTP_ERROR, State.UNREACHABLE, State.UNREACHABLE, State.UNREACHABLE, State.UNREACHABLE)
        assertFalse(Links.allDown(e))
        assertEquals("Obelisk hub, Ollama, Athena and Helios relay are not answering; the rest are up.", Links.verdict(e))
        assertFalse(Links.allDown(null))
        assertFalse(Links.allDown(emptyList()))
    }
}
