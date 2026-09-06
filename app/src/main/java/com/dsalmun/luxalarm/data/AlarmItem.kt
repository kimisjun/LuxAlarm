/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

@Entity(tableName = "alarms")
@TypeConverters(Converters::class)
data class AlarmItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hour: Int,
    val minute: Int,
    val isActive: Boolean = true,
    val repeatDays: Set<Int> = emptySet(),
    val ringtoneUri: String? = null,
    val volume: Float = 1f,
    val vibrationEnabled: Boolean = true,
    // Local day of a single skipped occurrence of a repeating alarm; see localDayOf. Self-expiring:
    // once the day is past it can never match again, so nothing has to clean it up.
    val skippedOccurrenceDay: Long? = null,
)
