package com.predicciones.data

import com.predicciones.engine.ExpectedGoals
import com.predicciones.engine.FinalScore
import com.predicciones.engine.MarketType
import com.predicciones.engine.MatchRecord
import com.predicciones.engine.Outcome
import com.predicciones.engine.OutcomeProb
import com.predicciones.engine.Pick
import org.json.JSONArray
import org.json.JSONObject

/** MatchRecord <-> JSON (org.json viene con Android, sin dependencias extra). */
object RecordCodec {
    fun toEntity(r: MatchRecord): MatchRecordEntity =
        MatchRecordEntity(r.matchId, r.fechaIso, r.result != null, encode(r).toString())

    fun fromEntity(e: MatchRecordEntity): MatchRecord = decode(e.json)

    fun encode(r: MatchRecord): JSONObject {
        val j = JSONObject()
        j.put("matchId", r.matchId)
        j.put("fechaIso", r.fechaIso)
        j.put("homeId", r.homeId)
        j.put("awayId", r.awayId)
        j.put("xgHome", r.xgBase.home)
        j.put("xgAway", r.xgBase.away)
        val probs = JSONArray()
        r.rawProbs.forEach { probs.put(encodeProb(it)) }
        j.put("rawProbs", probs)
        val picks = JSONArray()
        r.picks.forEach { picks.put(encodePick(it)) }
        j.put("picks", picks)
        r.result?.let { s ->
            j.put(
                "result",
                JSONObject().put("hf", s.homeFt).put("af", s.awayFt).put("hh", s.homeHt).put("ah", s.awayHt),
            )
        }
        return j
    }

    fun decode(text: String): MatchRecord {
        val j = JSONObject(text)
        val probsArr = j.getJSONArray("rawProbs")
        val picksArr = j.getJSONArray("picks")
        return MatchRecord(
            matchId = j.getString("matchId"),
            fechaIso = j.getString("fechaIso"),
            homeId = j.getString("homeId"),
            awayId = j.getString("awayId"),
            xgBase = ExpectedGoals(j.getDouble("xgHome"), j.getDouble("xgAway")),
            rawProbs = (0 until probsArr.length()).map { decodeProb(probsArr.getJSONObject(it)) },
            picks = (0 until picksArr.length()).map { decodePick(picksArr.getJSONObject(it)) },
            result = j.optJSONObject("result")?.let {
                FinalScore(it.getInt("hf"), it.getInt("af"), it.getInt("hh"), it.getInt("ah"))
            },
        )
    }

    private fun encodeOutcome(o: Outcome): JSONObject {
        val j = JSONObject()
        j.put("market", o.market.name)
        j.put("selection", o.selection)
        o.line?.let { j.put("line", it) }
        return j
    }

    private fun decodeOutcome(j: JSONObject): Outcome = Outcome(
        MarketType.valueOf(j.getString("market")),
        j.getString("selection"),
        if (j.has("line")) j.getDouble("line") else null,
    )

    private fun encodeProb(p: OutcomeProb): JSONObject {
        val j = JSONObject()
        j.put("o", encodeOutcome(p.outcome))
        j.put("p", p.probability)
        j.put("ci", p.ci95)
        return j
    }

    private fun decodeProb(j: JSONObject): OutcomeProb =
        OutcomeProb(decodeOutcome(j.getJSONObject("o")), j.getDouble("p"), j.getDouble("ci"))

    private fun encodePick(p: Pick): JSONObject {
        val j = JSONObject()
        j.put("o", encodeOutcome(p.outcome))
        j.put("odds", p.odds)
        j.put("implied", p.impliedProb)
        j.put("model", p.modelProb)
        j.put("mc", p.mcProb)
        j.put("ci", p.ci95)
        j.put("edge", p.edgePp)
        j.put("ev", p.ev)
        j.put("sinValor", p.sinValor)
        j.put("bajoUmbral", p.bajoUmbral)
        return j
    }

    private fun decodePick(j: JSONObject): Pick = Pick(
        outcome = decodeOutcome(j.getJSONObject("o")),
        odds = j.getDouble("odds"),
        impliedProb = j.getDouble("implied"),
        modelProb = j.getDouble("model"),
        mcProb = j.getDouble("mc"),
        ci95 = j.getDouble("ci"),
        edgePp = j.getDouble("edge"),
        ev = j.getDouble("ev"),
        sinValor = j.getBoolean("sinValor"),
        bajoUmbral = j.getBoolean("bajoUmbral"),
    )
}
