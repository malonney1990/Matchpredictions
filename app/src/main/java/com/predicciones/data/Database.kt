package com.predicciones.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** Un MatchRecord serializado en JSON; `settled` y `fechaIso` quedan fuera para poder consultar. */
@Entity(tableName = "match_records")
data class MatchRecordEntity(
    @PrimaryKey val matchId: String,
    val fechaIso: String,
    val settled: Boolean,
    val json: String,
)

@Dao
interface MatchRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MatchRecordEntity)

    @Query("SELECT * FROM match_records WHERE matchId = :matchId")
    suspend fun get(matchId: String): MatchRecordEntity?

    @Query("SELECT * FROM match_records ORDER BY fechaIso")
    suspend fun all(): List<MatchRecordEntity>

    @Query("SELECT * FROM match_records WHERE settled = 0 AND fechaIso <= :todayIso")
    suspend fun pending(todayIso: String): List<MatchRecordEntity>
}

@Database(entities = [MatchRecordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): MatchRecordDao
}
