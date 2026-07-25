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
package com.dsalmun.luxalarm.testing

import com.dsalmun.luxalarm.data.AlarmItem
import com.dsalmun.luxalarm.data.IAlarmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [IAlarmRepository]. [setShouldSucceed] drives every permission-gated call at once. */
class FakeAlarmRepository : IAlarmRepository {
    private val alarmsFlow = MutableStateFlow<List<AlarmItem>>(emptyList())
    private var shouldSucceed = true
    private var isRinging = false

    var addAlarmCallCount = 0
    var toggleAlarmCallCount = 0
    var lastToggleAlarmId: Int? = null
    var lastToggleIsActive: Boolean? = null
    var deleteAlarmCallCount = 0
    var deletedAlarmIds = mutableListOf<Int>()
    var updateAlarmTimeCallCount = 0
    var lastUpdateAlarmId: Int? = null
    var lastUpdateHour: Int? = null
    var lastUpdateMinute: Int? = null
    var setRepeatDaysCallCount = 0
    var lastRepeatDaysAlarmId: Int? = null
    var lastRepeatDays: Set<Int>? = null
    var setAlarmRingtoneCallCount = 0
    var lastRingtoneAlarmId: Int? = null
    var lastRingtoneUri: String? = null
    var setAlarmVolumeCallCount = 0
    var lastVolumeAlarmId: Int? = null
    var lastVolume: Float? = null
    var setAlarmVibrationCallCount = 0
    var lastVibrationAlarmId: Int? = null
    var lastVibrationEnabled: Boolean? = null
    var skipAlarmsCallCount = 0
    var lastSkipIds: List<Int>? = null
    var lastSkipTriggerMillis: Long? = null
    var cancelSkipCallCount = 0
    var lastCancelSkipAlarmId: Int? = null
    var scheduleNextAlarmCallCount = 0
    var setRingingAlarmCallCount = 0
    var clearRingingAlarmCallCount = 0
    var deactivatedAlarmIds: List<Int>? = null

    fun setShouldSucceed(succeed: Boolean) {
        shouldSucceed = succeed
    }

    /** Seeds the list outside a coroutine, for Compose tests that set content first. */
    fun setAlarms(alarms: List<AlarmItem>) {
        alarmsFlow.value = alarms
    }

    suspend fun emit(alarms: List<AlarmItem>) {
        alarmsFlow.emit(alarms)
    }

    override fun getAllAlarms(): Flow<List<AlarmItem>> = alarmsFlow

    override suspend fun addAlarm(hour: Int, minute: Int): Boolean {
        addAlarmCallCount++
        return shouldSucceed
    }

    override suspend fun toggleAlarm(alarmId: Int, isActive: Boolean): Boolean {
        toggleAlarmCallCount++
        lastToggleAlarmId = alarmId
        lastToggleIsActive = isActive
        return shouldSucceed
    }

    override suspend fun updateAlarmTime(alarmId: Int, hour: Int, minute: Int): Boolean {
        updateAlarmTimeCallCount++
        lastUpdateAlarmId = alarmId
        lastUpdateHour = hour
        lastUpdateMinute = minute
        return shouldSucceed
    }

    override suspend fun deleteAlarm(alarmId: Int) {
        deleteAlarmCallCount++
        deletedAlarmIds.add(alarmId)
    }

    override suspend fun setRepeatDays(alarmId: Int, repeatDays: Set<Int>) {
        setRepeatDaysCallCount++
        lastRepeatDaysAlarmId = alarmId
        lastRepeatDays = repeatDays
    }

    override suspend fun setAlarmRingtone(alarmId: Int, ringtoneUri: String?) {
        setAlarmRingtoneCallCount++
        lastRingtoneAlarmId = alarmId
        lastRingtoneUri = ringtoneUri
    }

    override suspend fun setAlarmVolume(alarmId: Int, volume: Float?) {
        setAlarmVolumeCallCount++
        lastVolumeAlarmId = alarmId
        lastVolume = volume
    }

    override suspend fun setAlarmVibration(alarmId: Int, enabled: Boolean) {
        setAlarmVibrationCallCount++
        lastVibrationAlarmId = alarmId
        lastVibrationEnabled = enabled
    }

    override suspend fun skipAlarms(ids: List<Int>, dismissedTriggerMillis: Long): Boolean {
        skipAlarmsCallCount++
        lastSkipIds = ids
        lastSkipTriggerMillis = dismissedTriggerMillis
        return shouldSucceed
    }

    override suspend fun cancelSkip(alarmId: Int): Boolean {
        cancelSkipCallCount++
        lastCancelSkipAlarmId = alarmId
        return shouldSucceed
    }

    override suspend fun scheduleNextAlarm(): Boolean {
        scheduleNextAlarmCallCount++
        return shouldSucceed
    }

    override fun canScheduleExactAlarms(): Boolean = shouldSucceed

    override fun isAlarmRinging(): Boolean = isRinging

    override fun setRingingAlarm(): Boolean {
        setRingingAlarmCallCount++
        if (isRinging) return false
        isRinging = true
        return true
    }

    override fun clearRingingAlarm() {
        clearRingingAlarmCallCount++
        isRinging = false
    }

    override suspend fun deactivateOneShotAlarms(ids: List<Int>) {
        deactivatedAlarmIds = ids
    }

    override suspend fun cancelV1Alarms() {}
}
