package com.predicciones.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class SimConfig(
    val runs: Int = 10_000,
    val seed: Long,
    val correlation: Double = 0.10,     // Poisson bivariante: lambda3 = correlation * min(lh, la)
    val scenarioSigma: Double = 0.10,   // volatilidad de intensidad/fatiga por corrida
    val firstHalfShare: Double = 0.45,  // fracción de goles que cae en la 1ª mitad
) {
    init {
        require(runs >= 1000) { "Se requieren al menos 1000 corridas" }
        require(correlation in 0.0..0.5) { "correlation debe estar entre 0 y 0.5" }
        require(firstHalfShare in 0.0..1.0) { "firstHalfShare debe estar entre 0 y 1" }
    }
}

/** Goles simulados por corrida (tiempo completo y 1ª mitad). */
class SimulationResult(
    val runs: Int,
    val seed: Long,
    val homeFt: IntArray,
    val awayFt: IntArray,
    val homeHt: IntArray,
    val awayHt: IntArray,
)

object MonteCarlo {
    /**
     * Cada corrida es un escenario distinto: factor común de ritmo (tempo) y factor propio de cada equipo
     * (alineación/fatiga alta o baja), ambos lognormales con media 1. Después se simulan los goles con
     * Poisson bivariante. Misma semilla = mismo resultado.
     */
    fun simulate(xg: ExpectedGoals, cfg: SimConfig): SimulationResult {
        val rng = Random(cfg.seed)
        val n = cfg.runs
        val homeFt = IntArray(n)
        val awayFt = IntArray(n)
        val homeHt = IntArray(n)
        val awayHt = IntArray(n)
        val s = cfg.scenarioSigma

        for (i in 0 until n) {
            val tempo = lognormalFactor(rng, s)
            val lh = xg.home * tempo * lognormalFactor(rng, s)
            val la = xg.away * tempo * lognormalFactor(rng, s)
            val l3 = cfg.correlation * min(lh, la)
            val shared = poisson(l3, rng)
            val h = poisson(lh - l3, rng) + shared
            val a = poisson(la - l3, rng) + shared
            homeFt[i] = h
            awayFt[i] = a
            homeHt[i] = binomial(h, cfg.firstHalfShare, rng)
            awayHt[i] = binomial(a, cfg.firstHalfShare, rng)
        }
        return SimulationResult(n, cfg.seed, homeFt, awayFt, homeHt, awayHt)
    }

    private fun gauss(rng: Random): Double {
        val u1 = 1.0 - rng.nextDouble()
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    private fun lognormalFactor(rng: Random, sigma: Double): Double =
        exp(sigma * gauss(rng) - sigma * sigma / 2.0)

    private fun poisson(lambda: Double, rng: Random): Int {
        if (lambda <= 0.0) return 0
        val limit = exp(-lambda)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= rng.nextDouble()
        } while (p > limit)
        return k - 1
    }

    private fun binomial(n: Int, p: Double, rng: Random): Int {
        var k = 0
        repeat(n) { if (rng.nextDouble() < p) k++ }
        return k
    }
}
