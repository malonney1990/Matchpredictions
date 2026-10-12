package com.predicciones.data

import com.predicciones.engine.FinalScore
import com.predicciones.engine.H2HMatch
import com.predicciones.engine.LeagueBaseline
import com.predicciones.engine.TeamStrength
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** espnSlug != null: la competición se lee de ESPN (no oficial, sin clave); si es null, de football-data.org. */
data class Comp(val name: String, val oddsSportKey: String, val espnSlug: String? = null)

/** Las 12 competiciones: 8 por football-data.org (plan gratis) y 4 por ESPN. */
object Competitions {
    val all: Map<String, Comp> = linkedMapOf(
        "PD" to Comp("LaLiga", "soccer_spain_la_liga"),
        "SA" to Comp("Serie A", "soccer_italy_serie_a"),
        "PL" to Comp("Premier League", "soccer_epl"),
        "FL1" to Comp("Ligue 1", "soccer_france_ligue_one"),
        "PPL" to Comp("Liga Portugal", "soccer_portugal_primeira_liga"),
        "BL1" to Comp("Bundesliga", "soccer_germany_bundesliga"),
        "DED" to Comp("Eredivisie", "soccer_netherlands_eredivisie"),
        "CL" to Comp("UEFA Champions League", "soccer_uefa_champs_league"),
        "KSA" to Comp("Saudi Pro League", "soccer_saudi_arabia_pro_league", "ksa.1"),
        "BEL" to Comp("Belgian Pro League", "soccer_belgium_first_div", "bel.1"),
        "UEL" to Comp("UEFA Europa League", "soccer_uefa_europa_league", "uefa.europa"),
        "UECL" to Comp("UEFA Conference League", "soccer_uefa_europa_conf_league", "uefa.europa.conf"),
    )

    /** Códigos que se piden a football-data.org. */
    val footballData: List<String> = all.filter { it.value.espnSlug == null }.keys.toList()
}

data class TeamTable(val played: Int, val gf: Int, val ga: Int)

class CompetitionTable(val teams: Map<String, TeamTable>, val avgHome: Double, val avgAway: Double) {
    val avgTeamGoals: Double get() = (avgHome + avgAway) / 2

    fun baseline(): LeagueBaseline =
        if (avgHome > 0.0 && avgAway > 0.0) LeagueBaseline(avgHome, avgAway) else LeagueBaseline()
}

object StandingsParser {
    fun parse(json: JSONObject): CompetitionTable {
        val standings = json.optJSONArray("standings") ?: return CompetitionTable(emptyMap(), 0.0, 0.0)
        val total = HashMap<String, TeamTable>()
        var homeGoals = 0
        var homeGames = 0
        var awayGoals = 0
        var awayGames = 0
        for (i in 0 until standings.length()) {
            val s = standings.getJSONObject(i)
            val type = s.optString("type")
            val rows = s.optJSONArray("table") ?: continue
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                val played = row.optInt("playedGames")
                val gf = row.optInt("goalsFor")
                val ga = row.optInt("goalsAgainst")
                val teamId = row.optJSONObject("team")?.optInt("id")?.toString() ?: continue
                when (type) {
                    "TOTAL" -> {
                        val prev = total[teamId]
                        total[teamId] = TeamTable(
                            (prev?.played ?: 0) + played,
                            (prev?.gf ?: 0) + gf,
                            (prev?.ga ?: 0) + ga,
                        )
                    }
                    "HOME" -> {
                        homeGoals += gf
                        homeGames += played
                    }
                    "AWAY" -> {
                        awayGoals += gf
                        awayGames += played
                    }
                }
            }
        }
        var avgHome = if (homeGames > 0) homeGoals.toDouble() / homeGames else 0.0
        var avgAway = if (awayGames > 0) awayGoals.toDouble() / awayGames else 0.0
        if (avgHome <= 0.0 || avgAway <= 0.0) {
            val games = total.values.sumOf { it.played }
            val goals = total.values.sumOf { it.gf }
            if (games > 0) {
                val avg = goals.toDouble() / games
                avgHome = avg * 1.12
                avgAway = avg * 0.88
            } else {
                avgHome = 0.0
                avgAway = 0.0
            }
        }
        return CompetitionTable(total, avgHome, avgAway)
    }

    /** Ataque y defensa relativos a la media de la competición, encogidos hacia 1.0 con pocos partidos. */
    fun strength(teamId: String, table: CompetitionTable, priorGames: Double = 6.0): TeamStrength {
        val t = table.teams[teamId]
        val avg = table.avgTeamGoals
        if (t == null || avg <= 0.0) return TeamStrength(1.0, 1.0)
        val attack = ((t.gf + priorGames * avg) / (t.played + priorGames)) / avg
        val defense = ((t.ga + priorGames * avg) / (t.played + priorGames)) / avg
        return TeamStrength(attack.coerceIn(0.5, 2.0), defense.coerceIn(0.5, 2.0))
    }
}

object H2hParser {
    /** Enfrentamientos previos vistos desde HOY: goalsHomeToday = goles del equipo que hoy es local. */
    fun parse(json: JSONObject, thisMatchId: Long, homeId: String, nowMillis: Long): List<H2HMatch> {
        val arr = json.optJSONArray("matches") ?: return emptyList()
        val out = ArrayList<H2HMatch>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            if (m.optLong("id") == thisMatchId) continue
            val ft = m.optJSONObject("score")?.optJSONObject("fullTime") ?: continue
            if (ft.isNull("home") || ft.isNull("away")) continue
            val hId = m.optJSONObject("homeTeam")?.optInt("id")?.toString() ?: continue
            val playedAt = try {
                Instant.parse(m.getString("utcDate")).toEpochMilli()
            } catch (e: Exception) {
                continue
            }
            val years = ((nowMillis - playedAt) / (365.25 * 24 * 3600 * 1000)).coerceAtLeast(0.0)
            val homeGoals = ft.getInt("home")
            val awayGoals = ft.getInt("away")
            if (hId == homeId) {
                out += H2HMatch(years, homeGoals, awayGoals, true)
            } else {
                out += H2HMatch(years, awayGoals, homeGoals, false)
            }
        }
        return out
    }
}

object ScoreParser {
    /** Marcador de 90 minutos (usa regularTime si hubo prórroga). Null si el partido no terminó. */
    fun parse(json: JSONObject): FinalScore? {
        val m = json.optJSONObject("match") ?: json
        if (m.optString("status") != "FINISHED") return null
        val score = m.optJSONObject("score") ?: return null
        val full = listOf("regularTime", "fullTime")
            .mapNotNull { score.optJSONObject(it) }
            .firstOrNull { !it.isNull("home") && !it.isNull("away") } ?: return null
        val half = score.optJSONObject("halfTime") ?: return null
        if (half.isNull("home") || half.isNull("away")) return null
        return FinalScore(full.getInt("home"), full.getInt("away"), half.getInt("home"), half.getInt("away"))
    }
}

class FootballDataClient(
    private val keys: ApiKeyStore,
    private val limiter: RateLimiter = RateLimiter(10),
) : ResultsSource {
    private val base = "https://api.football-data.org/v4"
    private val tables = ConcurrentHashMap<String, Pair<Long, CompetitionTable>>()

    private suspend fun get(path: String): JSONObject {
        val key = keys.footballDataKey
        if (key.isBlank()) {
            throw MissingKeyException("Falta la clave de football-data.org. Configúrala en Ajustes.")
        }
        limiter.acquire()
        val text = Http.get(base + path, mapOf("X-Auth-Token" to key)).body
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("Respuesta inesperada de football-data.org")
        }
    }

    /** Partidos del día (GMT) de las 8 competiciones. dateTo es exclusivo en la API, por eso se pide hasta el día siguiente. */
    suspend fun fixtures(dateIso: String): List<Fixture> {
        val next = LocalDate.parse(dateIso).plusDays(1).toString()
        val codes = Competitions.footballData.joinToString(",")
        val json = get("/matches?dateFrom=$dateIso&dateTo=$next&competitions=$codes")
        val arr = json.optJSONArray("matches") ?: return emptyList()
        val order = Competitions.all.keys.toList()
        val out = ArrayList<Fixture>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val utc = m.optString("utcDate")
            if (!utc.startsWith(dateIso)) continue
            val code = m.optJSONObject("competition")?.optString("code") ?: continue
            val home = m.optJSONObject("homeTeam") ?: continue
            val away = m.optJSONObject("awayTeam") ?: continue
            if (home.isNull("id") || away.isNull("id")) continue
            out += Fixture(
                id = "fd:${m.getLong("id")}",
                leagueName = Competitions.all[code]?.name ?: code,
                dateIso = dateIso,
                timeGmt = utc.substring(11, 16),
                homeId = home.getInt("id").toString(),
                homeName = home.optString("name"),
                awayId = away.getInt("id").toString(),
                awayName = away.optString("name"),
                competitionCode = code,
                utcMillis = Instant.parse(utc).toEpochMilli(),
                homeShort = home.optString("shortName"),
                awayShort = away.optString("shortName"),
            )
        }
        return out.sortedWith(
            compareBy<Fixture>({ order.indexOf(it.competitionCode) }, { it.utcMillis }),
        )
    }

    /** Tabla de la competición (se guarda 12 horas para no gastar consultas). */
    suspend fun table(code: String): CompetitionTable {
        val cached = tables[code]
        if (cached != null && System.currentTimeMillis() - cached.first < 12L * 3600_000L) return cached.second
        val table = StandingsParser.parse(get("/competitions/$code/standings"))
        tables[code] = System.currentTimeMillis() to table
        return table
    }

    suspend fun headToHead(matchId: Long, homeId: String, nowMillis: Long): List<H2HMatch> {
        val json = try {
            get("/matches/$matchId/head2head?limit=10")
        } catch (e: ApiException) {
            if (e.code == 403 || e.code == 404) return emptyList()
            throw e
        }
        return H2hParser.parse(json, matchId, homeId, nowMillis)
    }

    override suspend fun finalScore(matchId: String): FinalScore? {
        if (!matchId.startsWith("fd:")) return null
        return ScoreParser.parse(get("/matches/${matchId.removePrefix("fd:")}"))
    }
}
