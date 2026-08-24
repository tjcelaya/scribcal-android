package com.tjcelaya.scribcal.voice

import com.tjcelaya.scribcal.data.database.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher decides what a spoken phrase refers to, so a wrong answer here silently records the
 * wrong thing on someone's calendar. The bar is: match confidently, and ask rather than guess.
 */
class EventTypeMatcherTest {

    private fun type(id: Long, name: String) = EventType(id = id, name = name)

    private val exercise = type(1, "Exercise")
    private val coffee = type(2, "Coffee")
    private val reading = type(3, "Reading")
    private val types = listOf(exercise, coffee, reading)

    private fun matched(query: String?, candidates: List<EventType> = types): EventType? =
        (EventTypeMatcher.resolve(query, candidates) as? EventTypeMatcher.Resolution.Match)?.eventType

    // === exact and near-exact ===

    @Test
    fun `matches an exact name`() {
        assertEquals(exercise, matched("Exercise"))
    }

    @Test
    fun `ignores casing`() {
        assertEquals(exercise, matched("exercise"))
        assertEquals(exercise, matched("EXERCISE"))
    }

    @Test
    fun `ignores surrounding whitespace and punctuation`() {
        assertEquals(coffee, matched("  coffee.  "))
        assertEquals(coffee, matched("coffee!"))
    }

    @Test
    fun `ignores diacritics so dictation without accents still resolves`() {
        val siesta = type(4, "Siesta después de comer")
        assertEquals(siesta, matched("siesta despues de comer", listOf(siesta)))
    }

    // === carrier phrases ===

    @Test
    fun `strips leading verbs the assistant leaves attached`() {
        assertEquals(exercise, matched("start exercise"))
        assertEquals(exercise, matched("stop my exercise"))
        assertEquals(coffee, matched("record a coffee"))
    }

    @Test
    fun `strips Spanish carrier phrases`() {
        assertEquals(exercise, matched("iniciar exercise"))
        assertEquals(coffee, matched("registrar mi coffee"))
    }

    @Test
    fun `does not strip a filler word that is the entire name`() {
        val start = type(9, "Start")
        assertEquals(start, matched("start", listOf(start)))
    }

    // === fuzzy ===

    @Test
    fun `matches on a unique prefix`() {
        assertEquals(reading, matched("read"))
    }

    @Test
    fun `matches when the spoken phrase contains the name`() {
        assertEquals(exercise, matched("morning exercise routine"))
    }

    @Test
    fun `matches when the name contains the spoken phrase`() {
        val gym = type(5, "Exercise (gym)")
        assertEquals(gym, matched("gym", listOf(gym)))
    }

    // === refusing to guess ===

    @Test
    fun `asks when a prefix matches more than one type`() {
        val walk = type(6, "Walk")
        val walking = type(7, "Walking the dog")
        // "wal" is nobody's exact name, so neither candidate can win outright.
        val resolution = EventTypeMatcher.resolve("wal", listOf(walk, walking))

        assertTrue(resolution is EventTypeMatcher.Resolution.Ambiguous)
        assertEquals(
            listOf(walk, walking),
            (resolution as EventTypeMatcher.Resolution.Ambiguous).candidates
        )
    }

    @Test
    fun `prefers an exact match over an ambiguous prefix`() {
        val walk = type(6, "Walk")
        val walking = type(7, "Walking the dog")
        assertEquals(walk, matched("Walk", listOf(walk, walking)))
    }

    @Test
    fun `returns None for an unrelated phrase`() {
        assertEquals(EventTypeMatcher.Resolution.None, EventTypeMatcher.resolve("kayaking", types))
    }

    @Test
    fun `returns None for blank or missing input`() {
        assertEquals(EventTypeMatcher.Resolution.None, EventTypeMatcher.resolve(null, types))
        assertEquals(EventTypeMatcher.Resolution.None, EventTypeMatcher.resolve("   ", types))
    }

    @Test
    fun `returns None when the user has no event types`() {
        assertEquals(EventTypeMatcher.Resolution.None, EventTypeMatcher.resolve("Exercise", emptyList()))
    }

    // === normalization ===

    @Test
    fun `normalize collapses case accents punctuation and whitespace`() {
        assertEquals("cafe con leche", EventTypeMatcher.normalize("  Café,   con   Leche!  "))
    }
}
