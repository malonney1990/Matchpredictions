package com.predicciones.data

import com.predicciones.engine.FinalScore
import com.predicciones.engine.MarketType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ParsersTest {
    @Test fun nombresDeEquiposCoinciden() {
        assertEquals(1.0, TeamNames.similarity("Manchester City FC", "Man City", "Manchester City"), 1e-9)
        assertEquals(1.0, TeamNames.similarity("FC Internazionale Milano", "Inter", "Inter Milan"), 1e-9)
        assertEquals(1.0, TeamNames.similarity("Brighton & Hove Albion FC", "", "Brighton and Hove Albion"), 1e-9)
        assertEquals(1.0, TeamNames.similarity("Club Atlético de Madrid", "Atleti", "Atlético Madrid"), 1e-9)
        assertEquals(0.0, TeamNames.similarity("Arsenal FC", "Arsenal", "Chelsea"), 1e-9)
    }

    @Test fun parseaCuotasDeUnaCasa() {
        val event = JSONObject(
            """
            {"home_team":"Arsenal","away_team":"Chelsea","bookmakers":[{"key":"b1","markets":[
              {"key":"h2h","outcomes":[{"name":"Arsenal","price":2.0},{"name":"Chelsea","price":3.8},{"name":"Draw","price":3.4}]},
              {"key":"totals","outcomes":[
                {"name":"Over","price":1.9,"point":2.5},{"name":"Under","price":1.95,"point":2.5},
                {"name":"Over","price":1.5,"point":1.75},{"name":"Under","price":2.6,"point":1.75}]},
              {"key":"spreads","outcomes":[{"name":"Arsenal","price":2.1,"point":-0.5},{"name":"Chelsea","price":1.8,"point":0.5}]}
            ]}]}
            """.trimIndent(),
        )
        val books = OddsParser.books(event)
        assertEquals(3, books.size) // la línea 1.75 se ignora
        val x12 = books.first { it.market == MarketType.RESULTADO_1X2 }
        assertEquals(3.4, x12.odds["X"] ?: 0.0, 1e-9)
        val totals = books.first { it.market == MarketType.GOLES_PARTIDO }
        assertEquals(2.5, totals.line ?: 0.0, 1e-9)
        val hc = books.first { it.market == MarketType.HANDICAP }
        assertEquals(-0.5, hc.line ?: 0.0, 1e-9)
        assertEquals(2.1, hc.odds["Local"] ?: 0.0, 1e-9)
    }

    @Test fun calculaFuerzasDesdeLaTabla() {
        val json = JSONObject(
            """
            {"standings":[
              {"type":"TOTAL","table":[
                {"playedGames":10,"goalsFor":20,"goalsAgainst":10,"team":{"id":1}},
                {"playedGames":10,"goalsFor":10,"goalsAgainst":20,"team":{"id":2}}]},
              {"type":"HOME","table":[
                {"playedGames":5,"goalsFor":12,"goalsAgainst":4,"team":{"id":1}},
                {"playedGames":5,"goalsFor":6,"goalsAgainst":8,"team":{"id":2}}]},
              {"type":"AWAY","table":[
                {"playedGames":5,"goalsFor":8,"goalsAgainst":6,"team":{"id":1}},
                {"playedGames":5,"goalsFor":4,"goalsAgainst":12,"team":{"id":2}}]}
            ]}
            """.trimIndent(),
        )
        val table = StandingsParser.parse(json)
        assertEquals(1.8, table.avgHome, 1e-9)
        assertEquals(1.2, table.avgAway, 1e-9)
        val s = StandingsParser.strength("1", table)
        assertEquals(1.2083333, s.attack, 1e-6)
        assertEquals(0.7916667, s.defense, 1e-6)
        val unknown = StandingsParser.strength("99", table)
        assertEquals(1.0, unknown.attack, 1e-9)
    }

    @Test fun marcadorFinalSoloSiTermino() {
        val done = JSONObject(
            """{"id":1,"status":"FINISHED","score":{"fullTime":{"home":2,"away":1},"halfTime":{"home":1,"away":0}}}""",
        )
        assertEquals(FinalScore(2, 1, 1, 0), ScoreParser.parse(done))
        val pending = JSONObject("""{"id":1,"status":"SCHEDULED","score":{"fullTime":{"home":null,"away":null}}}""")
        assertNull(ScoreParser.parse(pending))
        val extra = JSONObject(
            """{"id":1,"status":"FINISHED","score":{"regularTime":{"home":2,"away":2},"fullTime":{"home":3,"away":2},"halfTime":{"home":1,"away":1}}}""",
        )
        assertEquals(FinalScore(2, 2, 1, 1), ScoreParser.parse(extra))
    }

    @Test fun historialSeInterpretaDesdeHoy() {
        val json = JSONObject(
            """
            {"matches":[
              {"id":10,"utcDate":"2024-10-12T19:00:00Z","homeTeam":{"id":57},"awayTeam":{"id":61},"score":{"fullTime":{"home":2,"away":1}}},
              {"id":11,"utcDate":"2024-04-12T19:00:00Z","homeTeam":{"id":61},"awayTeam":{"id":57},"score":{"fullTime":{"home":0,"away":3}}},
              {"id":99,"utcDate":"2026-10-12T19:00:00Z","homeTeam":{"id":57},"awayTeam":{"id":61},"score":{"fullTime":{"home":null,"away":null}}}
            ]}
            """.trimIndent(),
        )
        val now = Instant.parse("2026-10-12T19:00:00Z").toEpochMilli()
        val list = H2hParser.parse(json, 99L, "57", now)
        assertEquals(2, list.size)
        assertEquals(2, list[0].goalsHomeToday)
        assertTrue(list[0].atThisVenue)
        assertEquals(3, list[1].goalsHomeToday)
        assertEquals(0, list[1].goalsAwayToday)
        assertTrue(!list[1].atThisVenue)
        assertNotNull(list[0].yearsAgo)
        assertEquals(2.0, list[0].yearsAgo, 0.05)
    }
}
