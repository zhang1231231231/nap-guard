package com.example.napguard.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NapRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class NapDatabase : RoomDatabase() {
    abstract fun napDao(): NapDao
}
