package com.predicciones.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class FinalScore(val homeFt: Int, val awayFt: Int, val homeHt: Int, val awayHt: Int)

/** Decide si una selección se cumplió con el marcador final (mismas definiciones que la simulación). */
object Settle {
    fun hit(o: Outcome, r: FinalScore): Boolean {
        val total = r.homeFt + r.awayFt
        val totalHt = r.homeHt + r.awayHt
        val line = o.line ?: 0.0
        return when (o.market) {
            MarketType.RESULTADO_1X2 -> when (o.selection) {
                "1" -> r.homeFt > r.awayFt
                "X" -> r.homeFt == r.awayFt
                else -> r.homeFt < r.awayFt
            }
            MarketType.GOLES_1T -> if (o.selection == "Más") totalHt > line else totalHt < line
            MarketType.GOLES_PARTIDO -> if (o.selection == "Más") total > line else total < line
            MarketType.HANDICAP ->
                if (o.selection == "Local") r.homeFt + line > r.awayFt else r.homeFt + line < r.awayFt
            MarketType.GOLES_EXACTOS -> if (o.selection == "5+") total >= 5 else total == o.selection.toInt()
        }
    }
}

/**
 * Historial de una predicción. Se guarda al predecir (result = null) y se completa al terminar el partido.
 * xgBase = goles esperados ANTES del ajuste aprendido; rawProbs = probabilidades Monte Carlo ANTES de calibrar.
 * Con eso el aprendizaje se puede rehacer siempre desde cero y de forma reproducible.
 */
data class MatchRecord(
    val matchId: String,
    val fechaIso: String,
    val homeId: String,
    val awayId: String,
    val xgBase: ExpectedGoals,
    val rawProbs: List<OutcomeProb>,
    val picks: List<Pick>,
    val result: FinalScore? = null,
)

private fun logit(p: Double): Double {
    val c = p.coerceIn(1e-4, 1 - 1e-4)
    return ln(c / (1 - c))
}

private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

/** Calibración de Platt: p' = sigmoide(a * logit(p) + b). a=1, b=0 no cambia nada. */
data class PlattParams(val a: Double = 1.0, val b: Double = 0.0) {
    fun apply(p: Double): Double = sigmoid(a * logit(p) + b)
}

object Platt {
    /** Regresión logística de 2 parámetros (Newton) con regularización hacia a=1, b=0. */
    fun fit(samples: List<Pair<Double, Boolean>>, priorStrength: Double = 20.0): PlattParams {
        if (samples.isEmpty()) return PlattParams()
        var a = 1.0
        var b = 0.0
        repeat(25) {
            var ga = priorStrength * (a - 1.0)
            var gb = priorStrength * b
            var haa = priorStrength
            var hab = 0.0
            var hbb = priorStrength
            for ((p, y) in samples) {
                val x = logit(p)
                val q = sigmoid(a * x + b)
                val w = q * (1 - q)
                val e = q - (if (y) 1.0 else 0.0)
                ga += e * x
                gb += e
                haa += w * x * x
                hab += w * x
                hbb += w
            }
            val det = haa * hbb - hab * hab
            if (det <= 1e-12) return@repeat
            a = (a - (hbb * ga - hab * gb) / det).coerceIn(0.3, 2.0)
            b = (b - (haa * gb - hab * ga) / det).coerceIn(-1.5, 1.5)
        }
        return PlattParams(a, b)
    }
}

data class LearnerConfig(
    val minMatchesForCalibration: Int = 30,
    val priorStrength: Double = 20.0,   // goles esperados equivalentes del modelo base frente a lo observado
    val decay: Double = 0.97,           // los partidos recientes de un equipo pesan más
    val biasClamp: Double = 0.10,       // cada factor de equipo queda entre 0.90 y 1.10
)

data class LearnerStats(val matches: Int, val picks: Int, val pickHits: Int) {
    val hitRate: Double get() = if (picks == 0) 0.0 else pickHits.toDouble() / picks
}

/** Lo aprendido hasta hoy: calibración por mercado y factores de ataque/defensa por equipo. */
class LearnerState(
    val calibrators: Map<MarketType, PlattParams>,
    val teamAttack: Map<String, Double>,
    val teamDefense: Map<String, Double>,
    val stats: LearnerStats,
) {
    fun adjust(xg: ExpectedGoals, homeId: String, awayId: String): ExpectedGoals = ExpectedGoals(
        xg.home * (teamAttack[homeId] ?: 1.0) * (teamDefense[awayId] ?: 1.0),
        xg.away * (teamAttack[awayId] ?: 1.0) * (teamDefense[homeId] ?: 1.0),
    )

    fun calibrate(probs: List<OutcomeProb>): List<OutcomeProb> {
        if (calibrators.isEmpty()) return probs
        val adjusted = probs.map { op ->
            val params = calibrators[op.outcome.market]
            if (params == null) op else op.copy(probability = params.apply(op.probability))
        }
        // Cada mercado completo (1X2, una línea de goles, etc.) debe seguir sumando 1.
        val totals = adjusted.groupBy { it.outcome.bookKey }.mapValues { e -> e.value.sumOf { it.probability } }
        return adjusted.map { op -> op.copy(probability = op.probability / (totals[op.outcome.bookKey] ?: 1.0)) }
    }

    companion object {
        val EMPTY = LearnerState(emptyMap(), emptyMap(), emptyMap(), LearnerStats(0, 0, 0))
    }
}

private class TeamAcc {
    var gf = 0.0
    var xf = 0.0
    var ga = 0.0
    var xa = 0.0

    fun add(decay: Double, scored: Int, expFor: Double, conceded: Int, expAgainst: Double) {
        gf = gf * decay + scored
        xf = xf * decay + expFor
        ga = ga * decay + conceded
        xa = xa * decay + expAgainst
    }
}

object Learner {
    /**
     * Reentrena todo desde el historial (barato en el teléfono). Dos cosas se aprenden de los errores:
     * 1) Calibración Platt por mercado: si el modelo decía 90% y acertaba 70%, baja esas probabilidades.
     * 2) Factores por equipo: cuánto marca/concede cada equipo respecto a lo que el modelo esperaba
     *    (estimador bayesiano con memoria decreciente; sin datos suficientes se queda en 1.0).
     */
    fun fit(history: List<MatchRecord>, cfg: LearnerConfig = LearnerConfig()): LearnerState {
        val finished = history.filter { it.result != null }.sortedBy { it.fechaIso }
        if (finished.isEmpty()) return LearnerState.EMPTY

        val acc = HashMap<String, TeamAcc>()
        var picks = 0
        var hits = 0
        for (rec in finished) {
            val r = rec.result ?: continue
            acc.getOrPut(rec.homeId) { TeamAcc() }
                .add(cfg.decay, r.homeFt, rec.xgBase.home, r.awayFt, rec.xgBase.away)
            acc.getOrPut(rec.awayId) { TeamAcc() }
                .add(cfg.decay, r.awayFt, rec.xgBase.away, r.homeFt, rec.xgBase.home)
            for (p in rec.picks) {
                picks++
                if (Settle.hit(p.outcome, r)) hits++
            }
        }

        // Raíz cuadrada: ataque y defensa comparten el sesgo, así no se corrige dos veces el mismo error.
        val lo = 1 - cfg.biasClamp
        val hi = 1 + cfg.biasClamp
        val k = cfg.priorStrength
        val attack = acc.mapValues { sqrt((k + it.value.gf) / (k + it.value.xf)).coerceIn(lo, hi) }
        val defense = acc.mapValues { sqrt((k + it.value.ga) / (k + it.value.xa)).coerceIn(lo, hi) }

        val calibrators = HashMap<MarketType, PlattParams>()
        if (finished.size >= cfg.minMatchesForCalibration) {
            for (market in MarketType.values()) {
                val samples = ArrayList<Pair<Double, Boolean>>()
                for (rec in finished) {
                    val r = rec.result ?: continue
                    for (op in rec.rawProbs) {
                        if (op.outcome.market == market) samples += op.probability to Settle.hit(op.outcome, r)
                    }
                }
                if (samples.isNotEmpty()) calibrators[market] = Platt.fit(samples)
            }
        }
        return LearnerState(calibrators, attack, defense, LearnerStats(finished.size, picks, hits))
    }
}
