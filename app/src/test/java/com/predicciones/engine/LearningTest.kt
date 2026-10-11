package com.predicciones.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTest {
    private fun record(i: Int, homeId: String, awayId: String, xg: ExpectedGoals, score: FinalScore,
                       raw: List<OutcomeProb> = emptyList()) =
        MatchRecord("m$i", "2026-01-%02d".format(i % 28 + 1), homeId, awayId, xg, raw, emptyList(), score)

    @Test fun sinHistorialNoCambiaNada() {
        val r = HeadToHead.adjust(ExpectedGoals(1.5, 1.2), emptyList())
        assertEquals(1.5, r.xg.home, 1e-12)
        assertEquals(1.2, r.xg.away, 1e-12)
        assertEquals(0.0, r.effectiveMatches, 1e-12)
    }

    @Test fun unSoloPartidoMuevePoco() {
        val r = HeadToHead.adjust(ExpectedGoals(1.5, 1.2), listOf(H2HMatch(0.0, 5, 0, true)))
        assertTrue(r.xg.home > 1.5 && r.xg.home < 1.5 * 1.15)
        assertTrue(r.xg.away < 1.2)
    }

    @Test fun elHistorialTieneTope() {
        val history = List(10) { H2HMatch(0.5, 4, 0, true) }
        val r = HeadToHead.adjust(ExpectedGoals(1.5, 1.2), history)
        assertEquals(1.5 * 1.15, r.xg.home, 1e-9)
        assertEquals(1.2 * 0.85, r.xg.away, 1e-9)
    }

    @Test fun plattCorrigeSobreconfianza() {
        val samples = List(400) { i -> 0.9 to (i % 10 < 7) }
        val q = Platt.fit(samples).apply(0.9)
        assertEquals(0.7, q, 0.03)
    }

    @Test fun settleCoincideConLaSimulacion() {
        val sim = MonteCarlo.simulate(ExpectedGoals(1.7, 1.1), SimConfig(runs = 3_000, seed = 11))
        for (op in MarketProbabilities.compute(sim)) {
            var hits = 0
            for (i in 0 until sim.runs) {
                if (Settle.hit(op.outcome, FinalScore(sim.homeFt[i], sim.awayFt[i], sim.homeHt[i], sim.awayHt[i]))) hits++
            }
            assertEquals(op.probability, hits.toDouble() / sim.runs, 1e-12)
        }
    }

    @Test fun elEquipoQueMarcaMasDeLoEsperadoSubeSuFactor() {
        val history = List(40) { i -> record(i, "A", "B", ExpectedGoals(1.5, 1.2), FinalScore(3, 1, 1, 0)) }
        val state = Learner.fit(history)
        val attackA = state.teamAttack["A"] ?: 1.0
        assertTrue(attackA > 1.0 && attackA <= 1.10 + 1e-12)
        val adjusted = state.adjust(ExpectedGoals(1.5, 1.2), "A", "B")
        assertTrue(adjusted.home > 1.5)
    }

    @Test fun conPocosPartidosNoSeCalibra() {
        val raw = listOf(OutcomeProb(Outcome(MarketType.GOLES_PARTIDO, "Más", 2.5), 0.9, 0.0))
        val history = List(10) { i -> record(i, "A", "B", ExpectedGoals(1.5, 1.2), FinalScore(2, 1, 1, 0), raw) }
        assertTrue(Learner.fit(history).calibrators.isEmpty())
    }

    @Test fun conSuficientesPartidosBajaLaSobreconfianza() {
        val raw = listOf(OutcomeProb(Outcome(MarketType.GOLES_PARTIDO, "Más", 2.5), 0.9, 0.0))
        val history = List(40) { i ->
            val score = if (i % 10 < 7) FinalScore(2, 1, 1, 0) else FinalScore(1, 0, 0, 0)
            record(i, "A", "B", ExpectedGoals(1.5, 1.2), score, raw)
        }
        val state = Learner.fit(history)
        val q = state.calibrators[MarketType.GOLES_PARTIDO]?.apply(0.9) ?: 0.9
        assertTrue(q < 0.85 && q > 0.6)
        assertFalse(state.calibrators.isEmpty())
    }

    @Test fun mergeAllPromediaLasCasas() {
        val a = OddsBook(MarketType.RESULTADO_1X2, null, mapOf("1" to 2.0, "X" to 3.4, "2" to 3.8))
        val b = OddsBook(MarketType.RESULTADO_1X2, null, mapOf("1" to 2.2, "X" to 3.2, "2" to 3.6))
        val merged = Odds.mergeAll(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(2.1, merged[0].odds["1"] ?: 0.0, 1e-9)
    }
}
