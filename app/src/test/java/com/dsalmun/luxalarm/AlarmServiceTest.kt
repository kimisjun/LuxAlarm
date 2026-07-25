/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
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
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** Above all the teardown: a ringing alarm that cannot be stopped is this app's worst failure. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmServiceTest {
    private companion object {
        const val ALARM_ID = 7
        const val CUSTOM_RINGTONE = "content://media/internal/audio/media/42"

        /** Deliberately never registered with [ShadowMediaPlayer], so preparing it fails. */
        const val BROKEN_RINGTONE = "content://media/internal/audio/media/does-not-exist"
    }

    @get:Rule val appContainer = AppContainerTestRule()

    private lateinit var context: Context
    private lateinit var defaultUri: Uri
    private var controller: ServiceController<AlarmService>? = null
    private val createdPlayers = mutableListOf<MediaPlayer>()

    private val repository
        get() = appContainer.repository

    private val notificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val vibrator: Vibrator
        get() =
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator

    private val audioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        // The fallback relies on this, so BROKEN_RINGTONE is deliberately left unregistered.
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, defaultUri),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, CUSTOM_RINGTONE.toUri()),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )
        ShadowMediaPlayer.setCreateListener { player, _ -> createdPlayers.add(player) }
    }

    @After
    fun tearDown() {
        // isRunning is static, and only onDestroy -> stopAlarm() resets it before the next test.
        controller?.destroy()
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test
    fun start_createsTheAlarmChannelThatBypassesDoNotDisturb() {
        start()

        val channel =
            shadowOf(notificationManager)
                .notificationChannels
                .map { it as android.app.NotificationChannel }
                .single { it.id == AlarmService.ALARM_CHANNEL_ID }

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.canBypassDnd(), "An alarm has to ring through Do Not Disturb")
        // The service plays its own sound and vibration, so the channel must not add its own.
        assertFalse(channel.shouldVibrate())
        assertNull(channel.sound)
        assertFalse(channel.canShowBadge())
    }

    @Test
    fun start_postsAnOngoingFullScreenAlarmNotification() {
        val service = start()

        val shadowService = shadowOf(service)
        assertEquals(AlarmService.ALARM_NOTIFICATION_ID, shadowService.lastForegroundNotificationId)

        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(AlarmService.ALARM_CHANNEL_ID, notification.channelId)
        assertNotNull(
            notification.fullScreenIntent,
            "The alarm screen must be able to take over a locked device",
        )
        assertTrue(
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            "A ringing alarm must not be swipeable",
        )
    }

    @Test
    fun start_marksTheServiceRunning() {
        start()

        assertTrue(AlarmService.isRunning)
    }

    @Test
    fun stopAction_tearsDownTheForegroundServiceCompletely() {
        val service = start()

        stop()

        assertFalse(AlarmService.isRunning)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    /** Otherwise [MainActivity.onResume] bounces the user into the alarm screen forever. */
    @Test
    fun stopAction_clearsTheRingingFlagInTheRepository() {
        start()

        stop()

        assertEquals(1, repository.clearRingingAlarmCallCount)
        assertFalse(repository.isAlarmRinging())
    }

    @Test
    fun stopAction_isIdempotent() {
        start()

        stop()
        stop()

        assertEquals(1, repository.clearRingingAlarmCallCount, "The alarmStopped guard should hold")
    }

    @Test
    fun onDestroy_stopsTheAlarm() {
        start()

        controller!!.destroy()

        assertFalse(AlarmService.isRunning)
        assertEquals(1, repository.clearRingingAlarmCallCount)
    }

    @Test
    fun onBind_exposesTheServiceInstance() {
        val service = start()

        val binder = service.onBind(Intent(context, AlarmService::class.java))

        assertSame(service, (binder as AlarmService.LocalBinder).getService())
    }

    @Test
    fun start_withVibrationEnabled_vibratesTheRepeatingAlarmWaveform() {
        start(vibrationEnabled = true)

        val shadowVibrator = shadowOf(vibrator)
        assertTrue(shadowVibrator.isVibrating)
        assertEquals(listOf(0L, 1_000L, 500L, 1_000L, 500L), shadowVibrator.pattern?.toList())
        assertEquals(0, shadowVibrator.repeat, "Index 0 means the waveform loops")
    }

    @Test
    fun start_withVibrationDisabled_doesNotVibrate() {
        start(vibrationEnabled = false)

        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun stopAction_cancelsVibration() {
        start(vibrationEnabled = true)

        stop()

        assertFalse(shadowOf(vibrator).isVibrating)
        assertTrue(shadowOf(vibrator).isCancelled)
    }

    @Test
    fun start_playsTheCustomRingtoneOnLoop() {
        start(ringtoneUri = CUSTOM_RINGTONE)

        val player = createdPlayers.single()
        assertEquals(CUSTOM_RINGTONE.toUri(), shadowOf(player).sourceUri)
        assertTrue(player.isLooping, "An alarm must keep ringing until it is dismissed")
        assertTrue(player.isPlaying)
    }

    /** A chosen ringtone can disappear; without the fallback the alarm would be silent. */
    @Test
    fun start_whenTheChosenRingtoneFails_fallsBackToTheDefaultAlarmSound() {
        start(ringtoneUri = BROKEN_RINGTONE)

        val playing = createdPlayers.single { it.isPlaying }
        assertEquals(defaultUri, shadowOf(playing).sourceUri)
        assertTrue(playing.isLooping)
    }

    @Test
    fun start_withNoRingtoneChosen_playsTheDefaultAlarmSound() {
        start(ringtoneUri = null)

        val player = createdPlayers.single()
        assertEquals(defaultUri, shadowOf(player).sourceUri)
    }

    @Test
    fun start_appliesTheVolumeExtraToBothChannels() {
        start(volume = 0.25f)

        val player = createdPlayers.single()
        assertEquals(0.25f, shadowOf(player).leftVolume)
        assertEquals(0.25f, shadowOf(player).rightVolume)
    }

    /** The case that split notification, ringtone and vibration into separately guarded steps. */
    @Test
    fun start_whenEveryRingtoneFails_stillShowsAndVibrates() {
        ShadowMediaPlayer.addException(
            DataSource.toDataSource(context, defaultUri),
            IOException("the default ringtone is gone too"),
        )

        val service = start(ringtoneUri = BROKEN_RINGTONE, vibrationEnabled = true)

        assertTrue(AlarmService.isRunning, "The service must not fall over")
        assertNotNull(
            shadowOf(service).lastForegroundNotification,
            "A silent alarm still has to show itself",
        )
        assertTrue(createdPlayers.none { it.isPlaying }, "Nothing could be played")
        assertTrue(
            shadowOf(vibrator).isVibrating,
            "A device that cannot make a sound is exactly the one that has to buzz",
        )
    }

    @Test
    fun stopAction_afterAFailedStart_stillTearsEverythingDown() {
        ShadowMediaPlayer.addException(
            DataSource.toDataSource(context, defaultUri),
            IOException("the default ringtone is gone too"),
        )
        start(ringtoneUri = BROKEN_RINGTONE, vibrationEnabled = true)

        stop()

        assertFalse(AlarmService.isRunning)
        assertFalse(shadowOf(vibrator).isVibrating)
        assertNotNull(
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
            "Focus was taken before the audio failed, so it still has to be given back",
        )
    }

    @Test
    fun start_requestsAudioFocusWithAlarmUsage() {
        start()

        val request = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull(request, "Without focus the system may duck or stop the alarm")
        assertEquals(
            AudioAttributes.USAGE_ALARM,
            request.audioFocusRequest.audioAttributes.usage,
        )
    }

    @Test
    fun stopAction_abandonsAudioFocus() {
        start()

        stop()

        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    /** Three version gates in `startAlarm` take their other path at `minSdk`. */
    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun onTheOldestSupportedAndroid_theAlarmStillRingsAndVibrates() {
        val service = start(ringtoneUri = CUSTOM_RINGTONE, vibrationEnabled = true)

        assertEquals(
            AlarmService.ALARM_NOTIFICATION_ID,
            shadowOf(service).lastForegroundNotificationId,
        )
        assertTrue(createdPlayers.single().isPlaying)

        @Suppress("DEPRECATION")
        val legacyVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        assertTrue(shadowOf(legacyVibrator).isVibrating)
        assertEquals(
            listOf(0L, 1_000L, 500L, 1_000L, 500L),
            shadowOf(legacyVibrator).pattern?.toList(),
        )
    }

    private fun start(
        ringtoneUri: String? = null,
        volume: Float? = null,
        vibrationEnabled: Boolean = true,
    ): AlarmService {
        val intent =
            Intent(context, AlarmService::class.java).apply {
                putExtra("alarm_id", ALARM_ID)
                putExtra("ringtone_uri", ringtoneUri)
                volume?.let { putExtra("volume", it) }
                putExtra("vibration_enabled", vibrationEnabled)
            }
        controller = Robolectric.buildService(AlarmService::class.java, intent).create()
        controller!!.startCommand(0, 0)
        return controller!!.get()
    }

    private fun stop() {
        controller!!
            .get()
            .onStartCommand(
                Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_STOP_ALARM),
                0,
                1,
            )
    }
}
