/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegacySourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferencesName = "legacy-source-test"
    private val legacyFileName = "legacy-source-bytes"

    @After
    fun cleanUp() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, legacyFileName).delete()
    }

    @Test
    fun sharedPreferencesAndLegacyFileRemainByteForByteUnchangedDuringDiscovery() {
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        prefs
            .edit()
            .clear()
            .putString("wake_ramp_minutes", "wrong-type")
            .putFloat("wake_start_volume", .2f)
            .putString("wake_imported_audio_path", "legacy/song")
            .commit()
        val beforePrefs = prefs.all.toMap()
        val file =
            File(context.cacheDir, legacyFileName).apply {
                writeBytes(byteArrayOf(0, 1, 2, -1))
            }
        val beforeFile = file.readBytes()
        val store = RecordingStore()
        val source = RoomOpenedLegacyAlarmSource {
            listOf(AlarmItem(id = 5, hour = 7, minute = 0, isActive = true))
        }

        val result =
            LegacyBootstrapMigrator(
                    source,
                    SharedPreferencesLegacyWakeSettingsSource(prefs),
                    store,
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(5L to LegacyDisposition.SELECT_AS_WAKE))

        assertEquals(beforePrefs, prefs.all)
        assertContentEquals(beforeFile, file.readBytes())
        assertTrue("rampMinutes" in result.wakeProfileProposal.fallbackFields)
    }

    @Test
    fun settingsAcceptOnlyTheExactSharedPreferencesApiTypes() {
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putFloat("required_lux_level", 75f)
            .putInt("wake_ramp_minutes", 25)
            .putFloat("wake_start_volume", .2f)
            .putFloat("wake_max_volume", .8f)
            .putString("wake_dismissal", "CONFIRM")
            .putString("wake_imported_audio_path", "content://media/audio/1")
            .commit()

        assertEquals(
            LegacyWakeSettingsSnapshot(75f, 25, .2f, .8f, "CONFIRM", "content://media/audio/1"),
            SharedPreferencesLegacyWakeSettingsSource(prefs).readSettings(),
        )

        prefs
            .edit()
            .clear()
            .putLong("required_lux_level", 4_294_967_296L)
            .putLong("wake_ramp_minutes", 4_294_967_296L)
            .putInt("wake_start_volume", 1)
            .putString("wake_max_volume", "0.8")
            .putLong("wake_dismissal", 1L)
            .putFloat("wake_imported_audio_path", 1f)
            .commit()

        assertEquals(
            LegacyWakeSettingsSnapshot(null, null, null, null, null, null),
            SharedPreferencesLegacyWakeSettingsSource(prefs).readSettings(),
        )
        prefs.edit().putLong("wake_start_volume", java.lang.Double.doubleToRawLongBits(.2)).commit()
        assertEquals(
            null,
            SharedPreferencesLegacyWakeSettingsSource(prefs).readSettings().startVolume,
        )
    }

    @Test
    fun settingsRejectDoubleLongWrapAndIntegralFloatWithoutNumberCoercion() {
        val values: Map<String, Any> =
            mapOf(
                "required_lux_level" to 75.0,
                "wake_ramp_minutes" to 20f,
                "wake_start_volume" to 4_294_967_296L,
                "wake_max_volume" to 1,
                "wake_dismissal" to 1.0,
                "wake_imported_audio_path" to 1L,
            )
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                if (method.name == "getAll") values
                else throw UnsupportedOperationException(method.name)
            } as SharedPreferences

        assertEquals(
            LegacyWakeSettingsSnapshot(null, null, null, null, null, null),
            SharedPreferencesLegacyWakeSettingsSource(preferences).readSettings(),
        )
    }

    private class RecordingStore : LegacyDiscoveryStore {
        override fun requireReady() = LegacyDiscoveryReadiness("install-source")

        override fun persistDiscovery(
            discovery: LegacyDiscoveryPersistence,
            revalidate: () -> LegacyDiscoveryPersistence,
        ) {
            assertEquals(discovery, revalidate())
        }
    }
}
