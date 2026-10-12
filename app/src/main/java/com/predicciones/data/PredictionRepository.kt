package com.predicciones.data

import com.predicciones.engine.FinalScore
import com.predicciones.engine.H2HMatch
import com.predicciones.engine.LeagueBaseline
import com.predicciones.engine.LearnerState
import com.predicciones.engine.Learner
import com.predicciones.engine.MatchPrediction
import com.predicciones.engine.MatchPredictor
import com.predicciones.engine.OddsBook
import com.predicciones.engine.TeamStrength
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/** Fecha de hoy en GMT, formato AAAA-MM-DD (requiere minSdk 26 o desugaring). */
fun todayGmtIso(): String = LocalDate.now(ZoneOffset.UTC).toString()

/**
 * Fuente del marcador final. Devuelve null si el partido aún no terminó o no hay dato;
 * lanza IOException si falla la red (se reintenta luego). La implementación real con la API va en el paso de datos.
 */
interface ResultsSource {
    suspend fun finalScore(matchId: String): FinalScore?
}

data class PredictionRequest(
    val matchId: String,
    val fechaIso: String,
    val homeId: String,
    val awayId: String,
    val home: TeamStrength,
    val away: TeamStrength,
    val oddsFromAllBookmakers: List<OddsBook>,
    val league: LeagueBaseline = LeagueBaseline(),
    val neutral: Boolean = false,
    val h2h: List<H2HMatch> = emptyList(),
    val dataConfidence: Double = 1.0,
    val runs: Int = 10_000,
    val notes: List<String> = emptyList(),   // avisos para mostrar en pantalla (cuotas ausentes, etc.)
)

class PredictionRepository(
    private val store: RecordStore,
    private val results: ResultsSource,
    private val predictor: MatchPredictor = MatchPredictor(),
) {
    private val _learner = MutableStateFlow(LearnerState.EMPTY)

    /** Lo aprendido hasta hoy; se actualiza solo. */
    val learner: StateFlow<LearnerState> = _learner.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    /** Reentrena con todo el historial. Llamar al abrir la app (PredictionApp lo hace). */
    suspend fun refreshLearner() {
        try {
            val history = store.all()
            _learner.value = withContext(Dispatchers.Default) { Learner.fit(history) }
        } finally {
            ready.complete(Unit)
        }
    }

    /** Predice con lo aprendido y guarda el récord solo. Nunca pisa un partido ya cerrado. */
    suspend fun predictAndSave(req: PredictionRequest): MatchPrediction {
        ready.await()
        val prediction = withContext(Dispatchers.Default) {
            predictor.predict(
                matchId = req.matchId,
                fechaIso = req.fechaIso,
                homeId = req.homeId,
                awayId = req.awayId,
                home = req.home,
                away = req.away,
                oddsFromAllBookmakers = req.oddsFromAllBookmakers,
                league = req.league,
                neutral = req.neutral,
                h2h = req.h2h,
                learner = _learner.value,
                dataConfidence = req.dataConfidence,
                runs = req.runs,
            )
        }
        if (store.get(req.matchId)?.result == null) store.upsert(prediction.record)
        return prediction
    }

    /** Completa con el marcador final los récords pendientes y reentrena si hubo cambios. Devuelve cuántos cerró. */
    suspend fun settlePending(todayIso: String): Int {
        var settled = 0
        for (rec in store.pending(todayIso)) {
            val score: FinalScore? = try {
                results.finalScore(rec.matchId)
            } catch (e: IOException) {
                null
            }
            if (score == null) continue
            store.upsert(rec.copy(result = score))
            settled++
        }
        if (settled > 0) refreshLearner()
        return settled
    }
}
