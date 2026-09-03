/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class LegacyBootstrapAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "legacy-bootstrap-android-${UUID.randomUUID()}.db"
    private lateinit var database: AlarmDatabase

    @Before
    fun openRoomBeforeBootstrap() {
        database = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
        database.openHelper.writableDatabase
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun successfulDiscoveryPersistsExactEvidenceWithoutChangingLegacyOrRuntimeState() {
        insertLegacyAlarm()
        val alarmsBefore = alarmEvidence()
        val result = discover(RoomLegacyDiscoveryStore(database))
        val state = assertNotNull(database.legacyBootstrapDao().migrationState())

        assertEquals("DISCOVERED", state.bootstrapPhase)
        assertEquals(result.sourceFingerprint, state.sourceFingerprint)
        assertEquals(result.attemptToken, state.attemptToken)
        assertEquals(
            legacyDiscoveryAttemptToken(state.installEpoch, result.sourceFingerprint),
            result.attemptToken,
        )
        assertEquals(result.rows, database.legacyBootstrapDao().manifestRows())
        assertTrue(legacyDiscoveryRowsMatchFingerprint(result.sourceFingerprint, result.rows))
        assertEquals(alarmsBefore, alarmEvidence())
        assertRuntimeTablesEmpty()
    }

    @Test
    fun faultAfterManifestWriteRollsBackAllEvidenceAndLeavesLegacyAndRuntimeStateUntouched() {
        insertLegacyAlarm()
        discover(RoomLegacyDiscoveryStore(database))
        database.openHelper.writableDatabase.execSQL("UPDATE alarms SET minute=46 WHERE id=1")
        val alarmsBefore = alarmEvidence()
        val stateBefore = migrationStateEvidence()
        val manifestBefore = database.legacyBootstrapDao().manifestRows()

        assertFailsWith<Fault> {
            discover(RoomLegacyDiscoveryStore(database) { throw Fault() })
        }

        assertEquals(stateBefore, migrationStateEvidence())
        assertEquals(manifestBefore, database.legacyBootstrapDao().manifestRows())
        assertEquals(alarmsBefore, alarmEvidence())
        assertRuntimeTablesEmpty()
    }

    private fun discover(store: RoomLegacyDiscoveryStore): LegacyDiscoveryResult =
        LegacyBootstrapMigrator(
                legacySource =
                    RoomOpenedLegacyAlarmSource {
                        database.legacyBootstrapDao().activeLegacyAlarmsForDiscovery()
                    },
                settingsSource = {
                    LegacyWakeSettingsSnapshot(50f, 20, .05f, .35f, "CONFIRM", null)
                },
                store = store,
                nowMillis = { 0L },
                zoneId = ZoneId.of("UTC"),
            )
            .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))

    private fun insertLegacyAlarm() {
        runBlocking {
            database.alarmDao().insert(AlarmItem(hour = 6, minute = 45, isActive = true))
        }
    }

    private fun alarmEvidence(): List<List<String?>> =
        database.openHelper.writableDatabase.query("SELECT * FROM alarms ORDER BY id").use { cursor
            ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        }
                    )
                }
            }
        }

    private fun migrationStateEvidence(): List<String?> =
        database.openHelper.writableDatabase.query("SELECT * FROM migration_state WHERE id=1").use {
            it.moveToFirst()
            List(it.columnCount) { index -> if (it.isNull(index)) null else it.getString(index) }
        }

    private fun assertRuntimeTablesEmpty() {
        listOf(
                "wake_routine",
                "imported_track",
                "wake_run_snapshot",
                "wake_run_status",
                "wake_event_dispatch",
                "wake_recovery_anchor",
                "schedule_outbox",
                "track_lease",
                "schedule_occurrence_claim",
                "legacy_coordinator_member",
            )
            .forEach { table ->
                val count =
                    database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
                        it.moveToFirst()
                        it.getLong(0)
                    }
                assertEquals(0L, count, table)
            }
    }

    private class Fault : RuntimeException()
}
