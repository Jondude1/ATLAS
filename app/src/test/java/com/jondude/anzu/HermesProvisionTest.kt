package com.jondude.anzu

import com.jondude.anzu.assist.Hub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesProvisionTest {
    @Test
    fun parsesValidPayload() {
        val cfg = Hub.parseHermesConfig("""{"url":"http://example.test:8642/v1","key":"FAKE-TEST-KEY","model":"hermes-agent"}""")
        assertEquals(Hub.HermesConfig("http://example.test:8642/v1", "FAKE-TEST-KEY", "hermes-agent"), cfg)
    }

    @Test
    fun missingModelDefaultsToHermesAgent() {
        assertEquals("hermes-agent", Hub.parseHermesConfig("""{"url":"http://example.test/v1","key":"FAKE-TEST-KEY"}""")?.model)
    }

    @Test
    fun missingOrBlankKeyIsNotProvisioned() {
        assertNull(Hub.parseHermesConfig("""{"url":"http://example.test/v1","model":"m"}"""))
        assertNull(Hub.parseHermesConfig("""{"url":"http://example.test/v1","key":"  ","model":"m"}"""))
    }

    @Test
    fun blankOrMissingUrlIsNotProvisioned() {
        assertNull(Hub.parseHermesConfig("""{"url":"","key":"FAKE-TEST-KEY","model":"m"}"""))
        assertNull(Hub.parseHermesConfig("""{"key":"FAKE-TEST-KEY"}"""))
    }

    @Test
    fun malformedJsonIsNull() {
        assertNull(Hub.parseHermesConfig("not json {"))
        assertNull(Hub.parseHermesConfig(""))
    }
}
