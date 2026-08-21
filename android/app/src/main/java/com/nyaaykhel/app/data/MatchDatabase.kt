package com.nyaaykhel.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface MatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: Match)

    @Query("SELECT * FROM matches ORDER BY createdAt DESC")
    fun getAllMatchesFlow(): Flow<List<Match>>

    @Query("SELECT * FROM matches WHERE matchId = :matchId")
    suspend fun getMatch(matchId: String): Match?

    @Query("UPDATE matches SET isExported = 1 WHERE matchId = :matchId")
    suspend fun markExported(matchId: String)

    @Query("DELETE FROM matches WHERE matchId = :matchId")
    suspend fun deleteMatch(matchId: String)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventRecord)

    @Query("SELECT * FROM events WHERE matchId = :matchId ORDER BY rowid ASC")
    suspend fun getEventsForMatch(matchId: String): List<EventRecord>

    @Query("SELECT * FROM events WHERE matchId = :matchId ORDER BY rowid ASC")
    fun getEventsForMatchFlow(matchId: String): Flow<List<EventRecord>>

    @Query("SELECT COUNT(*) FROM events WHERE matchId = :matchId")
    suspend fun countEventsForMatch(matchId: String): Int

    @Query("SELECT hash FROM events WHERE matchId = :matchId ORDER BY rowid DESC LIMIT 1")
    suspend fun getLastHash(matchId: String): String?

    @Query("DELETE FROM events WHERE matchId = :matchId")
    suspend fun deleteEventsForMatch(matchId: String)
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [Match::class, EventRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class MatchDatabase : RoomDatabase() {
    abstract fun matchDao(): MatchDao
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var INSTANCE: MatchDatabase? = null

        fun getInstance(context: android.content.Context): MatchDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MatchDatabase::class.java,
                    "nyaaykhel_matches.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
