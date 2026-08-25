package com.tjcelaya.calwrite.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tjcelaya.calwrite.CalWriteApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = CalWriteApplication::class)
class VoiceStopBehaviorTest {

    private fun prefs(): StoragePreferences =
        StoragePreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `fromName falls back to the default for unknown or missing values`() {
        assertEquals(VoiceStopBehavior.SAVE_SILENTLY, VoiceStopBehavior.fromName(null))
        assertEquals(VoiceStopBehavior.SAVE_SILENTLY, VoiceStopBehavior.fromName(""))
        assertEquals(VoiceStopBehavior.SAVE_SILENTLY, VoiceStopBehavior.fromName("NOT_A_BEHAVIOR"))
    }

    @Test
    fun `fromName round-trips every value`() {
        for (behavior in VoiceStopBehavior.entries) {
            assertEquals(behavior, VoiceStopBehavior.fromName(behavior.name))
        }
    }

    @Test
    fun `the preference defaults to saving silently`() {
        assertEquals(VoiceStopBehavior.SAVE_SILENTLY, prefs().getVoiceStopBehavior())
    }

    @Test
    fun `the preference persists every value`() {
        val storagePreferences = prefs()
        for (behavior in VoiceStopBehavior.entries) {
            storagePreferences.setVoiceStopBehavior(behavior)
            assertEquals(behavior, storagePreferences.getVoiceStopBehavior())
            // A fresh instance reads the same SharedPreferences file, which is what the settings
            // screen and the voice surfaces each do.
            assertEquals(behavior, prefs().getVoiceStopBehavior())
        }
    }
}
