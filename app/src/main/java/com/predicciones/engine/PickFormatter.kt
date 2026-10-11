package com.predicciones.engine

import java.util.Locale

/** Formato estricto de salida. La UI puede localizar decimales; aquí se usa punto. */
object PickFormatter {
    fun header(local: String, visitante: String, fechaIso: String, horaGmt: String): String =
        "$local – $visitante | $fechaIso | $horaGmt GMT"

    fun pickLine(n: Int, p: Pick): String = String.format(
        Locale.US,
        "Pick %d: %s | %s | %.2f | %.1f%% | %.1f%% | %+.1f | %+.3f",
        n, p.outcome.market.etiqueta, p.outcome.etiqueta(), p.odds,
        p.impliedProb * 100, p.modelProb * 100, p.edgePp, p.ev,
    )

    fun justification(
        p: Pick,
        xg: ExpectedGoals,
        runs: Int,
        seed: Long,
        h2hEfectivos: Double = 0.0,
    ): String {
        val core = String.format(
            Locale.US,
            "Monte Carlo (%d corridas, semilla %d): %.1f%% ± %.1f p.p. (IC95%%), goles esperados %.2f local y %.2f visitante, edge %+.1f p.p. frente a la cuota sin vigorish",
            runs, seed, p.mcProb * 100, p.ci95 * 100, xg.home, xg.away, p.edgePp,
        )
        val h2h = if (h2hEfectivos > 0.0) {
            String.format(Locale.US, ", historial directo con peso de %.1f partidos", h2hEfectivos)
        } else {
            ""
        }
        val text = "$core$h2h."
        val avisos = ArrayList<String>()
        if (p.bajoUmbral) avisos.add("probabilidad por debajo del umbral de 70%")
        if (p.sinValor) avisos.add("sin valor (edge ≤ 1.5 p.p.), pick solo informativo")
        return if (avisos.isEmpty()) text else "$text Advertencia: ${avisos.joinToString("; ")}."
    }
}
