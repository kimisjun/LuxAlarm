/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.WEEKDAYS
import com.dsalmun.luxalarm.testing.alarm
import com.dsalmun.luxalarm.testing.clockTimeIn
import com.dsalmun.luxalarm.testing.uiState
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Driven directly, away from [AlarmViewModel]'s ticker and [android.media.RingtoneManager]. */
@RunWith(AndroidJUnit4::class)
class AlarmScreenContentTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val recorded = mutableListOf<String>()

    @Test
    fun withNoAlarms_showsThePromptToAddOne() {
        setContent(states = emptyList())

        composeRule.onNodeWithText("No alarms set. Tap '+' to add one.").assertIsDisplayed()
    }

    @Test
    fun rendersOneRowPerAlarm() {
        setContent(
            states =
                listOf(
                    uiState(alarm(id = 1, hour = 7, minute = 5)),
                    uiState(alarm(id = 2, hour = 21, minute = 30)),
                )
        )

        composeRule.onNodeWithText("07:05").assertIsDisplayed()
        composeRule.onNodeWithText("21:30").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Expand").assertCountEquals(2)
    }

    @Test
    fun showsTheRepeatDayLabel() {
        setContent(
            states =
                listOf(
                    uiState(alarm(id = 1, hour = 7, repeatDays = WEEKDAYS)),
                    uiState(alarm(id = 2, hour = 8, repeatDays = EVERY_DAY)),
                )
        )

        composeRule.onNodeWithText("Mon, Tue, Wed, Thu, Fri").assertIsDisplayed()
        composeRule.onNodeWithText("Every day").assertIsDisplayed()
    }

    @Test
    fun aCollapsedRow_hidesTheDetailControls() {
        setContent(states = listOf(uiState(alarm(id = 1))), expandedAlarmId = null)

        composeRule.onNodeWithContentDescription("Ringtone").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Volume").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Delete alarm").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun anExpandedRow_revealsEveryDetailControl() {
        setContent(states = listOf(uiState(alarm(id = 1))), expandedAlarmId = 1)

        composeRule.onNodeWithContentDescription("Collapse").assertExists()
        composeRule.onNodeWithContentDescription("Expand").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Ringtone").assertExists()
        composeRule.onNodeWithText(RINGTONE_NAME).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Volume").assertExists()
        composeRule.onNodeWithContentDescription("Vibration").assertExists()
        composeRule.onNodeWithContentDescription("Delete alarm").assertExists()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        // The two weekend circles both read "S".
        composeRule.onAllNodesWithText("S").assertCountEquals(2)
    }

    @Test
    fun onlyTheExpandedRowShowsItsDetails() {
        setContent(
            states = listOf(uiState(alarm(id = 1, hour = 7)), uiState(alarm(id = 2, hour = 8))),
            expandedAlarmId = 2,
        )

        composeRule.onAllNodesWithContentDescription("Collapse").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Expand").assertCountEquals(1)
        composeRule.onAllNodesWithText("Delete").assertCountEquals(1)
    }

    @Test
    fun anUpcomingRepeatingAlarm_offersSkipNext() {
        setContent(
            states = listOf(uiState(alarm(id = 1, repeatDays = EVERY_DAY), isUpcoming = true))
        )

        composeRule.onNodeWithText("Skip next").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
    }

    /** Skipping a one-shot only turns it off, which is the switch's job. */
    @Test
    fun anUpcomingOneShotAlarm_offersNoActionBesideItsSwitch() {
        val now = System.currentTimeMillis()
        val (hour, minute) = clockTimeIn(42 * 60 * 1000L)
        setContent(
            states =
                listOf(
                    uiState(
                        alarm(id = 1, hour = hour, minute = minute),
                        isUpcoming = true,
                        nowMillis = now,
                    )
                )
        )

        composeRule.onNodeWithText("Skip next").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Alarm enabled").assertIsDisplayed()
        composeRule.onNodeWithText("Rings in 42 min").assertIsDisplayed()
    }

    @Test
    fun anUpcomingAlarm_countsDownInsteadOfNamingItsDays() {
        val now = System.currentTimeMillis()
        val (hour, minute) = clockTimeIn(42 * 60 * 1000L)
        setContent(
            states =
                listOf(
                    uiState(
                        alarm(id = 1, hour = hour, minute = minute, repeatDays = EVERY_DAY),
                        isUpcoming = true,
                        nowMillis = now,
                    )
                )
        )

        composeRule.onNodeWithText("Rings in 42 min").assertIsDisplayed()
        composeRule.onNodeWithText("Every day").assertDoesNotExist()
    }

    @Test
    fun aSkippedAlarm_saysWhenItRingsInsteadAndOffersUndo() {
        setContent(
            states = listOf(uiState(alarm(id = 1, repeatDays = EVERY_DAY), isSkippingNext = true))
        )

        composeRule.onNodeWithText("Skipping", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("rings", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        composeRule.onNodeWithText("Skip next").assertDoesNotExist()
    }

    /** Guards the branch order: a skipped alarm is never also offered as skippable. */
    @Test
    fun skippingTakesPrecedenceOverUpcoming() {
        setContent(
            states =
                listOf(
                    uiState(
                        alarm(id = 1, repeatDays = EVERY_DAY),
                        isUpcoming = true,
                        isSkippingNext = true,
                    )
                )
        )

        composeRule.onNodeWithText("Skipping", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Skip next").assertDoesNotExist()
    }

    @Test
    fun anOrdinaryAlarm_offersNeitherAction() {
        setContent(states = listOf(uiState(alarm(id = 1))))

        composeRule.onNodeWithText("Skip next").assertDoesNotExist()
        composeRule.onNodeWithText("Skipping", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun theSkipAction_reportsTheAlarmToSkip() {
        setContent(
            states = listOf(uiState(alarm(id = 4, repeatDays = EVERY_DAY), isUpcoming = true))
        )

        composeRule.onNodeWithText("Skip next").performClick()

        assertEquals(listOf("skip:4"), recorded)
    }

    @Test
    fun undo_reportsTheAlarmToUnskip() {
        setContent(
            states = listOf(uiState(alarm(id = 4, repeatDays = EVERY_DAY), isSkippingNext = true))
        )

        composeRule.onNodeWithText("Undo").performClick()

        assertEquals(listOf("cancelSkip:4"), recorded)
    }

    @Test
    fun theAddButton_reportsAnAdd() {
        setContent(states = emptyList())

        composeRule.onNodeWithContentDescription("Add Alarm").performClick()

        assertEquals(listOf("add"), recorded)
    }

    @Test
    fun theSettingsIcon_reportsSettings() {
        setContent(states = emptyList())

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertEquals(listOf("settings"), recorded)
    }

    @Test
    fun tappingTheTime_reportsATimeEdit() {
        setContent(states = listOf(uiState(alarm(id = 3, hour = 7, minute = 5))))

        composeRule.onNodeWithText("07:05").performClick()

        assertEquals(listOf("time:3"), recorded)
    }

    @Test
    fun tappingTheRow_reportsAnExpandRequest() {
        setContent(states = listOf(uiState(alarm(id = 3, hour = 7, repeatDays = EVERY_DAY))))

        // The repeat label is inert text inside the clickable card.
        composeRule.onNodeWithText("Every day").performClick()

        assertEquals(listOf("click:3"), recorded)
    }

    @Test
    fun theSwitch_reportsTheInvertedState() {
        setContent(states = listOf(uiState(alarm(id = 3, isActive = true))))

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertEquals(listOf("toggle:3:false"), recorded)
    }

    @Test
    fun theVibrationCheckbox_reportsTheInvertedState() {
        setContent(
            states = listOf(uiState(alarm(id = 3, vibrationEnabled = true))),
            expandedAlarmId = 3,
        )

        composeRule.onNodeWithContentDescription("Vibration enabled").performClick()

        assertEquals(listOf("vibration:3:false"), recorded)
    }

    @Test
    fun theDaySelector_addsAnUnselectedDay() {
        setContent(
            states = listOf(uiState(alarm(id = 3, repeatDays = setOf(Calendar.MONDAY)))),
            expandedAlarmId = 3,
        )

        composeRule.onNodeWithText("W").performClick()

        assertEquals(listOf("days:3:[2, 4]"), recorded)
    }

    @Test
    fun theDaySelector_removesASelectedDay() {
        setContent(
            states =
                listOf(
                    uiState(alarm(id = 3, repeatDays = setOf(Calendar.MONDAY, Calendar.WEDNESDAY)))
                ),
            expandedAlarmId = 3,
        )

        composeRule.onNodeWithText("M").performClick()

        assertEquals(listOf("days:3:[4]"), recorded)
    }

    @Test
    fun theDeleteRow_reportsADeleteRequest() {
        setContent(states = listOf(uiState(alarm(id = 3))), expandedAlarmId = 3)

        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("delete:3"), recorded)
    }

    @Test
    fun theRingtoneRow_reportsARingtoneRequest() {
        setContent(states = listOf(uiState(alarm(id = 3))), expandedAlarmId = 3)

        composeRule.onNodeWithText(RINGTONE_NAME).performClick()

        assertEquals(listOf("ringtone:3"), recorded)
    }

    @Test
    fun inertPartsOfTheRowReportNothing() {
        setContent(states = listOf(uiState(alarm(id = 3))))

        assertNull(recorded.firstOrNull())
    }

    private fun setContent(
        states: List<AlarmViewModel.AlarmUiState>,
        expandedAlarmId: Int? = null,
    ) {
        composeRule.setContent {
            // dynamicColor defaults to true and would read the host's system palette.
            LuxAlarmTheme(dynamicColor = false) {
                AlarmScreenContent(
                    alarmStates = states,
                    expandedAlarmId = expandedAlarmId,
                    ringtoneNameFor = { RINGTONE_NAME },
                    onSettingsClick = { recorded += "settings" },
                    onAddClick = { recorded += "add" },
                    onAlarmClick = { recorded += "click:${it.id}" },
                    onTimeClick = { recorded += "time:${it.id}" },
                    onToggle = { a, active -> recorded += "toggle:${a.id}:$active" },
                    onSkip = { recorded += "skip:${it.id}" },
                    onCancelSkip = { recorded += "cancelSkip:${it.id}" },
                    onRepeatDaysChange = { a, days ->
                        recorded += "days:${a.id}:${days.sorted()}"
                    },
                    onVolumeChange = { a, v -> recorded += "volume:${a.id}:$v" },
                    onVibrationToggle = { a, on -> recorded += "vibration:${a.id}:$on" },
                    onDeleteClick = { recorded += "delete:${it.id}" },
                    onRingtoneClick = { recorded += "ringtone:${it.id}" },
                )
            }
        }
    }

    private companion object {
        const val RINGTONE_NAME = "Bright Morning"
    }
}
