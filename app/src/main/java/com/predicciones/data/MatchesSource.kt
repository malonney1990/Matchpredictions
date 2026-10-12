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

/** Fuente de partidos del almanaque. */
interface MatchesSource {
    suspend fun fixtures(dateIso: String): List<Fixture>
    suspend fun predictionRequest(fixture: Fixture): PredictionRequest
}
