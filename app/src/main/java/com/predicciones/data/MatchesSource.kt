package com.predicciones.data

data class Fixture(
    val id: String,
    val leagueName: String,
    val dateIso: String,   // AAAA-MM-DD en GMT
    val timeGmt: String,   // HH:MM
    val homeId: String,
    val homeName: String,
    val awayId: String,
    val awayName: String,
    val competitionCode: String = "",
    val utcMillis: Long = 0L,
    val homeShort: String = "",
    val awayShort: String = "",
)

/** Partidos de un día más los avisos de las fuentes que fallaron (el resto se muestra igual). */
data class DayFixtures(val fixtures: List<Fixture>, val warnings: List<String> = emptyList())

/** Fuente de partidos del almanaque. */
interface MatchesSource {
    suspend fun fixtures(dateIso: String): List<Fixture>

    suspend fun fixturesWithWarnings(dateIso: String): DayFixtures = DayFixtures(fixtures(dateIso))

    suspend fun predictionRequest(fixture: Fixture): PredictionRequest
}
