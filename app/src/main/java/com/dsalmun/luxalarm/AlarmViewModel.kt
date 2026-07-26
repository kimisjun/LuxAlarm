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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsalmun.luxalarm.data.AlarmItem
import com.dsalmun.luxalarm.data.IAlarmRepository
import com.dsalmun.luxalarm.data.UPCOMING_LEAD_MILLIS
import com.dsalmun.luxalarm.data.localDayOf
import com.dsalmun.luxalarm.data.nextTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlarmViewModel(private val repository: IAlarmRepository) : ViewModel() {
    // The alarm Flow only re-emits on DB changes, so tick to keep upcoming/skipping state live.
    private val ticker = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(millisUntilNextTick(now))
        }
    }

    val alarmUiStates: StateFlow<List<AlarmUiState>> =
        combine(repository.getAllAlarms(), ticker) { alarms, now ->
                alarms.map { alarm -> alarm.toUiState(now) }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    /** Reads [alarmUiStates] rather than the DAO: the screen subscribes to it, so it is warm. */
    private fun findAlarm(alarmId: Int): AlarmItem? =
        alarmUiStates.value.find { it.alarm.id == alarmId }?.alarm

    fun addAlarm(hour: Int, minute: Int) {
        viewModelScope.launch {
            if (repository.addAlarm(hour, minute)) {
                _events.emit(Event.ShowAlarmSetMessage(hour, minute, emptySet()))
            } else {
                _events.emit(Event.ShowPermissionError)
            }
        }
    }

    fun toggleAlarm(alarmId: Int, isActive: Boolean) {
        viewModelScope.launch {
            if (repository.toggleAlarm(alarmId, isActive)) {
                if (isActive) {
                    val alarm = findAlarm(alarmId)
                    if (alarm != null) {
                        _events.emit(
                            Event.ShowAlarmSetMessage(alarm.hour, alarm.minute, alarm.repeatDays)
                        )
                    }
                }
            } else {
                _events.emit(Event.ShowPermissionError)
            }
        }
    }

    fun updateAlarmTime(alarmId: Int, hour: Int, minute: Int) {
        viewModelScope.launch {
            if (repository.updateAlarmTime(alarmId, hour, minute)) {
                val alarm = findAlarm(alarmId)
                _events.emit(
                    Event.ShowAlarmSetMessage(hour, minute, alarm?.repeatDays ?: emptySet())
                )
            } else {
                _events.emit(Event.ShowPermissionError)
            }
        }
    }

    fun deleteAlarm(alarmId: Int) {
        viewModelScope.launch { repository.deleteAlarm(alarmId) }
    }

    fun setRepeatDays(alarmId: Int, repeatDays: Set<Int>) {
        viewModelScope.launch { repository.setRepeatDays(alarmId, repeatDays) }
    }

    fun setAlarmRingtone(alarmId: Int, ringtoneUri: String?) {
        viewModelScope.launch { repository.setAlarmRingtone(alarmId, ringtoneUri) }
    }

    fun setAlarmVolume(alarmId: Int, volume: Float?) {
        viewModelScope.launch { repository.setAlarmVolume(alarmId, volume) }
    }

    fun setAlarmVibration(alarmId: Int, enabled: Boolean) {
        viewModelScope.launch { repository.setAlarmVibration(alarmId, enabled) }
    }

    /** Backs the inline "Dismiss" on a card inside the upcoming window. */
    fun skipNext(alarm: AlarmItem) {
        val dismissed =
            nextTrigger(alarm.hour, alarm.minute, alarm.repeatDays, System.currentTimeMillis())
        viewModelScope.launch {
            if (!repository.skipAlarms(listOf(alarm.id), dismissed)) {
                _events.emit(Event.ShowPermissionError)
            }
        }
    }

    fun cancelSkip(alarmId: Int) {
        viewModelScope.launch {
            if (!repository.cancelSkip(alarmId)) {
                _events.emit(Event.ShowPermissionError)
            }
        }
    }

    private fun AlarmItem.toUiState(now: Long): AlarmUiState {
        val rawNext = nextTrigger(hour, minute, repeatDays, now)
        // Mirror the scheduler: it only honours a skip on the raw next occurrence's day, so an
        // expired skip must not be shown as skipping — the row would claim a still-ringing alarm.
        val isSkippingNext = skippedOccurrenceDay == localDayOf(rawNext)
        val isUpcoming = isActive && !isSkippingNext && (rawNext - now) in 0..UPCOMING_LEAD_MILLIS
        return AlarmUiState(
            alarm = this,
            isUpcoming = isUpcoming,
            isSkippingNext = isSkippingNext,
            skippedTriggerMillis = rawNext.takeIf { isSkippingNext },
            nextTriggerMillis =
                if (isSkippingNext) nextTrigger(hour, minute, repeatDays, now, skippedOccurrenceDay)
                else rawNext,
            nowMillis = now,
        )
    }

    /**
     * @param skippedTriggerMillis the occurrence being passed over; null unless [isSkippingNext].
     * @param nextTriggerMillis when the alarm rings next, with any skip already applied.
     */
    data class AlarmUiState(
        val alarm: AlarmItem,
        val isUpcoming: Boolean,
        val isSkippingNext: Boolean,
        val skippedTriggerMillis: Long?,
        val nextTriggerMillis: Long,
        val nowMillis: Long,
    )

    sealed class Event {
        data class ShowAlarmSetMessage(val hour: Int, val minute: Int, val repeatDays: Set<Int>) :
            Event()

        data object ShowPermissionError : Event()
    }
}

private const val TICK_INTERVAL_MILLIS = 60_000L
private const val TICK_SKEW_MILLIS = 100L

/**
 * Delay from [nowMillis] to the next state recompute. Alarm times carry no seconds, so upcoming and
 * skipping state only flips on a minute boundary; waking just past one keeps the flip prompt, where
 * a free-running interval would lag by up to its full period.
 */
internal fun millisUntilNextTick(nowMillis: Long): Long =
    TICK_INTERVAL_MILLIS - nowMillis % TICK_INTERVAL_MILLIS + TICK_SKEW_MILLIS

class AlarmViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AlarmViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AlarmViewModel(AppContainer.repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
