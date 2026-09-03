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

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlin.math.roundToInt

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null
    private var alarmStopped = false
    private var forcedAlarmVolume: Int? = null
    private var alarmVolumeWatcher: ContentObserver? = null
    private val wakeRampHandler = Handler(Looper.getMainLooper())
    private var wakeRampRunnable: Runnable? = null
    private var gentleWakeActive = false

    companion object {
        const val ACTION_STOP_ALARM = "com.dsalmun.luxalarm.STOP_ALARM"
        internal const val ALARM_CHANNEL_ID = "alarm_channel_id"
        const val ALARM_NOTIFICATION_ID = 1001
        private const val RAMP_UPDATE_INTERVAL_MILLIS = 100L
        var isRunning = false
            @VisibleForTesting internal set
    }

    /** Started, never bound: the alarm outlives any client, so there is nothing to bind to. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP_ALARM -> {
                stopAlarm()
                START_NOT_STICKY
            }
            else -> {
                val alarmId = intent?.getIntExtra("alarm_id", -1) ?: -1
                val ringtoneUri = intent?.getStringExtra("ringtone_uri")
                val volume = intent?.getFloatExtra("volume", 1f) ?: 1f
                val vibrationEnabled = intent?.getBooleanExtra("vibration_enabled", true) ?: true
                val gentleWake = intent?.getBooleanExtra("gentle_wake", false) ?: false
                val wakeProfile =
                    WakeProfile(
                        rampMinutes =
                            intent?.getIntExtra("ramp_minutes", WakeRamp.DEFAULT_RAMP_MINUTES)
                                ?: WakeRamp.DEFAULT_RAMP_MINUTES,
                        startVolume =
                            intent?.getFloatExtra("start_volume", WakeRamp.DEFAULT_START_VOLUME)
                                ?: WakeRamp.DEFAULT_START_VOLUME,
                        maxVolume =
                            intent?.getFloatExtra("max_volume", WakeRamp.DEFAULT_MAX_VOLUME)
                                ?: WakeRamp.DEFAULT_MAX_VOLUME,
                        dismissal =
                            intent?.getStringExtra("dismissal")?.let { stored ->
                                WakeDismissal.entries.firstOrNull { it.name == stored }
                            } ?: WakeDismissal.DEFAULT,
                    )
                startAlarm(
                    alarmId,
                    ringtoneUri,
                    volume,
                    vibrationEnabled,
                    gentleWake,
                    wakeProfile,
                )
                START_STICKY
            }
        }
    }

    private fun startAlarm(
        alarmId: Int,
        ringtoneUri: String?,
        volume: Float,
        vibrationEnabled: Boolean,
        gentleWake: Boolean,
        wakeProfile: WakeProfile,
    ) {
        alarmStopped = false
        isRunning = true
        gentleWakeActive = gentleWake
        val rampStartedAtElapsedRealtime = SystemClock.elapsedRealtime()
        val audioAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

        // Notification, ringtone and vibration are started independently so they also fail
        // independently: whatever survives is all that stands between the user and a missed alarm.
        independently {
            createNotificationChannel()
            val notification =
                buildAlarmNotification(alarmId, wakeProfile, rampStartedAtElapsedRealtime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ALARM_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(ALARM_NOTIFICATION_ID, notification)
            }
        }
        independently {
            startRingtone(
                ringtoneUri,
                volume,
                audioAttrs,
                gentleWake,
                wakeProfile,
                rampStartedAtElapsedRealtime,
            )
        }
        if (vibrationEnabled) {
            independently { startVibration(audioAttrs) }
        }
    }

    /** Runs one alarm signal, swallowing a failure so the others still get their turn. */
    private fun independently(startSignal: () -> Unit) {
        try {
            startSignal()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRingtone(
        ringtoneUri: String?,
        volume: Float,
        audioAttrs: AudioAttributes,
        gentleWake: Boolean,
        wakeProfile: WakeProfile,
        rampStartedAtElapsedRealtime: Long,
    ) {
        // Audio focus keeps the system from ducking or stopping the alarm.
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                .build()
        audioManager?.requestAudioFocus(audioFocusRequest!!)

        val playerVolume =
            if (gentleWake) {
                wakeProfile.startVolume.coerceIn(0f, 1f)
            } else {
                overrideAlarmStreamVolume(volume)
                if (forcedAlarmVolume != null) 1f else volume.coerceIn(0f, 1f)
            }

        val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val selectedAlarmUri = ringtoneUri?.toUri()
        val player =
            createPlayerForUri(selectedAlarmUri, audioAttrs, playerVolume)
                ?: createPlayerForUri(defaultAlarmUri, audioAttrs, playerVolume)
                ?: throw IllegalStateException("Failed to create MediaPlayer for alarm audio")
        mediaPlayer = player
        if (gentleWake) {
            startWakeRamp(player, wakeProfile, rampStartedAtElapsedRealtime)
        }
    }

    private fun startWakeRamp(
        player: MediaPlayer,
        profile: WakeProfile,
        startedAtElapsedRealtime: Long,
    ) {
        stopWakeRamp()
        val startVolume = profile.startVolume.coerceIn(0f, 1f)
        val maxVolume = profile.maxVolume.coerceIn(startVolume, 1f)
        val durationMillis = profile.rampMinutes.coerceAtLeast(0).toLong() * 60_000L
        lateinit var updateVolume: Runnable
        updateVolume = Runnable {
            if (alarmStopped || mediaPlayer !== player) return@Runnable
            val elapsedMillis =
                (SystemClock.elapsedRealtime() - startedAtElapsedRealtime).coerceAtLeast(0L)
            val progress =
                if (durationMillis == 0L) 1f
                else (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
            val rampVolume = WakeRamp.frameAt(progress, startVolume, maxVolume).audioVolume
            player.setVolume(rampVolume, rampVolume)
            if (progress < 1f) {
                wakeRampHandler.postDelayed(updateVolume, RAMP_UPDATE_INTERVAL_MILLIS)
            } else if (wakeRampRunnable === updateVolume) {
                wakeRampRunnable = null
            }
        }
        wakeRampRunnable = updateVolume
        wakeRampHandler.post(updateVolume)
    }

    private fun stopWakeRamp() {
        wakeRampRunnable?.let(wakeRampHandler::removeCallbacks)
        wakeRampRunnable = null
    }

    private fun overrideAlarmStreamVolume(volume: Float) {
        val audioManager = audioManager ?: return
        val alreadyRemembered = AppContainer.repository.rememberedDeviceAlarmVolume() != null
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val index =
                (volume.coerceIn(0f, 1f) * maxVolume).roundToInt().let {
                    if (volume > 0f) it.coerceAtLeast(1) else it
                }
            if (!alreadyRemembered) {
                AppContainer.repository.rememberDeviceAlarmVolume(
                    audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                )
            }
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != index) {
                Log.w("AlarmService", "The device kept its own alarm stream volume")
                if (!alreadyRemembered) AppContainer.repository.forgetDeviceAlarmVolume()
                forcedAlarmVolume = null
                return
            }
            forcedAlarmVolume = index
            watchForVolumeChanges()
        } catch (e: Exception) {
            Log.w("AlarmService", "Failed to override the alarm stream volume", e)
            if (!alreadyRemembered) AppContainer.repository.forgetDeviceAlarmVolume()
            forcedAlarmVolume = null
        }
    }

    private fun watchForVolumeChanges() {
        val audioManager = audioManager ?: return
        val watcher =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val forced = forcedAlarmVolume ?: return
                    try {
                        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == forced)
                            return
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, forced, 0)
                    } catch (e: Exception) {
                        Log.w("AlarmService", "Failed to hold the alarm stream volume", e)
                    }
                }
            }
        alarmVolumeWatcher = watcher
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, watcher)
    }

    private fun restoreAlarmStreamVolume() {
        val audioManager = audioManager ?: getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val previousVolume = AppContainer.repository.rememberedDeviceAlarmVolume()
        AppContainer.repository.forgetDeviceAlarmVolume()
        forcedAlarmVolume = null
        alarmVolumeWatcher?.let { contentResolver.unregisterContentObserver(it) }
        alarmVolumeWatcher = null
        if (audioManager == null || previousVolume == null) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
        } catch (e: Exception) {
            Log.w("AlarmService", "Failed to restore the alarm stream volume", e)
        }
    }

    /** [audioAttrs] is only read on pre-Tiramisu devices. */
    private fun startVibration(audioAttrs: AudioAttributes) {
        vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

        val vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500)
        val vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationAttrs =
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
            vibrator?.vibrate(vibrationEffect, vibrationAttrs)
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(vibrationEffect, audioAttrs)
        }
    }

    private fun createPlayerForUri(
        uri: Uri?,
        audioAttrs: AudioAttributes,
        volume: Float,
    ): MediaPlayer? {
        if (uri == null) return null
        var player: MediaPlayer? = null
        return try {
            MediaPlayer().apply {
                player = this
                setDataSource(applicationContext, uri)
                setAudioAttributes(audioAttrs)
                setVolume(volume, volume)
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w("AlarmService", "Failed to play ringtone URI: $uri", e)
            player?.release()
            null
        }
    }

    private fun stopAlarm() {
        if (alarmStopped) return
        alarmStopped = true
        isRunning = false
        stopWakeRamp()

        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null

        vibrator?.cancel()
        vibrator = null

        if (!gentleWakeActive) restoreAlarmStreamVolume()
        gentleWakeActive = false
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        audioManager = null

        AppContainer.repository.clearRingingAlarm()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val name = "Alarm notifications"
        val descriptionText = "Notifications for triggered alarms"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel =
            NotificationChannel(ALARM_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setBypassDnd(true)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun alarmActivityOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null

        val mode =
            if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }

        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(mode)
            .toBundle()
    }

    // Alarm apps have FullScreenIntent enabled
    @SuppressLint("FullScreenIntentPolicy")
    private fun buildAlarmNotification(
        alarmId: Int,
        wakeProfile: WakeProfile,
        rampStartedAtElapsedRealtime: Long,
    ): Notification {
        val fullScreenIntent =
            Intent(this, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("alarm_id", alarmId)
                putExtra("ramp_minutes", wakeProfile.rampMinutes)
                putExtra("dismissal", wakeProfile.dismissal.name)
                putExtra("ramp_started_elapsed_realtime", rampStartedAtElapsedRealtime)
            }
        val fullScreenPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                alarmActivityOptions(),
            )

        return NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm Ringing")
            .setContentText("Tap to open alarm screen")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }
}
