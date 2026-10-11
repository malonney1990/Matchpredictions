package com.predicciones.engine

data class MatchPrediction(
    val expected: ExpectedGoals,         // goles esperados finales (historial directo + aprendizaje)
    val picks: List<Pick>,
    val probabilities: List<OutcomeProb>, // calibradas
    val record: MatchRecord,             // guardar en la base; al terminar el partido: record.copy(result = ...)
    val runs: Int,
    val seed: Long,
    val h2hEfectivos: Double,            // partidos efectivos del historial directo usados (0 = no hay historial)
    val advertencias: List<String>,
)

class MatchPredictor(
    private val selector: SelectorConfig = SelectorConfig(),
    private val h2hCfg: H2HConfig = H2HConfig(),
) {
    /**
     * Pasos: goles esperados base -> historial directo en ese campo -> factores aprendidos por equipo
     * -> Monte Carlo -> calibración aprendida -> picks con las cuotas de TODAS las casas (promediadas).
     * @param dataConfidence 0..1; si faltan datos críticos (alineación/bajas) baja y ensancha los escenarios.
     * La semilla sale de matchId + fecha, así el mismo partido da siempre el mismo resultado.
     */
    fun predict(
        matchId: String,
        fechaIso: String,
        homeId: String,
        awayId: String,
        home: TeamStrength,
        away: TeamStrength,
        oddsFromAllBookmakers: List<OddsBook>,
        league: LeagueBaseline = LeagueBaseline(),
        neutral: Boolean = false,
        h2h: List<H2HMatch> = emptyList(),
        learner: LearnerState = LearnerState.EMPTY,
        dataConfidence: Double = 1.0,
        runs: Int = 10_000,
    ): MatchPrediction {
        val conf = dataConfidence.coerceIn(0.0, 1.0)
        val model = ExpectedGoalsModel.estimate(home, away, league, neutral)
        val withH2h = HeadToHead.adjust(model, h2h, h2hCfg)
        val xgBase = withH2h.xg
        val xg = learner.adjust(xgBase, homeId, awayId)

        val seed = "$matchId|$fechaIso".hashCode().toLong()
        val sim = MonteCarlo.simulate(
            xg,
            SimConfig(runs = runs, seed = seed, scenarioSigma = 0.10 + (1 - conf) * 0.15),
        )
        val raw = MarketProbabilities.compute(sim)
        val calibrated = learner.calibrate(raw)
        val books = Odds.mergeAll(oddsFromAllBookmakers)
        val picks = PickSelector.select(calibrated, books, selector)

        val avisos = ArrayList<String>()
        if (conf < 0.7) avisos.add("Datos críticos incompletos (alineación/bajas): confianza degradada.")
        if (neutral) avisos.add("Campo neutral: sin ventaja de localía.")

        val record = MatchRecord(matchId, fechaIso, homeId, awayId, xgBase, raw, picks)
        return MatchPrediction(xg, picks, calibrated, record, runs, seed, withH2h.effectiveMatches, avisos)
    }
}
