package com.predicciones.data

import com.predicciones.engine.FinalScore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class EspnParserTest {
    @Test fun leePartidosDelScoreboard() {
        val json = JSONObject(
            """
            {"events":[{"id":"700123","date":"2026-10-12T19:00Z",
              "status":{"type":{"completed":false,"state":"pre","name":"STATUS_SCHEDULED"}},
              "competitions":[{"competitors":[
                {"homeAway":"home","score":"0","team":{"id":"359","displayName":"Al Hilal","shortDisplayName":"Hilal"}},
                {"homeAway":"away","score":"0","team":{"id":"401","displayName":"Al Nassr","shortDisplayName":"Nassr"}}]}]},
              {"id":"700124","date":"2026-10-13T19:00Z","competitions":[]}]}
            """.trimIndent(),
        )
        val list = EspnParser.fixtures(json, EspnCompetitions.all.getValue("ksa.1"), "ksa.1", "2026-10-12")
        assertEquals(1, list.size)
        val f = list[0]
        assertEquals("espn:ksa.1:700123", f.id)
        assertEquals("espn:359", f.homeId)
        assertEquals("19:00", f.timeGmt)
        assertEquals(Instant.parse("2026-10-12T19:00:00Z").toEpochMilli(), f.utcMillis)
        assertEquals("espn:ksa.1", f.competitionCode)
    }

    @Test fun leeLaTablaDeLaLiga() {
        val json = JSONObject(
            """
            {"children":[{"standings":{"entries":[
              {"team":{"id":"359","displayName":"Al Hilal"},"stats":[
                {"name":"gamesPlayed","value":10.0},{"name":"pointsFor","value":25.0},{"name":"pointsAgainst","value":8.0}]},
              {"team":{"id":"401","displayName":"Al Nassr"},"stats":[
                {"name":"gamesPlayed","value":10.0},{"name":"pointsFor","value":15.0},{"name":"pointsAgainst","value":12.0}]}
            ]}}]}
            """.trimIndent(),
        )
        val table = EspnParser.standings(json)
        assertEquals(10, table.teams.getValue("espn:359").played)
        assertEquals(25, table.teams.getValue("espn:359").gf)
        assertEquals(2.0, table.avgTeamGoals, 1e-9)
    }

    @Test fun marcadorConDescanso() {
        val json = JSONObject(
            """
            {"header":{"competitions":[{"status":{"type":{"completed":true,"name":"STATUS_FULL_TIME"}},"competitors":[
              {"homeAway":"home","score":"2","linescores":[{"value":1.0},{"value":1.0}]},
              {"homeAway":"away","score":"1","linescores":[{"value":0.0},{"value":1.0}]}]}]}}
            """.trimIndent(),
        )
        assertEquals(FinalScore(2, 1, 1, 0), EspnParser.score(json))
    }

    @Test fun marcadorConProrrogaUsaLos90Minutos() {
        val json = JSONObject(
            """
            {"header":{"competitions":[{"status":{"type":{"completed":true}},"competitors":[
              {"homeAway":"home","score":"3","linescores":[{"value":1.0},{"value":1.0},{"value":1.0}]},
              {"homeAway":"away","score":"2","linescores":[{"value":0.0},{"value":2.0},{"value":0.0}]}]}]}}
            """.trimIndent(),
        )
        assertEquals(FinalScore(2, 2, 1, 0), EspnParser.score(json))
    }

    @Test fun sinGolesPorTiempoElDescansoQuedaDesconocido() {
        val json = JSONObject(
            """
            {"header":{"competitions":[{"status":{"type":{"completed":true}},"competitors":[
              {"homeAway":"home","score":"2"},{"homeAway":"away","score":"1"}]}]}}
            """.trimIndent(),
        )
        assertEquals(FinalScore(2, 1, -1, -1), EspnParser.score(json))
    }

    @Test fun partidoNoTerminadoNoDaMarcador() {
        val json = JSONObject(
            """
            {"header":{"competitions":[{"status":{"type":{"completed":false,"state":"in"}},"competitors":[
              {"homeAway":"home","score":"1"},{"homeAway":"away","score":"0"}]}]}}
            """.trimIndent(),
        )
        assertNull(EspnParser.score(json))
    }
}
