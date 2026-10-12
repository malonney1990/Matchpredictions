package com.predicciones.data

import com.predicciones.engine.MarketType
import com.predicciones.engine.OddsBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.math.abs
import kotlin.math.round

object OddsParser {
    private fun valid(p: Double) = p.isFinite() && p > 1.0

    private fun isHalfLine(v: Double): Boolean {
        if (!v.isFinite()) return false
        val x = v * 2
        return abs(x - round(x)) < 1e-9 && round(x).toLong() % 2L != 0L
    }

    /** Un OddsBook por casa, mercado y línea (1X2, total de goles y hándicap de líneas .5). */
    fun books(event: JSONObject): List<OddsBook> {
        val home = event.optString("home_team")
        val away = event.optString("away_team")
        val out = ArrayList<OddsBook>()
        val bookmakers = event.optJSONArray("bookmakers") ?: return out
        for (b in 0 until bookmakers.length()) {
            val markets = bookmakers.getJSONObject(b).optJSONArray("markets") ?: continue
            for (m in 0 until markets.length()) {
                val market = markets.getJSONObject(m)
                val outcomes = market.optJSONArray("outcomes") ?: continue
                when (market.optString("key")) {
                    "h2h" -> h2h(outcomes, home, away)?.let { out += it }
                    "totals" -> out += totals(outcomes)
                    "spreads" -> spreads(outcomes, home, away)?.let { out += it }
                }
            }
        }
        return out
    }

    private fun h2h(outcomes: JSONArray, home: String, away: String): OddsBook? {
        var h: Double? = null
        var d: Double? = null
        var a: Double? = null
        for (i in 0 until outcomes.length()) {
            val o = outcomes.getJSONObject(i)
            val price = o.optDouble("price")
            if (!valid(price)) continue
            when (o.optString("name")) {
                home -> h = price
                away -> a = price
                "Draw" -> d = price
            }
        }
        if (h == null || d == null || a == null) return null
        return OddsBook(MarketType.RESULTADO_1X2, null, mapOf("1" to h, "X" to d, "2" to a))
    }

    private fun totals(outcomes: JSONArray): List<OddsBook> {
        val over = HashMap<Double, Double>()
        val under = HashMap<Double, Double>()
        for (i in 0 until outcomes.length()) {
            val o = outcomes.getJSONObject(i)
            val price = o.optDouble("price")
            val point = o.optDouble("point")
            if (!valid(price) || !isHalfLine(point)) continue
            when (o.optString("name")) {
                "Over" -> over[point] = price
                "Under" -> under[point] = price
            }
        }
        return over.keys.filter { it in under }.map { line ->
            OddsBook(
                MarketType.GOLES_PARTIDO,
                line,
                mapOf("Más" to over.getValue(line), "Menos" to under.getValue(line)),
            )
        }
    }

    private fun spreads(outcomes: JSONArray, home: String, away: String): OddsBook? {
        var homePrice: Double? = null
        var homeLine: Double? = null
        var awayPrice: Double? = null
        for (i in 0 until outcomes.length()) {
            val o = outcomes.getJSONObject(i)
            val price = o.optDouble("price")
            if (!valid(price)) continue
            when (o.optString("name")) {
                home -> {
                    homePrice = price
                    homeLine = o.optDouble("point")
                }
                away -> awayPrice = price
            }
        }
        if (homePrice == null || awayPrice == null || homeLine == null || !isHalfLine(homeLine)) return null
        return OddsBook(MarketType.HANDICAP, homeLine, mapOf("Local" to homePrice, "Visitante" to awayPrice))
    }
}

object EventMatcher {
    /** Busca en los eventos de la liga el que corresponde al partido (misma hora aprox. y nombres parecidos). */
    fun find(events: JSONArray, f: Fixture): JSONObject? {
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (f.utcMillis != 0L) {
                val t = try {
                    Instant.parse(e.getString("commence_time")).toEpochMilli()
                } catch (ex: Exception) {
                    continue
                }
                if (abs(t - f.utcMillis) > 6L * 3600_000L) continue
            }
            val sh = TeamNames.similarity(f.homeName, f.homeShort, e.optString("home_team"))
            val sa = TeamNames.similarity(f.awayName, f.awayShort, e.optString("away_team"))
            if (sh < 0.5 || sa < 0.5) continue
            if (sh + sa > bestScore) {
                bestScore = sh + sa
                best = e
            }
        }
        return best
    }
}

/**
 * Cuotas de TODAS las casas de las regiones eu y uk. Una consulta trae todos los partidos de la liga
 * y se guarda 12 horas en el teléfono para gastar pocos créditos del plan gratis.
 */
class OddsApiClient(private val keys: ApiKeyStore, private val cacheDir: File) {
    private val base = "https://api.the-odds-api.com/v4"
    private val ttlMs = 12L * 3600_000L
    private val mutex = Mutex()

    suspend fun events(sportKey: String): JSONArray = mutex.withLock {
        val file = File(cacheDir, "odds_$sportKey.json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < ttlMs) {
            try {
                return@withLock withContext(Dispatchers.IO) { JSONArray(file.readText()) }
            } catch (e: JSONException) {
                // caché dañada: se vuelve a pedir
            }
        }
        val key = keys.oddsApiKey
        if (key.isBlank()) {
            throw MissingKeyException("Falta la clave de The Odds API. Configúrala en Ajustes.")
        }
        val url = "$base/sports/$sportKey/odds/?apiKey=$key&regions=eu,uk&markets=h2h,totals,spreads" +
            "&oddsFormat=decimal&dateFormat=iso"
        val response = Http.get(url)
        response.headers["x-requests-remaining"]?.toDoubleOrNull()?.let { keys.oddsCreditsRemaining = it.toInt() }
        val arr = try {
            JSONArray(response.body)
        } catch (e: JSONException) {
            throw IOException("Respuesta inesperada de The Odds API")
        }
        withContext(Dispatchers.IO) { file.writeText(response.body) }
        arr
    }
}
