package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rice_scans")
data class RiceScan(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val totalEnteros: Int,
    val totalPartidos: Int,
    val totalCumulos: Int,
    val thresholdValue: Int,
    val minArea: Int,
    val wholeArea: Int,
    val clusterArea: Int,
    val imagePath: String? = null
)
