/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Insert suspend fun insert(alarm: AlarmItem): Long

    @Update suspend fun update(alarm: AlarmItem)

    @Delete suspend fun delete(alarm: AlarmItem)

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC, id ASC")
    fun getAllAlarms(): Flow<List<AlarmItem>>

    @Query("SELECT * FROM alarms WHERE id = :id") suspend fun getAlarmById(id: Int): AlarmItem?

    @Query("SELECT * FROM alarms WHERE isActive = 1") suspend fun getActiveAlarms(): List<AlarmItem>

    @Query("SELECT id FROM alarms") suspend fun getAllAlarmIds(): List<Int>

    @Query("UPDATE alarms SET isActive = 0 WHERE id IN (:ids) AND repeatDays = ''")
    suspend fun deactivateOneShotAlarms(ids: List<Int>)
}
