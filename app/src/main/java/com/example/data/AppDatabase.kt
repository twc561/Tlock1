package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [CellLog::class, TowerDbEntry::class, SpeedTestEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao

    /**
     * Flushes the write-ahead log into the main database file. Must be called before
     * copying the file for a backup, otherwise recent writes are missing from the copy.
     * Call from a background thread.
     */
    fun checkpoint() {
        query("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 -> v3: capacity/geometry columns on cell_logs and the speed_tests
         * table. A real migration, so an upgrade never wipes the (potentially
         * large, hand-collected) tower and log history.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cell_logs ADD COLUMN timingAdvanceMeters REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cell_logs ADD COLUMN caCarrierCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE cell_logs ADD COLUMN aggregateBandwidthKhz INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `speed_tests` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`downloadMbps` REAL NOT NULL, " +
                            "`latencyMs` INTEGER NOT NULL, " +
                            "`label` TEXT NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "towerlock_database"
                )
                .addCallback(DatabaseCallback(scope))
                .addMigrations(MIGRATION_2_3)
                // Destructive fallback only fires for paths with no migration
                // (pre-v2 installs); v2 -> v3 uses MIGRATION_2_3 above.
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }

        /**
         * Closes the singleton connection so the underlying file can be replaced
         * (e.g. during a restore). The next [getDatabase] call reopens it.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateDatabase(database.cellDao())
                    }
                }
            }

            suspend fun populateDatabase(cellDao: CellDao) {
                // Populate some mock tower entries near US cities for first-turn demonstration
                // T-Mobile: MCC 310, MNC 260
                // Verizon: MCC 311, MNC 480
                // AT&T: MCC 310, MNC 410
                cellDao.insertTowers(
                    listOf(
                        TowerDbEntry(
                            radio = "NR",
                            mcc = "310",
                            mnc = "260",
                            area = 10452, // TAC
                            cid = 1234567, // nci
                            lat = 37.7749, // San Francisco
                            lon = -122.4194,
                            range = 500,
                            address = "Market St, San Francisco, CA"
                        ),
                        TowerDbEntry(
                            radio = "LTE",
                            mcc = "310",
                            mnc = "260",
                            area = 24500, // TAC
                            cid = 987654, // ci
                            lat = 40.7128, // New York
                            lon = -74.0060,
                            range = 250,
                            address = "Broadway, New York, NY"
                        ),
                        TowerDbEntry(
                            radio = "NR",
                            mcc = "310",
                            mnc = "260",
                            area = 5510, // TAC
                            cid = 456789, // nci
                            lat = 34.0522, // Los Angeles
                            lon = -118.2437,
                            range = 1000,
                            address = "Wilshire Blvd, Los Angeles, CA"
                        ),
                        // Seattle (T-Mobile headquarters region)
                        TowerDbEntry(
                            radio = "NR",
                            mcc = "310",
                            mnc = "260",
                            area = 1024,
                            cid = 88888,
                            lat = 47.6062,
                            lon = -122.3321,
                            range = 150,
                            address = "4th Ave, Seattle, WA"
                        ),
                        // Mountain View (Googleplex - default location for emulator GPS)
                        // Matches default mock cellular state: MCC 310, MNC 260, TAC 1024, Cell ID 1234567
                        TowerDbEntry(
                            radio = "NR",
                            mcc = "310",
                            mnc = "260",
                            area = 1024,
                            cid = 1234567,
                            lat = 37.4220,
                            lon = -122.0841,
                            range = 200,
                            address = "Googleplex, Mountain View, CA"
                        )
                    )
                )
            }
        }
    }
}
