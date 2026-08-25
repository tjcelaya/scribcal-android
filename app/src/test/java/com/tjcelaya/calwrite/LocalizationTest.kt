package com.tjcelaya.calwrite

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(application = CalWriteApplication::class)
class LocalizationTest {

    @Test
    fun testEnglishStrings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.ENGLISH)
        val localizedContext = context.createConfigurationContext(config)

        val addEvent = localizedContext.getString(R.string.record_new_event)
        val eventType = localizedContext.getString(R.string.event_type)
        val cancel = localizedContext.getString(R.string.cancel)

        // Verify English strings
        assert(addEvent == "Record New Event")
        assert(eventType == "Event Type")
        assert(cancel == "Cancel")

        println("✅ English localization test passed")
    }

    @Test
    fun testSpanishStrings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale("es"))
        val localizedContext = context.createConfigurationContext(config)

        val addEvent = localizedContext.getString(R.string.record_new_event)
        val eventType = localizedContext.getString(R.string.event_type)
        val cancel = localizedContext.getString(R.string.cancel)

        // Verify Spanish strings
        assert(addEvent == "Registrar Nuevo Evento")
        assert(eventType == "Tipo de Evento")
        assert(cancel == "Cancelar")

        println("✅ Spanish localization test passed")
    }

    @Test
    fun testAllRequiredStringsExist() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Test that all our key strings exist and are not empty
        val requiredStrings = listOf(
            R.string.app_name,
            R.string.record_new_event,
            R.string.event_type,
            R.string.event_type_hint,
            R.string.date_time,
            R.string.save_right_now,
            R.string.start_timed_event,
            R.string.cancel,
            R.string.instant_event_recorded,
            R.string.timed_event_started,
            R.string.ongoing_event_exists
        )

        for (stringRes in requiredStrings) {
            val string = context.getString(stringRes)
            assert(string.isNotEmpty()) { "String resource $stringRes is empty" }
        }

        println("✅ All required strings exist and are not empty")
    }

    /**
     * Shortcut labels are baked into what the launcher and Assistant show, so a missing Spanish
     * translation surfaces as an English label in a Spanish UI rather than as a build failure.
     */
    @Test
    fun testVoiceShortcutStringsAreTranslated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val voiceStrings = listOf(
            R.string.voice_shortcut_start,
            R.string.voice_shortcut_stop,
            R.string.voice_shortcut_record,
            R.string.voice_shortcut_status_short,
            R.string.voice_shortcut_status_long
        )

        for (locale in listOf(Locale.ENGLISH, Locale("es"))) {
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localized = context.createConfigurationContext(config)
            for (stringRes in voiceStrings) {
                assert(localized.getString(stringRes).isNotBlank()) {
                    "String resource $stringRes is missing for $locale"
                }
            }
        }

        val spanishConfig = Configuration(context.resources.configuration)
        spanishConfig.setLocale(Locale("es"))
        val spanish = context.createConfigurationContext(spanishConfig)
        assert(spanish.getString(R.string.voice_shortcut_start, "Café") == "Iniciar Café")

        println("✅ Voice shortcut strings are localized")
    }
}