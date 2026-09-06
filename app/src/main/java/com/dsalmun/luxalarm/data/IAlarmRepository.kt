/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import kotlinx.coroutines.flow.Flow

interface IAlarmRepository {
    fun getAllAlarms(): Flow<List<AlarmItem>>

    suspend fun addAlarm(hour: Int, minute: Int): Boolean

    suspend fun toggleAlarm(alarmId: Int, isActive: Boolean): Boolean

    suspend fun updateAlarmTime(alarmId: Int, hour: Int, minute: Int): Boolean

    suspend fun deleteAlarm(alarmId: Int)

    suspend fun setRepeatDays(alarmId: Int, repeatDays: Set<Int>)

    suspend fun setAlarmRingtone(alarmId: Int, ringtoneUri: String?)

    suspend fun setAlarmVolume(alarmId: Int, volume: Float)

    suspend fun setAlarmVibration(alarmId: Int, enabled: Boolean)

    suspend fun skipAlarms(ids: List<Int>, dismissedTriggerMillis: Long): Boolean

    suspend fun cancelSkip(alarmId: Int): Boolean

    suspend fun scheduleNextAlarm(): Boolean

    fun canScheduleExactAlarms(): Boolean

    fun isAlarmRinging(): Boolean

    fun setRingingAlarm(): Boolean

    fun clearRingingAlarm()

    fun rememberDeviceAlarmVolume(volume: Int)

    fun rememberedDeviceAlarmVolume(): Int?

    fun forgetDeviceAlarmVolume()

    suspend fun deactivateOneShotAlarms(ids: List<Int>)

    suspend fun cancelV1Alarms()
}
