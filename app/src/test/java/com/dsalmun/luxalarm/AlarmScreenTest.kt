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

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.data.AlarmItem
import com.dsalmun.luxalarm.data.localDayOf
import com.dsalmun.luxalarm.data.nextTrigger
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.alarm
import com.dsalmun.luxalarm.testing.clockTimeIn
import com.dsalmun.luxalarm.testing.pinLocalHourTo
import com.dsalmun.luxalarm.testing.restoreSystemTimeZone
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

/**
 * The alarm list over a real [AlarmViewModel] and a fake repository, so the screen's own state —
 * which dialog is open, add versus edit — is exercised rather than stubbed.
 */
@RunWith(AndroidJUnit4::class)
class AlarmScreenTest {
    private companion object {
        const val CUSTOM_RINGTONE = "content://media/internal/audio/media/42"

        /** What the injected ringtone seam reports, so the row is clickable by name. */
        const val RINGTONE_NAME = "Bright Morning"
    }

    @get:Rule(order = 0) val appContainer = AppContainerTestRule()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repository
        get() = appContainer.repository

    @Test
    fun theAddButton_opensTheTimePicker() {
        setContent()

        composeRule.onNodeWithContentDescription("Add Alarm").performClick()

        composeRule.onNodeWithText("Set Alarm Time").assertIsDisplayed()
    }

    @Test
    fun theTimePicker_addsAnAlarmOnSet() {
        setContent()
        composeRule.onNodeWithContentDescription("Add Alarm").performClick()

        composeRule.onNodeWithText("Set").performClick()

        assertEquals(1, repository.addAlarmCallCount)
        composeRule.onNodeWithText("Set Alarm Time").assertDoesNotExist()
    }

    @Test
    fun theTimePicker_addsNothingOnCancel() {
        setContent()
        composeRule.onNodeWithContentDescription("Add Alarm").performClick()

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, repository.addAlarmCallCount)
        composeRule.onNodeWithText("Set Alarm Time").assertDoesNotExist()
    }

    /** The add-versus-edit branch: editing an existing row must not create a second alarm. */
    @Test
    fun tappingTheTime_opensThePickerInEditMode() {
        setContent(alarm(id = 5, hour = 7, minute = 5))
        composeRule.onNodeWithText("07:05").performClick()

        composeRule.onNodeWithText("Set").performClick()

        assertEquals(1, repository.updateAlarmTimeCallCount)
        assertEquals(5, repository.lastUpdateAlarmId)
        assertEquals(0, repository.addAlarmCallCount, "Editing must not add a new alarm")
    }

    @Test
    fun theRow_expandsAndCollapses() {
        setContent(alarm(id = 5, hour = 7, minute = 5, repeatDays = EVERY_DAY))

        chevron().performClick()
        composeRule.onNodeWithContentDescription("Delete alarm").assertExists()

        chevron().performClick()
        composeRule.onNodeWithContentDescription("Delete alarm").assertDoesNotExist()
    }

    @Test
    fun theSwitch_togglesTheAlarm() {
        setContent(alarm(id = 5, isActive = true))

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertEquals(1, repository.toggleAlarmCallCount)
        assertEquals(5, repository.lastToggleAlarmId)
        assertEquals(false, repository.lastToggleIsActive)
    }

    @Test
    fun theDaySelector_setsTheRepeatDays() {
        setContent(alarm(id = 5, repeatDays = setOf(Calendar.MONDAY)))
        expandTheOnlyRow()

        composeRule.onNodeWithText("W").performClick()

        assertEquals(1, repository.setRepeatDaysCallCount)
        assertEquals(setOf(Calendar.MONDAY, Calendar.WEDNESDAY), repository.lastRepeatDays)
    }

    @Test
    fun deleting_asksForConfirmationFirst() {
        setContent(alarm(id = 5, hour = 7, minute = 5))
        expandTheOnlyRow()

        composeRule.onNodeWithText("Delete").performClick()

        composeRule.onNodeWithText("Delete the 07:05 alarm?").assertIsDisplayed()
        assertEquals(0, repository.deleteAlarmCallCount, "Nothing is deleted until confirmed")
    }

    @Test
    fun confirmingTheDialog_deletesTheAlarm() {
        setContent(alarm(id = 5, hour = 7, minute = 5))
        expandTheOnlyRow()
        composeRule.onNodeWithText("Delete").performClick()

        composeRule
            .onAllNodesWithText("Delete")
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()

        assertEquals(listOf(5), repository.deletedAlarmIds)
    }

    @Test
    fun cancellingTheDialog_keepsTheAlarm() {
        setContent(alarm(id = 5, hour = 7, minute = 5))
        expandTheOnlyRow()
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, repository.deleteAlarmCallCount)
        composeRule.onNodeWithText("Delete the 07:05 alarm?").assertDoesNotExist()
    }

    /** Inside the two-hour window, so the ViewModel computes `isUpcoming` rather than the test. */
    @Test
    fun dismissing_skipsTheNextOccurrence() {
        val (hour, minute) = clockTimeIn(30 * 60 * 1000L)
        setContent(alarm(id = 5, hour = hour, minute = minute, repeatDays = EVERY_DAY))

        composeRule.onNodeWithText("Dismiss").performClick()

        assertEquals(1, repository.skipAlarmsCallCount)
        assertEquals(listOf(5), repository.lastSkipIds)
    }

    @Test
    fun undoing_cancelsThePendingSkip() {
        val (hour, minute) = clockTimeIn(30 * 60 * 1000L)
        val next = nextTrigger(hour, minute, EVERY_DAY, System.currentTimeMillis())
        setContent(
            alarm(
                id = 5,
                hour = hour,
                minute = minute,
                repeatDays = EVERY_DAY,
                skippedOccurrenceDay = localDayOf(next),
            )
        )
        composeRule.onNodeWithText("Skipping next alarm").assertIsDisplayed()

        composeRule.onNodeWithText("Undo").performClick()

        assertEquals(1, repository.cancelSkipCallCount)
        assertEquals(5, repository.lastCancelSkipAlarmId)
    }

    @Test
    fun theSettingsIcon_isForwardedThroughTheStatefulScreen() {
        var settingsClicks = 0
        setContent(onSettingsClick = { settingsClicks++ })

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertEquals(1, settingsClicks)
    }

    @Test
    fun tappingTheRingtone_opensThePickerOnTheAlarmsCurrentChoice() {
        val picker = openTheRingtonePicker(alarm(id = 5, ringtoneUri = CUSTOM_RINGTONE))

        val intent = picker.launched.single()
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
        assertEquals(
            RingtoneManager.TYPE_ALARM,
            intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1),
        )
        assertEquals(
            CUSTOM_RINGTONE.toUri(),
            intent.pickerExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI),
        )
        assertEquals(
            defaultRingtone(),
            intent.pickerExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI),
        )
    }

    /** Silence is not on offer: an alarm nobody can hear is the outcome to rule out. */
    @Test
    fun thePickerOffersTheDefaultButNotSilence() {
        val picker = openTheRingtonePicker(alarm(id = 5))

        val intent = picker.launched.single()
        assertEquals(
            true,
            intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false),
        )
        assertEquals(
            false,
            intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true),
        )
    }

    @Test
    fun withNoRingtoneChosenYet_thePickerOpensOnTheSystemDefault() {
        val picker = openTheRingtonePicker(alarm(id = 5, ringtoneUri = null))

        val intent = picker.launched.single()
        assertEquals(
            defaultRingtone(),
            intent.pickerExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI),
        )
    }

    @Test
    fun pickingARingtone_storesItAgainstThatAlarm() {
        val picker = openTheRingtonePicker(alarm(id = 5))

        picker.deliver(Activity.RESULT_OK, pickedRingtone(CUSTOM_RINGTONE.toUri()))

        assertEquals(1, repository.setAlarmRingtoneCallCount)
        assertEquals(5, repository.lastRingtoneAlarmId)
        assertEquals(CUSTOM_RINGTONE, repository.lastRingtoneUri)
    }

    /** Storing the default as a URI would freeze today's default; null follows the system. */
    @Test
    fun pickingTheDefaultRingtone_storesNullRatherThanItsUri() {
        val picker = openTheRingtonePicker(alarm(id = 5, ringtoneUri = CUSTOM_RINGTONE))

        picker.deliver(Activity.RESULT_OK, pickedRingtone(defaultRingtone()))

        assertEquals(1, repository.setAlarmRingtoneCallCount)
        assertNull(repository.lastRingtoneUri)
    }

    @Test
    fun aResultCarryingNoRingtone_fallsBackToTheSystemDefault() {
        val picker = openTheRingtonePicker(alarm(id = 5, ringtoneUri = CUSTOM_RINGTONE))

        picker.deliver(Activity.RESULT_OK, data = null)

        assertEquals(1, repository.setAlarmRingtoneCallCount)
        assertNull(repository.lastRingtoneUri)
    }

    @Test
    fun backingOutOfThePicker_leavesTheRingtoneAlone() {
        val picker = openTheRingtonePicker(alarm(id = 5, ringtoneUri = CUSTOM_RINGTONE))

        picker.deliver(Activity.RESULT_CANCELED, pickedRingtone(defaultRingtone()))

        assertEquals(0, repository.setAlarmRingtoneCallCount)
    }

    @Test
    fun tappingTheRingtoneTwice_doesNotOpenTwoPickers() {
        val picker = openTheRingtonePicker(alarm(id = 5))

        composeRule.onNodeWithText(RINGTONE_NAME).performClick()

        assertEquals(1, picker.launched.size)
    }

    /** The guard is per-launch: the row has to keep working afterwards. */
    @Test
    fun onceThePickerHasClosed_theRowOpensItAgain() {
        val picker = openTheRingtonePicker(alarm(id = 5))
        picker.deliver(Activity.RESULT_CANCELED, data = null)

        composeRule.onNodeWithText(RINGTONE_NAME).performClick()

        assertEquals(2, picker.launched.size)
    }

    /**
     * The only tests that drop the naming seam for the real [RingtoneManager]. Nothing resolves
     * under Robolectric, so both pin that an unresolvable ringtone degrades to a label.
     */
    @Test
    fun aRingtoneThatCannotBeResolved_isLabelledUnknown() {
        setContentWithRealRingtoneLookup(alarm(id = 5, ringtoneUri = "content://media/gone/1"))

        composeRule.onNodeWithText("Unknown").assertIsDisplayed()
    }

    @Test
    fun anAlarmWithNoRingtoneChosen_looksUpTheSystemDefault() {
        setContentWithRealRingtoneLookup(alarm(id = 5, ringtoneUri = null))

        composeRule.onNodeWithText("Unknown").assertIsDisplayed()
    }

    @Test
    fun theVibrationSwitch_reachesTheRepository() {
        setContent(alarm(id = 5, vibrationEnabled = true))
        expandTheOnlyRow()

        composeRule.onNodeWithContentDescription("Vibration enabled").performClick()

        assertEquals(1, repository.setAlarmVibrationCallCount)
        assertEquals(5, repository.lastVibrationAlarmId)
        assertEquals(false, repository.lastVibrationEnabled)
    }

    /** The repository reverts the write, and this toast is the only sign the alarm did not take. */
    @Test
    fun whenExactAlarmsAreDenied_theScreenSaysSo() {
        repository.setShouldSucceed(false)
        setContent(alarm(id = 5, isActive = false))

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertToastSays("Cannot schedule exact alarms. Please grant permission in settings.")
    }

    /**
     * The toast recomputes the countdown from the wall clock, so pin the hour: an alarm an hour
     * ahead of 23:xx lands tomorrow and reads differently.
     */
    @Test
    fun turningOnAnAlarm_countsTheMinutesUntilItRings() {
        pinLocalHourTo(10)
        val (hour, minute) = clockTimeIn(60 * 1000L)
        setContent(alarm(id = 5, hour = hour, minute = minute, isActive = false))

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertToastSays("Alarm set for 1 minute from now.")
    }

    @Test
    fun theCountdownPluralisesWholeHours() {
        pinLocalHourTo(10)
        val (hour, minute) = clockTimeIn(2 * 60 * 60 * 1000L)
        setContent(alarm(id = 5, hour = hour, minute = minute, isActive = false))

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertToastSays("Alarm set for 2 hours from now.")
    }

    /** Just over a day out — the one shape reaching both singular "day" and singular "hour". */
    @Test
    fun theCountdownUsesSingularUnits() {
        pinLocalHourTo(10)
        val (hour, minute) = clockTimeIn(60 * 60 * 1000L)
        setContent(
            alarm(
                id = 5,
                hour = hour,
                minute = minute,
                isActive = false,
                repeatDays = setOf(dayOfWeekTomorrow()),
            )
        )

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertToastSays("Alarm set for 1 day, 1 hour from now.")
    }

    @Test
    fun theCountdownPluralisesWholeDays() {
        pinLocalHourTo(10)
        val (hour, minute) = clockTimeIn(60 * 60 * 1000L)
        setContent(
            alarm(
                id = 5,
                hour = hour,
                minute = minute,
                isActive = false,
                repeatDays = setOf(dayOfWeekIn(days = 2)),
            )
        )

        composeRule.onNodeWithContentDescription("Alarm enabled").performClick()

        assertToastSays("Alarm set for 2 days, 1 hour from now.")
    }

    @After
    fun restoreTimeZone() {
        restoreSystemTimeZone()
    }

    /** Seeds one alarm, expands it and taps the ringtone row, returning the picker double. */
    private fun openTheRingtonePicker(alarm: AlarmItem): FakeRingtonePicker {
        val picker = FakeRingtonePicker()
        setContent(alarm, registry = picker)
        expandTheOnlyRow()
        composeRule.onNodeWithText(RINGTONE_NAME).performClick()
        return picker
    }

    /** Uses [AlarmScreen]'s own ringtone naming rather than the seam. */
    private fun setContentWithRealRingtoneLookup(alarm: AlarmItem) {
        repository.setAlarms(listOf(alarm))
        val viewModel = AlarmViewModel(repository)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) { AlarmScreen(alarmViewModel = viewModel) }
        }
        expandTheOnlyRow()
    }

    private fun defaultRingtone(): Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    private fun pickedRingtone(uri: Uri): Intent =
        Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)

    @Suppress("DEPRECATION")
    private fun Intent.pickerExtra(name: String): Uri? = getParcelableExtra(name) as Uri?

    private fun assertToastSays(expected: String) {
        composeRule.waitForIdle()
        assertEquals(expected, ShadowToast.getTextOfLatestToast())
    }

    private fun dayOfWeekTomorrow(): Int = dayOfWeekIn(days = 1)

    private fun dayOfWeekIn(days: Int): Int =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, days) }[Calendar.DAY_OF_WEEK]

    /**
     * From the unmerged tree: the merged one resolves to the whole card, whose centre is the volume
     * slider, and the slider eats the click.
     */
    private fun chevron(): SemanticsNodeInteraction =
        composeRule.onNode(
            hasContentDescription("Expand") or hasContentDescription("Collapse"),
            useUnmergedTree = true,
        )

    private fun expandTheOnlyRow() {
        chevron().performClick()
    }

    private fun setContent(
        vararg alarms: AlarmItem,
        onSettingsClick: () -> Unit = {},
        registry: ActivityResultRegistry? = null,
    ) {
        repository.setAlarms(alarms.toList())
        val viewModel = AlarmViewModel(repository)
        val owner = registry?.let { given ->
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = given
            }
        }
        composeRule.setContent {
            // dynamicColor defaults to true and would read the host's system palette.
            LuxAlarmTheme(dynamicColor = false) {
                val screen =
                    @Composable {
                        AlarmScreen(
                            onSettingsClick = onSettingsClick,
                            alarmViewModel = viewModel,
                            ringtoneNameFor = { RINGTONE_NAME },
                        )
                    }
                if (owner == null) {
                    screen()
                } else {
                    CompositionLocalProvider(
                        LocalActivityResultRegistryOwner provides owner,
                        content = screen,
                    )
                }
            }
        }
    }

    /**
     * Stands in for the system ringtone picker. Results are delivered explicitly rather than
     * inline, which is what makes the "tapped twice before it opened" case reachable.
     */
    private class FakeRingtonePicker : ActivityResultRegistry() {
        val launched = mutableListOf<Intent>()
        private var pending: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launched += input as Intent
            pending = requestCode
        }

        fun deliver(resultCode: Int, data: Intent?) {
            val requestCode = checkNotNull(pending) { "The picker was never launched" }
            pending = null
            dispatchResult(requestCode, resultCode, data)
        }
    }
}
