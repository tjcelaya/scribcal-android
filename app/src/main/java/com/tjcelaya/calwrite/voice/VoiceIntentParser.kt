package com.tjcelaya.calwrite.voice

import java.net.URLDecoder

/** The five things any voice surface can ask CalWrite to do. */
enum class VoiceActionType { START, STOP, RECORD, EXTEND, STATUS }

data class VoiceRequest(val action: VoiceActionType, val typeQuery: String? = null)

/**
 * Turns the several shapes an incoming voice intent can take into one [VoiceRequest].
 *
 * There are three callers and they do not agree on a format: Assistant launches the capability
 * intents declared in `res/xml/shortcuts.xml` (explicit component, one action string each, the
 * matched `exercise.name` handed over as an extra), while adb and automation apps send a
 * `calwrite://action/start?name=…` deep link. Keeping the parsing here — pure Kotlin, no Android
 * types — means both paths are covered by the same unit-tested ladder.
 */
object VoiceIntentParser {

    const val SCHEME = "calwrite"
    const val HOST = "action"

    // One action per capability. A capability intent is delivered to an explicit component, so
    // the action is free to carry the verb; that beats depending on a static <extra>, whose
    // support inside a shortcuts.xml capability intent is undocumented.
    const val ACTION_START = "com.tjcelaya.calwrite.action.VOICE_START"
    const val ACTION_STOP = "com.tjcelaya.calwrite.action.VOICE_STOP"
    const val ACTION_RECORD = "com.tjcelaya.calwrite.action.VOICE_RECORD"
    const val ACTION_EXTEND = "com.tjcelaya.calwrite.action.VOICE_EXTEND"
    const val ACTION_STATUS = "com.tjcelaya.calwrite.action.VOICE_STATUS"

    /** Intent extra the `exercise.name` BII parameter is bound to. */
    const val EXTRA_TYPE_NAME = "name"

    /** Deep link query parameter, deliberately identical to [EXTRA_TYPE_NAME]. */
    const val PARAM_NAME = "name"

    /** `calwrite://action/{start|stop|record|extend|status}?name=…` */
    fun fromDeepLink(uri: String?): VoiceRequest? {
        val rest = uri?.trim()?.takeIf { it.startsWith("$SCHEME://", ignoreCase = true) }
            ?.substring(SCHEME.length + 3)
            ?: return null

        val beforeFragment = rest.substringBefore('#')
        val path = beforeFragment.substringBefore('?')
        val query = beforeFragment.substringAfter('?', "")

        val segments = path.split('/').filter { it.isNotBlank() }
        val verb = when (segments.size) {
            // Tolerate calwrite://start as well as the documented calwrite://action/start.
            1 -> segments[0]
            2 -> segments[1].takeIf { segments[0].equals(HOST, ignoreCase = true) }
            else -> null
        } ?: return null

        val action = actionTypeForVerb(verb) ?: return null
        return VoiceRequest(action, queryParameter(query, PARAM_NAME))
    }

    /** An Assistant capability intent, or anything else that names the verb in its action. */
    fun fromAction(action: String?, typeQuery: String?): VoiceRequest? {
        val type = when (action) {
            ACTION_START -> VoiceActionType.START
            ACTION_STOP -> VoiceActionType.STOP
            ACTION_RECORD -> VoiceActionType.RECORD
            ACTION_EXTEND -> VoiceActionType.EXTEND
            ACTION_STATUS -> VoiceActionType.STATUS
            else -> return null
        }
        return VoiceRequest(type, typeQuery?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun actionTypeForVerb(verb: String?): VoiceActionType? = when (verb?.trim()?.lowercase()) {
        "start" -> VoiceActionType.START
        "stop" -> VoiceActionType.STOP
        "record" -> VoiceActionType.RECORD
        "extend" -> VoiceActionType.EXTEND
        "status" -> VoiceActionType.STATUS
        else -> null
    }

    fun actionNameFor(type: VoiceActionType): String = when (type) {
        VoiceActionType.START -> ACTION_START
        VoiceActionType.STOP -> ACTION_STOP
        VoiceActionType.RECORD -> ACTION_RECORD
        VoiceActionType.EXTEND -> ACTION_EXTEND
        VoiceActionType.STATUS -> ACTION_STATUS
    }

    private fun queryParameter(query: String, key: String): String? = query
        .split('&')
        .asSequence()
        .filter { it.isNotEmpty() }
        .mapNotNull { pair ->
            val name = pair.substringBefore('=')
            if (!name.equals(key, ignoreCase = true)) null else pair.substringAfter('=', "")
        }
        .map { decode(it) }
        .firstOrNull { it.isNotBlank() }
        ?.trim()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace('+', ' '), "UTF-8") }.getOrDefault(value)
}
