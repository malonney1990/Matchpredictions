package com.predicciones.data

import com.predicciones.engine.H2HMatch
import com.predicciones.engine.OddsBook
import kotlinx.coroutines.CancellationException

/**
 * Partidos de las 12 competiciones: 8 por football-data.org y 4 por ESPN.
 * Cuotas de The Odds API (todas las casas de eu y uk, promediadas).
 */
class RealMatchesSource(
    private val football: FootballDataClient,
    private val espn: EspnClient,
    private val odds: OddsApiClient,
) : MatchesSource {

    override suspend fun fixtures(dateIso: String): List<Fixture> = fixturesWithWarnings(dateIso).fixtures

    private suspend fun <T> safely(label: String, warnings: MutableList<String>, block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "$label: ${e.message ?: "sin respuesta"}"
            emptyList()
        }

    /** Si una fuente falla se avisa y se muestran igual los partidos de las demás. */
    override suspend fun fixturesWithWarnings(dateIso: String): DayFixtures {
        val warnings = ArrayList<String>()
        val all = ArrayList<Fixture>()
        all += safely("football-data.org", warnings) { football.fixtures(dateIso) }
        for ((code, comp) in Competitions.all) {
            if (comp.espnSlug == null) continue
            all += safely("${comp.name} (ESPN)", warnings) { espn.fixtures(code, comp, dateIso) }
        }
        val order = Competitions.all.keys.toList()
        val sorted = all.sortedWith(compareBy<Fixture>({ order.indexOf(it.competitionCode) }, { it.utcMillis }))
        return DayFixtures(sorted, warnings)
    }

    override suspend fun predictionRequest(fixture: Fixture): PredictionRequest {
        val notes = ArrayList<String>()
        val comp = Competitions.all[fixture.competitionCode]
        val slug = comp?.espnSlug

        val table = if (slug != null) {
            try {
                espn.table(slug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "Sin resultados recientes de ESPN (${e.message}): se usa fuerza neutral para los dos equipos."
                CompetitionTable(emptyMap(), 0.0, 0.0)
            }
        } else {
            football.table(fixture.competitionCode)
        }
        val home = StandingsParser.strength(fixture.homeId, table)
        val away = StandingsParser.strength(fixture.awayId, table)

        val h2h: List<H2HMatch> = if (slug != null) {
            notes += "Historial directo no disponible en esta competición (fuente ESPN)."
            emptyList()
        } else {
            try {
                football.headToHead(fixture.id.removePrefix("fd:").toLong(), fixture.homeId, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

        var books: List<OddsBook> = emptyList()
        val sportKey = comp?.oddsSportKey
        if (sportKey != null) {
            try {
                val event = EventMatcher.find(odds.events(sportKey), fixture)
                if (event == null) {
                    notes += "No hay cuotas de este partido en The Odds API (aún no publicadas, o la liga o el nombre no coinciden)."
                } else {
                    books = OddsParser.books(event)
                    if (books.isEmpty()) notes += "El partido no trae cuotas de 1X2, goles o hándicap."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "Cuotas no disponibles: ${e.message}"
            }
        }
        if (books.isNotEmpty()) {
            notes += "Cuotas: promedio de las casas disponibles. Solo 1X2, total de goles y hándicap (sin 1ª mitad ni goles exactos)."
        }

        return PredictionRequest(
            matchId = fixture.id,
            fechaIso = fixture.dateIso,
            homeId = fixture.homeId,
            awayId = fixture.awayId,
            home = home,
            away = away,
            oddsFromAllBookmakers = books,
            league = table.baseline(),
            h2h = h2h,
            dataConfidence = 0.8, // sin alineaciones ni lesiones
            notes = notes,
        )
    }
}
