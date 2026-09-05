/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartPrimaryWakeSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun successfulStartPrimaryCallReturnsOnlyAfterThePortAcceptsCanonicalOperation() {
        val calls = mutableListOf<Pair<Long, PendingIntent>>()
        val port = WakeAlarmClockPort { trigger, operation -> calls += trigger to operation }
        val event = WakeEventIdentity("tracer-start", WakeEventKind.START, 70_000L)

        val outcome = StartPrimaryWakeScheduler(context, port).schedule(event)

        assertEquals(listOf(70_000L), calls.map { it.first })
        assertEquals(
            WakePendingIntentData.primary(event),
            shadowOf(calls.single().second).savedIntent.dataString,
        )
        assertEquals(event, outcome.event)
        assertEquals(70_000L, outcome.triggerEpochMillis)
    }

    @Test
    fun androidPortRegistersARealAlarmClockEntry() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val event = WakeEventIdentity("real-start", WakeEventKind.START, 80_000L)

        StartPrimaryWakeScheduler(context, AndroidWakeAlarmClockPort(alarmManager)).schedule(event)

        val scheduled = assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
        assertEquals(80_000L, scheduled.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type)
        assertEquals(
            WakePendingIntentData.primary(event),
            shadowOf(scheduled.operation).savedIntent.dataString,
        )
        assertEquals(80_000L, assertNotNull(scheduled.alarmClockInfo).triggerTime)
    }

    @Test
    fun schedulingPortExceptionPropagatesUnchanged() {
        val sentinel = IllegalStateException("sentinel scheduling failure")
        val port = WakeAlarmClockPort { _, _ -> throw sentinel }
        val event = WakeEventIdentity("failed-start", WakeEventKind.START, 90_000L)

        val thrown =
            assertFailsWith<IllegalStateException> {
                StartPrimaryWakeScheduler(context, port).schedule(event)
            }

        assertSame(sentinel, thrown)
    }
}
