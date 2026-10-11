package com.predicciones.data

import com.predicciones.engine.FinalScore
import com.predicciones.engine.MarketType
import com.predicciones.engine.MatchRecord
import com.predicciones.engine.OddsBook
import com.predicciones.engine.TeamStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeStore : RecordStore {
    val map = LinkedHashMap<String, MatchRecord>()
    override suspend fun upsert(r: MatchRecord) { map[r.matchId] = r }
    override suspend fun get(matchId: String): MatchRecord? = map[matchId]
    override suspend fun all(): List<MatchRecord> = map.values.toList()
    override suspend fun pending(todayIso: String): List<MatchRecord> =
        map.values.filter { it.result == null && it.fechaIso <= todayIso }
}

private class FakeResults(var score: FinalScore?) : ResultsSource {
    override suspend fun finalScore(matchId: String): FinalScore? = score
}

class RepositoryTest {
    private val request = PredictionRequest(
        matchId = "m1", fechaIso = "2026-10-11", homeId = "A", awayId = "B",
        home = TeamStrength(1.2, 0.9), away = TeamStrength(0.9, 1.1),
        oddsFromAllBookmakers = listOf(
            OddsBook(MarketType.RESULTADO_1X2, null, mapOf("1" to 1.9, "X" to 3.5, "2" to 4.2)),
        ),
        runs = 2_000,
    )

    @Test fun guardaSoloCierraSoloYAprende() = runBlocking<Unit> {
        val store = FakeStore()
        val results = FakeResults(null)
        val repo = PredictionRepository(store, results)
        repo.refreshLearner()

        repo.predictAndSave(request)
        assertNotNull(store.map["m1"])
        assertNull(store.map["m1"]?.result)

        assertEquals(0, repo.settlePending("2026-10-12"))   // aún no hay marcador
        results.score = FinalScore(2, 1, 1, 0)
        assertEquals(1, repo.settlePending("2026-10-12"))
        assertEquals(FinalScore(2, 1, 1, 0), store.map["m1"]?.result)
        assertEquals(1, repo.learner.value.stats.matches)
    }

    @Test fun noPisaUnPartidoYaCerrado() = runBlocking<Unit> {
        val store = FakeStore()
        val results = FakeResults(FinalScore(0, 0, 0, 0))
        val repo = PredictionRepository(store, results)
        repo.refreshLearner()
        repo.predictAndSave(request)
        repo.settlePending("2026-10-12")

        repo.predictAndSave(request)   // se vuelve a abrir el partido
        assertEquals(FinalScore(0, 0, 0, 0), store.map["m1"]?.result)
    }
}
