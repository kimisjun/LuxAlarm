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
