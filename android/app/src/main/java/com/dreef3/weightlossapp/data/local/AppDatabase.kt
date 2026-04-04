package com.dreef3.weightlossapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dreef3.weightlossapp.data.local.dao.CoachChatMessageDao
import com.dreef3.weightlossapp.data.local.dao.CoachChatSessionDao
import com.dreef3.weightlossapp.data.local.dao.DailyCalorieBudgetPeriodDao
import com.dreef3.weightlossapp.data.local.dao.FoodEntryDao
import com.dreef3.weightlossapp.data.local.dao.ProfileDao
import com.dreef3.weightlossapp.data.local.entity.CoachChatMessageEntity
import com.dreef3.weightlossapp.data.local.entity.CoachChatSessionEntity
import com.dreef3.weightlossapp.data.local.entity.DailyCalorieBudgetPeriodEntity
import com.dreef3.weightlossapp.data.local.entity.FoodEntryEntity
import com.dreef3.weightlossapp.data.local.entity.ProfileEntity

@Database(
    entities = [
        ProfileEntity::class,
        DailyCalorieBudgetPeriodEntity::class,
        FoodEntryEntity::class,
        CoachChatSessionEntity::class,
        CoachChatMessageEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun dailyCalorieBudgetPeriodDao(): DailyCalorieBudgetPeriodDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun coachChatSessionDao(): CoachChatSessionDao
    abstract fun coachChatMessageDao(): CoachChatMessageDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "weight-loss-app.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
