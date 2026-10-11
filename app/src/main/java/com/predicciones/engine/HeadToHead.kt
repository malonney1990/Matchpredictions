package com.predicciones.engine

import kotlin.math.pow

/**
 * Un enfrentamiento previo entre los dos equipos, visto desde HOY:
 * goalsHomeToday = goles del equipo que hoy es local; goalsAwayToday = goles del que hoy es visitante.
 * atThisVenue = true si se jugó en el mismo campo donde se jugará hoy (el local de hoy era el local).
 */
data class H2HMatch(
    val yearsAgo: Double,
    val goalsHomeToday: Int,
    val goalsAwayToday: Int,
    val atThisVenue: Boolean,
)

data class H2HConfig(
    val priorStrength: Double = 20.0,   // peso del modelo base frente al historial (en "goles equivalentes")
    val halfLifeYears: Double = 3.0,    // un partido de hace 3 años pesa la mitad
    val otherVenueWeight: Double = 0.4, // los partidos en el campo contrario pesan menos
    val maxChange: Double = 0.15,       // el historial nunca mueve los goles esperados más de ±15%
)

data class H2HResult(val xg: ExpectedGoals, val effectiveMatches: Double)

object HeadToHead {
    /** Mezcla bayesiana: pocos partidos casi no mueven el modelo; muchos y recientes, más (con tope). */
    fun adjust(base: ExpectedGoals, history: List<H2HMatch>, cfg: H2HConfig = H2HConfig()): H2HResult {
        if (history.isEmpty()) return H2HResult(base, 0.0)
        var wSum = 0.0
        var homeSum = 0.0
        var awaySum = 0.0
        for (m in history) {
            val venueWeight = if (m.atThisVenue) 1.0 else cfg.otherVenueWeight
            val w = 0.5.pow(m.yearsAgo.coerceAtLeast(0.0) / cfg.halfLifeYears) * venueWeight
            wSum += w
            homeSum += w * m.goalsHomeToday
            awaySum += w * m.goalsAwayToday
        }
        fun shrink(prior: Double, observedSum: Double): Double {
            val blended = (cfg.priorStrength * prior + observedSum) / (cfg.priorStrength + wSum)
            return blended.coerceIn(prior * (1 - cfg.maxChange), prior * (1 + cfg.maxChange))
        }
        return H2HResult(ExpectedGoals(shrink(base.home, homeSum), shrink(base.away, awaySum)), wSum)
    }
}
