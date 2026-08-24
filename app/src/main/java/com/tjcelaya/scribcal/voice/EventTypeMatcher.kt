package com.tjcelaya.scribcal.voice

import com.tjcelaya.scribcal.data.database.EventType
import java.text.Normalizer

/**
 * Resolves a spoken phrase to one of the user's event types.
 *
 * Assistants hand over loosely-transcribed text: casing varies, accents get dropped, and the
 * carrier phrase often survives into the parameter ("start my exercise" rather than "Exercise").
 * Event type names are entirely user-defined, so we cannot rely on a fixed vocabulary.
 *
 * Deliberately pure Kotlin — no Android types — so the ladder below can be tested directly.
 */
object EventTypeMatcher {

    sealed interface Resolution {
        data class Match(val eventType: EventType) : Resolution
        /** More than one plausible type; ask rather than guess. */
        data class Ambiguous(val candidates: List<EventType>) : Resolution
        data object None : Resolution
    }

    /**
     * Words an assistant tends to leave attached to the parameter. Stripped only from the start,
     * and only when something remains — an event type legitimately named "Start" still resolves.
     */
    private val LEADING_FILLER = setOf(
        // English
        "start", "starting", "stop", "stopping", "end", "ending", "record", "recording",
        "log", "logging", "track", "tracking", "extend", "extending", "begin", "beginning",
        "my", "a", "an", "the", "some",
        // Spanish
        "iniciar", "inicia", "empezar", "empieza", "comenzar", "comienza",
        "parar", "para", "detener", "deten", "terminar", "termina",
        "registrar", "registra", "grabar", "graba", "extender", "extiende",
        "mi", "mis", "un", "una", "el", "la", "los", "las"
    )

    fun resolve(query: String?, eventTypes: List<EventType>): Resolution {
        if (query.isNullOrBlank() || eventTypes.isEmpty()) return Resolution.None

        val candidates = listOfNotNull(
            query.takeIf { it.isNotBlank() },
            stripLeadingFiller(query).takeIf { it.isNotBlank() && it != query }
        )

        for (candidate in candidates) {
            when (val resolution = resolveExact(candidate, eventTypes)) {
                is Resolution.Match, is Resolution.Ambiguous -> return resolution
                Resolution.None -> Unit
            }
        }
        for (candidate in candidates) {
            when (val resolution = resolveFuzzy(candidate, eventTypes)) {
                is Resolution.Match, is Resolution.Ambiguous -> return resolution
                Resolution.None -> Unit
            }
        }
        return Resolution.None
    }

    /** Tier 1-2: exact, then accent/case/punctuation-insensitive. */
    private fun resolveExact(query: String, eventTypes: List<EventType>): Resolution {
        eventTypes.firstOrNull { it.name == query }?.let { return Resolution.Match(it) }

        val normalized = normalize(query)
        if (normalized.isEmpty()) return Resolution.None
        return eventTypes.filter { normalize(it.name) == normalized }.toResolution()
    }

    /** Tier 3-4: unique prefix, then unique substring. */
    private fun resolveFuzzy(query: String, eventTypes: List<EventType>): Resolution {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return Resolution.None

        val byPrefix = eventTypes.filter { normalize(it.name).startsWith(normalized) }
        if (byPrefix.isNotEmpty()) return byPrefix.toResolution()

        // Also match the other direction: "exercise" should find a type named "Exercise (gym)",
        // and "morning exercise routine" should find "Exercise".
        val bySubstring = eventTypes.filter {
            val name = normalize(it.name)
            name.contains(normalized) || normalized.contains(name)
        }
        return bySubstring.toResolution()
    }

    private fun List<EventType>.toResolution(): Resolution = when (size) {
        0 -> Resolution.None
        1 -> Resolution.Match(first())
        else -> Resolution.Ambiguous(this)
    }

    private fun stripLeadingFiller(query: String): String {
        var words = query.trim().split(Regex("\\s+"))
        while (words.size > 1 && normalize(words.first()) in LEADING_FILLER) {
            words = words.drop(1)
        }
        return words.joinToString(" ")
    }

    /** Lowercase, strip diacritics and punctuation, collapse whitespace. */
    internal fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
