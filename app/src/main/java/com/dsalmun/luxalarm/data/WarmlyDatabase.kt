/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dsalmun.luxalarm.SleepPlan
import com.dsalmun.luxalarm.SleepPlanStore

@Entity(tableName = "sleep_plan")
data class SleepPlanEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val wakeMinutes: Int,
    val bedtimeMinutes: Int,
    val bedtimeDayOffset: Int,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface SleepPlanDao {
    @Query("SELECT * FROM sleep_plan WHERE singletonId = 1") suspend fun load(): SleepPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(plan: SleepPlanEntity)
}

@Database(entities = [SleepPlanEntity::class], version = 1, exportSchema = false)
abstract class WarmlyDatabase : RoomDatabase() {
    abstract fun sleepPlanDao(): SleepPlanDao

    companion object {
        @Volatile private var instance: WarmlyDatabase? = null

        fun getDatabase(context: Context): WarmlyDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                WarmlyDatabase::class.java,
                                "warmly.db",
                            )
                            .build()
                            .also { instance = it }
                }
    }
}

class RoomSleepPlanStore(private val dao: SleepPlanDao) : SleepPlanStore {
    override suspend fun load(): SleepPlan? =
        dao.load()?.let {
            SleepPlan(
                wakeMinutes = it.wakeMinutes,
                bedtimeMinutes = it.bedtimeMinutes,
                bedtimeDayOffset = it.bedtimeDayOffset,
            )
        }

    override suspend fun save(plan: SleepPlan) {
        dao.save(
            SleepPlanEntity(
                wakeMinutes = plan.wakeMinutes,
                bedtimeMinutes = plan.bedtimeMinutes,
                bedtimeDayOffset = plan.bedtimeDayOffset,
            )
        )
    }
}
