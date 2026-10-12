package com.predicciones.data

import com.predicciones.engine.OddsBook
import java.io.IOException

/** Partidos, tablas e historial de football-data.org; cuotas de The Odds API (todas las casas, promediadas). */
class RealMatchesSource(
    private val football: FootballDataClient,
    private val odds: OddsApiClient,
) : MatchesSource {

    override suspend fun fixtures(dateIso: String): List<Fixture> = football.fixtures(dateIso)

    override suspend fun predictionRequest(fixture: Fixture): PredictionRequest {
        val table = football.table(fixture.competitionCode)
        val home = StandingsParser.strength(fixture.homeId, table)
        val away = StandingsParser.strength(fixture.awayId, table)
        val matchId = fixture.id.removePrefix("fd:").toLong()

        val h2h = try {
            football.headToHead(matchId, fixture.homeId, System.currentTimeMillis())
        } catch (e: IOException) {
            emptyList()
        }

        val notes = ArrayList<String>()
        var books: List<OddsBook> = emptyList()
        val sportKey = Competitions.all[fixture.competitionCode]?.oddsSportKey
        if (sportKey != null) {
            try {
                val event = EventMatcher.find(odds.events(sportKey), fixture)
                if (event == null) {
                    notes += "No hay cuotas de este partido en The Odds API (aún no publicadas o el nombre no coincide)."
                } else {
                    books = OddsParser.books(event)
                    if (books.isEmpty()) notes += "El partido no trae cuotas de 1X2, goles o hándicap."
                }
            } catch (e: IOException) {
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
