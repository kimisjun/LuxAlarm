/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
 * Modified for GentleWake in 2026 by 김은준.
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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.dsalmun.luxalarm.data.AlarmDatabase
import com.dsalmun.luxalarm.data.AlarmDatabase.Companion.MIGRATION_1_2
import com.dsalmun.luxalarm.data.AlarmDatabase.Companion.MIGRATION_2_3
import com.dsalmun.luxalarm.data.AlarmDatabase.Companion.MIGRATION_3_4
import com.dsalmun.luxalarm.data.AlarmDatabase.Companion.MIGRATION_4_5
import com.dsalmun.luxalarm.data.AlarmDatabase.Companion.MIGRATION_5_6
import com.dsalmun.luxalarm.data.AlarmItem
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

@MediumTest
class MigrationTest {
    private lateinit var context: Context
    private val dbName = "migration_test_db"

    private data class V1Alarm(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val isActive: Boolean,
        val repeatDays: String,
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun databaseFromFixture(assetPath: String): SQLiteDatabase {
        val dbPath = context.getDatabasePath(dbName)
        dbPath.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        val sql =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open(assetPath)
                .bufferedReader()
                .use { it.readText() }
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
        return db
    }

    private fun createV1Database(alarms: List<V1Alarm>) {
        val db = databaseFromFixture("legacy/v1-alarms.sql")
        for (alarm in alarms) {
            val values =
                ContentValues().apply {
                    put("id", alarm.id)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("isActive", if (alarm.isActive) 1 else 0)
                    put("repeatDays", alarm.repeatDays)
                }
            db.insert("alarms", null, values)
        }
        db.close()
    }

    private data class V2Alarm(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val isActive: Boolean,
        val repeatDays: String,
        val ringtoneUri: String?,
    )

    private fun createV2Database(alarms: List<V2Alarm>) {
        val db = databaseFromFixture("legacy/v2-ringtone.sql")
        for (alarm in alarms) {
            val values =
                ContentValues().apply {
                    put("id", alarm.id)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("isActive", if (alarm.isActive) 1 else 0)
                    put("repeatDays", alarm.repeatDays)
                    put("ringtoneUri", alarm.ringtoneUri)
                }
            db.insert("alarms", null, values)
        }
        db.close()
    }

    private fun createLegacyRingingStateV2Database() {
        databaseFromFixture("legacy/v2-ringing-state.sql").close()
    }

    private data class V3Alarm(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val isActive: Boolean,
        val repeatDays: String,
        val ringtoneUri: String?,
        val volume: Float?,
        val vibrationEnabled: Boolean,
    )

    private fun createV3Database(alarms: List<V3Alarm>) {
        val db = databaseFromFixture("legacy/v3-volume-vibration.sql")
        for (alarm in alarms) {
            val values =
                ContentValues().apply {
                    put("id", alarm.id)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("isActive", if (alarm.isActive) 1 else 0)
                    put("repeatDays", alarm.repeatDays)
                    put("ringtoneUri", alarm.ringtoneUri)
                    put("volume", alarm.volume)
                    put("vibrationEnabled", if (alarm.vibrationEnabled) 1 else 0)
                }
            db.insert("alarms", null, values)
        }
        db.close()
    }

    private data class V4Alarm(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val isActive: Boolean,
        val repeatDays: String,
        val ringtoneUri: String?,
        val volume: Float?,
        val vibrationEnabled: Boolean,
        val skippedOccurrenceDay: Long?,
    )

    private fun createV4Database(alarms: List<V4Alarm>) {
        val db = databaseFromFixture("legacy/v4-skipped-occurrence.sql")
        for (alarm in alarms) {
            val values =
                ContentValues().apply {
                    put("id", alarm.id)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("isActive", if (alarm.isActive) 1 else 0)
                    put("repeatDays", alarm.repeatDays)
                    put("ringtoneUri", alarm.ringtoneUri)
                    put("volume", alarm.volume)
                    put("vibrationEnabled", if (alarm.vibrationEnabled) 1 else 0)
                    put("skippedOccurrenceDay", alarm.skippedOccurrenceDay)
                }
            db.insert("alarms", null, values)
        }
        db.close()
    }

    private fun openCurrentV6Database(): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, dbName)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()

    @Test
    fun migration4To5Output_matchesCommittedV5RoomExport() {
        val export =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("com.dsalmun.luxalarm.data.AlarmDatabase/5.json")
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val expectedSql =
            export
                .getJSONArray("entities")
                .getJSONObject(0)
                .getString("createSql")
                .replace("\${TABLE_NAME}", "alarms")

        createV4Database(emptyList())
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
                                ) {
                                    assertEquals(4, oldVersion)
                                    assertEquals(5, newVersion)
                                    MIGRATION_4_5.migrate(db)
                                }
                            }
                        )
                        .build()
                )
        val frameworkDb = helper.writableDatabase
        try {
            val actualSql =
                frameworkDb
                    .query("SELECT sql FROM sqlite_master WHERE type='table' AND name='alarms'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getString(0)
                    }

            assertEquals(5, export.getInt("version"))
            assertEquals(5, frameworkDb.version)
            assertEquals(normalizeCreateSql(expectedSql), normalizeCreateSql(actualSql))
        } finally {
            helper.close()
        }
    }

    private fun normalizeCreateSql(sql: String): String =
        sql.lowercase()
            .replace("`", "")
            .replace("if not exists ", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    @Test
    fun migrate1To2_addsRingtoneUriColumn() {
        val v1Alarms =
            listOf(
                V1Alarm(id = 1, hour = 7, minute = 30, isActive = true, repeatDays = "1,2,3,4,5"),
                V1Alarm(id = 2, hour = 9, minute = 0, isActive = false, repeatDays = ""),
            )
        createV1Database(v1Alarms)

        val db = openCurrentV6Database()
        try {
            val alarms = runBlocking { db.alarmDao().getAllAlarms().first() }
            assertEquals(2, alarms.size)

            val alarm1 = alarms.first { it.id == 1 }
            assertEquals(7, alarm1.hour)
            assertEquals(30, alarm1.minute)
            assertEquals(true, alarm1.isActive)
            assertEquals(setOf(1, 2, 3, 4, 5), alarm1.repeatDays)
            assertNull(alarm1.ringtoneUri)

            val alarm2 = alarms.first { it.id == 2 }
            assertEquals(9, alarm2.hour)
            assertEquals(0, alarm2.minute)
            assertEquals(false, alarm2.isActive)
            assertEquals(emptySet(), alarm2.repeatDays)
            assertNull(alarm2.ringtoneUri)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate1To2_newRowsCanHaveRingtoneUri() {
        val v1Alarms =
            listOf(V1Alarm(id = 1, hour = 6, minute = 0, isActive = true, repeatDays = ""))
        createV1Database(v1Alarms)

        val db = openCurrentV6Database()
        try {
            val dao = db.alarmDao()
            runBlocking {
                dao.insert(
                    AlarmItem(hour = 8, minute = 15, ringtoneUri = "content://media/ringtone")
                )
            }

            val alarms = runBlocking { dao.getAllAlarms().first() }
            assertEquals(2, alarms.size)

            val migrated = alarms.first { it.id == 1 }
            assertNull(migrated.ringtoneUri)

            val newAlarm = alarms.first { it.id != 1 }
            assertEquals(8, newAlarm.hour)
            assertEquals(15, newAlarm.minute)
            assertEquals("content://media/ringtone", newAlarm.ringtoneUri)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_addsVolumeColumn() {
        val v2Alarms =
            listOf(
                V2Alarm(
                    id = 1,
                    hour = 7,
                    minute = 30,
                    isActive = true,
                    repeatDays = "1,2,3,4,5",
                    ringtoneUri = "content://media/ringtone",
                ),
                V2Alarm(
                    id = 2,
                    hour = 9,
                    minute = 0,
                    isActive = false,
                    repeatDays = "",
                    ringtoneUri = null,
                ),
            )
        createV2Database(v2Alarms)

        val db = openCurrentV6Database()
        try {
            val alarms = runBlocking { db.alarmDao().getAllAlarms().first() }
            assertEquals(2, alarms.size)

            val alarm1 = alarms.first { it.id == 1 }
            assertEquals(7, alarm1.hour)
            assertEquals(30, alarm1.minute)
            assertEquals("content://media/ringtone", alarm1.ringtoneUri)
            assertEquals(1f, alarm1.volume)
            assertEquals(true, alarm1.vibrationEnabled)

            val alarm2 = alarms.first { it.id == 2 }
            assertEquals(9, alarm2.hour)
            assertEquals(0, alarm2.minute)
            assertNull(alarm2.ringtoneUri)
            assertEquals(1f, alarm2.volume)
            assertEquals(true, alarm2.vibrationEnabled)
        } finally {
            db.close()
        }
    }

    @Test
    fun legacyRingingStateV2_hasNoSafeAutomaticMigration() {
        createLegacyRingingStateV2Database()

        val db = openCurrentV6Database()
        try {
            val failure = assertFails { db.openHelper.writableDatabase }
            assertTrue(
                failure.message.orEmpty().contains("ringtoneUri"),
                "The known version collision must fail on its missing ringtoneUri column",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_newRowsCanHaveCustomVolume() {
        val v2Alarms =
            listOf(
                V2Alarm(
                    id = 1,
                    hour = 6,
                    minute = 0,
                    isActive = true,
                    repeatDays = "",
                    ringtoneUri = null,
                )
            )
        createV2Database(v2Alarms)

        val db = openCurrentV6Database()
        try {
            val dao = db.alarmDao()
            runBlocking { dao.insert(AlarmItem(hour = 8, minute = 15, volume = 0.5f)) }

            val alarms = runBlocking { dao.getAllAlarms().first() }
            assertEquals(2, alarms.size)

            val migrated = alarms.first { it.id == 1 }
            assertEquals(1f, migrated.volume)

            val newAlarm = alarms.first { it.id != 1 }
            assertEquals(8, newAlarm.hour)
            assertEquals(15, newAlarm.minute)
            assertEquals(0.5f, newAlarm.volume)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_newRowsCanHaveVibrationDisabled() {
        val v2Alarms =
            listOf(
                V2Alarm(
                    id = 1,
                    hour = 6,
                    minute = 0,
                    isActive = true,
                    repeatDays = "",
                    ringtoneUri = null,
                )
            )
        createV2Database(v2Alarms)

        val db = openCurrentV6Database()
        try {
            val dao = db.alarmDao()
            runBlocking { dao.insert(AlarmItem(hour = 8, minute = 15, vibrationEnabled = false)) }

            val alarms = runBlocking { dao.getAllAlarms().first() }
            assertEquals(2, alarms.size)

            val migrated = alarms.first { it.id == 1 }
            assertEquals(true, migrated.vibrationEnabled)

            val newAlarm = alarms.first { it.id != 1 }
            assertEquals(8, newAlarm.hour)
            assertEquals(15, newAlarm.minute)
            assertEquals(false, newAlarm.vibrationEnabled)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3To4_addsSkippedOccurrenceDayColumn() {
        val v3Alarms =
            listOf(
                V3Alarm(
                    id = 1,
                    hour = 7,
                    minute = 30,
                    isActive = true,
                    repeatDays = "1,2,3,4,5",
                    ringtoneUri = "content://media/ringtone",
                    volume = 0.5f,
                    vibrationEnabled = true,
                )
            )
        createV3Database(v3Alarms)

        val db = openCurrentV6Database()
        try {
            val alarms = runBlocking { db.alarmDao().getAllAlarms().first() }
            assertEquals(1, alarms.size)

            val alarm1 = alarms.first { it.id == 1 }
            assertEquals(7, alarm1.hour)
            assertEquals(30, alarm1.minute)
            assertEquals(0.5f, alarm1.volume)
            assertNull(alarm1.skippedOccurrenceDay)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3To4_newRowsCanHaveSkippedOccurrenceDay() {
        val v3Alarms =
            listOf(
                V3Alarm(
                    id = 1,
                    hour = 6,
                    minute = 0,
                    isActive = true,
                    repeatDays = "",
                    ringtoneUri = null,
                    volume = null,
                    vibrationEnabled = true,
                )
            )
        createV3Database(v3Alarms)

        val db = openCurrentV6Database()
        try {
            val dao = db.alarmDao()
            runBlocking {
                dao.insert(AlarmItem(hour = 8, minute = 15, skippedOccurrenceDay = 123456789L))
            }

            val alarms = runBlocking { dao.getAllAlarms().first() }
            assertEquals(2, alarms.size)

            val migrated = alarms.first { it.id == 1 }
            assertNull(migrated.skippedOccurrenceDay)

            val newAlarm = alarms.first { it.id != 1 }
            assertEquals(123456789L, newAlarm.skippedOccurrenceDay)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate4To5_turnsAnUnsetVolumeIntoAFullOne() {
        val v4Alarms =
            listOf(
                V4Alarm(
                    id = 1,
                    hour = 7,
                    minute = 30,
                    isActive = true,
                    repeatDays = "1,2,3,4,5",
                    ringtoneUri = "content://media/ringtone",
                    volume = null,
                    vibrationEnabled = true,
                    skippedOccurrenceDay = 19_000L,
                )
            )
        createV4Database(v4Alarms)

        val db = openCurrentV6Database()
        try {
            val alarm = runBlocking { db.alarmDao().getAllAlarms().first() }.single()
            assertEquals(1f, alarm.volume, "A slider drawn at full has to start ringing at full")
            // The rebuilt table has to bring the rest of the row across untouched.
            assertEquals(7, alarm.hour)
            assertEquals(30, alarm.minute)
            assertEquals(true, alarm.isActive)
            assertEquals(setOf(1, 2, 3, 4, 5), alarm.repeatDays)
            assertEquals("content://media/ringtone", alarm.ringtoneUri)
            assertEquals(true, alarm.vibrationEnabled)
            assertEquals(19_000L, alarm.skippedOccurrenceDay)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate4To5_keepsAVolumeSomeoneChose() {
        createV4Database(
            listOf(
                V4Alarm(
                    id = 1,
                    hour = 6,
                    minute = 0,
                    isActive = true,
                    repeatDays = "",
                    ringtoneUri = null,
                    volume = 0.25f,
                    vibrationEnabled = false,
                    skippedOccurrenceDay = null,
                )
            )
        )

        val db = openCurrentV6Database()
        try {
            val alarm = runBlocking { db.alarmDao().getAllAlarms().first() }.single()
            assertEquals(0.25f, alarm.volume)
            assertEquals(false, alarm.vibrationEnabled)
        } finally {
            db.close()
        }
    }

    /** Autoincrement survives the table swap, so a new alarm cannot reuse a live id. */
    @Test
    fun migrate4To5_newRowsStillGetFreshIds() {
        createV4Database(
            listOf(
                V4Alarm(
                    id = 9,
                    hour = 6,
                    minute = 0,
                    isActive = true,
                    repeatDays = "",
                    ringtoneUri = null,
                    volume = null,
                    vibrationEnabled = true,
                    skippedOccurrenceDay = null,
                )
            )
        )

        val db = openCurrentV6Database()
        try {
            val dao = db.alarmDao()
            runBlocking { dao.insert(AlarmItem(hour = 8, minute = 15, volume = 0.5f)) }

            val alarms = runBlocking { dao.getAllAlarms().first() }
            assertEquals(2, alarms.size)
            val newAlarm = alarms.first { it.id != 9 }
            assertEquals(0.5f, newAlarm.volume)
            assertEquals(10, newAlarm.id, "A rebuilt table must not restart ids at 1")
        } finally {
            db.close()
        }
    }
}
