package com.jondude.anzu

import com.jondude.anzu.assist.Hub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsMarketplaceTest {
    @Test fun searchParsesTwoHits() {
        val body = """{"query":"music","results":[
            {"name":"spotify-control","identifier":"official/media/spotify-control","source":"official","trust_level":"builtin","description":"Control Spotify playback."},
            {"name":"lofi-maker","identifier":"skills-sh/acme/lofi-maker","source":"skills.sh","trust_level":"community","description":"Generate lofi beats."}]}"""
        val hits = Hub.parseSkillSearch(body)
        assertEquals(2, hits.size)
        assertEquals(Hub.SkillHit("spotify-control", "official/media/spotify-control", "official", "builtin", "Control Spotify playback."), hits[0])
        assertEquals(Hub.SkillHit("lofi-maker", "skills-sh/acme/lofi-maker", "skills.sh", "community", "Generate lofi beats."), hits[1])
    }

    @Test fun searchEmptyAndGarbage() {
        assertTrue(Hub.parseSkillSearch("""{"query":"x","results":[]}""").isEmpty())
        assertTrue(Hub.parseSkillSearch("not json at all").isEmpty())
        assertTrue(Hub.parseSkillSearch("").isEmpty())
    }

    @Test fun installedParsesRow() {
        val rows = Hub.parseSkillInstalled("""{"installed":[{"name":"spotify-control","category":"media","source":"official","trust":"builtin","status":"enabled"}]}""")
        assertEquals(listOf(Hub.SkillInstalled("spotify-control", "media", "official", "builtin", "enabled")), rows)
    }

    @Test fun installedEmpty() {
        assertTrue(Hub.parseSkillInstalled("""{"installed":[]}""").isEmpty())
        assertTrue(Hub.parseSkillInstalled("garbage").isEmpty())
    }

    @Test fun inspectReturnsTextOrNull() {
        assertEquals("# Skill\nDoes things.", Hub.parseSkillInspect("""{"identifier":"a/b","text":"# Skill\nDoes things."}"""))
        assertNull(Hub.parseSkillInspect("""{"identifier":"a/b"}"""))
        assertNull(Hub.parseSkillInspect("garbage"))
    }

    @Test fun installParsesOkAndFailure() {
        val ok = Hub.parseSkillInstall("""{"ok":true,"installed":"spotify-control","scan":"CAUTION","output":"done"}""")
        assertNotNull(ok)
        assertTrue(ok!!.ok)
        assertEquals("spotify-control", ok.installed)
        assertEquals("CAUTION", ok.scan)
        val bad = Hub.parseSkillInstall("""{"ok":false,"installed":"","scan":"DANGEROUS","output":"blocked by scan"}""")
        assertNotNull(bad)
        assertFalse(bad!!.ok)
        assertEquals("blocked by scan", bad.output)
        assertNull(Hub.parseSkillInstall("garbage"))
    }
}
