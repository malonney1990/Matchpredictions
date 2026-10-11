package com.predicciones.data

import com.predicciones.engine.FinalScore
import com.predicciones.engine.MarketType
import com.predicciones.engine.MatchPredictor
import com.predicciones.engine.OddsBook
import com.predicciones.engine.TeamStrength
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordCodecTest {
    @Test fun idaYVueltaConservaLosDatos() {
        val prediction = MatchPredictor().predict(
            matchId = "m1", fechaIso = "2026-10-11", homeId = "A", awayId = "B",
            home = TeamStrength(1.2, 0.9), away = TeamStrength(0.9, 1.1),
            oddsFromAllBookmakers = listOf(
                OddsBook(MarketType.RESULTADO_1X2, null, mapOf("1" to 1.9, "X" to 3.5, "2" to 4.2)),
            ),
            runs = 2_000,
        )
        val rec = prediction.record.copy(result = FinalScore(2, 1, 1, 0))
        val back = RecordCodec.decode(RecordCodec.encode(rec).toString())

        assertEquals(rec.matchId, back.matchId)
        assertEquals(rec.result, back.result)
        assertEquals(rec.xgBase.home, back.xgBase.home, 1e-12)
        assertEquals(rec.rawProbs.size, back.rawProbs.size)
        assertEquals(rec.rawProbs[0].outcome, back.rawProbs[0].outcome)
        assertEquals(rec.rawProbs[0].probability, back.rawProbs[0].probability, 1e-12)
        assertEquals(rec.picks.size, back.picks.size)
    }
}
