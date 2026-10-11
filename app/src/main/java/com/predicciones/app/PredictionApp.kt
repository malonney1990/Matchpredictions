package com.predicciones.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.predicciones.data.AppDatabase
import com.predicciones.data.PredictionRepository
import com.predicciones.data.ResultsSource
import com.predicciones.data.RoomRecordStore
import com.predicciones.data.SettleWorker
import com.predicciones.data.todayGmtIso
import com.predicciones.engine.FinalScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Temporal: hasta conectar la API de resultados (paso de datos) no cierra ningún partido. */
object NoResultsSource : ResultsSource {
    override suspend fun finalScore(matchId: String): FinalScore? = null
}

class PredictionApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repository: PredictionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "predicciones.db").build()
        repository = PredictionRepository(RoomRecordStore(db.records()), NoResultsSource)

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
