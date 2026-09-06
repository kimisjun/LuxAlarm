/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
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
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
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
    fun gentleWakeNotificationPassesItsRingingScreenProfileToTheActivity() {
        val service =
            start(
                gentleWake = true,
                rampMinutes = 12,
                startVolume = 0.08f,
                maxVolume = 0.42f,
                dismissal = WakeDismissal.LUX,
            )

        val notification = assertNotNull(shadowOf(service).lastForegroundNotification)
        val activityIntent = assertNotNull(shadowOf(notification.fullScreenIntent).savedIntent)
        assertEquals(ALARM_ID, activityIntent.getIntExtra("alarm_id", -1))
        assertTrue(activityIntent.getBooleanExtra("gentle_wake", false))
        assertEquals(12, activityIntent.getIntExtra("ramp_minutes", -1))
        assertEquals(WakeDismissal.LUX.name, activityIntent.getStringExtra("dismissal"))
        assertTrue(activityIntent.hasExtra("ramp_started_elapsed_realtime"))
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
    fun onBind_refusesBinding() {
        val service = start()

        assertNull(service.onBind(Intent(context, AlarmService::class.java)))
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

    @Test
    fun start_withAnAppPrivateImportedFileUri_playsTheFileWithoutFallingBack() {
        val importedFile =
            File(context.filesDir, "gentle-wake-audio/selected-audio").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val importedUri = Uri.fromFile(importedFile)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, importedUri),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )

        start(ringtoneUri = importedUri.toString())

        val player = createdPlayers.single()
        assertEquals(importedUri, shadowOf(player).sourceUri)
        assertTrue(player.isLooping)
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
    fun start_withAVolumeOverride_setsTheDeviceAlarmVolumeToIt() {
        setDeviceAlarmVolume(1)

        start(volume = 1f)

        assertEquals(maxDeviceAlarmVolume, deviceAlarmVolume)
    }

    @Test
    fun start_withAQuietVolumeOverride_turnsTheDeviceAlarmVolumeDown() {
        setDeviceAlarmVolume(maxDeviceAlarmVolume)

        start(volume = 0.25f)

        assertEquals(2, deviceAlarmVolume, "A quarter of the device's seven steps")
    }

    @Test
    fun start_withAVolumeOverrideTooSmallToRound_stillMakesASound() {
        setDeviceAlarmVolume(maxDeviceAlarmVolume)

        start(volume = 0.01f)

        assertEquals(1, deviceAlarmVolume)
    }

    /** An alarm armed before the volume became non-null reaches the service without the extra. */
    @Test
    fun start_withNoVolumeExtra_ringsAtFullVolume() {
        setDeviceAlarmVolume(3)

        start(volume = null)

        assertEquals(maxDeviceAlarmVolume, deviceAlarmVolume)
    }

    @Test
    fun stop_afterAnAlarmWithNoVolumeExtra_putsTheDeviceAlarmVolumeBack() {
        setDeviceAlarmVolume(3)
        start(volume = null)

        stop()

        assertEquals(3, deviceAlarmVolume)
    }

    @Test
    fun whileRinging_aVolumeChangeFromElsewhereIsPutBack() {
        setDeviceAlarmVolume(1)
        start(volume = 1f)

        setDeviceAlarmVolume(2)
        notifyVolumeSettingChanged()

        assertEquals(maxDeviceAlarmVolume, deviceAlarmVolume, "The alarm's volume has to win")
    }

    @Test
    fun afterStopping_aVolumeChangeIsLeftAlone() {
        setDeviceAlarmVolume(1)
        start(volume = 1f)
        stop()

        setDeviceAlarmVolume(5)
        notifyVolumeSettingChanged()

        assertEquals(5, deviceAlarmVolume, "A stopped alarm has no say over the device volume")
    }

    @Test
    fun stop_putsTheDeviceAlarmVolumeBack() {
        setDeviceAlarmVolume(1)
        start(volume = 1f)

        stop()

        assertEquals(1, deviceAlarmVolume, "The override lasts only as long as the alarm")
    }

    @Test
    fun aStickyRestart_stillPutsTheUsersVolumeBack() {
        setDeviceAlarmVolume(3)
        start(volume = 1f)
        assertEquals(maxDeviceAlarmVolume, deviceAlarmVolume, "Precondition: the override took")

        restartWithoutAnIntent()
        stop()

        assertEquals(3, deviceAlarmVolume, "The user's volume, not the alarm's")
    }

    @Test
    fun start_whenTheStreamCarriesTheVolume_leavesThePlayerAtFullGain() {
        start(volume = 0.25f)

        assertEquals(
            1f,
            shadowOf(createdPlayers.single()).leftVolume,
            "Attenuating twice would leave a quarter-volume alarm barely audible",
        )
    }

    @Test
    fun gentleWakeStartsAtFivePercentWithoutForcingTheDeviceAlarmVolume() {
        setDeviceAlarmVolume(5)

        start(gentleWake = true)

        assertEquals(5, deviceAlarmVolume, "Gentle wake must respect the user's system volume")
        assertEquals(
            WakeRamp.DEFAULT_START_VOLUME,
            shadowOf(createdPlayers.single()).leftVolume,
        )

        setDeviceAlarmVolume(2)
        notifyVolumeSettingChanged()

        assertEquals(2, deviceAlarmVolume, "Gentle wake must not install the forced-volume watcher")
    }

    @Test
    fun gentleWakeRisesViaWakeRampToTheConfiguredMaximum() {
        val startVolume = 0.1f
        val maxVolume = 0.4f
        val player =
            start(
                    gentleWake = true,
                    rampMinutes = 1,
                    startVolume = startVolume,
                    maxVolume = maxVolume,
                )
                .let { createdPlayers.single() }

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)

        assertEquals(
            WakeRamp.frameAt(0.5f, startVolume, maxVolume).audioVolume,
            shadowOf(player).leftVolume,
        )

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)

        assertEquals(maxVolume, shadowOf(player).leftVolume)
    }

    @Test
    fun stoppingGentleWakeRemovesFurtherRampUpdates() {
        val startVolume = 0.1f
        val maxVolume = 0.4f
        start(
            gentleWake = true,
            rampMinutes = 1,
            startVolume = startVolume,
            maxVolume = maxVolume,
        )
        val stoppedPlayer = createdPlayers.last()
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        val volumeBeforeStop = shadowOf(stoppedPlayer).leftVolume
        assertTrue(volumeBeforeStop > startVolume + 0.001f && volumeBeforeStop < maxVolume)
        stop()
        val volumeAtStop = shadowOf(stoppedPlayer).leftVolume

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES)

        assertEquals(volumeAtStop, shadowOf(stoppedPlayer).leftVolume)
    }

    @Test
    @Config(shadows = [ShadowUnchangeableAudioManager::class])
    fun start_whenTheDeviceRefusesTheOverride_turnsThePlayerDownInstead() {
        val deviceVolume = deviceAlarmVolume

        start(volume = 0.25f)

        assertEquals(0.25f, shadowOf(createdPlayers.single()).leftVolume)
        assertEquals(deviceVolume, deviceAlarmVolume, "Nothing moved, so nothing was overridden")
        assertNull(repository.deviceAlarmVolume, "Nothing to put back either")
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

    private val maxDeviceAlarmVolume
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    private val deviceAlarmVolume
        get() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    private fun notifyVolumeSettingChanged() {
        context.contentResolver.notifyChange(Settings.System.CONTENT_URI, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setDeviceAlarmVolume(volume: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
    }

    private fun start(
        ringtoneUri: String? = null,
        volume: Float? = null,
        vibrationEnabled: Boolean = true,
        gentleWake: Boolean = false,
        rampMinutes: Int = WakeRamp.DEFAULT_RAMP_MINUTES,
        startVolume: Float = WakeRamp.DEFAULT_START_VOLUME,
        maxVolume: Float = WakeRamp.DEFAULT_MAX_VOLUME,
        dismissal: WakeDismissal = WakeDismissal.DEFAULT,
    ): AlarmService {
        val intent =
            Intent(context, AlarmService::class.java).apply {
                putExtra("alarm_id", ALARM_ID)
                putExtra("ringtone_uri", ringtoneUri)
                volume?.let { putExtra("volume", it) }
                putExtra("vibration_enabled", vibrationEnabled)
                putExtra("gentle_wake", gentleWake)
                putExtra("ramp_minutes", rampMinutes)
                putExtra("start_volume", startVolume)
                putExtra("max_volume", maxVolume)
                putExtra("dismissal", dismissal.name)
            }
        controller = Robolectric.buildService(AlarmService::class.java, intent).create()
        controller!!.startCommand(0, 0)
        return controller!!.get()
    }

    private fun restartWithoutAnIntent(): AlarmService {
        controller = Robolectric.buildService(AlarmService::class.java).create()
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

@Implements(AudioManager::class)
class ShadowUnchangeableAudioManager : ShadowAudioManager() {
    @Implementation override fun setStreamVolume(streamType: Int, index: Int, flags: Int) = Unit
}
