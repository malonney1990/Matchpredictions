package com.predicciones.engine

import java.util.Locale
import kotlin.math.sqrt

enum class MarketType(val etiqueta: String) {
    RESULTADO_1X2("1X2"),               // incluye el empate como selección "X"
    GOLES_1T("Altas/Bajas 1ª mitad"),
    GOLES_PARTIDO("Altas/Bajas partido completo"),
    HANDICAP("Hándicap/Asiático"),
    GOLES_EXACTOS("Goles exactos"),     // total de goles del partido
}

fun makeBookKey(market: MarketType, line: Double?): String = "${market.name}|${line ?: "-"}"

/**
 * Una selección de un mercado. En HANDICAP, `line` es la línea del local:
 * "Local" +1.5 y "Visitante" -1.5 comparten la misma línea de mercado (1.5).
 */
data class Outcome(val market: MarketType, val selection: String, val line: Double? = null) {
    val bookKey: String get() = makeBookKey(market, line)

    fun etiqueta(): String {
        val l = line
        return when (market) {
            MarketType.RESULTADO_1X2 -> when (selection) {
                "1" -> "Local (1)"
                "X" -> "Empate (X)"
                else -> "Visitante (2)"
            }
            MarketType.GOLES_1T, MarketType.GOLES_PARTIDO -> "$selection de ${num(l ?: 0.0)}"
            MarketType.HANDICAP ->
                if (selection == "Local") "Local ${signed(l ?: 0.0)}" else "Visitante ${signed(-(l ?: 0.0))}"
            MarketType.GOLES_EXACTOS -> "$selection goles"
        }
    }

    private fun num(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f", v)
}

/** Probabilidad Monte Carlo de una selección, con la mitad del intervalo de confianza al 95%. */
data class OutcomeProb(val outcome: Outcome, val probability: Double, val ci95: Double)

object MarketProbabilities {
    private val HALF_TIME_LINES = listOf(0.5, 1.5)
    private val FULL_TIME_LINES = listOf(1.5, 2.5, 3.5)
    private val HANDICAP_LINES = listOf(-1.5, -0.5, 0.5, 1.5) // solo medias líneas (sin push)

    fun compute(sim: SimulationResult): List<OutcomeProb> {
        val n = sim.runs
        val out = ArrayList<OutcomeProb>()

        fun count(pred: (Int) -> Boolean): Int {
            var c = 0
            for (i in 0 until n) if (pred(i)) c++
            return c
        }

        fun add(o: Outcome, hits: Int) {
            val p = hits.toDouble() / n
            out += OutcomeProb(o, p, 1.96 * sqrt(p * (1 - p) / n))
        }

        add(Outcome(MarketType.RESULTADO_1X2, "1"), count { sim.homeFt[it] > sim.awayFt[it] })
        add(Outcome(MarketType.RESULTADO_1X2, "X"), count { sim.homeFt[it] == sim.awayFt[it] })
        add(Outcome(MarketType.RESULTADO_1X2, "2"), count { sim.homeFt[it] < sim.awayFt[it] })

        for (line in HALF_TIME_LINES) {
            add(Outcome(MarketType.GOLES_1T, "Más", line), count { sim.homeHt[it] + sim.awayHt[it] > line })
            add(Outcome(MarketType.GOLES_1T, "Menos", line), count { sim.homeHt[it] + sim.awayHt[it] < line })
        }

        for (line in FULL_TIME_LINES) {
            add(Outcome(MarketType.GOLES_PARTIDO, "Más", line), count { sim.homeFt[it] + sim.awayFt[it] > line })
            add(Outcome(MarketType.GOLES_PARTIDO, "Menos", line), count { sim.homeFt[it] + sim.awayFt[it] < line })
        }

        for (h in HANDICAP_LINES) {
            add(Outcome(MarketType.HANDICAP, "Local", h), count { sim.homeFt[it] + h > sim.awayFt[it] })
            add(Outcome(MarketType.HANDICAP, "Visitante", h), count { sim.homeFt[it] + h < sim.awayFt[it] })
        }

        for (g in 0..4) {
            add(Outcome(MarketType.GOLES_EXACTOS, g.toString()), count { sim.homeFt[it] + sim.awayFt[it] == g })
        }
        add(Outcome(MarketType.GOLES_EXACTOS, "5+"), count { sim.homeFt[it] + sim.awayFt[it] >= 5 })

        return out
    }
}
