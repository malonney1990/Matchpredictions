package com.predicciones.data

import com.predicciones.engine.ExpectedGoals
import com.predicciones.engine.ExpectedGoalsModel
import com.predicciones.engine.H2HMatch
import com.predicciones.engine.MarketProbabilities
import com.predicciones.engine.MonteCarlo
import com.predicciones.engine.OddsBook
import com.predicciones.engine.SimConfig
import com.predicciones.engine.TeamStrength
import kotlin.math.round
import kotlin.random.Random

/**
 * DATOS DE EJEMPLO, inventados y deterministas. Sirven para probar el almanaque y el motor
 * sin API. Los ids empiezan con "demo:" para poder borrarlos cuando se conecte la API real.
 */
class SampleMatchesSource : MatchesSource {

    private val leagues: Map<String, List<String>> = linkedMapOf(
        "LaLiga" to listOf("Real Madrid", "Barcelona", "Atlético de Madrid", "Sevilla", "Valencia", "Villarreal"),
        "Serie A" to listOf("Inter", "Milan", "Juventus", "Napoli", "Roma", "Lazio"),
        "Premier League" to listOf("Manchester City", "Arsenal", "Liverpool", "Chelsea", "Tottenham", "Newcastle"),
        "Ligue 1" to listOf("PSG", "Marsella", "Lyon", "Mónaco", "Lille", "Niza"),
        "Liga Portugal Betclic" to listOf("Benfica", "Porto", "Sporting CP", "Braga"),
        "Bundesliga" to listOf("Bayern Múnich", "Borussia Dortmund", "Bayer Leverkusen", "RB Leipzig", "Stuttgart", "Eintracht Frankfurt"),
        "Saudi Pro League" to listOf("Al-Hilal", "Al-Nassr", "Al-Ittihad", "Al-Ahli"),
        "Belgian Pro League" to listOf("Club Brugge", "Anderlecht", "Genk", "Union SG"),
        "Eredivisie" to listOf("Ajax", "PSV", "Feyenoord", "AZ Alkmaar"),
        "UEFA Champions League" to listOf("Real Madrid", "Manchester City", "Bayern Múnich", "PSG", "Inter", "Arsenal"),
        "UEFA Europa League" to listOf("Roma", "Lazio", "Tottenham", "Villarreal", "Porto", "Braga"),
        "UEFA Conference League" to listOf("Chelsea", "AZ Alkmaar", "Club Brugge", "Genk", "Anderlecht", "Sporting CP"),
    )

    private val times = listOf("12:00", "14:00", "15:30", "17:00", "19:00", "19:45", "20:00")

    override suspend fun fixtures(dateIso: String): List<Fixture> {
        val out = ArrayList<Fixture>()
        var leagueIndex = 0
        for ((league, teams) in leagues) {
            val rng = Random("$dateIso|$league".hashCode())
            val count = rng.nextInt(1, 3) // 1 a 2 partidos por liga y día
            val pool = teams.shuffled(rng)
            for (i in 0 until count) {
                val home = pool[i * 2]
                val away = pool[i * 2 + 1]
                out += Fixture(
                    id = "demo:$dateIso:$leagueIndex:$i",
                    leagueName = league,
                    dateIso = dateIso,
                    timeGmt = times[rng.nextInt(times.size)],
                    homeId = home,
                    homeName = home,
                    awayId = away,
                    awayName = away,
                )
            }
            leagueIndex++
        }
        return out
    }

    override suspend fun predictionRequest(fixture: Fixture): PredictionRequest {
        val home = strength(fixture.homeId)
        val away = strength(fixture.awayId)
        val xg = ExpectedGoalsModel.estimate(home, away)
        val seedA = "${fixture.id}|casaA".hashCode().toLong()
        val seedB = "${fixture.id}|casaB".hashCode().toLong()
        val books = bookmaker(xg, seedA, 0.05) + bookmaker(xg, seedB, 0.07)
        return PredictionRequest(
            matchId = fixture.id,
            fechaIso = fixture.dateIso,
            homeId = fixture.homeId,
            awayId = fixture.awayId,
            home = home,
            away = away,
            oddsFromAllBookmakers = books,
            h2h = sampleH2h(fixture),
            dataConfidence = 0.85,
        )
    }

    private fun strength(id: String): TeamStrength {
        val r = Random(id.hashCode())
        return TeamStrength(attack = 0.8 + r.nextDouble() * 0.6, defense = 0.75 + r.nextDouble() * 0.5)
    }

    private fun sampleH2h(f: Fixture): List<H2HMatch> {
        val r = Random("${f.homeId}|${f.awayId}".hashCode())
        return List(r.nextInt(0, 5)) {
            H2HMatch(
                yearsAgo = r.nextDouble() * 5,
                goalsHomeToday = r.nextInt(0, 4),
                goalsAwayToday = r.nextInt(0, 3),
                atThisVenue = r.nextBoolean(),
            )
        }
    }

    /** Una "casa" inventada: parte de los goles esperados con algo de ruido y le aplica su margen. */
    private fun bookmaker(xg: ExpectedGoals, seed: Long, margin: Double): List<OddsBook> {
        val rng = Random(seed)
        val noisy = ExpectedGoals(
            xg.home * (0.9 + rng.nextDouble() * 0.2),
            xg.away * (0.9 + rng.nextDouble() * 0.2),
        )
        val probs = MarketProbabilities.compute(MonteCarlo.simulate(noisy, SimConfig(runs = 4_000, seed = seed)))
        return probs.groupBy { it.outcome.bookKey }.values.map { group ->
            val first = group.first().outcome
            OddsBook(first.market, first.line, group.associate { it.outcome.selection to oddsFrom(it.probability, margin) })
        }
    }

    private fun oddsFrom(prob: Double, margin: Double): Double {
        val p = prob.coerceIn(0.01, 0.99)
        return (round(100.0 / (p * (1 + margin))) / 100.0).coerceAtLeast(1.01)
    }
}
