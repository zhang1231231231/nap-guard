package com.example.napguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: NapRecord): Long

    @Query("SELECT * FROM nap_records ORDER BY startTimeMs DESC")
    fun getAllRecords(): Flow<List<NapRecord>>

    @Query("SELECT * FROM nap_records ORDER BY startTimeMs DESC LIMIT 1")
    fun getLatestRecord(): Flow<NapRecord?>

    @Query("DELETE FROM nap_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
