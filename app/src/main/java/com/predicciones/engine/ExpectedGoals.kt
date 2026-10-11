package com.predicciones.engine

/** Fuerza relativa de un equipo (1.0 = media de la liga). */
data class TeamStrength(
    val attack: Double,                 // >1 marca más que la media
    val defense: Double,                // >1 concede más que la media (defensa más débil)
    val absenceImpact: Double = 0.0,    // fracción de ataque perdida por bajas clave (0.0 a 0.6)
    val fatiguePenalty: Double = 0.0,   // reducción por fatiga, viajes o poco descanso (0.0 a 0.3)
)

data class LeagueBaseline(
    val avgHomeGoals: Double = 1.50,
    val avgAwayGoals: Double = 1.20,
)

data class ExpectedGoals(val home: Double, val away: Double)

object ExpectedGoalsModel {
    private const val MIN_LAMBDA = 0.20
    private const val MAX_LAMBDA = 4.00

    fun estimate(
        home: TeamStrength,
        away: TeamStrength,
        league: LeagueBaseline = LeagueBaseline(),
        neutral: Boolean = false,
    ): ExpectedGoals {
        val baseHome = if (neutral) (league.avgHomeGoals + league.avgAwayGoals) / 2 else league.avgHomeGoals
        val baseAway = if (neutral) baseHome else league.avgAwayGoals
        val lh = baseHome * home.attack * away.defense * adjust(home)
        val la = baseAway * away.attack * home.defense * adjust(away)
        return ExpectedGoals(lh.coerceIn(MIN_LAMBDA, MAX_LAMBDA), la.coerceIn(MIN_LAMBDA, MAX_LAMBDA))
    }

    private fun adjust(t: TeamStrength): Double =
        (1.0 - t.absenceImpact.coerceIn(0.0, 0.6)) * (1.0 - t.fatiguePenalty.coerceIn(0.0, 0.3))
}
