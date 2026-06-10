package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RiceScanDao {
    @Query("SELECT * FROM rice_scans ORDER BY timestamp DESC")
    fun getAllScans(): Flow<List<RiceScan>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: RiceScan): Long

    @Query("DELETE FROM rice_scans WHERE id = :id")
    suspend fun deleteScanById(id: Int)

    @Query("DELETE FROM rice_scans")
    suspend fun deleteAllScans()
}
