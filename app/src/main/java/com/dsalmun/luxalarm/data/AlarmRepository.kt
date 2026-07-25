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
package com.dsalmun.luxalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import com.dsalmun.luxalarm.AlarmReceiver
import com.dsalmun.luxalarm.MainActivity
import com.dsalmun.luxalarm.UpcomingAlarmNotifier
import com.dsalmun.luxalarm.UpcomingAlarmReceiver
import kotlinx.coroutines.flow.Flow

class AlarmRepository(private val alarmDao: AlarmDao, private val context: Context) :
    IAlarmRepository {
    private companion object {
        const val NEXT_ALARM_REQUEST_CODE = 0
        const val UPCOMING_SHOW_REQUEST_CODE = 1
        const val PREFS_NAME = "alarm_state"
        const val KEY_IS_RINGING = "is_ringing"
        const val KEY_V1_MIGRATED = "v1_migrated"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getAllAlarms(): Flow<List<AlarmItem>> = alarmDao.getAllAlarms()

    override suspend fun addAlarm(hour: Int, minute: Int): Boolean {
        val newAlarm = AlarmItem(hour = hour, minute = minute)
        val newId = alarmDao.insert(newAlarm)

        if (!scheduleNextAlarm()) {
            alarmDao.delete(newAlarm.copy(id = newId.toInt()))
            return false
        }
        return true
    }

    override suspend fun toggleAlarm(alarmId: Int, isActive: Boolean): Boolean {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return true
        val updatedAlarm = alarm.copy(isActive = isActive)
        alarmDao.update(updatedAlarm)

        if (!scheduleNextAlarm()) {
            alarmDao.update(alarm)
            return false
        }
        return true
    }

    override suspend fun updateAlarmTime(alarmId: Int, hour: Int, minute: Int): Boolean {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return true
        // Editing the alarm invalidates any pending skip (matches Google Clock).
        val updatedAlarm =
            alarm.copy(hour = hour, minute = minute, isActive = true, skippedOccurrenceDay = null)
        alarmDao.update(updatedAlarm)

        if (!scheduleNextAlarm()) {
            alarmDao.update(alarm)
            return false
        }
        return true
    }

    override suspend fun deleteAlarm(alarmId: Int) {
        alarmDao.getAlarmById(alarmId)?.let { alarm ->
            alarmDao.delete(alarm)
            scheduleNextAlarm()
        }
    }

    override suspend fun setRepeatDays(alarmId: Int, repeatDays: Set<Int>) {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return
        // Editing the schedule invalidates any pending skip (matches Google Clock).
        val updatedAlarm = alarm.copy(repeatDays = repeatDays, skippedOccurrenceDay = null)
        alarmDao.update(updatedAlarm)
        scheduleNextAlarm()
    }

    override suspend fun skipAlarms(ids: List<Int>, triggerMillis: Long): Boolean {
        // The caller names the occurrence by the instant it was armed for. Resolve it to a local
        // day here, in the current zone, so the stored skip survives a later time-zone change.
        val skipDay = localDayOf(triggerMillis)
        val originals = mutableListOf<AlarmItem>()
        for (id in ids) {
            val alarm = alarmDao.getAlarmById(id) ?: continue
            val updated =
                if (alarm.repeatDays.isEmpty()) {
                    // One-shot: Google Clock's Dismiss turns the alarm off entirely.
                    alarm.copy(isActive = false)
                } else {
                    alarm.copy(skippedOccurrenceDay = skipDay)
                }
            originals.add(alarm)
            alarmDao.update(updated)
        }

        if (!scheduleNextAlarm()) {
            originals.forEach { alarmDao.update(it) }
            return false
        }
        return true
    }

    override suspend fun cancelSkip(alarmId: Int): Boolean {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return true
        if (alarm.skippedOccurrenceDay == null) return true
        alarmDao.update(alarm.copy(skippedOccurrenceDay = null))

        if (!scheduleNextAlarm()) {
            alarmDao.update(alarm)
            return false
        }
        return true
    }

    override suspend fun setAlarmRingtone(alarmId: Int, ringtoneUri: String?) {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(ringtoneUri = ringtoneUri)
        alarmDao.update(updatedAlarm)
        // Ringtone, volume and vibration ride in the PendingIntent extras, so re-arm on a change.
        scheduleNextAlarm()
    }

    override suspend fun setAlarmVolume(alarmId: Int, volume: Float?) {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(volume = volume?.coerceIn(0f, 1f))
        alarmDao.update(updatedAlarm)
        scheduleNextAlarm()
    }

    override suspend fun setAlarmVibration(alarmId: Int, enabled: Boolean) {
        val alarm = alarmDao.getAlarmById(alarmId) ?: return
        val updatedAlarm = alarm.copy(vibrationEnabled = enabled)
        alarmDao.update(updatedAlarm)
        scheduleNextAlarm()
    }

    override suspend fun scheduleNextAlarm(): Boolean {
        val activeAlarms = alarmDao.getActiveAlarms()

        if (activeAlarms.isEmpty()) {
            cancelNextAlarm()
            return true
        }

        if (!canScheduleExactAlarms()) {
            // Nothing can be armed, so an "upcoming alarm" notice would be a lie. The pending show
            // alarm is inexact, so it outlives the revocation and has to go too.
            cancelUpcomingNotification()
            return false
        }

        val now = System.currentTimeMillis()
        val alarmTriggers = activeAlarms.map { alarm ->
            alarm to
                nextTrigger(
                    alarm.hour,
                    alarm.minute,
                    alarm.repeatDays,
                    now,
                    alarm.skippedOccurrenceDay,
                )
        }

        val minTriggerTime = alarmTriggers.minOf { it.second }

        val nextAlarms =
            alarmTriggers.filter { it.second == minTriggerTime }.sortedBy { it.first.id }
        val alarmIds = nextAlarms.map { it.first.id }
        // When multiple alarms fire simultaneously, use ringtone from lowest-ID alarm
        val nextAlarm = nextAlarms.first().first

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                putIntegerArrayListExtra("alarm_ids", ArrayList(alarmIds))
                putExtra("ringtone_uri", nextAlarm.ringtoneUri)
                nextAlarm.volume?.let { putExtra("volume", it) }
                putExtra("vibration_enabled", nextAlarm.vibrationEnabled)
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                NEXT_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val showIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(minTriggerTime, showIntent),
            pendingIntent,
        )

        scheduleUpcomingNotification(alarmIds, minTriggerTime, now)
        return true
    }

    /**
     * Posts the "upcoming alarm" notification, or arms it for later. Inexact and allow-while-idle:
     * a 2h-ahead notice needs no precise timing, and this needs no exact-alarm permission.
     */
    private fun scheduleUpcomingNotification(
        alarmIds: List<Int>,
        triggerMillis: Long,
        now: Long,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val notifyAt = triggerMillis - UPCOMING_LEAD_MILLIS
        // FLAG_UPDATE_CURRENT never returns null.
        val showPendingIntent = upcomingShowPendingIntent(alarmIds, triggerMillis)!!
        if (notifyAt <= now) {
            alarmManager.cancel(showPendingIntent)
            UpcomingAlarmNotifier.post(context, alarmIds, triggerMillis)
        } else {
            UpcomingAlarmNotifier.cancel(context)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAt, showPendingIntent)
        }
    }

    private fun upcomingShowPendingIntent(
        alarmIds: List<Int>,
        triggerMillis: Long,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT,
    ): PendingIntent? {
        val intent =
            Intent(context, UpcomingAlarmReceiver::class.java).apply {
                action = UpcomingAlarmReceiver.ACTION_SHOW
                putIntegerArrayListExtra(UpcomingAlarmReceiver.EXTRA_ALARM_IDS, ArrayList(alarmIds))
                putExtra(UpcomingAlarmReceiver.EXTRA_TRIGGER_MILLIS, triggerMillis)
            }
        return PendingIntent.getBroadcast(
            context,
            UPCOMING_SHOW_REQUEST_CODE,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelUpcomingNotification() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // FLAG_NO_CREATE: only an already-armed show alarm needs cancelling, and this avoids
        // registering a PendingIntent just to throw it away.
        upcomingShowPendingIntent(emptyList(), 0L, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager.cancel(it)
        }
        UpcomingAlarmNotifier.cancel(context)
    }

    override fun isAlarmRinging(): Boolean = prefs.getBoolean(KEY_IS_RINGING, false)

    @Synchronized
    override fun setRingingAlarm(): Boolean {
        if (prefs.getBoolean(KEY_IS_RINGING, false)) return false
        prefs.edit { putBoolean(KEY_IS_RINGING, true) }
        return true
    }

    override fun clearRingingAlarm() {
        prefs.edit { putBoolean(KEY_IS_RINGING, false) }
    }

    override suspend fun deactivateOneShotAlarms(ids: List<Int>) {
        alarmDao.deactivateOneShotAlarms(ids)
    }

    override suspend fun cancelV1Alarms() {
        if (prefs.getBoolean(KEY_V1_MIGRATED, false)) return
        for (id in alarmDao.getAllAlarmIds()) {
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            pendingIntent?.cancel()
        }
        prefs.edit { putBoolean(KEY_V1_MIGRATED, true) }
        scheduleNextAlarm()
    }

    override fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    private fun cancelNextAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                NEXT_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.cancel(pendingIntent)
        cancelUpcomingNotification()
    }
}
