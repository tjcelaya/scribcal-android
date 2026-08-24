package com.tjcelaya.scribcal.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The deep link is both the automation hook and the adb test harness, so it has to survive
 * whatever gets typed at it — a shell-quoted name with spaces, a stray trailing slash, an
 * unexpected verb.
 */
class VoiceIntentParserTest {

    @Test
    fun `parses each documented verb`() {
        val verbs = mapOf(
            "start" to VoiceActionType.START,
            "stop" to VoiceActionType.STOP,
            "record" to VoiceActionType.RECORD,
            "extend" to VoiceActionType.EXTEND,
            "status" to VoiceActionType.STATUS
        )
        verbs.forEach { (verb, expected) ->
            assertEquals(
                VoiceRequest(expected),
                VoiceIntentParser.fromDeepLink("scribcal://action/$verb")
            )
        }
    }

    @Test
    fun `parses the name parameter`() {
        assertEquals(
            VoiceRequest(VoiceActionType.START, "Exercise"),
            VoiceIntentParser.fromDeepLink("scribcal://action/start?name=Exercise")
        )
    }

    @Test
    fun `decodes percent and plus encoded names`() {
        assertEquals(
            "Morning Run",
            VoiceIntentParser.fromDeepLink("scribcal://action/start?name=Morning%20Run")?.typeQuery
        )
        assertEquals(
            "Morning Run",
            VoiceIntentParser.fromDeepLink("scribcal://action/start?name=Morning+Run")?.typeQuery
        )
        assertEquals(
            "Café",
            VoiceIntentParser.fromDeepLink("scribcal://action/record?name=Caf%C3%A9")?.typeQuery
        )
    }

    @Test
    fun `ignores unrelated query parameters and fragments`() {
        assertEquals(
            VoiceRequest(VoiceActionType.STOP, "Exercise"),
            VoiceIntentParser.fromDeepLink("scribcal://action/stop?source=tasker&name=Exercise#x")
        )
    }

    @Test
    fun `treats a blank name as absent`() {
        assertNull(VoiceIntentParser.fromDeepLink("scribcal://action/stop?name=")?.typeQuery)
        assertNull(VoiceIntentParser.fromDeepLink("scribcal://action/stop?name=%20")?.typeQuery)
    }

    @Test
    fun `tolerates casing and a trailing slash`() {
        assertEquals(
            VoiceRequest(VoiceActionType.STATUS),
            VoiceIntentParser.fromDeepLink("SCRIBCAL://Action/Status/")
        )
    }

    @Test
    fun `accepts the host-less short form`() {
        assertEquals(
            VoiceRequest(VoiceActionType.START, "Exercise"),
            VoiceIntentParser.fromDeepLink("scribcal://start?name=Exercise")
        )
    }

    @Test
    fun `rejects anything that is not a voice deep link`() {
        assertNull(VoiceIntentParser.fromDeepLink(null))
        assertNull(VoiceIntentParser.fromDeepLink(""))
        assertNull(VoiceIntentParser.fromDeepLink("https://scribcal.app/action/start"))
        assertNull(VoiceIntentParser.fromDeepLink("scribcal://action/pause"))
        assertNull(VoiceIntentParser.fromDeepLink("scribcal://other/start"))
        assertNull(VoiceIntentParser.fromDeepLink("scribcal://a/b/c/start"))
        assertNull(VoiceIntentParser.fromDeepLink("scribcal://"))
    }

    @Test
    fun `maps capability intent actions round trip`() {
        VoiceActionType.entries.forEach { type ->
            assertEquals(
                VoiceRequest(type, "Exercise"),
                VoiceIntentParser.fromAction(VoiceIntentParser.actionNameFor(type), "Exercise")
            )
        }
    }

    @Test
    fun `an unknown intent action is not a voice request`() {
        assertNull(VoiceIntentParser.fromAction("android.intent.action.VIEW", "Exercise"))
        assertNull(VoiceIntentParser.fromAction(null, null))
    }

    @Test
    fun `trims a name Assistant padded with whitespace`() {
        assertEquals(
            "Exercise",
            VoiceIntentParser.fromAction(VoiceIntentParser.ACTION_START, "  Exercise ")?.typeQuery
        )
        assertNull(
            VoiceIntentParser.fromAction(VoiceIntentParser.ACTION_START, "   ")?.typeQuery
        )
    }
}
