package com.tjcelaya.calwrite.ui.ledger

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.tjcelaya.calwrite.R
import com.tjcelaya.calwrite.CalWriteApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = CalWriteApplication::class)
class LedgerStringsTest {

    private val newKeys = listOf(
        R.string.ledger_title,
        R.string.ledger_empty_title,
        R.string.ledger_empty_message,
        R.string.ledger_day_today,
        R.string.ledger_day_yesterday,
        R.string.ledger_badge_synced,
        R.string.ledger_badge_unsynced,
        R.string.ledger_instant_label,
        R.string.ledger_action_extend,
        R.string.ledger_action_adjust,
        R.string.ledger_action_delete,
        R.string.ledger_undo,
        R.string.ledger_adjust_start,
        R.string.ledger_adjust_end,
        R.string.ledger_adjust_notes,
        R.string.ledger_adjust_save,
        R.string.ledger_adjust_end_before_start,
        R.string.ledger_undo_channel_name,
        R.string.ledger_undo_channel_description,
        R.string.settings_voice_section_title,
        R.string.settings_voice_stop_behavior_title,
        R.string.settings_voice_stop_behavior_description,
        R.string.settings_voice_stop_save_silently,
        R.string.settings_voice_stop_confirm_dialog,
        R.string.settings_voice_stop_save_with_undo,
        R.string.settings_voice_stop_status_save_silently,
        R.string.settings_voice_stop_status_confirm_dialog,
        R.string.settings_voice_stop_status_save_with_undo
    )

    private fun localized(locale: Locale): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    @Test
    fun `every new key resolves in both English and Spanish`() {
        val english = localized(Locale.ENGLISH)
        val spanish = localized(Locale("es"))
        for (key in newKeys) {
            assertTrue(english.getString(key).isNotBlank())
            assertTrue(spanish.getString(key).isNotBlank())
        }
    }

    @Test
    fun `the Spanish strings are actually translated`() {
        val english = localized(Locale.ENGLISH)
        val spanish = localized(Locale("es"))
        // A representative sample; identical text here would mean the es file was never updated.
        for (key in listOf(R.string.ledger_title, R.string.ledger_day_today, R.string.ledger_undo)) {
            assertNotEquals(english.getString(key), spanish.getString(key))
        }
    }

    @Test
    fun `duration formatting picks the coarsest useful unit`() {
        val context = localized(Locale.ENGLISH)
        assertEquals("45s", LedgerFormatting.duration(context, TimeUnit.SECONDS.toMillis(45)))
        assertEquals("42m", LedgerFormatting.duration(context, TimeUnit.MINUTES.toMillis(42)))
        assertEquals(
            "1h 23m",
            LedgerFormatting.duration(context, TimeUnit.MINUTES.toMillis(83))
        )
        // Instant events report a zero duration; it must not render as an empty string.
        assertEquals("0s", LedgerFormatting.duration(context, 0L))
        assertEquals("0s", LedgerFormatting.duration(context, -5L))
    }
}
