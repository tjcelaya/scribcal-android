package com.tjcelaya.scribcal.voice

import com.tjcelaya.scribcal.data.database.Cadence
import com.tjcelaya.scribcal.data.database.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceShortcutPlanTest {

    private fun type(
        id: Long,
        name: String,
        sortOrder: Int = 0,
        cadence: Cadence = Cadence.BOTH
    ) = EventType(id = id, name = name, sortOrder = sortOrder, cadence = cadence)

    @Test
    fun `ranks by sort order first`() {
        val types = listOf(type(1, "Coffee", sortOrder = 2), type(2, "Exercise", sortOrder = 1))

        val ranked = VoiceShortcutPlan.rank(types, emptyMap())

        assertEquals(listOf("Exercise", "Coffee"), ranked.map { it.name })
    }

    @Test
    fun `breaks a sort order tie with recency then name`() {
        val types = listOf(
            type(1, "Coffee"),
            type(2, "Exercise"),
            type(3, "Reading")
        )
        val lastUsed = mapOf(1L to 100L, 2L to 900L, 3L to null)

        val ranked = VoiceShortcutPlan.rank(types, lastUsed)

        assertEquals(listOf("Exercise", "Coffee", "Reading"), ranked.map { it.name })
    }

    @Test
    fun `gives every type a primary shortcut before any stop shortcut`() {
        val ranked = (1..4).map { type(it.toLong(), "Type $it", sortOrder = it) }

        val plan = VoiceShortcutPlan.plan(ranked, budget = 6)

        val starts = plan.entries.filter { it.action == VoiceActionType.START }
        val stops = plan.entries.filter { it.action == VoiceActionType.STOP }
        assertEquals(4, starts.size)
        assertEquals(2, stops.size)
        // The stops that fit belong to the highest-ranked types.
        assertEquals(listOf("Type 1", "Type 2"), stops.map { it.eventType.name })
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun `reports the types that did not fit instead of truncating silently`() {
        val ranked = (1..5).map { type(it.toLong(), "Type $it", sortOrder = it) }

        val plan = VoiceShortcutPlan.plan(ranked, budget = 3)

        assertEquals(3, plan.entries.size)
        assertTrue(plan.entries.all { it.action == VoiceActionType.START })
        assertEquals(listOf("Type 4", "Type 5"), plan.dropped.map { it.name })
    }

    @Test
    fun `an instant-only type gets a record shortcut, never a stopwatch one`() {
        val ranked = listOf(type(1, "Coffee", cadence = Cadence.INSTANT))

        val plan = VoiceShortcutPlan.plan(ranked, budget = 6)

        assertEquals(listOf(VoiceActionType.RECORD), plan.entries.map { it.action })
    }

    @Test
    fun `a zero budget publishes nothing and drops everything`() {
        val ranked = listOf(type(1, "Coffee"))

        val plan = VoiceShortcutPlan.plan(ranked, budget = 0)

        assertTrue(plan.entries.isEmpty())
        assertEquals(1, plan.dropped.size)
    }

    @Test
    fun `shortcut ids stay clear of the bubble shortcut namespace`() {
        val ids = VoiceActionType.entries.map { VoiceShortcutPlan.shortcutId(7L, it) }

        assertTrue(ids.all { VoiceShortcutPlan.isVoiceShortcutId(it) })
        assertTrue(ids.none { it.startsWith("event_ongoing_") })
        assertFalse(VoiceShortcutPlan.isVoiceShortcutId("event_ongoing_7"))
        assertEquals("voice_start_7", VoiceShortcutPlan.shortcutId(7L, VoiceActionType.START))
        assertEquals("voice_stop_7", VoiceShortcutPlan.shortcutId(7L, VoiceActionType.STOP))
    }
}
