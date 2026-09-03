/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Modifier
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegacyAudioBootstrapDescriptorFactoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AlarmDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "legacy-audio-factory-${UUID.randomUUID()}.db"
        db = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO alarms(id,hour,minute,isActive,repeatDays,ringtoneUri,volume,vibrationEnabled,skippedOccurrenceDay) " +
                "VALUES(41,6,45,1,'',NULL,1.0,1,NULL)"
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun exactCurrentCanonicalDiscoveryProducesDescriptorForItsImportedAudioPath() {
        val settings = LegacyWakeSettingsSource { validSettings("legacy/audio.mp3") }
        val alarms = roomAlarms()
        val proposal = mapOf(41L to LegacyDisposition.SELECT_AS_WAKE)
        LegacyBootstrapMigrator(alarms, settings, RoomLegacyDiscoveryStore(db), { 0L }, UTC)
            .discover(proposal)

        val descriptor =
            LegacyAudioBootstrapDescriptorFactory(
                    db,
                    alarms,
                    settings,
                    proposal,
                    { 0L },
                    UTC,
                )
                .create()

        assertEquals("legacy/audio.mp3", descriptor.sourcePath)
        assertEquals(
            db.legacyBootstrapDao().migrationState()!!.sourceFingerprint,
            descriptor.sourceFingerprint,
        )
    }

    @Test
    fun revalidationRecomputesCanonicalOccurrenceAtTheCurrentTime() {
        var now = 0L
        val settings = LegacyWakeSettingsSource { validSettings("legacy/audio.mp3") }
        val alarms = roomAlarms()
        val proposal = mapOf(41L to LegacyDisposition.SELECT_AS_WAKE)
        LegacyBootstrapMigrator(alarms, settings, RoomLegacyDiscoveryStore(db), { now }, UTC)
            .discover(proposal)
        val descriptor =
            LegacyAudioBootstrapDescriptorFactory(db, alarms, settings, proposal, { now }, UTC)
                .create()

        // Crossing the scheduled occurrence changes canonical discovery; bootstrap must retry
        // fresh.
        now = 7L * 60L * 60L * 1000L

        assertFailsWith<IllegalStateException> { descriptor.requireCurrentSource() }
    }

    @Test
    fun arbitraryAlternateAppPrivatePathIsRejectedDespiteValidPersistedEvidence() {
        val alarms = roomAlarms()
        val proposal = mapOf(41L to LegacyDisposition.SELECT_AS_WAKE)
        LegacyBootstrapMigrator(
                alarms,
                { validSettings("legacy/audio.mp3") },
                RoomLegacyDiscoveryStore(db),
                { 0L },
                UTC,
            )
            .discover(proposal)
        val stateBefore = db.legacyBootstrapDao().migrationState()
        val manifestBefore = db.legacyBootstrapDao().manifestRows()

        assertFailsWith<IllegalStateException> {
            LegacyAudioBootstrapDescriptorFactory(
                    db,
                    alarms,
                    { validSettings("private/alternate.mp3") },
                    proposal,
                    { 0L },
                    UTC,
                )
                .create()
        }

        assertEquals(stateBefore, db.legacyBootstrapDao().migrationState())
        assertEquals(manifestBefore, db.legacyBootstrapDao().manifestRows())
    }

    @Test
    fun productionDescriptorHasNoFixtureSeamAndItsConstructorRemainsPrivate() {
        val descriptorClass = LegacyAudioBootstrapDescriptor::class.java

        assertFalse(descriptorClass.declaredMethods.any { it.name.contains("fixtureForTests") })
        val constructor =
            descriptorClass.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                BootstrapPhase::class.java,
                kotlin.Function0::class.java,
            )
        assertTrue(Modifier.isPrivate(constructor.modifiers))
    }

    private fun roomAlarms() = RoomOpenedLegacyAlarmSource {
        db.legacyBootstrapDao().activeLegacyAlarmsForDiscovery()
    }

    private fun validSettings(path: String?) =
        LegacyWakeSettingsSnapshot(50f, 20, .05f, .35f, "CONFIRM", path)

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
