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

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.UpcomingAlarmNotifier
import com.dsalmun.luxalarm.UpcomingAlarmReceiver
import com.dsalmun.luxalarm.testing.pinLocalHourTo
import com.dsalmun.luxalarm.testing.restoreSystemTimeZone
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Runs on a plain [Application]: `AppContainer.onCreate` would build a second repository and
 * reschedule on a background thread, racing these assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmRepositoryTest {
    private companion object {
        const val THIRTY_MINUTES = 30 * 60 * 1000L
        const val ONE_HOUR = 60 * 60 * 1000L
        const val THREE_HOURS = 3 * 60 * 60 * 1000L

        /** Far enough from midnight that shifting the zone cannot move the date. */
        const val MIDDAY = 12

        /** Close enough to midnight that shifting the zone does move the date. */
        const val MIDNIGHT = 0

        val EVERY_DAY = setOf(1, 2, 3, 4, 5, 6, 7)
    }

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var dao: AlarmDao
    private lateinit var repository: AlarmRepository
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.alarmDao()
        repository = AlarmRepository(dao, context)
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context.getSharedPreferences("alarm_state", Context.MODE_PRIVATE).edit { clear() }
    }

    @After
    fun tearDown() {
        database.close()
        UpcomingAlarmNotifier.cancel(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Process-wide, so a shifted zone would leak into every test that follows.
        restoreSystemTimeZone()
    }

    @Test
    fun skipAlarms_repeatingAlarm_marksOccurrenceSkippedAndRearmsTheFollowingOne() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        val skipped = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())

        val result = runBlocking { repository.skipAlarms(listOf(1), skipped) }

        assertTrue(result, "skipAlarms should succeed while exact alarms are permitted")
        assertEquals(localDayOf(skipped), dao.alarm(1).skippedOccurrenceDay)
        assertTrue(
            dao.alarm(1).isActive,
            "A repeating alarm stays on when one occurrence is skipped",
        )
        assertEquals(
            skipped + 24 * 60 * 60 * 1000L,
            scheduledAlarmClock()?.triggerAtMs,
            "The alarm clock should move to tomorrow's occurrence",
        )
        assertNull(
            upcomingNotification(),
            "Tomorrow's occurrence is outside the window, so the notice must be cleared",
        )
    }

    @Test
    fun skipAlarms_oneShotAlarm_deactivatesItInstead() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = emptySet())
        val skipped = nextTrigger(hour, minute, emptySet(), System.currentTimeMillis())

        val result = runBlocking { repository.skipAlarms(listOf(1), skipped) }

        assertTrue(result)
        assertFalse(dao.alarm(1).isActive, "Dismissing a one-shot alarm turns it off entirely")
        assertNull(dao.alarm(1).skippedOccurrenceDay, "A deactivated one-shot needs no skip marker")
    }

    @Test
    fun skipAlarms_unknownId_isIgnored() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        val skipped = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())

        val result = runBlocking { repository.skipAlarms(listOf(1, 99), skipped) }

        assertTrue(result, "A missing id should be skipped over, not fail the whole call")
        assertEquals(localDayOf(skipped), dao.alarm(1).skippedOccurrenceDay)
    }

    @Test
    fun skipAlarms_forAnOccurrenceAlreadyPast_changesNothing() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)

        val rung = System.currentTimeMillis() - ONE_HOUR
        assertTrue(runBlocking { repository.skipAlarms(listOf(1), rung) })

        assertNull(
            dao.alarm(1).skippedOccurrenceDay,
            "A notice left over from an alarm that rang must not silence the next one",
        )
    }

    @Test
    fun skipAlarms_whenExactAlarmsDenied_revertsEveryAlarmAndReturnsFalse() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        insertAlarm(id = 2, hour = hour, minute = minute, repeatDays = emptySet())
        val skipped = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = runBlocking { repository.skipAlarms(listOf(1, 2), skipped) }

        assertFalse(result, "A skip that cannot be rescheduled must report failure")
        assertNull(
            dao.alarm(1).skippedOccurrenceDay,
            "The repeating alarm's skip must be rolled back",
        )
        assertTrue(dao.alarm(2).isActive, "The one-shot alarm must not stay deactivated")
    }

    @Test
    fun skipAlarms_whenExactAlarmsDenied_clearsTheUpcomingNotification() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        val skipped = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())
        runBlocking { repository.scheduleNextAlarm() }
        assertNotNull(upcomingNotification(), "Precondition: the notice is showing")

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(runBlocking { repository.skipAlarms(listOf(1), skipped) })

        // The row is rolled back, but nothing is armed any more, so the notice still has to go.
        assertNull(dao.alarm(1).skippedOccurrenceDay)
        assertNull(upcomingNotification(), "No alarm can ring, so no upcoming notice may remain")
    }

    @Test
    fun cancelSkip_clearsPendingSkipAndReArmsTheOccurrence() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        val skipped = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())
        insertAlarm(
            id = 1,
            hour = hour,
            minute = minute,
            repeatDays = EVERY_DAY,
            skippedOccurrenceDay = localDayOf(skipped),
        )

        val result = runBlocking { repository.cancelSkip(1) }

        assertTrue(result)
        assertNull(dao.alarm(1).skippedOccurrenceDay)
        assertEquals(skipped, scheduledAlarmClock()?.triggerAtMs, "The occurrence is armed again")
        assertNotNull(upcomingNotification(), "It is back inside the window, so the notice returns")
    }

    @Test
    fun cancelSkip_whenExactAlarmsDenied_restoresTheSkipAndReturnsFalse() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        val skipped = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())
        insertAlarm(
            id = 1,
            hour = hour,
            minute = minute,
            repeatDays = EVERY_DAY,
            skippedOccurrenceDay = localDayOf(skipped),
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = runBlocking { repository.cancelSkip(1) }

        assertFalse(result)
        assertEquals(
            localDayOf(skipped),
            dao.alarm(1).skippedOccurrenceDay,
            "The skip must be reinstated",
        )
    }

    @Test
    fun cancelSkip_withoutAPendingSkip_succeedsWithoutRescheduling() {
        insertAlarm(id = 1, hour = 8, minute = 0, repeatDays = EVERY_DAY)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        // Returns true despite the missing permission: with nothing to undo it never reschedules.
        assertTrue(runBlocking { repository.cancelSkip(1) })
    }

    @Test
    fun cancelSkip_unknownAlarm_succeedsWithoutRescheduling() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertTrue(runBlocking { repository.cancelSkip(99) })
    }

    @Test
    fun scheduleNextAlarm_whenExactAlarmsDenied_cancelsTheUpcomingNotification() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        assertNotNull(
            upcomingNotification(),
            "Precondition: inside the window the notice is posted",
        )

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val result = runBlocking { repository.scheduleNextAlarm() }

        assertFalse(result)
        assertNull(upcomingNotification(), "An unarmable alarm must not advertise itself")
    }

    @Test
    fun scheduleNextAlarm_whenExactAlarmsDenied_cancelsThePendingShowAlarm() {
        val (hour, minute) = clockTimeIn(THREE_HOURS)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        assertNotNull(
            scheduledShowAlarm(),
            "Precondition: outside the window the inexact show alarm is armed",
        )

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val result = runBlocking { repository.scheduleNextAlarm() }

        assertFalse(result)
        // Inexact, so it outlives the revocation and would announce an alarm that cannot ring.
        assertNull(scheduledShowAlarm(), "The pending show alarm must be cancelled too")
    }

    @Test
    fun scheduleNextAlarm_withNoActiveAlarms_clearsEverythingAndSucceeds() {
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        assertNotNull(upcomingNotification())

        runBlocking { dao.update(dao.alarm(1).copy(isActive = false)) }
        val result = runBlocking { repository.scheduleNextAlarm() }

        assertTrue(result, "Having nothing to schedule is success, not a permission failure")
        assertNull(upcomingNotification())
        assertNull(scheduledShowAlarm())
    }

    @Test
    fun scheduleNextAlarm_afterATimeZoneChange_rearmsAtTheSameWallClockTime() {
        pinLocalHourTo(MIDDAY)
        val (hour, minute) = clockTimeIn(THREE_HOURS)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        val armedBefore = assertNotNull(scheduledAlarmClock()?.triggerAtMs)

        shiftZoneBy(-1)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })

        val armedAfter = assertNotNull(scheduledAlarmClock()?.triggerAtMs)
        assertEquals(
            hour to minute,
            localTimeOf(armedAfter),
            "The alarm must still ring at the wall-clock time the user set",
        )
        // One hour west pushes that wall-clock time one hour later in absolute terms.
        assertEquals(armedBefore + ONE_HOUR, armedAfter, "The armed instant has to move")
    }

    @Test
    fun scheduleNextAlarm_afterATimeZoneChange_reAnchorsTheUpcomingNotification() {
        pinLocalHourTo(MIDDAY)
        val (hour, minute) = clockTimeIn(THREE_HOURS)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        assertNull(upcomingNotification(), "Precondition: three hours out is outside the window")
        assertNotNull(scheduledShowAlarm(), "Precondition: the show alarm is armed instead")

        // Two hours east: the same wall-clock time is only an hour away now.
        shiftZoneBy(2)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })

        assertNotNull(upcomingNotification(), "The alarm is inside the window now, so notify")
    }

    /** The skip is stored as a local day, not an instant, precisely so this survives. */
    @Test
    fun scheduleNextAlarm_afterATimeZoneChange_stillHonoursASkippedOccurrence() {
        pinLocalHourTo(MIDDAY)
        val (hour, minute) = clockTimeIn(THREE_HOURS)
        val skippedDay =
            localDayOf(nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis()))
        insertAlarm(
            id = 1,
            hour = hour,
            minute = minute,
            repeatDays = EVERY_DAY,
            skippedOccurrenceDay = skippedDay,
        )

        shiftZoneBy(-1)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })

        val armed = assertNotNull(scheduledAlarmClock()?.triggerAtMs)
        assertEquals(
            skippedDay + 1,
            localDayOf(armed),
            "The skipped day must still be passed over in the new zone",
        )
        assertEquals(hour to minute, localTimeOf(armed))
    }

    /** A stale instant would read as already rung, and the notice would quote the wrong time. */
    @Test
    fun scheduleNextAlarm_afterATimeZoneChange_refreshesTheDismissActionsInstant() {
        pinLocalHourTo(MIDDAY)
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        val armedBefore = assertNotNull(scheduledAlarmClock()?.triggerAtMs)
        assertEquals(armedBefore, dismissTriggerMillis(), "Precondition: Dismiss names the alarm")

        // One hour west: 90 minutes out now, so the notice stays up.
        shiftZoneBy(-1)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })

        val armedAfter = assertNotNull(scheduledAlarmClock()?.triggerAtMs)
        assertNotNull(upcomingNotification(), "Precondition: still inside the window")
        assertEquals(
            armedAfter,
            dismissTriggerMillis(),
            "Dismiss must name the re-armed occurrence, not the pre-change instant",
        )
    }

    /** The same invariant one step earlier: this instant is what ACTION_SHOW hands the notifier. */
    @Test
    fun scheduleNextAlarm_afterATimeZoneChange_refreshesThePendingShowAlarmsInstant() {
        pinLocalHourTo(MIDDAY)
        val (hour, minute) = clockTimeIn(THREE_HOURS)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        assertNotNull(scheduledShowAlarm(), "Precondition: outside the window, so it is armed")

        shiftZoneBy(-1)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })

        val armedAfter = assertNotNull(scheduledAlarmClock()?.triggerAtMs)
        assertEquals(
            armedAfter,
            showAlarmTriggerMillis(),
            "The show alarm carries the instant into the notification, so it has to move too",
        )
    }

    /** A zone change with no reschedule leaves Dismiss naming an instant from the old offset. */
    @Test
    fun skipAlarms_withAnInstantFromTheOldZone_skipsTheOccurrenceThatIsNextNow() {
        pinLocalTimeTo(MIDNIGHT)
        val (hour, minute) = clockTimeIn(THIRTY_MINUTES)
        insertAlarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY)
        assertTrue(runBlocking { repository.scheduleNextAlarm() })
        val dismissed = assertNotNull(dismissTriggerMillis())

        // One hour west: 00:30 today, but yesterday's 23:30 in the zone just left behind.
        shiftZoneBy(-1)
        val next = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())
        assertEquals(
            localDayOf(dismissed) + 1,
            localDayOf(next),
            "Precondition: the instant now names the previous local day",
        )

        assertTrue(runBlocking { repository.skipAlarms(listOf(1), dismissed) })

        assertEquals(
            localDayOf(next),
            dao.alarm(1).skippedOccurrenceDay,
            "The skip must name the occurrence that is next in this zone",
        )
        assertEquals(
            next + 24 * 60 * 60 * 1000L,
            scheduledAlarmClock()?.triggerAtMs,
            "A skip resolved from the stale instant would leave that occurrence armed",
        )
    }

    /** Positive is east. */
    private fun shiftZoneBy(hours: Int) {
        setZoneToOffset(TimeZone.getDefault().rawOffset / 60_000 + hours * 60)
    }

    /** Pins "now" to [hour]:00 exactly; [pinLocalHourTo] leaves the minute to the real clock. */
    private fun pinLocalTimeTo(hour: Int) {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val nowMinutes = utc[Calendar.HOUR_OF_DAY] * 60 + utc[Calendar.MINUTE]
        // Real offsets stop at +14, so anything beyond becomes the equivalent negative one.
        val offset =
            (hour * 60 - nowMinutes).mod(24 * 60).let { if (it > 14 * 60) it - 24 * 60 else it }
        setZoneToOffset(offset)
    }

    /** The id must stay a GMT offset — `localDayOf` resolves it via `ZoneId`. */
    private fun setZoneToOffset(minutes: Int) {
        val sign = if (minutes < 0) "-" else "+"
        val magnitude = abs(minutes)
        TimeZone.setDefault(
            TimeZone.getTimeZone("GMT$sign%02d:%02d".format(magnitude / 60, magnitude % 60))
        )
    }

    private fun localTimeOf(millis: Long): Pair<Int, Int> =
        Calendar.getInstance()
            .apply { timeInMillis = millis }
            .let {
                it[Calendar.HOUR_OF_DAY] to it[Calendar.MINUTE]
            }

    private fun clockTimeIn(offsetMillis: Long): Pair<Int, Int> {
        val calendar =
            Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis() + offsetMillis
            }
        return calendar[Calendar.HOUR_OF_DAY] to calendar[Calendar.MINUTE]
    }

    private fun insertAlarm(
        id: Int,
        hour: Int,
        minute: Int,
        repeatDays: Set<Int> = emptySet(),
        skippedOccurrenceDay: Long? = null,
    ) {
        runBlocking {
            dao.insert(
                AlarmItem(
                    id = id,
                    hour = hour,
                    minute = minute,
                    isActive = true,
                    repeatDays = repeatDays,
                    skippedOccurrenceDay = skippedOccurrenceDay,
                )
            )
        }
    }

    private fun AlarmDao.alarm(id: Int): AlarmItem =
        runBlocking { getAlarmById(id) } ?: error("Alarm $id is missing from the database")

    private fun upcomingNotification(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(UpcomingAlarmNotifier.NOTIFICATION_ID)

    private fun scheduledAlarmClock(): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(alarmManager).scheduledAlarms.firstOrNull { it.alarmClockInfo != null }

    private fun scheduledShowAlarm(): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(alarmManager).scheduledAlarms.firstOrNull { it.alarmClockInfo == null }

    /** The instant the notification's Dismiss action would forward to `skipAlarms`. */
    private fun dismissTriggerMillis(): Long? =
        upcomingNotification()
            ?.actions
            ?.map { shadowOf(it.actionIntent).savedIntent }
            ?.single { it.action == UpcomingAlarmReceiver.ACTION_SKIP }
            ?.getLongExtra(UpcomingAlarmReceiver.EXTRA_TRIGGER_MILLIS, 0L)

    /** The instant the armed ACTION_SHOW broadcast would post the notification for. */
    @Suppress("DEPRECATION") // ScheduledAlarm offers no accessor for `operation`.
    private fun showAlarmTriggerMillis(): Long? =
        scheduledShowAlarm()?.let {
            shadowOf(it.operation)
                .savedIntent
                .getLongExtra(UpcomingAlarmReceiver.EXTRA_TRIGGER_MILLIS, 0L)
        }
}
