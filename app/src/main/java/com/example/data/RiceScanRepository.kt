package com.example.data

import kotlinx.coroutines.flow.Flow

class RiceScanRepository(private val riceScanDao: RiceScanDao) {
    val allScans: Flow<List<RiceScan>> = riceScanDao.getAllScans()

    suspend fun insert(scan: RiceScan): Long {
        return riceScanDao.insertScan(scan)
    }

    suspend fun delete(id: Int) {
        riceScanDao.deleteScanById(id)
    }

    suspend fun clearAll() {
        riceScanDao.deleteAllScans()
    }
}
