package com.jondude.anzu

import com.jondude.anzu.assist.HomeAssistant
import com.jondude.anzu.assist.PutUpIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "put it up" ask intents: every brief phrase matches, and the near-misses flow on to the model. */
class PutUpIntentsTest {

    private val names = listOf("Front Porch", "Living Room", "Garage")
    private val cams = listOf(
        HomeAssistant.HaCamera("camera.front_porch", "Front Porch"),
        HomeAssistant.HaCamera("camera.living_room", "Living Room"),
        HomeAssistant.HaCamera("camera.garage", "Garage")
    )

    private fun match(text: String) = PutUpIntents.match(text, names)

    // ---- Camera put-ups ----

    @Test
    fun showMeTheFrontPorch() {
        assertEquals(PutUpIntents.Result.Camera("Front Porch"), match("show me the front porch"))
        assertEquals(PutUpIntents.Result.Camera("Front Porch"), match("Atlas, show me the front porch please"))
    }

    @Test
    fun putThePorchCameraUp() {
        val r = match("put the porch camera up")
        assertTrue(r is PutUpIntents.Result.Camera)
        assertEquals(0, PutUpIntents.resolveCamera((r as PutUpIntents.Result.Camera).query, cams))
    }

    @Test
    fun genericCameraPhraseCarriesTheQuery() {
        // A camera that exists is matched by name first; an unknown one falls to the generic query.
        assertEquals(PutUpIntents.Result.Camera("Garage"), match("show me the garage camera"))
        assertEquals(2, PutUpIntents.resolveCamera("garage", cams))
        assertEquals(PutUpIntents.Result.Camera("back deck"), PutUpIntents.match("show me the back deck camera", emptyList()))
    }

    @Test
    fun showMeTheCamerasIsAll() {
        assertEquals(PutUpIntents.Result.CamerasAll, match("show me the cameras"))
        assertEquals(PutUpIntents.Result.CamerasAll, match("put the cameras up"))
        assertEquals(PutUpIntents.Result.CamerasAll, match("show me all the cameras"))
    }

    @Test
    fun resolveCameraRules() {
        assertEquals(0, PutUpIntents.resolveCamera("Front Porch", cams)) // exact name
        assertEquals(1, PutUpIntents.resolveCamera("living room", cams)) // full name in the query
        assertEquals(0, PutUpIntents.resolveCamera("porch", cams)) // distinctive word
        assertEquals(-1, PutUpIntents.resolveCamera("back deck", cams)) // no such camera
        assertEquals(-1, PutUpIntents.resolveCamera("", cams)) // blank is ambiguous with several cameras
        assertEquals(0, PutUpIntents.resolveCamera("", cams.take(1))) // blank is fine with exactly one
    }

    // ---- Brief / house / next ----

    @Test
    fun briefPhrases() {
        assertEquals(PutUpIntents.Result.Brief, match("put the brief up"))
        assertEquals(PutUpIntents.Result.Brief, match("show my brief"))
        assertEquals(PutUpIntents.Result.Brief, match("show me the brief"))
    }

    @Test
    fun housePhrases() {
        assertEquals(PutUpIntents.Result.House, match("show the house"))
        assertEquals(PutUpIntents.Result.House, match("show me the house"))
    }

    @Test
    fun nextPhrases() {
        assertEquals(PutUpIntents.Result.NextEvent, match("what's next"))
        assertEquals(PutUpIntents.Result.NextEvent, match("show my next event"))
        assertEquals(PutUpIntents.Result.NextEvent, match("show me what's next"))
    }

    // ---- Near-misses: these must flow on to the model ----

    @Test
    fun nearMissesDoNotMatch() {
        val misses = listOf(
            "show me the front porch weather", // weather that happens to name a camera
            "what's the weather on the front porch", // a question, not a put-up
            "show me the camera settings", // settings, not a feed
            "open the camera app", // the camera APP
            "show me photos of the house", // photos, and not the house phrase
            "tell me about the brief history of rome", // "brief" as an adjective
            "put the briefcase down", // briefcase is not the brief
            "what's next for the company", // not the calendar ask
            "what time is the next event", // a question about an event
            "brief me", // the spoken brief (handled by Spoken.isBriefRequest, not tiles)
            "show me a movie",
            "show me the house rules for poker night"
        )
        for (m in misses) assertEquals("should not match: $m", PutUpIntents.Result.None, match(m))
    }

    @Test
    fun aCameraThatDoesNotExistCannotBeNamed() {
        // With no camera list (HA unset), a bare room name is not enough: the ask flows on.
        assertEquals(PutUpIntents.Result.None, PutUpIntents.match("show me the front porch", emptyList()))
        // But the explicit camera phrasing still matches, so the handler can answer honestly.
        assertEquals(PutUpIntents.Result.Camera("porch"), PutUpIntents.match("put the porch camera up", emptyList()))
    }

    @Test
    fun mightMatchGate() {
        assertTrue(PutUpIntents.mightMatch("show me the front porch"))
        assertTrue(PutUpIntents.mightMatch("what's next"))
        assertTrue(PutUpIntents.mightMatch("put the brief up"))
        assertFalse(PutUpIntents.mightMatch("what's the weather on the front porch"))
        assertFalse(PutUpIntents.mightMatch("tell me a story"))
    }

    // ---- Holodeck (v1.2.19) ----

    @Test
    fun holodeckPhrases() {
        assertEquals(PutUpIntents.Result.Holodeck, match("show my print job"))
        assertEquals(PutUpIntents.Result.Holodeck, match("show the holodeck job"))
        assertEquals(PutUpIntents.Result.Holodeck, match("what's my build doing"))
        assertEquals(PutUpIntents.Result.Holodeck, match("put the build job up"))
        assertTrue(PutUpIntents.mightMatch("what's my build doing"))
        assertEquals(PutUpIntents.Result.None, match("show me the build quality of the house"))
    }

    // ---- Control phrases (v1.2.19) ----

    @Test
    fun controlPhrases() {
        assertEquals(PutUpIntents.Result.ClearAll, PutUpIntents.matchControl("clear the screen"))
        assertEquals(PutUpIntents.Result.ClearAll, PutUpIntents.matchControl("clear"))
        assertEquals(PutUpIntents.Result.ClearAll, PutUpIntents.matchControl("take it all down"))
        assertEquals(PutUpIntents.Result.Pin, PutUpIntents.matchControl("keep that up"))
        assertEquals(PutUpIntents.Result.Pin, PutUpIntents.matchControl("pin that"))
        assertEquals(PutUpIntents.Result.Unpin, PutUpIntents.matchControl("unpin that"))
        assertEquals(PutUpIntents.Result.Unpin, PutUpIntents.matchControl("let it go"))
        assertEquals(PutUpIntents.Result.Grow, PutUpIntents.matchControl("make it bigger"))
        assertEquals(PutUpIntents.Result.Shrink, PutUpIntents.matchControl("make it smaller"))
        // Near-misses are conversation, not tile commands.
        assertEquals(null, PutUpIntents.matchControl("clearance sale prices"))
        assertEquals(null, PutUpIntents.matchControl("keep that thought"))
        assertEquals(null, PutUpIntents.matchControl("make it bigger next time we order pizza"))
    }

    // ---- Multi put-up in one breath (v1.2.19) ----

    @Test
    fun multiBriefAndPorch() {
        val r = PutUpIntents.matchMulti("put the brief up and the porch", names)
        assertTrue(r is PutUpIntents.Result.Multi)
        r as PutUpIntents.Result.Multi
        assertEquals(listOf(PutUpIntents.Result.Brief, PutUpIntents.Result.Camera("Front Porch")), r.parts)
        assertEquals(emptyList<String>(), r.unresolved)
    }

    @Test
    fun multiThreeItems() {
        val r = PutUpIntents.matchMulti("show the house and my next event and the brief", names)
        assertTrue(r is PutUpIntents.Result.Multi)
        r as PutUpIntents.Result.Multi
        assertEquals(
            listOf(PutUpIntents.Result.House, PutUpIntents.Result.NextEvent, PutUpIntents.Result.Brief),
            r.parts
        )
        assertEquals(emptyList<String>(), r.unresolved)
    }

    @Test
    fun multiCamerasAndHolodeck() {
        val r = PutUpIntents.matchMulti("show the cameras and the holodeck job", names)
        assertTrue(r is PutUpIntents.Result.Multi)
        r as PutUpIntents.Result.Multi
        assertEquals(listOf(PutUpIntents.Result.CamerasAll, PutUpIntents.Result.Holodeck), r.parts)
    }

    @Test
    fun multiNamesTheUnresolvable() {
        val r = PutUpIntents.matchMulti("put the brief up and the game score", names)
        assertTrue(r is PutUpIntents.Result.Multi)
        r as PutUpIntents.Result.Multi
        assertEquals(listOf(PutUpIntents.Result.Brief), r.parts)
        assertEquals(listOf("game score"), r.unresolved) // named back, never silently dropped
    }

    @Test
    fun multiNeedsAConjunctionAndAResolution() {
        assertEquals(null, PutUpIntents.matchMulti("put the brief up", names))
        assertEquals(null, PutUpIntents.matchMulti("show me love and affection", names))
    }
}
