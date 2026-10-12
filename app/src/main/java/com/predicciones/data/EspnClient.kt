package com.predicciones.data

import com.predicciones.engine.FinalScore
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class EspnEvent(
    val id: String,
    val utcMillis: Long,
    val completed: Boolean,
    val homeId: String,
    val homeName: String,
    val homeShort: String,
    val awayId: String,
    val awayName: String,
    val awayShort: String,
    val homeGoals: Int?,   // goles de los 90 minutos
    val awayGoals: Int?,
    val homeHalf: Int?,    // goles de la 1ª mitad (null si ESPN no da el parcial)
    val awayHalf: Int?,
)

object EspnParser {
    /** ESPN da fechas como "2026-10-15T19:00Z" (sin segundos). */
    fun parseDate(s: String): Long? {
        val fixed = if (s.length == 17 && s.endsWith("Z")) s.dropLast(1) + ":00Z" else s
        return try {
            Instant.parse(fixed).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private fun lineValue(c: JSONObject, index: Int): Int? {
        val v = c.optJSONArray("linescores")?.optJSONObject(index)?.optDouble("value") ?: return null
        return if (v.isNaN()) null else v.toInt()
    }

    /** (goles de 90 min, goles de la 1ª mitad). Con prórroga ESPN añade más parciales: solo se usan los dos primeros. */
    private fun goals(c: JSONObject): Pair<Int?, Int?> {
        val total = c.optString("score").toIntOrNull()
        val first = lineValue(c, 0)
        val second = lineValue(c, 1)
        return if (first != null && second != null) (first + second) to first else total to first
    }

    fun events(json: JSONObject): List<EspnEvent> {
        val arr = json.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<EspnEvent>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val id = e.optString("id")
            if (id.isEmpty()) continue
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors") ?: continue
            var home: JSONObject? = null
            var away: JSONObject? = null
            for (k in 0 until competitors.length()) {
                val c = competitors.getJSONObject(k)
                when (c.optString("homeAway")) {
                    "home" -> home = c
                    "away" -> away = c
                }
            }
            if (home == null || away == null) continue
            val homeTeam = home.optJSONObject("team") ?: continue
            val awayTeam = away.optJSONObject("team") ?: continue
            val utc = parseDate(e.optString("date")) ?: continue
            val completed = e.optJSONObject("status")?.optJSONObject("type")?.optBoolean("completed", false) ?: false
            val (hg, hh) = goals(home)
            val (ag, ah) = goals(away)
            out += EspnEvent(
                id = id,
                utcMillis = utc,
                completed = completed,
                homeId = "es:" + homeTeam.optString("id"),
                homeName = homeTeam.optString("displayName"),
                homeShort = homeTeam.optString("shortDisplayName"),
                awayId = "es:" + awayTeam.optString("id"),
                awayName = awayTeam.optString("displayName"),
                awayShort = awayTeam.optString("shortDisplayName"),
                homeGoals = hg,
                awayGoals = ag,
                homeHalf = hh,
                awayHalf = ah,
            )
        }
        return out
    }

    /** Goles a favor y en contra de cada equipo, y promedios de local y visitante, con los partidos terminados. */
    fun table(events: List<EspnEvent>): CompetitionTable {
        val acc = HashMap<String, TeamTable>()
        var homeGoals = 0
        var awayGoals = 0
        var games = 0
        for (e in events) {
            if (!e.completed) continue
            val hg = e.homeGoals ?: continue
            val ag = e.awayGoals ?: continue
            games++
            homeGoals += hg
            awayGoals += ag
            val h = acc[e.homeId]
            acc[e.homeId] = TeamTable((h?.played ?: 0) + 1, (h?.gf ?: 0) + hg, (h?.ga ?: 0) + ag)
            val a = acc[e.awayId]
            acc[e.awayId] = TeamTable((a?.played ?: 0) + 1, (a?.gf ?: 0) + ag, (a?.ga ?: 0) + hg)
        }
        if (games == 0) return CompetitionTable(emptyMap(), 0.0, 0.0)
        return CompetitionTable(acc, homeGoals.toDouble() / games, awayGoals.toDouble() / games)
    }
}

/**
 * Datos públicos de ESPN (sin clave, pero NO es una API oficial: puede cambiar o dejar de funcionar sin aviso).
 * Se usa solo para Saudi Pro League, liga belga, Europa League y Conference League.
 * Id de partido: es:{slug}:{AAAA-MM-DD}:{idEvento}
 */
class EspnClient(private val limiter: RateLimiter = RateLimiter(20)) : ResultsSource {
    private val base = "https://site.api.espn.com/apis/site/v2/sports/soccer"
    private val headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) Predicciones/0.1")
    private val histories = ConcurrentHashMap<String, Pair<Long, CompetitionTable>>()

    private fun fmt(d: LocalDate) = d.toString().replace("-", "")

    private suspend fun scoreboard(slug: String, from: LocalDate, to: LocalDate, limit: Int? = null): JSONObject {
        limiter.acquire()
        val range = if (from == to) fmt(from) else "${fmt(from)}-${fmt(to)}"
        val extra = if (limit != null) "&limit=$limit" else ""
        val text = Http.get("$base/$slug/scoreboard?dates=$range$extra", headers).body
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("Respuesta inesperada de ESPN")
        }
    }

    /** Partidos del día GMT. ESPN agrupa por día de EE. UU., por eso se pide también el día anterior y se filtra por fecha GMT. */
    suspend fun fixtures(code: String, comp: Comp, dateIso: String): List<Fixture> {
        val slug = comp.espnSlug ?: return emptyList()
        val day = LocalDate.parse(dateIso)
        val json = try {
            scoreboard(slug, day.minusDays(1), day)
        } catch (e: ApiException) {
            if (e.code == 400) scoreboard(slug, day, day) else throw e
        }
        return EspnParser.events(json)
            .filter { Instant.ofEpochMilli(it.utcMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() == dateIso }
            .map { e ->
                Fixture(
                    id = "es:$slug:$dateIso:${e.id}",
                    leagueName = comp.name,
                    dateIso = dateIso,
                    timeGmt = Instant.ofEpochMilli(e.utcMillis).atZone(ZoneOffset.UTC).toLocalTime().toString().take(5),
                    homeId = e.homeId,
                    homeName = e.homeName,
                    awayId = e.awayId,
                    awayName = e.awayName,
                    competitionCode = code,
                    utcMillis = e.utcMillis,
                    homeShort = e.homeShort,
                    awayShort = e.awayShort,
                )
            }
    }

    /** Fuerza de los equipos con los resultados de los últimos 180 días de la competición (se guarda 12 horas). */
    suspend fun table(slug: String): CompetitionTable {
        val cached = histories[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < 12L * 3600_000L) return cached.second
        val today = LocalDate.now(ZoneOffset.UTC)
        val json = scoreboard(slug, today.minusDays(180), today, limit = 1000)
        val table = EspnParser.table(EspnParser.events(json))
        histories[slug] = System.currentTimeMillis() to table
        return table
    }

    override suspend fun finalScore(matchId: String): FinalScore? {
        val parts = matchId.split(":")
        if (parts.size != 4 || parts[0] != "es") return null
        val slug = parts[1]
        val day = LocalDate.parse(parts[2])
        val eventId = parts[3]
        val json = scoreboard(slug, day.minusDays(1), day.plusDays(1))
        val e = EspnParser.events(json).firstOrNull { it.id == eventId } ?: return null
        if (!e.completed) return null
        val hg = e.homeGoals ?: return null
        val ag = e.awayGoals ?: return null
        val hh = e.homeHalf ?: return null // sin parcial de la 1ª mitad no se puede cerrar el partido
        val ah = e.awayHalf ?: return null
        return FinalScore(hg, ag, hh, ah)
    }
}

/** Reparte según el prefijo del id: fd: football-data.org, es: ESPN (los demás, como los de ejemplo, se ignoran). */
class CompositeResultsSource(
    private val football: ResultsSource,
    private val espn: ResultsSource,
) : ResultsSource {
    override suspend fun finalScore(matchId: String): FinalScore? = when {
        matchId.startsWith("fd:") -> football.finalScore(matchId)
        matchId.startsWith("es:") -> espn.finalScore(matchId)
        else -> null
    }
}
