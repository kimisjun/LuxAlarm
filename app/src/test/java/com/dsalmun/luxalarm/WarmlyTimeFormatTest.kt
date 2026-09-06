/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class WarmlyTimeFormatTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun followsTheSystem24HourPreference() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")

        assertEquals("07:15", formatWarmlyTime(context, 7 * 60 + 15))
    }

    @Test
    fun followsTheSystem12HourPreference() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")

        assertEquals("7:15 AM", formatWarmlyTime(context, 7 * 60 + 15))
    }
}
