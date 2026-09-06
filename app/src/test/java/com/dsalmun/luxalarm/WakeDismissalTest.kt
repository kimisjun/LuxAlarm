/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
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
