/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class V6DatabaseContractTest {
    @Test
    fun task2ModifiedUpstreamFilesRetainGentleWakeModificationNotice() {
        val root = repositoryRoot()
        listOf(
                "app/src/main/java/com/dsalmun/luxalarm/data/AlarmDatabase.kt",
                "app/src/androidTest/java/com/dsalmun/luxalarm/MigrationTest.kt",
            )
            .forEach { relativePath ->
                val header = File(root, relativePath).readText().substringBefore("package ")
                assertTrue(
                    header.contains("Daniel Salmun"),
                    "$relativePath must retain upstream notice",
                )
                assertTrue(
                    header.contains("Modified for GentleWake in 2026 by 김은준"),
                    "$relativePath must retain the complete GentleWake modification notice",
                )
            }
    }

    @Test
    fun singletonCreationRechecksCurrentValueAfterWaitingForLock() {
        val lock = Any()
        val current = AtomicReference<Any?>(null)
        val reads = AtomicInteger(0)
        val firstRead = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        val existing = Any()

        synchronized(lock) {
            thread(name = "database-singleton-contender") {
                result.set(
                    getOrCreateSingleton(
                        lock = lock,
                        current = {
                            if (reads.incrementAndGet() == 1) firstRead.countDown()
                            current.get()
                        },
                        create = {
                            error("must not create after another thread installs instance")
                        },
                        store = current::set,
                    )
                )
                completed.countDown()
            }
            assertTrue(
                firstRead.await(5, TimeUnit.SECONDS),
                "contender did not perform initial read",
            )
            current.set(existing)
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS), "contender did not complete")
        assertSame(existing, result.get())
        assertEquals(2, reads.get(), "singleton must be re-read inside the synchronized block")
    }

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))) {
                it.parentFile
            }
            .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun instrumentationMigrationChainTargetsCurrentV6WithoutPretendingRoomIsV5() {
        val source =
            File(
                    repositoryRoot(),
                    "app/src/androidTest/java/com/dsalmun/luxalarm/MigrationTest.kt",
                )
                .readText()

        assertTrue(source.contains("MIGRATION_5_6"))
        assertTrue(source.contains("openCurrentV6Database"))
        assertTrue(!source.contains("openCurrentV5Database"))
    }

    @Test
    fun exportedSchemaRegistersEveryV6EntityAtVersionSix() {
        val schema =
            File(
                    repositoryRoot(),
                    "app/schemas/com.dsalmun.luxalarm.data.AlarmDatabase/6.json",
                )
                .readText()
        val tables =
            Regex("\\\"tableName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(schema)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(Regex("\\\"version\\\"\\s*:\\s*6").containsMatchIn(schema))
        assertEquals(
            setOf(
                "alarms",
                "wake_routine",
                "imported_track",
                "wake_run_snapshot",
                "wake_run_status",
                "wake_event_dispatch",
                "wake_recovery_anchor",
                "schedule_outbox",
                "track_lease",
                "schedule_occurrence_claim",
                "legacy_migration_manifest",
                "legacy_coordinator_state",
                "legacy_coordinator_member",
                "migration_state",
            ),
            tables,
        )
        assertTrue(
            Regex("\\\"identityHash\\\"\\s*:\\s*\\\"[0-9a-f]{32}\\\"").containsMatchIn(schema)
        )
    }
}
