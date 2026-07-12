package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CellDao {
    @Query("SELECT * FROM cell_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<CellLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CellLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<CellLog>)

    @Delete
    suspend fun deleteLog(log: CellLog)

    @Query("DELETE FROM cell_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM cell_logs")
    suspend fun clearAllLogs()

    @Query("SELECT * FROM cell_logs WHERE id = :id")
    suspend fun getLogById(id: Long): CellLog?

    @Query("SELECT COUNT(*) FROM cell_logs WHERE nodebId = :nodebId")
    suspend fun countLogsForNodebId(nodebId: Long): Int

    // Local Tower DB operations
    @Query("""
        SELECT * FROM tower_db_entries 
        WHERE (mcc = :mcc OR CAST(mcc AS INTEGER) = CAST(:mcc AS INTEGER)) 
          AND (mnc = :mnc OR CAST(mnc AS INTEGER) = CAST(:mnc AS INTEGER)) 
          AND area = :area 
          AND cid = :cid 
        LIMIT 1
    """)
    suspend fun findTower(mcc: String, mnc: String, area: Int, cid: Long): TowerDbEntry?

    @Query("""
        SELECT * FROM tower_db_entries 
        WHERE (mcc = :mcc OR CAST(mcc AS INTEGER) = CAST(:mcc AS INTEGER)) 
          AND (mnc = :mnc OR CAST(mnc AS INTEGER) = CAST(:mnc AS INTEGER)) 
          AND area = :area
    """)
    suspend fun findTowersInArea(mcc: String, mnc: String, area: Int): List<TowerDbEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTower(entry: TowerDbEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTowers(entries: List<TowerDbEntry>)

    @Query("UPDATE tower_db_entries SET address = :address WHERE mcc = :mcc AND mnc = :mnc AND area = :area AND cid = :cid")
    suspend fun updateTowerAddress(mcc: String, mnc: String, area: Int, cid: Long, address: String)

    @Query("SELECT * FROM tower_db_entries")
    fun getAllTowers(): Flow<List<TowerDbEntry>>

    @Query("SELECT * FROM tower_db_entries")
    suspend fun getAllTowersOnce(): List<TowerDbEntry>

    // Viewport-bounded page so the map never loads the whole (potentially
    // 30k+ row) imported table at once.
    @Query("""
        SELECT * FROM tower_db_entries
        WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon
        LIMIT :limit
    """)
    suspend fun getTowersInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int
    ): List<TowerDbEntry>

    @Query("SELECT COUNT(*) FROM tower_db_entries")
    fun getTowerCount(): Flow<Int>

    @Query("""
        SELECT * FROM tower_db_entries
        WHERE CAST(cid AS TEXT) LIKE '%' || :query || '%'
           OR address LIKE '%' || :query || '%'
        LIMIT :limit
    """)
    suspend fun searchTowers(query: String, limit: Int): List<TowerDbEntry>

    @Query("DELETE FROM tower_db_entries")
    suspend fun clearAllTowers()

    // Speed test history
    @Insert
    suspend fun insertSpeedTest(result: SpeedTestEntity)

    @Query("SELECT * FROM speed_tests ORDER BY timestamp DESC LIMIT 25")
    fun getSpeedTests(): Flow<List<SpeedTestEntity>>

    @Query("SELECT * FROM speed_tests ORDER BY timestamp DESC")
    suspend fun getSpeedTestsOnce(): List<SpeedTestEntity>
}
