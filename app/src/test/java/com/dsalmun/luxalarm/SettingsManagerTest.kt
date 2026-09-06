/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
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

    /** The StateFlow is per-instance; harmless while [AppContainer] builds exactly one. */
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

    /** Unreachable today, but an unclamped threshold would leave an alarm un-dismissable. */
    @Test
    fun setRequiredLuxLevel_isNotClampedToTheAdvertisedRange() {
        val manager = SettingsManager(context)

        manager.setRequiredLuxLevel(0f)
        assertEquals(0f, manager.getRequiredLuxLevel(), "Below MIN_LUX_LEVEL is stored as-is")

        manager.setRequiredLuxLevel(5_000f)
        assertEquals(5_000f, manager.getRequiredLuxLevel(), "Above MAX_LUX_LEVEL is stored as-is")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun wakeProfileDefaultsAndAWholeProfileUpdateAreAtomic() = runTest {
        val manager = SettingsManager(context)
        val defaults =
            WakeProfile(
                rampMinutes = 20,
                startVolume = 0.05f,
                maxVolume = 0.35f,
                dismissal = WakeDismissal.CONFIRM,
            )
        val updated = defaults.copy(rampMinutes = 30, maxVolume = 0.4f)
        val seen = mutableListOf<WakeProfile>()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.wakeProfile.collect { seen.add(it) }
            }

        manager.updateWakeProfile(updated)

        assertEquals(listOf(defaults, updated), seen, "One update must emit one complete profile")
        assertEquals(updated, SettingsManager(context).getWakeProfile())
        job.cancel()
    }
}
