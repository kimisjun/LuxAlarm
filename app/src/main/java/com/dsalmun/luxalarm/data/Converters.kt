/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun fromIntSet(value: Set<Int>): String = value.joinToString(separator = ",")

    @TypeConverter
    fun toIntSet(value: String): Set<Int> =
        if (value.isEmpty()) {
            emptySet()
        } else {
            value.split(',').map { it.toInt() }.toSet()
        }
}
