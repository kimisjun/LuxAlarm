/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.WakeDismissal
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LegacyBootstrapMigratorTest {
    @Test
    fun activeAlarmLimitIsAcceptedAndOneMoreFailsBeforePersistence() {
        fun alarms(count: Int) = (1..count).map { alarm(7, 0, it) }
        fun proposal(count: Int) =
            (1..count).associate { id ->
                id.toLong() to
                    if (id == 1) LegacyDisposition.SELECT_AS_WAKE
                    else LegacyDisposition.KEEP_UNTIL_TERMINAL
            }

        assertEquals(
            MAX_ACTIVE_LEGACY_ALARMS,
            migrator(
                    FakeLegacySource(alarms(MAX_ACTIVE_LEGACY_ALARMS)),
                    FakeDiscoveryStore(),
                    "2026-09-03T06:00:00Z",
                )
                .discover(proposal(MAX_ACTIVE_LEGACY_ALARMS))
                .rows
                .size,
        )
        val store = FakeDiscoveryStore()
        assertFailsWith<IllegalArgumentException> {
            migrator(
                    FakeLegacySource(alarms(MAX_ACTIVE_LEGACY_ALARMS + 1)),
                    store,
                    "2026-09-03T06:00:00Z",
                )
                .discover(proposal(MAX_ACTIVE_LEGACY_ALARMS + 1))
        }
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun repeatDaysAcceptTheSevenDayLimitAndRejectAnEighthOrOutOfRangeValue() {
        val everyDay = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
        assertEquals(
            1,
            migrator(
                    FakeLegacySource(listOf(alarm(7, 0, 1, days = everyDay))),
                    FakeDiscoveryStore(),
                    "2026-09-03T06:00:00Z",
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
                .rows
                .size,
        )
        listOf(everyDay + 8, setOf(0)).forEach { invalidDays ->
            assertFailsWith<IllegalArgumentException> {
                migrator(
                        FakeLegacySource(listOf(alarm(7, 0, 1, days = invalidDays))),
                        FakeDiscoveryStore(),
                        "2026-09-03T06:00:00Z",
                    )
                    .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
            }
        }
    }

    @Test
    fun localStringLimitsUseUtf8BytesAndRejectOneCodePointOver() {
        val exact = "é".repeat(MAX_LEGACY_LOCAL_STRING_UTF8_BYTES / 2)
        val over = exact + "é"
        val accepted =
            LegacyBootstrapMigrator(
                    FakeLegacySource(listOf(alarm(7, 0, 1).copy(ringtoneUri = exact))),
                    { validSettings().copy(importedAudioPath = exact) },
                    FakeDiscoveryStore(),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
        assertEquals(exact, accepted.wakeProfileProposal.profile.importedAudioPath)

        assertFailsWith<IllegalArgumentException> {
            LegacyBootstrapMigrator(
                    FakeLegacySource(listOf(alarm(7, 0, 1).copy(ringtoneUri = over))),
                    { validSettings() },
                    FakeDiscoveryStore(),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
        }
        val pathFallback =
            LegacyBootstrapMigrator(
                    FakeLegacySource(listOf(alarm(7, 0, 1))),
                    { validSettings().copy(importedAudioPath = over) },
                    FakeDiscoveryStore(),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
        assertEquals(null, pathFallback.wakeProfileProposal.profile.importedAudioPath)
    }

    @Test
    fun installEpochLimitIsAcceptedAndOneMoreFailsBeforeSourceReads() {
        var sourceReads = 0
        fun run(epoch: String) =
            LegacyBootstrapMigrator(
                    LegacyAlarmSource {
                        sourceReads++
                        emptyList()
                    },
                    { validSettings() },
                    object : LegacyDiscoveryStore {
                        override fun requireReady() = LegacyDiscoveryReadiness(epoch)

                        override fun persistDiscovery(
                            discovery: LegacyDiscoveryPersistence,
                            revalidate: () -> LegacyDiscoveryPersistence,
                        ) = Unit
                    },
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(emptyMap())

        run("e".repeat(MAX_INSTALL_EPOCH_UTF8_BYTES))
        assertFailsWith<IllegalArgumentException> {
            run("e".repeat(MAX_INSTALL_EPOCH_UTF8_BYTES + 1))
        }
        assertEquals(1, sourceReads)
    }

    @Test
    fun discoveryRequiresRoomReadinessBeforeSettingsNowAlarmsOrFiles() {
        val operations = mutableListOf<String>()
        val source = LegacyAlarmSource {
            operations += "alarms"
            listOf(alarm(7, 30, 1))
        }
        val settings = LegacyWakeSettingsSource {
            operations += "settings"
            validSettings()
        }
        val store =
            object : LegacyDiscoveryStore {
                override fun requireReady(): LegacyDiscoveryReadiness {
                    operations += "room-ready"
                    return LegacyDiscoveryReadiness("install-A")
                }

                override fun persistDiscovery(
                    discovery: LegacyDiscoveryPersistence,
                    revalidate: () -> LegacyDiscoveryPersistence,
                ) {
                    operations += "persist"
                    assertEquals(discovery, revalidate())
                }
            }

        LegacyBootstrapMigrator(
                source,
                settings,
                store,
                {
                    operations += "now"
                    Instant.parse("2026-09-03T06:00:00Z").toEpochMilli()
                },
                ZoneId.of("UTC"),
            )
            .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))

        assertEquals(
            listOf("room-ready", "now", "settings", "alarms", "persist", "settings", "alarms"),
            operations,
        )
    }

    @Test
    fun discoverySnapshotsNowOnceForEveryCandidate() {
        var reads = 0
        val source = FakeLegacySource(listOf(alarm(7, 30, 1), alarm(7, 30, 2)))
        val store = FakeDiscoveryStore()
        val base = Instant.parse("2026-09-03T07:29:59.999Z").toEpochMilli()
        val migrator =
            LegacyBootstrapMigrator(
                source,
                { validSettings() },
                store,
                { base + reads++ },
                ZoneId.of("UTC"),
            )

        val result =
            migrator.discover(
                mapOf(
                    1L to LegacyDisposition.SELECT_AS_WAKE,
                    2L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                )
            )

        assertEquals(1, reads)
        assertEquals(1, result.rows.map { it.goalEpochMs }.distinct().size)
    }

    @Test
    fun zeroActiveAlarmsAllowsEmptyProposalAndInactiveRowsAreExcluded() {
        val source = FakeLegacySource(listOf(alarm(7, 0, 1, active = false)))
        val result =
            migrator(source, FakeDiscoveryStore(), "2026-09-03T06:00:00Z").discover(emptyMap())

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun simultaneousAlarmsRemainSeparateRowsWithSharedGlobalIdentity() {
        val source = FakeLegacySource(listOf(alarm(8, 0, 9), alarm(8, 0, 3), alarm(9, 0, 7)))
        val result =
            migrator(source, FakeDiscoveryStore(), "2026-09-03T06:00:00Z")
                .discover(
                    mapOf(
                        9L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                        3L to LegacyDisposition.SELECT_AS_WAKE,
                        7L to LegacyDisposition.DISABLE_AFTER_CONFIRM,
                    )
                )

        assertEquals(3, result.rows.size)
        val simultaneous =
            result.rows.filter {
                it.goalEpochMs == Instant.parse("2026-09-03T08:00:00Z").toEpochMilli()
            }
        assertEquals(listOf(3L, 9L), simultaneous.map { it.legacyAlarmId })
        assertEquals(1, simultaneous.map { it.pendingIntentIdentity }.distinct().size)
    }

    @Test
    fun manifestRecordsTheExistingGlobalPendingIntentIdentityNotMutableExtras() {
        val source = FakeLegacySource(listOf(alarm(8, 0, 9), alarm(8, 0, 3), alarm(9, 0, 7)))

        val identities =
            migrator(source, FakeDiscoveryStore(), "2026-09-03T06:00:00Z")
                .discover(
                    mapOf(
                        9L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                        3L to LegacyDisposition.SELECT_AS_WAKE,
                        7L to LegacyDisposition.DISABLE_AFTER_CONFIRM,
                    )
                )
                .rows
                .map { it.pendingIntentIdentity }
                .distinct()

        assertEquals(
            listOf(
                "broadcast;requestCode=0;component=com.dsalmun.luxalarm/com.dsalmun.luxalarm.AlarmReceiver;action=null;data=null;categories=[];package=null"
            ),
            identities,
        )
    }

    @Test
    fun nextOccurrenceIsStrictlyFutureAcrossMidnightWeekdaysAndZone() {
        val monday = setOf(Calendar.MONDAY)
        val source =
            FakeLegacySource(listOf(alarm(0, 0, 1), alarm(7, 0, 2, days = monday), alarm(9, 0, 3)))
        val store = FakeDiscoveryStore()
        val migrator =
            LegacyBootstrapMigrator(
                source,
                { validSettings() },
                store,
                { Instant.parse("2026-09-07T07:00:00Z").toEpochMilli() },
                ZoneId.of("Asia/Seoul"),
            )

        val rows =
            migrator
                .discover(
                    mapOf(
                        1L to LegacyDisposition.SELECT_AS_WAKE,
                        2L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                        3L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                    )
                )
                .rows
                .associateBy { it.legacyAlarmId }

        assertEquals(
            Instant.parse("2026-09-07T15:00:00Z").toEpochMilli(),
            rows.getValue(1).goalEpochMs,
        )
        assertEquals(
            Instant.parse("2026-09-13T22:00:00Z").toEpochMilli(),
            rows.getValue(2).goalEpochMs,
        )
        assertEquals(
            Instant.parse("2026-09-08T00:00:00Z").toEpochMilli(),
            rows.getValue(3).goalEpochMs,
        )
        assertTrue(
            rows.values.all {
                it.goalEpochMs > Instant.parse("2026-09-07T07:00:00Z").toEpochMilli()
            }
        )
    }

    @Test
    fun skippedOccurrenceDayAdvancesMigratorToNextValidRepeat() {
        assertMigratorMatchesLegacyNextTrigger(
            zone = "UTC",
            now = "2026-09-03T06:00:00Z",
            alarm =
                alarm(7, 0, 1, days = setOf(Calendar.THURSDAY, Calendar.FRIDAY))
                    .copy(skippedOccurrenceDay = 20_699L),
            expected = "2026-09-04T07:00:00Z",
        )
    }

    @Test
    fun dstGapUsesLegacyCalendarLenientWallTime() {
        assertMigratorMatchesLegacyNextTrigger(
            zone = "America/New_York",
            now = "2026-03-08T05:00:00Z",
            alarm = alarm(2, 30, 1, days = setOf(Calendar.SUNDAY)),
            expected = "2026-03-08T07:30:00Z",
        )
    }

    @Test
    fun dstOverlapUsesLegacyCalendarLaterOffset() {
        assertMigratorMatchesLegacyNextTrigger(
            zone = "America/New_York",
            now = "2026-11-01T04:00:00Z",
            alarm = alarm(1, 30, 1, days = setOf(Calendar.SUNDAY)),
            expected = "2026-11-01T06:30:00Z",
        )
    }

    @Test
    fun invalidRepeatDayPreservesSchedulerFallbackButIsRejectedAtBootstrapBoundary() {
        val now = Instant.parse("2026-09-03T06:00:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-09-10T07:00:00Z").toEpochMilli(),
            nextTrigger(7, 0, setOf(99), now, ZoneId.of("UTC")),
        )
        assertFailsWith<IllegalArgumentException> {
            LegacyBootstrapMigrator(
                    FakeLegacySource(listOf(alarm(7, 0, 1, days = setOf(99)))),
                    { validSettings() },
                    FakeDiscoveryStore(),
                    { now },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
        }
    }

    @Test
    fun fingerprintTokenRowsAndStagingKeyAreIndependentOfAlarmInputOrder() {
        val a = alarm(8, 0, 1)
        val b = alarm(7, 0, 2)
        val settings = validSettings().copy(importedAudioPath = "/private/legacy/song.mp3")
        fun run(input: List<LegacyAlarmSnapshot>): LegacyDiscoveryResult {
            val store = FakeDiscoveryStore()
            return LegacyBootstrapMigrator(
                    FakeLegacySource(input),
                    { settings },
                    store,
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(
                    mapOf(
                        1L to LegacyDisposition.SELECT_AS_WAKE,
                        2L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                    )
                )
        }

        val forward = run(listOf(a, b))
        val reverse = run(listOf(b, a))

        assertEquals(forward.rows, reverse.rows)
        assertEquals(forward.sourceFingerprint, reverse.sourceFingerprint)
        assertEquals(forward.attemptToken, reverse.attemptToken)
        assertEquals(forward.targetStorageKey, reverse.targetStorageKey)
        val targetStorageKey = requireNotNull(forward.targetStorageKey)
        assertFalse(targetStorageKey.startsWith("/"))
        assertFalse(targetStorageKey.contains("private"))
    }

    @Test
    fun fingerprintAndTokenChangeWhenProposedDispositionsChange() {
        val source = FakeLegacySource(listOf(alarm(7, 0, 1), alarm(8, 0, 2)))
        fun discover(proposal: Map<Long, LegacyDisposition>) =
            migrator(source, FakeDiscoveryStore(), "2026-09-03T06:00:00Z").discover(proposal)

        val first =
            discover(
                mapOf(
                    1L to LegacyDisposition.SELECT_AS_WAKE,
                    2L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                )
            )
        val second =
            discover(
                mapOf(
                    1L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                    2L to LegacyDisposition.SELECT_AS_WAKE,
                )
            )

        assertFalse(first.sourceFingerprint == second.sourceFingerprint)
        assertFalse(first.attemptToken == second.attemptToken)
    }

    @Test
    fun fingerprintUsesCanonicalFallbackSettingsRatherThanInvalidRawRepresentations() {
        val source = FakeLegacySource(listOf(alarm(7, 0, 1)))
        fun discover(settings: LegacyWakeSettingsSnapshot) =
            LegacyBootstrapMigrator(
                    source,
                    { settings },
                    FakeDiscoveryStore(),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))

        val first = discover(LegacyWakeSettingsSnapshot(Float.NaN, 0, Float.NaN, 2f, "bad-a", null))
        val second =
            discover(
                LegacyWakeSettingsSnapshot(
                    Float.NEGATIVE_INFINITY,
                    99,
                    -1f,
                    Float.POSITIVE_INFINITY,
                    "bad-b",
                    null,
                )
            )

        assertEquals(first.wakeProfileProposal, second.wakeProfileProposal)
        assertEquals(first.sourceFingerprint, second.sourceFingerprint)
        assertEquals(first.attemptToken, second.attemptToken)
    }

    @Test
    fun fingerprintCanonicalizesEquivalentSignedZeroSettings() {
        val source = FakeLegacySource(listOf(alarm(7, 0, 1)))
        fun discover(startVolume: Float) =
            LegacyBootstrapMigrator(
                    source,
                    { validSettings().copy(startVolume = startVolume) },
                    FakeDiscoveryStore(),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))

        assertEquals(discover(0f).sourceFingerprint, discover(-0f).sourceFingerprint)
    }

    @Test
    fun nullableStringsCannotCollideWithLiteralNullOrFieldSeparators() {
        fun discover(
            settings: LegacyWakeSettingsSnapshot = validSettings(),
            ringtoneUri: String? = null,
        ) =
            LegacyBootstrapMigrator(
                    FakeLegacySource(listOf(alarm(7, 0, 1).copy(ringtoneUri = ringtoneUri))),
                    { settings },
                    FakeDiscoveryStore(),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))

        val noAudio = discover()
        val literalNullAudio = discover(validSettings().copy(importedAudioPath = "null"))
        assertFalse(noAudio.sourceFingerprint == literalNullAudio.sourceFingerprint)
        assertFalse(noAudio.attemptToken == literalNullAudio.attemptToken)
        assertEquals(null, noAudio.targetStorageKey)
        assertEquals(
            "bootstrap/${literalNullAudio.attemptToken}/legacy-audio",
            literalNullAudio.targetStorageKey,
        )

        assertFalse(
            discover(ringtoneUri = null).sourceFingerprint ==
                discover(ringtoneUri = "null").sourceFingerprint
        )
        assertFalse(
            discover(validSettings().copy(dismissal = null)).sourceFingerprint ==
                discover(validSettings().copy(dismissal = "null")).sourceFingerprint
        )

        val separatorInAudio = discover(validSettings().copy(importedAudioPath = "a|b\nc"))
        val separatorInRingtone = discover(ringtoneUri = "a|b\nc")
        assertFalse(separatorInAudio.sourceFingerprint == separatorInRingtone.sourceFingerprint)
    }

    @Test
    fun incompleteUnknownAndNonSingularSelectionProposalsAreRejectedBeforeWrite() {
        val source = FakeLegacySource(listOf(alarm(7, 0, 1), alarm(8, 0, 2)))
        val invalid =
            listOf(
                mapOf(1L to LegacyDisposition.SELECT_AS_WAKE),
                mapOf(
                    1L to LegacyDisposition.SELECT_AS_WAKE,
                    2L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                    3L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                ),
                mapOf(
                    1L to LegacyDisposition.KEEP_UNTIL_TERMINAL,
                    2L to LegacyDisposition.DISABLE_AFTER_CONFIRM,
                ),
                mapOf(
                    1L to LegacyDisposition.SELECT_AS_WAKE,
                    2L to LegacyDisposition.SELECT_AS_WAKE,
                ),
            )
        invalid.forEach { proposal ->
            val store = FakeDiscoveryStore()
            assertFailsWith<IllegalArgumentException> {
                migrator(source, store, "2026-09-03T06:00:00Z").discover(proposal)
            }
            assertTrue(store.rows.isEmpty())
            assertEquals(null, store.state.bootstrapPhase)
        }
    }

    @Test
    fun duplicateActiveAlarmIdsAreRejectedBeforeWrite() {
        val store = FakeDiscoveryStore()
        val source = FakeLegacySource(listOf(alarm(7, 0, 1), alarm(8, 0, 1)))

        assertFailsWith<IllegalArgumentException> {
            migrator(source, store, "2026-09-03T06:00:00Z")
                .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))
        }

        assertTrue(store.rows.isEmpty())
        assertEquals(null, store.state.bootstrapPhase)
    }

    @Test
    fun blankOrOversizedLegacyAudioPathFallsBackWithoutProducingStagingKey() {
        listOf("   ", "x".repeat(4097)).forEach { invalidPath ->
            val source = FakeLegacySource(listOf(alarm(7, 0, 1)))
            val result =
                LegacyBootstrapMigrator(
                        source,
                        { validSettings().copy(importedAudioPath = invalidPath) },
                        FakeDiscoveryStore(),
                        { 0L },
                        ZoneId.of("UTC"),
                    )
                    .discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE))

            assertEquals(null, result.wakeProfileProposal.profile.importedAudioPath)
            assertTrue("importedAudioPath" in result.wakeProfileProposal.fallbackFields)
            assertEquals(null, result.targetStorageKey)
        }
    }

    @Test
    fun invalidLegacySettingsYieldBoundedDocumentedFallbackProposalWithoutRewrite() {
        val raw =
            LegacyWakeSettingsSnapshot(
                Float.NaN,
                0,
                Float.NEGATIVE_INFINITY,
                2f,
                "BOGUS",
                "/legacy/audio",
            )
        val source = FakeLegacySource(listOf(alarm(7, 0, 1)))
        val store = FakeDiscoveryStore()
        val migrator = LegacyBootstrapMigrator(source, { raw }, store, { 0L }, ZoneId.of("UTC"))

        val proposal =
            migrator.discover(mapOf(1L to LegacyDisposition.SELECT_AS_WAKE)).wakeProfileProposal

        assertEquals(
            setOf("rampMinutes", "startVolume", "maxVolume", "dismissal", "requiredLuxLevel"),
            proposal.fallbackFields,
        )
        assertEquals(20, proposal.profile.rampMinutes)
        assertTrue(proposal.profile.startVolume in 0f..1f)
        assertTrue(proposal.profile.maxVolume in proposal.profile.startVolume..1f)
        assertEquals(raw.importedAudioPath, proposal.profile.importedAudioPath)
    }

    @Test
    fun oneActiveAlarmIsDiscoveredWithoutActivatingRuntimeState() {
        val store = FakeDiscoveryStore()
        val source = FakeLegacySource(listOf(alarm(7, 30, id = 41)))
        val migrator = migrator(source, store, "2026-09-03T06:00:00Z")

        val result = migrator.discover(mapOf(41L to LegacyDisposition.SELECT_AS_WAKE))

        assertEquals(1, result.rows.size)
        assertEquals(41L, result.rows.single().legacyAlarmId)
        assertEquals(
            Instant.parse("2026-09-03T07:30:00Z").toEpochMilli(),
            result.rows.single().goalEpochMs,
        )
        assertEquals(0, result.rows.single().userConfirmed)
        assertTrue(result.rows.single().pendingIntentIdentity.contains("requestCode=0"))

        assertEquals("DISCOVERED", store.state.bootstrapPhase)
        assertEquals(0, store.runtimeRowWrites)
        assertEquals(source.original, source.alarms)
    }

    private fun migrator(source: FakeLegacySource, store: FakeDiscoveryStore, now: String) =
        LegacyBootstrapMigrator(
            legacySource = source,
            settingsSource = { validSettings() },
            store = store,
            nowMillis = { Instant.parse(now).toEpochMilli() },
            zoneId = ZoneId.of("UTC"),
        )

    private fun assertMigratorMatchesLegacyNextTrigger(
        zone: String,
        now: String,
        alarm: LegacyAlarmSnapshot,
        expected: String,
    ) {
        val frozenNow = Instant.parse(now).toEpochMilli()
        val expectedEpoch = Instant.parse(expected).toEpochMilli()
        val legacyEpoch =
            nextTrigger(
                alarm.hour,
                alarm.minute,
                alarm.repeatDays,
                frozenNow,
                ZoneId.of(zone),
                alarm.skippedOccurrenceDay,
            )
        val migratedEpoch =
            LegacyBootstrapMigrator(
                    FakeLegacySource(listOf(alarm)),
                    { validSettings() },
                    FakeDiscoveryStore(),
                    { frozenNow },
                    ZoneId.of(zone),
                )
                .discover(mapOf(alarm.id.toLong() to LegacyDisposition.SELECT_AS_WAKE))
                .rows
                .single()
                .goalEpochMs

        assertEquals(expectedEpoch, legacyEpoch, "legacy scheduling characterization")
        assertEquals(expectedEpoch, migratedEpoch, "bootstrap must preserve legacy scheduling")
    }

    private fun alarm(
        hour: Int,
        minute: Int,
        id: Int,
        active: Boolean = true,
        days: Set<Int> = emptySet(),
    ) = LegacyAlarmSnapshot(id, hour, minute, active, days, null, 1f, true, null)

    private fun validSettings() =
        LegacyWakeSettingsSnapshot(50f, 20, .05f, .35f, WakeDismissal.CONFIRM.name, null)

    private class FakeLegacySource(initial: List<LegacyAlarmSnapshot>) : LegacyAlarmSource {
        val original = initial.toList()
        val alarms = initial.toMutableList()

        override fun readAlarms(): List<LegacyAlarmSnapshot> = alarms.toList()
    }

    private class FakeDiscoveryStore : LegacyDiscoveryStore {
        override fun requireReady() = LegacyDiscoveryReadiness("install-A")

        var state =
            MigrationStateEntity(
                1,
                "LEGACY",
                null,
                0,
                16,
                null,
                "install-A",
                null,
                null,
                null,
                null,
            )
        var rows = emptyList<LegacyMigrationManifestEntity>()
        var runtimeRowWrites = 0

        override fun persistDiscovery(
            discovery: LegacyDiscoveryPersistence,
            revalidate: () -> LegacyDiscoveryPersistence,
        ) {
            assertEquals(discovery, revalidate())
            rows = discovery.rows
            state =
                state.copy(
                    sourceFingerprint = discovery.sourceFingerprint,
                    targetStorageKey = discovery.targetStorageKey,
                    bootstrapPhase = "DISCOVERED",
                    attemptToken = discovery.attemptToken,
                )
        }
    }
}
