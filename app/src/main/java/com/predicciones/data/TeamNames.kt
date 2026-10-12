package com.predicciones.data

import java.text.Normalizer
import kotlin.math.min

/** Compara nombres de equipos entre APIs ("Manchester City FC" vs "Manchester City", "Inter" vs "Internazionale"). */
object TeamNames {
    private val stop = setOf(
        "fc", "cf", "afc", "sc", "ac", "as", "ssc", "sv", "fk", "bv", "fsv", "club", "de", "del",
        "la", "le", "the", "and", "calcio", "cp",
    )

    fun tokens(name: String): List<String> {
        val plain = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in stop }
    }

    private fun same(x: String, y: String): Boolean =
        x == y || (x.length >= 4 && y.length >= 4 && (x.startsWith(y) || y.startsWith(x)))

    /** Fracción (0 a 1) de las palabras del nombre más corto que aparecen en el otro. */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val matched = ta.count { x -> tb.any { y -> same(x, y) } }
        return matched.toDouble() / min(ta.size, tb.size)
    }

    fun similarity(name: String, shortName: String, other: String): Double =
        maxOf(similarity(name, other), similarity(shortName, other))
}
