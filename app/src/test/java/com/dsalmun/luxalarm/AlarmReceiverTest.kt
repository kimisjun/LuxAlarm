/*
 * This file is part of Lux Alarm, authored by Daniel Salmun, and was modified
 * for GentleWake in 2026.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Extras are read with raw string keys, so these intents mirror what
 * `AlarmRepository.scheduleNextAlarm` writes: a key drifting on one side alone silently defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmReceiverTest {
    private companion object {
        const val RINGTONE = "content://media/internal/audio/media/42"
        val ALARM_IDS = arrayListOf(1, 2)
    }

    @get:Rule val appContainer = AppContainerTestRule()

    private lateinit var context: Context

    private val repository
        get() = appContainer.repository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun whenTheRingingFlagIsClaimed_theAlarmIsHandedToTheService() {
        sendAlarmBroadcast(alarmIntent())

        val started = assertNotNull(nextStartedService(), "The alarm must reach AlarmService")
        assertEquals(AlarmService::class.java.name, started.component?.className)
        assertEquals(1, started.getIntExtra("alarm_id", -1), "The first id is the ringing one")
        assertEquals(RINGTONE, started.getStringExtra("ringtone_uri"))
        assertEquals(0.4f, started.getFloatExtra("volume", -1f))
        assertFalse(started.getBooleanExtra("vibration_enabled", true))
    }

    @Test
    fun importedWakeMusicOverridesTheLegacyPerAlarmRingtoneAtFireTime() {
        val imported =
            File(context.filesDir, "gentle-wake-audio/selected-audio").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val profile =
            WakeProfile(
                rampMinutes = 12,
                startVolume = 0.08f,
                maxVolume = 0.42f,
                dismissal = WakeDismissal.LUX,
                importedAudioPath = imported.path,
            )
        appContainer.settingsManager.updateWakeProfile(profile)

        sendAlarmBroadcast(alarmIntent())

        val started = assertNotNull(nextStartedService())
        assertEquals(Uri.fromFile(imported).toString(), started.getStringExtra("ringtone_uri"))
        assertTrue(started.getBooleanExtra("gentle_wake", false))
        assertEquals(profile.rampMinutes, started.getIntExtra("ramp_minutes", -1))
        assertEquals(profile.startVolume, started.getFloatExtra("start_volume", -1f))
        assertEquals(profile.maxVolume, started.getFloatExtra("max_volume", -1f))
        assertEquals(profile.dismissal.name, started.getStringExtra("dismissal"))
    }

    @Test
    fun aMissingImportedWakeMusicFallsBackToTheLegacyPerAlarmRingtone() {
        appContainer.settingsManager.updateWakeProfile(
            WakeProfile(importedAudioPath = File(context.filesDir, "missing-audio").path)
        )

        sendAlarmBroadcast(alarmIntent())

        val started = assertNotNull(nextStartedService())
        assertEquals(RINGTONE, started.getStringExtra("ringtone_uri"))
    }

    /** The receiver still has to reschedule, or the second alarm of the pair kills the chain. */
    @Test
    fun whenAnAlarmIsAlreadyRinging_noSecondServiceIsStarted() {
        assertTrue(repository.setRingingAlarm(), "Precondition: the first claim succeeds")

        sendAlarmBroadcast(alarmIntent())

        assertNull(nextStartedService(), "A second ringing alarm must not start another service")
        runAsyncWorkToCompletion()
        assertEquals(1, repository.scheduleNextAlarmCallCount, "Rescheduling still has to happen")
    }

    @Test
    fun withNoAlarmIds_theServiceIsToldTheAlarmIsUnknown() {
        sendAlarmBroadcast(Intent(context, AlarmReceiver::class.java))

        val started = assertNotNull(nextStartedService())
        assertEquals(-1, started.getIntExtra("alarm_id", 0))
    }

    /** An alarm armed before the volume became non-null carries no extra, and rings at full. */
    @Test
    fun withNoVolumeExtra_theServiceIsToldFullVolume() {
        sendAlarmBroadcast(alarmIntent(volume = null))

        val started = assertNotNull(nextStartedService())
        assertEquals(1f, started.getFloatExtra("volume", -1f))
    }

    @Test
    fun withNoVibrationExtra_vibrationIsOn() {
        sendAlarmBroadcast(alarmIntent(vibrationEnabled = null))

        val started = assertNotNull(nextStartedService())
        assertTrue(started.getBooleanExtra("vibration_enabled", false))
    }

    /** Called directly, not broadcast: a null intent cannot be dispatched. */
    @Test
    fun aNullIntent_isSurvivedWithEveryDefault() {
        AlarmReceiver().onReceive(context, null)

        val started = assertNotNull(nextStartedService())
        assertEquals(-1, started.getIntExtra("alarm_id", 0))
        assertNull(started.getStringExtra("ringtone_uri"))
        assertEquals(1f, started.getFloatExtra("volume", -1f))
        assertTrue(started.getBooleanExtra("vibration_enabled", false))
    }

    @Test
    fun theFiredAlarmsAreRetiredAndTheNextOneScheduled() {
        sendAlarmBroadcast(alarmIntent())

        assertNull(repository.deactivatedAlarmIds, "The async work has not run yet")
        assertEquals(0, repository.scheduleNextAlarmCallCount)

        runAsyncWorkToCompletion()

        assertEquals(ALARM_IDS.toList(), repository.deactivatedAlarmIds)
        assertEquals(1, repository.scheduleNextAlarmCallCount)
    }

    private fun alarmIntent(
        volume: Float? = 0.4f,
        vibrationEnabled: Boolean? = false,
    ): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            putIntegerArrayListExtra("alarm_ids", ALARM_IDS)
            putExtra("ringtone_uri", RINGTONE)
            volume?.let { putExtra("volume", it) }
            vibrationEnabled?.let { putExtra("vibration_enabled", it) }
        }

    /** Dispatched through the framework: `goAsync` needs the pending result it installs. */
    private fun sendAlarmBroadcast(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runAsyncWorkToCompletion() {
        appContainer.scheduler.advanceUntilIdle()
    }

    private fun nextStartedService(): Intent? = shadowOf(context as Application).nextStartedService
}
