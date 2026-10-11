package com.predicciones.engine

data class Pick(
    val outcome: Outcome,
    val odds: Double,
    val impliedProb: Double,   // sin vigorish
    val modelProb: Double,     // Monte Carlo mezclado con el prior débil del mercado
    val mcProb: Double,        // Monte Carlo puro
    val ci95: Double,          // mitad del IC95% de la prob. Monte Carlo
    val edgePp: Double,        // (modelProb - impliedProb) * 100
    val ev: Double,            // modelProb * (cuota - 1) - (1 - modelProb)
    val sinValor: Boolean,     // edge <= minEdgePp
    val bajoUmbral: Boolean,   // modelProb <= minProb
)

data class SelectorConfig(
    val minProb: Double = 0.70,
    val minEdgePp: Double = 1.5,
    val marketWeight: Double = 0.10,  // peso del mercado como prior débil
    val maxPicks: Int = 3,
    val maxPerMarket: Int = 3,        // 1 = fuerza variedad de mercados; 3 = sigue la especificación al pie de la letra
)

object PickSelector {
    fun select(
        probs: List<OutcomeProb>,
        books: List<OddsBook>,
        cfg: SelectorConfig = SelectorConfig(),
    ): List<Pick> {
        val index = books.associateBy { it.key }
        val fairCache = HashMap<String, Map<String, Double>>()

        val candidates = probs.mapNotNull { op ->
            val book = index[op.outcome.bookKey] ?: return@mapNotNull null
            val odds = book.odds[op.outcome.selection] ?: return@mapNotNull null
            val fair = fairCache.getOrPut(book.key) { book.fairProbabilities() }[op.outcome.selection]
                ?: return@mapNotNull null
            val model = (1 - cfg.marketWeight) * op.probability + cfg.marketWeight * fair
            val edgePp = (model - fair) * 100
            Pick(
                outcome = op.outcome,
                odds = odds,
                impliedProb = fair,
                modelProb = model,
                mcProb = op.probability,
                ci95 = op.ci95,
                edgePp = edgePp,
                ev = model * (odds - 1) - (1 - model),
                sinValor = edgePp <= cfg.minEdgePp,
                bajoUmbral = model <= cfg.minProb,
            )
        }

        val eligible = candidates.filter { !it.bajoUmbral }
        val valued = eligible.filter { it.edgePp > 0 }.sortedByDescending { it.ev }
        val byProb = eligible.sortedByDescending { it.modelProb }
        val rest = candidates.sortedByDescending { it.modelProb }

        // 1) mayor EV con edge positivo; 2) si faltan, mejores informativos (marcados sin valor);
        // 3) si nada supera el umbral, lo mejor disponible (marcado bajo umbral).
        val passes = listOf(
            valued to cfg.maxPerMarket,
            valued to cfg.maxPicks,
            byProb to cfg.maxPerMarket,
            byProb to cfg.maxPicks,
            rest to cfg.maxPicks,
        )
        val picks = ArrayList<Pick>()
        for ((source, cap) in passes) {
            for (p in source) {
                if (picks.size >= cfg.maxPicks) break
                if (p in picks) continue
                if (picks.count { it.outcome.market == p.outcome.market } >= cap) continue
                picks += p
            }
        }
        return picks
    }
}
