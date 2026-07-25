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

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.WEEKDAYS
import com.dsalmun.luxalarm.testing.alarm
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The write half of [AlarmRepository]; skip and scheduling live in [AlarmRepositoryTest]. Every
 * mutating call reschedules, and a withdrawn permission has to roll the write back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmRepositoryCrudTest {
    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var dao: AlarmDao
    private lateinit var repository: AlarmRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.alarmDao()
        repository = AlarmRepository(dao, context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context.getSharedPreferences("alarm_state", Context.MODE_PRIVATE).edit { clear() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addAlarm_storesAnActiveAlarm() = runBlocking {
        assertTrue(repository.addAlarm(hour = 7, minute = 5))

        val stored = dao.getActiveAlarms().single()
        assertEquals(7, stored.hour)
        assertEquals(5, stored.minute)
        assertTrue(stored.isActive)
    }

    @Test
    fun addAlarm_whenExactAlarmsAreDenied_removesTheAlarmAgain() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(repository.addAlarm(hour = 7, minute = 5))

        assertEquals(emptyList(), dao.getAllAlarmIds())
    }

    @Test
    fun addAlarm_whenDenied_leavesAnyExistingAlarmsAlone() = runBlocking {
        dao.insert(alarm(id = 1, hour = 6, minute = 0, repeatDays = EVERY_DAY))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(repository.addAlarm(hour = 7, minute = 5))

        assertEquals(listOf(1), dao.getAllAlarmIds())
    }

    @Test
    fun toggleAlarm_disablesAndReEnables() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = EVERY_DAY, isActive = true))

        assertTrue(repository.toggleAlarm(1, isActive = false))
        assertFalse(dao.getAlarmById(1)!!.isActive)

        assertTrue(repository.toggleAlarm(1, isActive = true))
        assertTrue(dao.getAlarmById(1)!!.isActive)
    }

    @Test
    fun toggleAlarm_enablingWhenDenied_revertsAndFails() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = EVERY_DAY, isActive = false))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(repository.toggleAlarm(1, isActive = true))

        assertFalse(dao.getAlarmById(1)!!.isActive, "The alarm must stay off")
    }

    /** With nothing left to arm, scheduling short-circuits before it ever checks the permission. */
    @Test
    fun toggleAlarm_disablingTheLastAlarmWhenDenied_stillSucceeds() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = EVERY_DAY, isActive = true))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertTrue(repository.toggleAlarm(1, isActive = false))

        assertFalse(dao.getAlarmById(1)!!.isActive)
    }

    @Test
    fun toggleAlarm_onAnUnknownId_isANoOpThatReportsSuccess() = runBlocking {
        assertTrue(repository.toggleAlarm(99, isActive = true))

        assertEquals(emptyList(), dao.getAllAlarmIds())
    }

    @Test
    fun updateAlarmTime_movesTheAlarmReactivatesItAndClearsAnyPendingSkip() = runBlocking {
        dao.insert(
            alarm(
                id = 1,
                hour = 6,
                minute = 0,
                isActive = false,
                repeatDays = EVERY_DAY,
                skippedOccurrenceDay = 19_000L,
            )
        )

        assertTrue(repository.updateAlarmTime(1, hour = 9, minute = 45))

        val stored = dao.getAlarmById(1)!!
        assertEquals(9, stored.hour)
        assertEquals(45, stored.minute)
        assertTrue(stored.isActive, "Editing the time re-arms the alarm")
        assertNull(stored.skippedOccurrenceDay, "Editing the time cancels a pending skip")
    }

    @Test
    fun updateAlarmTime_whenDenied_restoresEveryFieldItTouched() = runBlocking {
        dao.insert(
            alarm(
                id = 1,
                hour = 6,
                minute = 0,
                isActive = false,
                repeatDays = EVERY_DAY,
                skippedOccurrenceDay = 19_000L,
            )
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(repository.updateAlarmTime(1, hour = 9, minute = 45))

        val stored = dao.getAlarmById(1)!!
        assertEquals(6, stored.hour)
        assertEquals(0, stored.minute)
        assertFalse(stored.isActive)
        assertEquals(19_000L, stored.skippedOccurrenceDay, "The skip has to come back too")
    }

    @Test
    fun updateAlarmTime_onAnUnknownId_isANoOpThatReportsSuccess() = runBlocking {
        assertTrue(repository.updateAlarmTime(99, hour = 9, minute = 45))
    }

    @Test
    fun deleteAlarm_removesOnlyThatAlarm() = runBlocking {
        dao.insert(alarm(id = 1, hour = 6))
        dao.insert(alarm(id = 2, hour = 7))

        repository.deleteAlarm(1)

        assertEquals(listOf(2), dao.getAllAlarmIds())
    }

    @Test
    fun deleteAlarm_onAnUnknownId_isANoOp() = runBlocking {
        dao.insert(alarm(id = 1, hour = 6))

        repository.deleteAlarm(99)

        assertEquals(listOf(1), dao.getAllAlarmIds())
    }

    @Test
    fun setRepeatDays_replacesTheScheduleAndClearsAnyPendingSkip() = runBlocking {
        dao.insert(
            alarm(id = 1, repeatDays = setOf(Calendar.MONDAY), skippedOccurrenceDay = 19_000L)
        )

        repository.setRepeatDays(1, WEEKDAYS)

        val stored = dao.getAlarmById(1)!!
        assertEquals(WEEKDAYS, stored.repeatDays)
        assertNull(stored.skippedOccurrenceDay, "Editing the schedule cancels a pending skip")
    }

    @Test
    fun setRepeatDays_canClearTheScheduleBackToOneShot() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = EVERY_DAY))

        repository.setRepeatDays(1, emptySet())

        assertEquals(emptySet(), dao.getAlarmById(1)!!.repeatDays)
    }

    @Test
    fun setRepeatDays_onAnUnknownId_isANoOp() = runBlocking {
        repository.setRepeatDays(99, WEEKDAYS)

        assertEquals(emptyList(), dao.getAllAlarmIds())
    }

    // Sound and haptics change how an alarm rings, not whether it is armed, so they do not revert.

    @Test
    fun setAlarmRingtone_storesAndClearsTheUri() = runBlocking {
        dao.insert(alarm(id = 1))
        val uri = "content://media/internal/audio/media/42"

        repository.setAlarmRingtone(1, uri)
        assertEquals(uri, dao.getAlarmById(1)!!.ringtoneUri)

        repository.setAlarmRingtone(1, null)
        assertNull(dao.getAlarmById(1)!!.ringtoneUri, "Null means fall back to the default sound")
    }

    @Test
    fun setAlarmRingtone_whenDeniedTheChoiceIsStillKept() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = EVERY_DAY))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        repository.setAlarmRingtone(1, "content://media/internal/audio/media/42")

        assertEquals(
            "content://media/internal/audio/media/42",
            dao.getAlarmById(1)!!.ringtoneUri,
            "A ringtone choice is not worth discarding over a scheduling failure",
        )
    }

    @Test
    fun setAlarmVolume_storesTheValue() = runBlocking {
        dao.insert(alarm(id = 1))

        repository.setAlarmVolume(1, 0.25f)

        assertEquals(0.25f, dao.getAlarmById(1)!!.volume)
    }

    @Test
    fun setAlarmVolume_clampsOutOfRangeValues() = runBlocking {
        dao.insert(alarm(id = 1))

        repository.setAlarmVolume(1, 1.5f)
        assertEquals(1f, dao.getAlarmById(1)!!.volume, "Above full volume clamps to full")

        repository.setAlarmVolume(1, -0.5f)
        assertEquals(0f, dao.getAlarmById(1)!!.volume, "Below silence clamps to silence")
    }

    @Test
    fun setAlarmVolume_nullRestoresTheSystemDefault() = runBlocking {
        dao.insert(alarm(id = 1, volume = 0.5f))

        repository.setAlarmVolume(1, null)

        assertNull(dao.getAlarmById(1)!!.volume)
    }

    @Test
    fun setAlarmVibration_togglesTheFlag() = runBlocking {
        dao.insert(alarm(id = 1, vibrationEnabled = true))

        repository.setAlarmVibration(1, false)
        assertFalse(dao.getAlarmById(1)!!.vibrationEnabled)

        repository.setAlarmVibration(1, true)
        assertTrue(dao.getAlarmById(1)!!.vibrationEnabled)
    }

    @Test
    fun perAlarmSettings_onAnUnknownId_areNoOps() = runBlocking {
        repository.setAlarmRingtone(99, "content://whatever")
        repository.setAlarmVolume(99, 0.5f)
        repository.setAlarmVibration(99, false)

        assertEquals(emptyList(), dao.getAllAlarmIds())
    }

    @Test
    fun noAlarmIsRingingByDefault() {
        assertFalse(repository.isAlarmRinging())
    }

    /** The second claim is refused, so no second receiver starts a competing service. */
    @Test
    fun setRingingAlarm_onlySucceedsForTheFirstClaimant() {
        assertTrue(repository.setRingingAlarm())
        assertTrue(repository.isAlarmRinging())

        assertFalse(repository.setRingingAlarm(), "A second alarm must not be able to claim it")
    }

    @Test
    fun clearRingingAlarm_releasesTheClaim() {
        repository.setRingingAlarm()

        repository.clearRingingAlarm()

        assertFalse(repository.isAlarmRinging())
        assertTrue(repository.setRingingAlarm(), "The next alarm can claim it again")
    }

    @Test
    fun theRingingFlagSurvivesANewRepositoryInstance() {
        repository.setRingingAlarm()

        assertTrue(
            AlarmRepository(dao, context).isAlarmRinging(),
            "The flag is persisted, so a process restart mid-alarm is still recoverable",
        )
    }

    @Test
    fun deactivateOneShotAlarms_leavesRepeatingAlarmsArmed() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = emptySet()))
        dao.insert(alarm(id = 2, repeatDays = EVERY_DAY))

        repository.deactivateOneShotAlarms(listOf(1, 2))

        assertFalse(dao.getAlarmById(1)!!.isActive)
        assertTrue(dao.getAlarmById(2)!!.isActive)
    }

    @Test
    fun getAllAlarms_emitsTheStoredAlarmsInTimeOrder() = runBlocking {
        dao.insert(alarm(id = 1, hour = 9, minute = 30))
        dao.insert(alarm(id = 2, hour = 6, minute = 15))

        assertEquals(listOf(2, 1), repository.getAllAlarms().first().map { it.id })
    }

    @Test
    fun canScheduleExactAlarms_reflectsThePlatformPermission() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(repository.canScheduleExactAlarms())

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(repository.canScheduleExactAlarms())
    }

    @Test
    fun cancelV1Alarms_runsOnceAndThenMarksItselfDone() {
        runBlocking {
            dao.insert(alarm(id = 1, repeatDays = EVERY_DAY))
            val prefs = context.getSharedPreferences("alarm_state", Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean("v1_migrated", false), "Precondition: not yet migrated")

            repository.cancelV1Alarms()

            assertTrue(prefs.getBoolean("v1_migrated", false))
            assertNotNull(dao.getAlarmById(1), "Migration must not touch the user's alarms")

            // A second run is a no-op: it neither throws nor loses data.
            repository.cancelV1Alarms()
            assertNotNull(dao.getAlarmById(1))
        }
    }
}
