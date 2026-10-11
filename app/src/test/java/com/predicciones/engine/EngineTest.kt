package com.predicciones.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val strong = TeamStrength(attack = 1.4, defense = 0.8)
    private val weak = TeamStrength(attack = 0.8, defense = 1.3)

    private val books = listOf(
        OddsBook(MarketType.RESULTADO_1X2, null, mapOf("1" to 1.55, "X" to 4.20, "2" to 6.00)),
        OddsBook(MarketType.GOLES_PARTIDO, 1.5, mapOf("Más" to 1.30, "Menos" to 3.40)),
        OddsBook(MarketType.GOLES_PARTIDO, 3.5, mapOf("Más" to 2.80, "Menos" to 1.45)),
        OddsBook(MarketType.HANDICAP, 1.5, mapOf("Local" to 1.12, "Visitante" to 6.50)),
    )

    @Test fun vigorishSumaUno() {
        val fair = Odds.removeVigorish(mapOf("1" to 1.80, "X" to 3.60, "2" to 4.50))
        assertEquals(1.0, fair.values.sum(), 1e-9)
    }

    @Test fun probabilidades1X2SumanUno() {
        val sim = MonteCarlo.simulate(ExpectedGoalsModel.estimate(strong, weak), SimConfig(runs = 5_000, seed = 42))
        val sum = MarketProbabilities.compute(sim)
            .filter { it.outcome.market == MarketType.RESULTADO_1X2 }
            .sumOf { it.probability }
        assertEquals(1.0, sum, 1e-9)
    }

    @Test fun mismaSemillaMismoResultado() {
        val xg = ExpectedGoals(1.6, 1.1)
        val a = MonteCarlo.simulate(xg, SimConfig(runs = 2_000, seed = 7))
        val b = MonteCarlo.simulate(xg, SimConfig(runs = 2_000, seed = 7))
        assertArrayEquals(a.homeFt, b.homeFt)
        assertArrayEquals(a.awayFt, b.awayFt)
    }

    @Test fun mediaSimuladaCercaDeLoEsperado() {
        val sim = MonteCarlo.simulate(ExpectedGoals(1.8, 0.9), SimConfig(runs = 50_000, seed = 1))
        assertEquals(1.8, sim.homeFt.average(), 0.05)
        assertEquals(0.9, sim.awayFt.average(), 0.05)
    }

    @Test fun picksRespetanFormulasYTamano() {
        val sim = MonteCarlo.simulate(ExpectedGoalsModel.estimate(strong, weak), SimConfig(runs = 10_000, seed = 3))
        val picks = PickSelector.select(MarketProbabilities.compute(sim), books)
        assertEquals(3, picks.size)
        for (p in picks) {
            assertEquals(p.modelProb * (p.odds - 1) - (1 - p.modelProb), p.ev, 1e-12)
            assertEquals((p.modelProb - p.impliedProb) * 100, p.edgePp, 1e-9)
            if (!p.bajoUmbral) assertTrue(p.modelProb > 0.70)
        }
    }

    @Test fun sinCuotasNoHayPicks() {
        val sim = MonteCarlo.simulate(ExpectedGoals(1.5, 1.2), SimConfig(runs = 2_000, seed = 5))
        assertTrue(PickSelector.select(MarketProbabilities.compute(sim), emptyList()).isEmpty())
    }

    @Test fun encabezadoConFormatoEstricto() {
        assertEquals(
            "Real Madrid – Getafe | 2026-10-12 | 19:00 GMT",
            PickFormatter.header("Real Madrid", "Getafe", "2026-10-12", "19:00"),
        )
    }
}
