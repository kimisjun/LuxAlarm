/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V6MigrationTest {
    private lateinit var context: Context
    private val dbName = "v6_migration_host_test.db"

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: error("user.dir unavailable"))) {
                it.parentFile
            }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun exportedDatabase(version: Int): JSONObject =
        JSONObject(
                File(
                        repositoryRoot(),
                        "app/schemas/com.dsalmun.luxalarm.data.AlarmDatabase/$version.json",
                    )
                    .readText()
            )
            .getJSONObject("database")

    /**
     * Reads the approved runtime DDL contract; this, not Room's generated 6.json, is authoritative.
     */
    private fun contractStatements(): Map<String, String> {
        val contract = File(repositoryRoot().parentFile, "V6_SCHEMA_CONTRACT.md").readText()
        val sql = contract.substringAfter("```sql").substringBefore("```")
        return sql.split(';').map(String::trim).filter(String::isNotEmpty).associateBy { statement
            ->
            checkNotNull(
                    Regex("CREATE (?:UNIQUE )?(?:TABLE|INDEX) ([a-z_]+)", RegexOption.IGNORE_CASE)
                        .find(statement)
                )
                .groupValues[1]
        }
    }

    private fun normalizeOutsideSingleQuotedLiterals(sql: String): String {
        val normalized = StringBuilder(sql.length)
        var position = 0
        while (position < sql.length) {
            if (sql[position] == '\'') {
                val literalStart = position++
                while (position < sql.length) {
                    if (sql[position] != '\'') {
                        position++
                    } else if (position + 1 < sql.length && sql[position + 1] == '\'') {
                        position += 2
                    } else {
                        position++
                        break
                    }
                }
                normalized.append(sql, literalStart, position)
            } else {
                val outsideStart = position
                while (position < sql.length && sql[position] != '\'') position++
                normalized.append(
                    sql.substring(outsideStart, position)
                        .lowercase()
                        .replace("`", "")
                        .replace(Regex("\\bif\\s+not\\s+exists\\s+"), "")
                        .replace(Regex("\\s+on\\s+update\\s+no\\s+action\\b"), "")
                        .replace(Regex("\\s+"), " ")
                        .replace(Regex("\\s*([(),])\\s*"), "$1")
                )
            }
        }
        return normalized.toString().trim()
    }

    /**
     * Canonicalizes spelling, never constraints or SQL literal contents. SQLite's implicit NO
     * ACTION and Room's quoting / IF NOT EXISTS are harmless. A single-column inline PK is
     * equivalent to a table PK.
     */
    private fun normalizeSql(sql: String): String {
        var normalized = normalizeOutsideSingleQuotedLiterals(sql)
        if (!normalized.startsWith("create table ")) return normalized

        val opening = normalized.indexOf('(')
        val closing = normalized.lastIndexOf(')')
        check(opening > 0 && closing > opening) { "Malformed CREATE TABLE: $sql" }
        val prefix = normalized.substring(0, opening + 1)
        val body = normalized.substring(opening + 1, closing)
        val parts = mutableListOf<String>()
        var depth = 0
        var quoted = false
        var start = 0
        body.forEachIndexed { index, character ->
            when (character) {
                '\'' -> quoted = !quoted
                '(' -> if (!quoted) depth++
                ')' -> if (!quoted) depth--
                ',' ->
                    if (!quoted && depth == 0) {
                        parts += body.substring(start, index)
                        start = index + 1
                    }
            }
        }
        parts += body.substring(start)

        val canonical = mutableListOf<String>()
        for (rawPart in parts) {
            val part = rawPart.trim()
            if (
                !part.startsWith("primary key(") &&
                    !part.startsWith("foreign key(") &&
                    !part.startsWith("unique(") &&
                    !part.startsWith("check(") &&
                    Regex("\\bprimary key\\b").containsMatchIn(part)
            ) {
                val column = part.substringBefore(' ')
                canonical += part.replaceFirst(Regex("\\s+primary key\\b"), "")
                canonical += "primary key($column)"
            } else {
                canonical += part
            }
        }
        return prefix + canonical.sorted().joinToString(",") + ")"
    }

    @Test
    fun normalizeSqlPreservesCaseAndEscapesInsideSingleQuotedLiterals() {
        val uppercase =
            normalizeSql(
                "CREATE TABLE sample (owner TEXT CHECK(owner IN ('LEGACY','WAKE')), " +
                    "label TEXT DEFAULT 'DON''T')"
            )
        val lowercase =
            normalizeSql(
                "create table sample (owner text check(owner in ('legacy','wake')), " +
                    "label text default 'don''t')"
            )

        assertNotEquals(uppercase, lowercase)
        assertTrue(uppercase.contains("('LEGACY','WAKE')"))
        assertTrue(uppercase.contains("'DON''T'"))
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun executeSqlFixture(relativePath: String): SQLiteDatabase {
        val dbPath = context.getDatabasePath(dbName)
        dbPath.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        val sql =
            File(repositoryRoot(), "app/schemas/$relativePath")
                .readLines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
        return db
    }

    private fun insertRepresentativeAlarm(db: SQLiteDatabase, version: Int) {
        db.insertOrThrow(
            "alarms",
            null,
            ContentValues().apply {
                put("id", 41)
                put("hour", 6)
                put("minute", 35)
                put("isActive", 1)
                put("repeatDays", "1,2,3,4,5")
                if (version >= 2) put("ringtoneUri", "content://fixture/tone")
                if (version >= 3) {
                    put("volume", if (version == 4) null as Float? else 0.4f)
                    put("vibrationEnabled", 0)
                }
                if (version >= 4) put("skippedOccurrenceDay", 20_000L)
            },
        )
    }

    private fun createSqlFixture(path: String, version: Int) {
        executeSqlFixture(path).use { insertRepresentativeAlarm(it, version) }
    }

    private fun createV5ExportFixtureDatabase() {
        val export = exportedDatabase(5)
        val entity = export.getJSONArray("entities").getJSONObject(0)
        val dbPath = context.getDatabasePath(dbName)
        dbPath.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "alarms"))
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
                arrayOf(export.getString("identityHash")),
            )
            insertRepresentativeAlarm(db, 5)
            db.version = 5
        }
    }

    private fun migrationsFrom(version: Int): Array<Migration> =
        when (version) {
            1 ->
                arrayOf(
                    AlarmDatabase.MIGRATION_1_2,
                    AlarmDatabase.MIGRATION_2_3,
                    AlarmDatabase.MIGRATION_3_4,
                    AlarmDatabase.MIGRATION_4_5,
                    AlarmDatabase.MIGRATION_5_6,
                )
            2 ->
                arrayOf(
                    AlarmDatabase.MIGRATION_2_3,
                    AlarmDatabase.MIGRATION_3_4,
                    AlarmDatabase.MIGRATION_4_5,
                    AlarmDatabase.MIGRATION_5_6,
                )
            3 ->
                arrayOf(
                    AlarmDatabase.MIGRATION_3_4,
                    AlarmDatabase.MIGRATION_4_5,
                    AlarmDatabase.MIGRATION_5_6,
                )
            4 -> arrayOf(AlarmDatabase.MIGRATION_4_5, AlarmDatabase.MIGRATION_5_6)
            5 -> arrayOf(AlarmDatabase.MIGRATION_5_6)
            else -> error("Unsupported fixture version $version")
        }

    private fun openMigratedDatabase(version: Int = 5): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .addMigrations(*migrationsFrom(version))
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }

    private inline fun <T> withMigratedDatabase(
        version: Int = 5,
        block: (AlarmDatabase) -> T,
    ): T {
        val database = openMigratedDatabase(version)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun assertRepresentativeAlarmAndValidatedV6(
        room: AlarmDatabase,
        expectedVolume: Float,
    ) {
        val db = room.openHelper.writableDatabase
        assertEquals(6, db.version)
        db.query("SELECT * FROM alarms WHERE id=41").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(6, cursor.getInt(cursor.getColumnIndexOrThrow("hour")))
            assertEquals(35, cursor.getInt(cursor.getColumnIndexOrThrow("minute")))
            assertEquals("1,2,3,4,5", cursor.getString(cursor.getColumnIndexOrThrow("repeatDays")))
            assertEquals(expectedVolume, cursor.getFloat(cursor.getColumnIndexOrThrow("volume")))
        }
        db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(exportedDatabase(6).getString("identityHash"), cursor.getString(0))
        }
    }

    @Test
    fun v1FixtureMigratesThroughExplicitChainToValidatedV6() {
        createSqlFixture("legacy/v1-alarms.sql", 1)
        withMigratedDatabase(1) { assertRepresentativeAlarmAndValidatedV6(it, 1f) }
    }

    @Test
    fun v2RingtoneFixtureMigratesThroughExplicitChainToValidatedV6() {
        createSqlFixture("legacy/v2-ringtone.sql", 2)
        withMigratedDatabase(2) { assertRepresentativeAlarmAndValidatedV6(it, 1f) }
    }

    @Test
    fun v3FixtureMigratesThroughExplicitChainToValidatedV6() {
        createSqlFixture("legacy/v3-volume-vibration.sql", 3)
        withMigratedDatabase(3) { assertRepresentativeAlarmAndValidatedV6(it, 0.4f) }
    }

    @Test
    fun v4FixtureMigratesThroughExplicitChainToValidatedV6() {
        createSqlFixture("legacy/v4-skipped-occurrence.sql", 4)
        withMigratedDatabase(4) { room ->
            assertRepresentativeAlarmAndValidatedV6(room, 1f)
            room.openHelper.writableDatabase
                .query("SELECT skippedOccurrenceDay FROM alarms WHERE id=41")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(20_000L, cursor.getLong(0))
                }
        }
    }

    @Test
    fun v5ExportFixtureMigratesThroughExplicitChainToValidatedV6() {
        createV5ExportFixtureDatabase()
        withMigratedDatabase(5) { room ->
            assertRepresentativeAlarmAndValidatedV6(room, 0.4f)
            room.openHelper.writableDatabase
                .query("SELECT skippedOccurrenceDay FROM alarms WHERE id=41")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(20_000L, cursor.getLong(0))
                }
        }
    }

    @Test
    fun v2RingingStateCollisionIsRejectedWithoutAutomaticMigration() {
        executeSqlFixture("legacy/v2-ringing-state.sql").use { db ->
            db.execSQL(
                "INSERT INTO alarms(id,hour,minute,isActive,repeatDays) VALUES(41,6,35,1,'1,2,3')"
            )
        }
        val database =
            Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
                .addMigrations(*migrationsFrom(2))
                .allowMainThreadQueries()
                .build()
        try {
            val failure = assertFails { database.openHelper.writableDatabase }
            assertTrue(failure.message.orEmpty().contains("ringtoneUri"))
        } finally {
            database.close()
        }
    }

    @Test
    fun initializerUsesInjectedInstallEpochAndTimestampSuppliers() {
        createV5ExportFixtureDatabase()
        val helper =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(dbName)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(5) {
                                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            }
                        )
                        .build()
                )
        val db = helper.writableDatabase
        try {
            createAndInitializeV6Schema(
                db,
                installEpochSupplier = { "fixed-install-epoch" },
                timestampSupplier = { 123_456L },
            )
            db.query("SELECT install_epoch FROM migration_state WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixed-install-epoch", cursor.getString(0))
            }
            db.query("SELECT updated_at FROM legacy_coordinator_state WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(123_456L, cursor.getLong(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationCreatesExactlyTheRequiredInitialStateOnce() {
        createV5ExportFixtureDatabase()
        withMigratedDatabase { room ->
            val db = room.openHelper.writableDatabase
            db.query("SELECT * FROM migration_state").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                assertEquals(
                    "LEGACY",
                    cursor.getString(cursor.getColumnIndexOrThrow("schedule_owner")),
                )
                assertNull(cursor.getString(cursor.getColumnIndexOrThrow("active_generation")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("bootstrap_version")))
                assertEquals(
                    16,
                    cursor.getInt(cursor.getColumnIndexOrThrow("rollback_allowed_until_version")),
                )
                val installEpoch = cursor.getString(cursor.getColumnIndexOrThrow("install_epoch"))
                assertTrue(
                    installEpoch.matches(
                        Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                    )
                )
                for (column in
                    listOf(
                        "active_generation",
                        "handoff_fence_occurrence_id",
                        "source_fingerprint",
                        "target_storage_key",
                        "bootstrap_phase",
                        "attempt_token",
                    )) assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(column)), column)
                assertTrue(!cursor.moveToNext())
            }
            db.query("SELECT * FROM legacy_coordinator_state").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("token_generation")))
                assertEquals("IDLE", cursor.getString(cursor.getColumnIndexOrThrow("state")))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("scheduled_goal_epoch_ms")))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("pending_intent_identity")))
                assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")) > 0L)
                assertTrue(!cursor.moveToNext())
            }
            for (table in
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
                    "legacy_migration_manifest",
                    "legacy_coordinator_member",
                )) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0), "$table must start empty")
                }
            }
        }
    }

    @Test
    fun eachMigrationGetsANewInstallEpoch() {
        createV5ExportFixtureDatabase()
        val first = withMigratedDatabase { room ->
            room.openHelper.writableDatabase
                .query("SELECT install_epoch FROM migration_state")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
        }
        context.deleteDatabase(dbName)
        createV5ExportFixtureDatabase()
        val second = withMigratedDatabase { room ->
            room.openHelper.writableDatabase
                .query("SELECT install_epoch FROM migration_state")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
        }
        assertNotEquals(first, second)
    }

    @Test
    fun migratedSqliteMasterSemanticallyMatchesApprovedContractIncludingChecksAndPartialIndex() {
        createV5ExportFixtureDatabase()
        withMigratedDatabase { room ->
            val db = room.openHelper.writableDatabase
            val contract = contractStatements()
            for ((name, expectedSql) in contract) {
                db.query(
                        "SELECT sql FROM sqlite_master WHERE name=? AND type IN ('table','index')",
                        arrayOf(name),
                    )
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst(), "$name is absent from sqlite_master")
                        assertEquals(
                            normalizeSql(expectedSql),
                            normalizeSql(cursor.getString(0)),
                            name,
                        )
                        assertTrue(
                            !cursor.moveToNext(),
                            "$name must identify exactly one schema object",
                        )
                    }
            }
            val expectedObjects = contract.keys
            val actualObjects = mutableSetOf<String>()
            db.query(
                    "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
                        "AND name NOT IN ('alarms','room_master_table','android_metadata') " +
                        "AND type IN ('table','index')"
                )
                .use { cursor -> while (cursor.moveToNext()) actualObjects += cursor.getString(0) }
            assertEquals(expectedObjects, actualObjects)
        }
    }

    /**
     * Verifies the genuine KSP/Room validation projection and its deliberate metadata surrogate.
     * Runtime-only CHECK clauses and the partial-index predicate are covered against the approved
     * DDL contract by
     * [migratedSqliteMasterSemanticallyMatchesApprovedContractIncludingChecksAndPartialIndex].
     */
    @Test
    fun kspExportMatchesRoomValidationProjectionAndIdentityNotFullRuntimeContract() {
        createV5ExportFixtureDatabase()
        withMigratedDatabase { room ->
            val db = room.openHelper.writableDatabase
            val export = exportedDatabase(6)
            assertEquals(6, export.getInt("version"))
            db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(export.getString("identityHash"), cursor.getString(0))
            }

            val entities = export.getJSONArray("entities")
            for (position in 0 until entities.length()) {
                val entity = entities.getJSONObject(position)
                val table = entity.getString("tableName")
                val liveColumns = linkedMapOf<String, List<Any?>>()
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        liveColumns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                            listOf(
                                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                                if (cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) null
                                else cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                            )
                    }
                }
                val fields = entity.getJSONArray("fields")
                val exportedColumns = linkedMapOf<String, List<Any?>>()
                for (fieldPosition in 0 until fields.length()) {
                    val field = fields.getJSONObject(fieldPosition)
                    val name = field.getString("columnName")
                    exportedColumns[name] =
                        listOf(
                            field.getString("affinity"),
                            field.optBoolean("notNull", false),
                            if (!field.has("defaultValue") || field.isNull("defaultValue")) null
                            else field.getString("defaultValue"),
                            0,
                        )
                }
                val pk = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
                for (pkPosition in 0 until pk.length()) {
                    val name = pk.getString(pkPosition)
                    exportedColumns[name] =
                        exportedColumns.getValue(name).dropLast(1) + (pkPosition + 1)
                }
                assertEquals(exportedColumns, liveColumns, "$table columns/defaults/PK")

                val expectedForeignKeys = mutableSetOf<String>()
                val foreignKeys = entity.optJSONArray("foreignKeys")
                if (foreignKeys != null)
                    for (fkPosition in 0 until foreignKeys.length()) {
                        val fk = foreignKeys.getJSONObject(fkPosition)
                        expectedForeignKeys +=
                            listOf(
                                    fk.getString("table"),
                                    fk.getJSONArray("columns").getString(0),
                                    fk.getJSONArray("referencedColumns").getString(0),
                                    fk.getString("onDelete"),
                                    fk.getString("onUpdate"),
                                )
                                .joinToString("|")
                    }
                val liveForeignKeys = mutableSetOf<String>()
                db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        liveForeignKeys +=
                            listOf(
                                    cursor.getString(cursor.getColumnIndexOrThrow("table")),
                                    cursor.getString(cursor.getColumnIndexOrThrow("from")),
                                    cursor.getString(cursor.getColumnIndexOrThrow("to")),
                                    cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                                    cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                                )
                                .joinToString("|")
                    }
                }
                assertEquals(expectedForeignKeys, liveForeignKeys, "$table foreign keys")

                val indices = entity.optJSONArray("indices")
                if (indices != null)
                    for (indexPosition in 0 until indices.length()) {
                        val index = indices.getJSONObject(indexPosition)
                        val name = index.getString("name")
                        val live = indexMetadata(db, table).getValue(name)
                        val expectedColumns = index.getJSONArray("columnNames")
                        assertEquals(
                            (0 until expectedColumns.length()).map(expectedColumns::getString),
                            live.columns,
                            name,
                        )
                        assertEquals(index.getBoolean("unique"), live.unique, name)
                    }
            }

            val approvedUniqueKeys =
                mapOf(
                    "imported_track" to setOf(setOf("storage_key"), setOf("content_hash")),
                    "wake_run_snapshot" to setOf(setOf("occurrence_id")),
                    "wake_event_dispatch" to setOf(setOf("snapshot_id", "event_kind")),
                )
            for ((table, expected) in approvedUniqueKeys) {
                val actual =
                    indexMetadata(db, table)
                        .values
                        .filter { it.unique && it.origin != "pk" }
                        .map { it.columns.toSet() }
                        .toSet()
                assertEquals(expected, actual, "$table unique constraints")
            }
            assertTrue(indexMetadata(db, "imported_track").getValue("uq_track_live_hash").partial)
        }
    }

    private data class IndexMetadata(
        val unique: Boolean,
        val partial: Boolean,
        val origin: String,
        val columns: List<String>,
    )

    private fun indexMetadata(
        db: SupportSQLiteDatabase,
        table: String,
    ): Map<String, IndexMetadata> {
        val result = linkedMapOf<String, IndexMetadata>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val columns = mutableListOf<String>()
                db.query("PRAGMA index_info(`$name`)").use { indexCursor ->
                    while (indexCursor.moveToNext()) {
                        columns += indexCursor.getString(indexCursor.getColumnIndexOrThrow("name"))
                    }
                }
                result[name] =
                    IndexMetadata(
                        unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1,
                        partial = cursor.getInt(cursor.getColumnIndexOrThrow("partial")) == 1,
                        origin = cursor.getString(cursor.getColumnIndexOrThrow("origin")),
                        columns = columns,
                    )
            }
        }
        return result
    }

    @Test
    fun migrationEnforcesChecksForeignKeysAndPartialLiveHashUniqueness() {
        createV5ExportFixtureDatabase()
        withMigratedDatabase { room ->
            val db = room.openHelper.writableDatabase
            assertFailsWith<SQLiteConstraintException> {
                db.execSQL(
                    "INSERT INTO schedule_occurrence_claim " +
                        "(canonical_occurrence_key, goal_epoch_ms, owner, state) " +
                        "VALUES ('bad-owner', 1, 'OTHER', 'PREPARED')"
                )
            }
            assertFailsWith<SQLiteConstraintException> {
                db.execSQL(
                    "INSERT INTO wake_run_status (snapshot_id, state) VALUES ('missing', 'PREPARED')"
                )
            }
            fun insertTrack(id: String, lifecycle: String) {
                db.execSQL(
                    "INSERT INTO imported_track " +
                        "(id, storage_key, title, duration_ms, mime_type, content_hash, lifecycle_state, availability, added_at) " +
                        "VALUES (?, ?, 'track', 1, 'audio/test', 'same-hash', ?, 'AVAILABLE', 1)",
                    arrayOf(id, "storage-$id", lifecycle),
                )
            }
            insertTrack("deleted-a", "DELETED")
            insertTrack("deleted-b", "DELETED")
            insertTrack("live-a", "AVAILABLE")
            assertFailsWith<SQLiteConstraintException> { insertTrack("live-b", "AVAILABLE") }
        }
    }

    @Test
    fun newDatabaseCallbackFailsClosedBeforeDroppingAnyPopulatedV6Table() {
        val freshName = "populated_v6_callback_test.db"
        context.deleteDatabase(freshName)
        try {
            val room =
                AlarmDatabase.databaseBuilder(context, freshName).allowMainThreadQueries().build()
            try {
                val db = room.openHelper.writableDatabase
                db.execSQL(
                    "INSERT INTO imported_track " +
                        "(id, storage_key, title, duration_ms, mime_type, content_hash, lifecycle_state, availability, added_at) " +
                        "VALUES ('preserved', 'preserved-key', 'preserved', 1, 'audio/test', " +
                        "'preserved-hash', 'AVAILABLE', 'AVAILABLE', 1)"
                )
                val schemaBefore = mutableMapOf<String, String>()
                db.query(
                        "SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
                            "AND type IN ('table','index') ORDER BY name"
                    )
                    .use { cursor ->
                        while (cursor.moveToNext()) schemaBefore[cursor.getString(0)] =
                            cursor.getString(1)
                    }

                assertFailsWith<IllegalStateException> { V6_NEW_DATABASE_CALLBACK.onCreate(db) }

                val schemaAfter = mutableMapOf<String, String>()
                db.query(
                        "SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
                            "AND type IN ('table','index') ORDER BY name"
                    )
                    .use { cursor ->
                        while (cursor.moveToNext()) schemaAfter[cursor.getString(0)] =
                            cursor.getString(1)
                    }
                assertEquals(schemaBefore, schemaAfter, "callback must not drop any schema object")
                db.query("SELECT title FROM imported_track WHERE id='preserved'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("preserved", cursor.getString(0))
                    assertTrue(!cursor.moveToNext())
                }
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(freshName)
        }
    }

    @Test
    fun freshV6DatabaseEnforcesTheSameContractAsMigratedV6() {
        val freshName = "fresh_v6_contract_test.db"
        context.deleteDatabase(freshName)
        try {
            val room =
                AlarmDatabase.databaseBuilder(context, freshName).allowMainThreadQueries().build()
            try {
                val db = room.openHelper.writableDatabase
                for ((name, expectedSql) in contractStatements()) {
                    db.query(
                            "SELECT sql FROM sqlite_master WHERE name=? AND type IN ('table','index')",
                            arrayOf(name),
                        )
                        .use { cursor ->
                            assertTrue(
                                cursor.moveToFirst(),
                                "$name is absent from fresh sqlite_master",
                            )
                            assertEquals(
                                normalizeSql(expectedSql),
                                normalizeSql(cursor.getString(0)),
                                name,
                            )
                        }
                }
                val expectedObjects = contractStatements().keys
                val actualObjects = mutableSetOf<String>()
                db.query(
                        "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
                            "AND name NOT IN ('alarms','room_master_table','android_metadata') " +
                            "AND type IN ('table','index')"
                    )
                    .use { cursor ->
                        while (cursor.moveToNext()) actualObjects += cursor.getString(0)
                    }
                assertEquals(expectedObjects, actualObjects, "fresh V6 schema objects")
                db.query("SELECT COUNT(*) FROM migration_state").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                db.query("SELECT schedule_owner, install_epoch FROM migration_state WHERE id=1")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("LEGACY", cursor.getString(0))
                        assertTrue(cursor.getString(1).isNotBlank())
                    }
                db.query(
                        "SELECT COUNT(*) FROM legacy_coordinator_state WHERE id=1 AND state='IDLE'"
                    )
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                assertFailsWith<SQLiteConstraintException> {
                    db.execSQL(
                        "INSERT INTO imported_track " +
                            "(id, storage_key, title, duration_ms, mime_type, content_hash, lifecycle_state, availability, added_at) " +
                            "VALUES ('bad', 'key', 'bad', -1, 'audio/test', 'hash', 'INVALID', 'AVAILABLE', 1)"
                    )
                }
                fun insertDeleted(id: String, storageKey: String) {
                    db.execSQL(
                        "INSERT INTO imported_track " +
                            "(id, storage_key, title, duration_ms, mime_type, content_hash, lifecycle_state, availability, added_at) " +
                            "VALUES (?, ?, 'deleted', 0, 'audio/test', 'same-deleted-hash', 'DELETED', 'AVAILABLE', 1)",
                        arrayOf(id, storageKey),
                    )
                }
                insertDeleted("deleted-1", "storage-1")
                insertDeleted("deleted-2", "storage-2")
                assertFailsWith<SQLiteConstraintException> {
                    db.execSQL(
                        "INSERT INTO imported_track " +
                            "(id, storage_key, title, duration_ms, mime_type, content_hash, lifecycle_state, availability, added_at) " +
                            "VALUES ('duplicate-storage', 'storage-1', 'duplicate', 0, 'audio/test', 'other-hash', 'DELETED', 'AVAILABLE', 1)"
                    )
                }
            } finally {
                room.close()
            }
            val reopened =
                AlarmDatabase.databaseBuilder(context, freshName).allowMainThreadQueries().build()
            try {
                reopened.openHelper.writableDatabase
                    .query(
                        "SELECT COUNT(*) FROM imported_track WHERE content_hash='same-deleted-hash'"
                    )
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(2, cursor.getInt(0))
                    }
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(freshName)
        }
    }
}
