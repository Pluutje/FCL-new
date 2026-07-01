package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * ============================================================================
 * FCL AI-Advisor — HistoryRepository
 * ============================================================================
 *
 * Append-only JSONL-log van elke AI-suggestie en wat de gebruiker ermee deed
 * (PENDING/APPROVED/REJECTED). Twee doelen:
 *  1. Audit-trail: nooit een parameter aangepast zonder logspoor.
 *  2. Cooldown: een afgewezen voorstel voor dezelfde parameter komt niet
 *     elke dag terug — pas opnieuw tonen na COOLDOWN_DAYS, of als de
 *     onderbouwing/proposedValue significant afwijkt van de vorige keer.
 *
 * Bestand: Documents/AAPS/ANALYSE/FCLvNext_AiAdvisorHistory.jsonl
 * (zelfde map als de andere FCL-analysebestanden — bewust geen database,
 * consistent met hoe FCLvNextActiveParamsWriter/FclLearnerLogger werken.)
 */
object FclAiAdvisorHistoryRepository {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val FILE_NAME = "FCLvNext_AiAdvisorHistory.jsonl"
    private const val COOLDOWN_DAYS = 2

    private fun file(): File {
        val dir = File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    data class HistoryEntry(
        val tsUtc: String,
        val param: String,
        val proposedValue: Double,
        val status: AiSuggestionStatus,
        val reasonNl: String
    )

    fun record(suggestion: AiParamSuggestion, status: AiSuggestionStatus) {
        val entry = JSONObject().apply {
            put("ts_utc", Instant.now().toString())
            put("param", suggestion.param)
            put("current_value", suggestion.currentValue)
            put("proposed_value", suggestion.proposedValue)
            put("confidence", suggestion.confidence)
            put("status", status.name)
            put("reason", suggestion.reasonNl)
        }
        try {
            file().appendText(entry.toString() + "\n")
        } catch (_: Exception) {
            // Bewust stil falen: een logfout mag de advisor-flow niet blokkeren.
            // (Consistent met FclLearnerLogger's fail-safe gedrag.)
        }
    }

    /** Laatste entry per parameter, voor cooldown-check. */
    fun lastEntryFor(param: String): HistoryEntry? {
        val f = file()
        if (!f.exists()) return null
        var last: HistoryEntry? = null
        f.forEachLine { line ->
            try {
                val o = JSONObject(line)
                if (o.optString("param") == param) {
                    last = HistoryEntry(
                        tsUtc = o.optString("ts_utc"),
                        param = param,
                        proposedValue = o.optDouble("proposed_value"),
                        status = AiSuggestionStatus.valueOf(o.optString("status")),
                        reasonNl = o.optString("reason")
                    )
                }
            } catch (_: Exception) { /* corrupte regel overslaan */ }
        }
        return last
    }

    /**
     * Filtert nieuwe suggesties: laat een suggestie weg als hij binnen de
     * cooldown-periode al eens is AFGEWEZEN met (vrijwel) dezelfde waarde.
     * Een significant gewijzigd voorstel (>10% andere proposedValue) komt
     * wel weer door — dat is immers een ander voorstel.
     */
    fun filterCooldown(suggestions: List<AiParamSuggestion>): List<AiParamSuggestion> =
        suggestions.filter { s ->
            val last = lastEntryFor(s.param) ?: return@filter true
            if (last.status != AiSuggestionStatus.REJECTED) return@filter true

            val daysSince = try {
                java.time.Duration.between(Instant.parse(last.tsUtc), Instant.now()).toDays()
            } catch (_: Exception) { Long.MAX_VALUE }

            val sameValue = last.proposedValue != 0.0 &&
                kotlin.math.abs(s.proposedValue - last.proposedValue) / kotlin.math.abs(last.proposedValue) < 0.10

            !(daysSince < COOLDOWN_DAYS && sameValue)
        }
}
