package com.predicciones.data

import android.util.Log
import com.predicciones.engine.MatchRecord
import org.json.JSONException

/** Almacén de récords. La app usa Room; las pruebas usan uno en memoria. */
interface RecordStore {
    suspend fun upsert(r: MatchRecord)
    suspend fun get(matchId: String): MatchRecord?
    suspend fun all(): List<MatchRecord>
    suspend fun pending(todayIso: String): List<MatchRecord>
}

class RoomRecordStore(private val dao: MatchRecordDao) : RecordStore {
    override suspend fun upsert(r: MatchRecord) {
        dao.upsert(RecordCodec.toEntity(r))
    }

    override suspend fun get(matchId: String): MatchRecord? = dao.get(matchId)?.let { decodeOrNull(it) }

    override suspend fun all(): List<MatchRecord> = dao.all().mapNotNull { decodeOrNull(it) }

    override suspend fun pending(todayIso: String): List<MatchRecord> =
        dao.pending(todayIso).mapNotNull { decodeOrNull(it) }

    // Un registro dañado se omite en vez de tumbar la app.
    private fun decodeOrNull(e: MatchRecordEntity): MatchRecord? = try {
        RecordCodec.fromEntity(e)
    } catch (ex: JSONException) {
        Log.w("RoomRecordStore", "Registro ${e.matchId} ilegible, se omite", ex)
        null
    }
}
