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
package com.dsalmun.luxalarm

import com.dsalmun.luxalarm.data.AlarmItem
import com.dsalmun.luxalarm.testing.FakeAlarmRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class AlarmViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: AlarmViewModel
    private lateinit var fakeRepository: FakeAlarmRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeAlarmRepository()
        viewModel = AlarmViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addAlarm_callsRepositoryAndHandlesSuccess() = runTest {
        val hour = 6
        val minute = 30
        fakeRepository.setShouldSucceed(true)

        viewModel.addAlarm(hour, minute)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.addAlarmCallCount)
    }

    @Test
    fun addAlarm_callsRepositoryAndEmitsEventOnPermissionError() = runTest {
        val hour = 6
        val minute = 30
        fakeRepository.setShouldSucceed(false)

        val collectedEvents = mutableListOf<AlarmViewModel.Event>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { collectedEvents.add(it) }
            }

        viewModel.addAlarm(hour, minute)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, collectedEvents.size)
        assertEquals(AlarmViewModel.Event.ShowPermissionError, collectedEvents[0])

        job.cancel()
        assertEquals(1, fakeRepository.addAlarmCallCount)
    }

    @Test
    fun toggleAlarm_callsRepository() = runTest {
        val alarmId = 1
        val isActive = true
        fakeRepository.setShouldSucceed(true)

        viewModel.toggleAlarm(alarmId, isActive)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.toggleAlarmCallCount)
    }

    @Test
    fun toggleAlarm_emitsEventOnPermissionError() = runTest {
        fakeRepository.setShouldSucceed(false)

        val collectedEvents = mutableListOf<AlarmViewModel.Event>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { collectedEvents.add(it) }
            }

        viewModel.toggleAlarm(alarmId = 1, isActive = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, collectedEvents.size)
        assertEquals(AlarmViewModel.Event.ShowPermissionError, collectedEvents[0])

        job.cancel()
        assertEquals(1, fakeRepository.toggleAlarmCallCount)
    }

    /** Turning an alarm off has nothing to confirm, so the success path stays silent. */
    @Test
    fun toggleAlarm_whenTurningOff_emitsNothing() = runTest {
        fakeRepository.setShouldSucceed(true)

        val collectedEvents = mutableListOf<AlarmViewModel.Event>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { collectedEvents.add(it) }
            }

        viewModel.toggleAlarm(alarmId = 1, isActive = false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), collectedEvents)

        job.cancel()
    }

    @Test
    fun deleteAlarm_callsRepository() = runTest {
        val alarmId = 1
        viewModel.deleteAlarm(alarmId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, fakeRepository.deleteAlarmCallCount)
    }

    @Test
    fun updateAlarmTime_callsRepository() = runTest {
        val alarmId = 1
        val hour = 10
        val minute = 30
        fakeRepository.setShouldSucceed(true)

        viewModel.updateAlarmTime(alarmId, hour, minute)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.updateAlarmTimeCallCount)
    }

    @Test
    fun updateAlarmTime_emitsEventOnPermissionError() = runTest {
        fakeRepository.setShouldSucceed(false)

        val collectedEvents = mutableListOf<AlarmViewModel.Event>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { collectedEvents.add(it) }
            }

        viewModel.updateAlarmTime(alarmId = 1, hour = 10, minute = 30)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, collectedEvents.size)
        assertEquals(AlarmViewModel.Event.ShowPermissionError, collectedEvents[0])

        job.cancel()
        assertEquals(1, fakeRepository.updateAlarmTimeCallCount)
    }

    @Test
    fun setRepeatDays_callsRepository() = runTest {
        val alarmId = 1
        val repeatDays = setOf(1, 2, 3)

        viewModel.setRepeatDays(alarmId, repeatDays)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.setRepeatDaysCallCount)
    }

    @Test
    fun setAlarmRingtone_callsRepository() = runTest {
        val alarmId = 1
        val ringtoneUri = "content://media/internal/audio/media/1"

        viewModel.setAlarmRingtone(alarmId, ringtoneUri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.setAlarmRingtoneCallCount)
        assertEquals(alarmId, fakeRepository.lastRingtoneAlarmId)
        assertEquals(ringtoneUri, fakeRepository.lastRingtoneUri)
    }

    @Test
    fun setAlarmRingtone_withNull_callsRepositoryWithNull() = runTest {
        val alarmId = 2

        viewModel.setAlarmRingtone(alarmId, null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.setAlarmRingtoneCallCount)
        assertEquals(alarmId, fakeRepository.lastRingtoneAlarmId)
        assertEquals(null, fakeRepository.lastRingtoneUri)
    }

    @Test
    fun setAlarmVolume_callsRepository() = runTest {
        val alarmId = 1

        viewModel.setAlarmVolume(alarmId, 0.25f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.setAlarmVolumeCallCount)
        assertEquals(alarmId, fakeRepository.lastVolumeAlarmId)
        assertEquals(0.25f, fakeRepository.lastVolume)
    }

    /** Null is the "follow the system alarm volume" sentinel, so it has to reach the repository. */
    @Test
    fun setAlarmVolume_withNull_callsRepositoryWithNull() = runTest {
        val alarmId = 2

        viewModel.setAlarmVolume(alarmId, null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.setAlarmVolumeCallCount)
        assertEquals(alarmId, fakeRepository.lastVolumeAlarmId)
        assertEquals(null, fakeRepository.lastVolume)
    }

    @Test
    fun setAlarmVibration_callsRepository() = runTest {
        val alarmId = 1

        viewModel.setAlarmVibration(alarmId, false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.setAlarmVibrationCallCount)
        assertEquals(alarmId, fakeRepository.lastVibrationAlarmId)
        assertEquals(false, fakeRepository.lastVibrationEnabled)
    }

    @Test
    fun skipNext_callsRepositoryWithAlarmId() = runTest {
        val alarm = AlarmItem(id = 7, hour = 8, minute = 0, repeatDays = setOf(2, 3, 4, 5, 6))

        viewModel.skipNext(alarm)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.skipAlarmsCallCount)
        assertEquals(listOf(7), fakeRepository.lastSkipIds)
    }

    @Test
    fun skipNext_emitsEventOnPermissionError() = runTest {
        val alarm = AlarmItem(id = 7, hour = 8, minute = 0, repeatDays = setOf(2, 3, 4, 5, 6))
        fakeRepository.setShouldSucceed(false)

        val collectedEvents = mutableListOf<AlarmViewModel.Event>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { collectedEvents.add(it) }
            }

        viewModel.skipNext(alarm)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, collectedEvents.size)
        assertEquals(AlarmViewModel.Event.ShowPermissionError, collectedEvents[0])

        job.cancel()
        assertEquals(1, fakeRepository.skipAlarmsCallCount)
    }

    @Test
    fun cancelSkip_callsRepository() = runTest {
        viewModel.cancelSkip(7)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeRepository.cancelSkipCallCount)
        assertEquals(7, fakeRepository.lastCancelSkipAlarmId)
    }

    @Test
    fun cancelSkip_emitsEventOnPermissionError() = runTest {
        fakeRepository.setShouldSucceed(false)

        val collectedEvents = mutableListOf<AlarmViewModel.Event>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.collect { collectedEvents.add(it) }
            }

        viewModel.cancelSkip(7)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, collectedEvents.size)
        assertEquals(AlarmViewModel.Event.ShowPermissionError, collectedEvents[0])

        job.cancel()
        assertEquals(1, fakeRepository.cancelSkipCallCount)
    }

    @Test
    fun alarmUiStates_stateFlowCollectsFromRepository() = runTest {
        val fakeAlarms = listOf(AlarmItem(id = 1, hour = 8, minute = 0))
        val newViewModel = AlarmViewModel(fakeRepository)

        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                newViewModel.alarmUiStates.collect {}
            }
        // runCurrent, not advanceUntilIdle: the endless ticker in alarmUiStates never goes idle.
        testDispatcher.scheduler.runCurrent()

        fakeRepository.emit(fakeAlarms)
        testDispatcher.scheduler.runCurrent()

        assertEquals(fakeAlarms, newViewModel.alarmUiStates.value.map { it.alarm })

        job.cancel()
    }

    @Test
    fun updateAlarmTime_toastEventCarriesRepeatDaysFromCachedState() = runTest {
        val repeatDays = setOf(2, 4, 6)
        val newViewModel = AlarmViewModel(fakeRepository)
        val collectedEvents = mutableListOf<AlarmViewModel.Event>()

        val stateJob =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                newViewModel.alarmUiStates.collect {}
            }
        val eventJob =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                newViewModel.events.collect { collectedEvents.add(it) }
            }
        fakeRepository.emit(
            listOf(AlarmItem(id = 1, hour = 8, minute = 0, repeatDays = repeatDays))
        )
        testDispatcher.scheduler.runCurrent()

        newViewModel.updateAlarmTime(1, 9, 30)
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, collectedEvents.size)
        assertEquals(
            AlarmViewModel.Event.ShowAlarmSetMessage(9, 30, repeatDays),
            collectedEvents[0],
        )

        eventJob.cancel()
        stateJob.cancel()
    }

    @Test
    fun toggleAlarmOn_emitsToastEventFromCachedState() = runTest {
        val newViewModel = AlarmViewModel(fakeRepository)
        val collectedEvents = mutableListOf<AlarmViewModel.Event>()

        val stateJob =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                newViewModel.alarmUiStates.collect {}
            }
        val eventJob =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                newViewModel.events.collect { collectedEvents.add(it) }
            }
        fakeRepository.emit(listOf(AlarmItem(id = 1, hour = 8, minute = 0)))
        testDispatcher.scheduler.runCurrent()

        newViewModel.toggleAlarm(1, true)
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, collectedEvents.size)
        assertEquals(
            AlarmViewModel.Event.ShowAlarmSetMessage(8, 0, emptySet()),
            collectedEvents[0],
        )

        eventJob.cancel()
        stateJob.cancel()
    }
}
