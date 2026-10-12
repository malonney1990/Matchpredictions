package com.predicciones.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.predicciones.data.ApiKeyStore
import com.predicciones.data.AppDatabase
import com.predicciones.data.FootballDataClient
import com.predicciones.data.MatchesSource
import com.predicciones.data.OddsApiClient
import com.predicciones.data.PredictionRepository
import com.predicciones.data.RealMatchesSource
import com.predicciones.data.RoomRecordStore
import com.predicciones.data.SettleWorker
import com.predicciones.data.todayGmtIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PredictionApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: PredictionRepository
        private set
    lateinit var keys: ApiKeyStore
        private set
    lateinit var matchesSource: MatchesSource
        private set

    override fun onCreate() {
        super.onCreate()
        keys = ApiKeyStore(this)
        val football = FootballDataClient(keys)
        matchesSource = RealMatchesSource(football, OddsApiClient(keys, cacheDir))

        val db = Room.databaseBuilder(this, AppDatabase::class.java, "predicciones.db").build()
        repository = PredictionRepository(RoomRecordStore(db.records()), football)

        // Al abrir: reentrena con el historial y cierra los partidos que ya terminaron.
        appScope.launch {
            repository.refreshLearner()
            try {
                repository.settlePending(todayGmtIso())
            } catch (e: Exception) {
                Log.w("PredictionApp", "No se pudieron cerrar partidos", e)
            }
        }
        SettleWorker.schedule(this)
    }
}
