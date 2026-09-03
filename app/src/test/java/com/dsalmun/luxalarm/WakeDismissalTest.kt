/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import kotlin.test.assertEquals
import org.junit.Test

class WakeDismissalTest {
    @Test
    fun offersConfirmationByDefaultAndLuxAsTheOnlyOptionalMission() {
        assertEquals(WakeDismissal.CONFIRM, WakeDismissal.DEFAULT)
        assertEquals(listOf(WakeDismissal.CONFIRM, WakeDismissal.LUX), WakeDismissal.entries)
    }
}
