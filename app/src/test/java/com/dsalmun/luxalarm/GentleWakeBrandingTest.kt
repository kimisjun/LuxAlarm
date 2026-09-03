/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GentleWakeBrandingTest {
    @Test
    fun appIsKoreanBrandedWhileCreditingTheGplUpstreamAndModification() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("GentleWake · 부드러운 기상", context.getString(R.string.app_name))
        val notice = context.getString(R.string.gpl_modification_notice)
        assertTrue("Lux Alarm 2.4.1" in notice)
        assertTrue("GPLv3" in notice)
        assertTrue("GentleWake" in notice)
        assertTrue("2026" in notice)
    }
}
