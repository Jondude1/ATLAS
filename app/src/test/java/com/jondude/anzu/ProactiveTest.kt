package com.jondude.anzu

import com.jondude.anzu.assist.Hub
import com.jondude.anzu.assist.Proactive
import com.jondude.anzu.assist.Proactive.Act
import com.jondude.anzu.assist.Proactive.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveTest {
    private val now = 1_000_000_000L
    private fun speak(text: String = "Someone is at the door.") = Decision(Act.SPEAK, text, "worth knowing")
    private fun enforce(
        d: Decision, kind: String = "person_seen", key: String = "k1", seen: Map<String, Long> = emptyMap(),
        speakAt: List<Long> = emptyList(), notifyAt: List<Long> = emptyList(), quiet: Boolean = false
    ) = Proactive.enforce(kind, key, d, seen, speakAt, notifyAt, quiet, now)

    @Test
    fun parseTriggersReadsValidBatch() {
        val body = """{"triggers":[
            {"id":1,"source":"frigate","kind":"person_seen","summary":"Person at door","payload":{"cam":"front"},"created_at":"2026-11-19T10:00:00Z","dedupe_key":"frigate:front"},
            {"id":2,"source":"ha","kind":"door_open","summary":"Back door","payload":{},"created_at":"2026-11-19T10:01:00Z","dedupe_key":"ha:back"}]}"""
        val l = Hub.parseTriggers(body)
        assertEquals(2, l.size)
        assertEquals(1L, l[0].id)
        assertEquals("person_seen", l[0].kind)
        assertEquals("front", l[0].payload.getString("cam"))
        assertEquals("frigate:front", l[0].dedupeKey)
        assertEquals("2026-11-19T10:01:00Z", l[1].createdAt)
    }

    @Test
    fun parseTriggersSkipsMalformedEntries() {
        val body = """{"triggers":[42,{"id":1,"kind":"a","source":"s","summary":"ok"},{"source":"s","kind":"nokid"},{"id":3,"source":"s"},{"id":4,"kind":"b","source":"s"}]}"""
        assertEquals(listOf(1L, 4L), Hub.parseTriggers(body).map { it.id })
    }

    @Test
    fun parseTriggersMissingOptionalFieldsGetDefaults() {
        val t = Hub.parseTriggers("""{"triggers":[{"id":5,"source":"ha","kind":"door_open"}]}""").single()
        assertEquals("", t.summary)
        assertEquals(0, t.payload.length())
        assertEquals("ha:door_open", t.dedupeKey)
    }

    @Test
    fun parseTriggersBadBodyIsEmpty() {
        assertTrue(Hub.parseTriggers("not json {").isEmpty())
        assertTrue(Hub.parseTriggers("""{"nope":1}""").isEmpty())
    }

    @Test
    fun parseDecisionReadsEachAct() {
        assertEquals(Decision(Act.SPEAK, "Hi", "r"), Proactive.parseDecision("""{"act":"speak","text":"Hi","reason":"r"}"""))
        assertEquals(Decision(Act.NOTIFY, "Hi", "r"), Proactive.parseDecision("""{"act":"notify","text":"Hi","reason":"r"}"""))
        assertEquals(Act.GATE, Proactive.parseDecision("""{"act":"gate","text":"Pay it?","reason":"money"}""").act)
        assertEquals(Act.SUPPRESS, Proactive.parseDecision("""{"act":"suppress","reason":"noise"}""").act)
    }

    @Test
    fun parseDecisionExtractsJsonFromChatter() {
        val d = Proactive.parseDecision("Sure!\n```json\n{\"act\":\"Speak\",\"text\":\"Hi\",\"reason\":\"r\"}\n```")
        assertEquals(Act.SPEAK, d.act)
    }

    @Test
    fun parseDecisionFailsClosed() {
        listOf(
            "", "garbage", "{not json}", """{"act":"launch","text":"x","reason":"r"}""",
            """{"text":"x","reason":"r"}""", """{"act":"speak","reason":"r"}""", """{"act":"speak","text":"x"}""",
            """{"act":"notify","text":"  ","reason":"r"}"""
        ).forEach {
            val d = Proactive.parseDecision(it)
            assertEquals(it, Act.SUPPRESS, d.act)
            assertEquals("unparseable decision", d.reason)
        }
    }

    @Test
    fun duplicateKeyWithinADaySuppresses() {
        val d = enforce(speak(), seen = mapOf("k1" to now - 3_600_000L))
        assertEquals(Act.SUPPRESS, d.act)
        assertEquals("duplicate", d.reason)
    }

    @Test
    fun oldKeyIsNotADuplicate() {
        assertEquals(Act.SPEAK, enforce(speak(), seen = mapOf("k1" to now - 25 * 3_600_000L)).act)
    }

    @Test
    fun speakCapSuppressesButNotifyStillHasRoom() {
        val full = List(Proactive.SPEAK_CAP_PER_HOUR) { now - 60_000L }
        val d = enforce(speak(), speakAt = full)
        assertEquals(Act.SUPPRESS, d.act)
        assertEquals("rate limit", d.reason)
        assertEquals(Act.NOTIFY, enforce(Decision(Act.NOTIFY, "x", "r"), speakAt = full).act)
        assertEquals(Act.SPEAK, enforce(speak(), speakAt = full.map { now - 2 * 3_600_000L }).act)
    }

    @Test
    fun notifyCapSuppresses() {
        val full = List(Proactive.NOTIFY_CAP_PER_HOUR) { now - 60_000L }
        assertEquals("rate limit", enforce(Decision(Act.NOTIFY, "x", "r"), notifyAt = full).reason)
    }

    @Test
    fun quietDowngradesSpeakToNotify() {
        val d = enforce(speak(), quiet = true)
        assertEquals(Act.NOTIFY, d.act)
        assertEquals(Act.SPEAK, enforce(speak(), quiet = false).act)
    }

    @Test
    fun quietDowngradeRespectsNotifyCap() {
        val full = List(Proactive.NOTIFY_CAP_PER_HOUR) { now - 60_000L }
        assertEquals(Act.SUPPRESS, enforce(speak(), quiet = true, notifyAt = full).act)
    }

    @Test
    fun gatedKindOverridesSpeak() {
        listOf("payment_due", "send_reply", "delete_old_files", "email_draft").forEach {
            assertEquals(it, Act.GATE, enforce(speak(), kind = it).act)
        }
    }

    @Test
    fun gatedIntentInTextOverridesNotify() {
        val d = enforce(Decision(Act.NOTIFY, "I'll send a text to Sam that you're late.", "helpful"))
        assertEquals(Act.GATE, d.act)
    }

    @Test
    fun modelGateStaysGatedAndPlainEventsPass() {
        assertEquals(Act.GATE, enforce(Decision(Act.GATE, "Okay to proceed?", "r")).act)
        assertEquals(Act.NOTIFY, enforce(Decision(Act.NOTIFY, "Package delivered.", "r"), kind = "delivery").act)
    }
}
