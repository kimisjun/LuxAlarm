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

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The SharedPreferences-backed lux threshold that decides when an alarm may be turned off. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsManagerTest {
    private companion object {
        // Mirrors SettingsManager's private PREFS_NAME rather than widening its visibility.
        const val PREFS_NAME = "lux_alarm_settings"
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric reuses the sandbox across classes, so start from a known-empty store.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    @Test
    fun withNothingStored_returnsTheDefault() {
        val manager = SettingsManager(context)

        assertEquals(SettingsManager.DEFAULT_LUX_LEVEL, manager.getRequiredLuxLevel())
        assertEquals(SettingsManager.DEFAULT_LUX_LEVEL, manager.requiredLuxLevel.value)
    }

    @Test
    fun setRequiredLuxLevel_persistsAndReadsBack() {
        val manager = SettingsManager(context)

        manager.setRequiredLuxLevel(250f)

        assertEquals(250f, manager.getRequiredLuxLevel())
        assertEquals(250f, manager.requiredLuxLevel.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun setRequiredLuxLevel_emitsOnTheStateFlow() = runTest {
        val manager = SettingsManager(context)
        val seen = mutableListOf<Float>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.requiredLuxLevel.collect { seen.add(it) }
            }

        manager.setRequiredLuxLevel(120f)
        manager.setRequiredLuxLevel(300f)

        assertEquals(listOf(SettingsManager.DEFAULT_LUX_LEVEL, 120f, 300f), seen)
        job.cancel()
    }

    /** [SettingsScreen] keys its slider on the value, so a re-emission would reset a drag. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun settingTheSameValue_doesNotReEmit() = runTest {
        val manager = SettingsManager(context)
        val seen = mutableListOf<Float>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.requiredLuxLevel.collect { seen.add(it) }
            }

        manager.setRequiredLuxLevel(SettingsManager.DEFAULT_LUX_LEVEL)

        assertEquals(listOf(SettingsManager.DEFAULT_LUX_LEVEL), seen)
        job.cancel()
    }

    @Test
    fun aSecondInstance_readsThePersistedValue() {
        SettingsManager(context).setRequiredLuxLevel(300f)

        val fresh = SettingsManager(context)

        assertEquals(300f, fresh.getRequiredLuxLevel())
        assertEquals(300f, fresh.requiredLuxLevel.value, "The StateFlow is seeded from storage")
    }

    /**
     * The StateFlow is per-instance and ignores SharedPreferences changes, so a second instance
     * goes stale. Harmless while [AppContainer] builds exactly one.
     */
    @Test
    fun aSecondInstance_doesNotSeeLaterWritesFromTheFirst() {
        val first = SettingsManager(context)
        val second = SettingsManager(context)

        first.setRequiredLuxLevel(400f)

        assertEquals(400f, second.getRequiredLuxLevel(), "Storage is shared")
        assertEquals(
            SettingsManager.DEFAULT_LUX_LEVEL,
            second.requiredLuxLevel.value,
            "But the second instance's StateFlow is stale",
        )
    }

    /**
     * Nothing clamps to MIN/MAX_LUX_LEVEL. Unreachable today — only the slider's `valueRange`
     * writes — but a threshold above any achievable reading would leave an alarm un-dismissable.
     */
    @Test
    fun setRequiredLuxLevel_isNotClampedToTheAdvertisedRange() {
        val manager = SettingsManager(context)

        manager.setRequiredLuxLevel(0f)
        assertEquals(0f, manager.getRequiredLuxLevel(), "Below MIN_LUX_LEVEL is stored as-is")

        manager.setRequiredLuxLevel(5_000f)
        assertEquals(5_000f, manager.getRequiredLuxLevel(), "Above MAX_LUX_LEVEL is stored as-is")
    }
}
