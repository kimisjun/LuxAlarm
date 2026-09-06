/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar

internal fun formatWarmlyTime(context: Context, minutes: Int): String {
    val wrapped = ((minutes % (24 * 60)) + (24 * 60)) % (24 * 60)
    val instant =
        Calendar.getInstance().apply {
            clear()
            set(2000, Calendar.JANUARY, 1, wrapped / 60, wrapped % 60, 0)
        }
    return DateFormat.getTimeFormat(context).format(instant.time)
}
