package com.jondude.anzu

import com.jondude.anzu.assist.CadIntents
import com.jondude.anzu.assist.Holodeck
import com.jondude.anzu.assist.Hub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone-dispatched CAD jobs: the build ask matches conservatively, the hub's reply parses, the DONE pop is exact. */
class CadDispatchTest {

    @Test
    fun buildAsksDispatchWithTheOriginalUtterance() {
        for (m in listOf(
            "make me a shelf bracket with two holes", "print a phone stand",
            "build me a cable clip", "model a gear knob"
        )) assertEquals(m, CadIntents.match(m))
        assertEquals("Make Me A Shelf Bracket", CadIntents.match("  Make Me A Shelf Bracket  "))
    }

    @Test
    fun nearMissesFlowOnToTheModel() {
        val misses = listOf(
            "make a note to buy milk", "make me a coffee", "can you make a bracket?",
            "make a list of the parts", "what's my build doing", "make a", "put my build up",
            "make me a plan for tomorrow", "could you build a shelf", "make a bracket?"
        )
        for (m in misses) assertNull("should not dispatch: $m", CadIntents.match(m))
    }

    @Test
    fun parseCadJobCreated() {
        assertEquals("ab12", Hub.parseCadJobCreated("""{"job_id":"ab12","status":"queued","kind":"new","parent":null}"""))
        assertNull(Hub.parseCadJobCreated("""{"status":"queued"}"""))
        assertNull(Hub.parseCadJobCreated("""{"job_id":"  "}"""))
        assertNull(Hub.parseCadJobCreated("not json"))
        assertNull(Hub.parseCadJobCreated(""))
    }

    private fun job(status: String) = Hub.CadJob("abc123", status, status, "make a bracket", "", "", 1)

    @Test
    fun shouldPopTruthTable() {
        assertTrue(Holodeck.shouldPop(seenActive = true, announced = false, job = job("done")))
        assertFalse(Holodeck.shouldPop(seenActive = false, announced = false, job = job("done")))
        assertFalse(Holodeck.shouldPop(seenActive = true, announced = true, job = job("done")))
        assertFalse(Holodeck.shouldPop(seenActive = true, announced = false, job = job("working")))
        assertFalse(Holodeck.shouldPop(seenActive = true, announced = false, job = job("failed")))
        assertFalse(Holodeck.shouldPop(seenActive = false, announced = true, job = job("queued")))
    }
}
