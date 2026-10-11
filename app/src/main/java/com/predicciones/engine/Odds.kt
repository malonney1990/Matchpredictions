package com.predicciones.engine

/**
 * Cuotas decimales de un mercado COMPLETO (selecciones mutuamente excluyentes y exhaustivas),
 * p. ej. 1X2 {"1","X","2"}, Más/Menos de una línea, Local/Visitante de una línea de hándicap,
 * o goles exactos {"0","1","2","3","4","5+"}. Si falta una selección, el vigorish queda mal calculado.
 */
data class OddsBook(val market: MarketType, val line: Double?, val odds: Map<String, Double>) {
    val key: String get() = makeBookKey(market, line)

    /** Probabilidades implícitas sin vigorish (normalización proporcional). */
    fun fairProbabilities(): Map<String, Double> = Odds.removeVigorish(odds)
}

object Odds {
    fun removeVigorish(odds: Map<String, Double>): Map<String, Double> {
        require(odds.isNotEmpty() && odds.values.all { it > 1.0 }) { "Cuotas inválidas" }
        val raw = odds.mapValues { 1.0 / it.value }
        val total = raw.values.sum()
        return raw.mapValues { it.value / total }
    }

    /** Margen de la casa (overround): suma de implícitas - 1. */
    fun overround(odds: Map<String, Double>): Double = odds.values.sumOf { 1.0 / it } - 1.0

    /** Promedia varias casas para el mismo mercado y línea. */
    fun average(books: List<OddsBook>): OddsBook {
        val first = books.first()
        val selections = books.flatMap { it.odds.keys }.toSet()
        val avg = selections.associateWith { sel -> books.mapNotNull { it.odds[sel] }.average() }
        return first.copy(odds = avg)
    }

    /** Une las cuotas de TODAS las casas públicas disponibles: promedio por mercado, línea y selección. */
    fun mergeAll(all: List<OddsBook>): List<OddsBook> =
        all.groupBy { it.key }.values.map { average(it) }
}
