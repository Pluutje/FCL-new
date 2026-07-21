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
        val currentValue: Double,
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
        } catch (_: Exception) { }
    }

    /** Alle entries, nieuwste eerst — voor de geschiedenislog in de UI. */
    fun readAll(): List<HistoryEntry> {
        val f = file()
        if (!f.exists()) return emptyList()
        val result = mutableListOf<HistoryEntry>()
        f.forEachLine { line ->
            try {
                val o = JSONObject(line)
                result.add(HistoryEntry(
                    tsUtc         = o.optString("ts_utc"),
                    param         = o.optString("param"),
                    currentValue  = o.optDouble("current_value"),
                    proposedValue = o.optDouble("proposed_value"),
                    status        = runCatching { AiSuggestionStatus.valueOf(o.optString("status")) }
                        .getOrDefault(AiSuggestionStatus.REJECTED),
                    reasonNl      = o.optString("reason")
                ))
            } catch (_: Exception) { }
        }
        return result.reversed()  // nieuwste eerst
    }

    /** Alleen goedgekeurde entries — voor de parameter-tijdlijn. */
    fun readApproved(): List<HistoryEntry> = readAll().filter { it.status == AiSuggestionStatus.APPROVED }

    /**
     * Is dit voorstel nog onbeoordeeld? (05/07/2026, Ecko — bugfix)
     *
     * PROBLEEM: [readPendingFromLastRun] filtert op status==PENDING, maar
     * [record] wordt in de praktijk NOOIT met PENDING aangeroepen — alleen
     * met APPROVED of REJECTED, ná een expliciete Goedkeuren/Afwijzen-klik
     * (zie FclAiAdvisorApplier.kt). Er wordt dus nooit een PENDING-regel
     * weggeschreven, waardoor [readPendingFromLastRun] altijd leeg was —
     * dit verklaart mede waarom de melding niet terugkwam.
     *
     * JUISTE CHECK: een voorstel is nog onbeoordeeld als er GEEN eerdere
     * geschiedenis-entry voor deze parameter bestaat die bij de huidige
     * voorgestelde waarde hoort — exact dezelfde logica als SuggestionCard
     * in FclAiAdvisorScreen.kt al gebruikt om de knoppen te tonen/verbergen,
     * hier alleen ook bruikbaar buiten dat scherm (voor de badge/notificatie).
     */
    fun isStillPending(suggestion: AiParamSuggestion): Boolean {
        // 21/07/2026 (Ecko): BUGFIX — een suggestie die de validator zelf al
        // rejected heeft (buiten bereik, te lage confidence, ontbrekende
        // evidenceFields, ...) is nooit "nog te beoordelen" geweest; die kwam
        // niet eens als goed te keuren kaart in beeld. Zonder deze check telde
        // de badge op het Analyzer-dashboard zo'n auto-verworpen suggestie toch
        // mee (want lastEntryFor vindt niets — die is immers nooit aan de
        // gebruiker voorgelegd — en gaf dan altijd true terug), terwijl het AI
        // Advisor-scherm zelf 'rejected' er via 'accepted = filter { !it.rejected }'
        // wél al uitsloot. Concreet incident: badge toonde "1 wacht", scherm
        // toonde "0 voorstel(len), 1 automatisch verworpen" — zelfde suggestie.
        if (suggestion.rejected) return false
        val last = lastEntryFor(suggestion.param) ?: return true
        return kotlin.math.abs(last.proposedValue - suggestion.proposedValue) >= 0.001
    }

    /**
     * Openstaande (nog niet beoordeelde) voorstellen uit het laatste rapport.
     * Gebruikt door het Analyzer-dashboard om een badge/notificatie te tonen.
     * "Laatste rapport" = alle PENDING entries met de meest recente ts_utc.
     * Zo zie je alleen de batch van de laatste run, niet oeroude PENDING's.
     * (02/07/2026, Ecko)
     *
     * LET OP (05/07/2026, Ecko): deze functie levert door de hierboven
     * beschreven reden altijd een lege lijst. Bewust NIET verwijderd (kan nog
     * ergens gebruikt worden voor toekomstige history-weergave), maar voor
     * "is er nog iets te beoordelen" moet [isStillPending] gebruikt worden,
     * toegepast op de suggesties uit FclAiAdvisorScheduler.latestResult().
     */
    fun readPendingFromLastRun(): List<HistoryEntry> {
        val all = readAll().filter { it.status == AiSuggestionStatus.PENDING }
        if (all.isEmpty()) return emptyList()
        val parsed = all.mapNotNull { e ->
            runCatching { Instant.parse(e.tsUtc) to e }.getOrNull()
        }
        if (parsed.isEmpty()) return emptyList()
        val newestTs = parsed.maxOf { it.first }
        val twoMin = java.time.Duration.ofMinutes(2)
        return parsed
            .filter { java.time.Duration.between(it.first, newestTs).abs() <= twoMin }
            .map { it.second }
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
                        tsUtc         = o.optString("ts_utc"),
                        param         = param,
                        currentValue  = o.optDouble("current_value"),
                        proposedValue = o.optDouble("proposed_value"),
                        status        = AiSuggestionStatus.valueOf(o.optString("status")),
                        reasonNl      = o.optString("reason")
                    )
                }
            } catch (_: Exception) { }
        }
        return last
    }

    /**
     * Filtert nieuwe suggesties: laat een suggestie weg als hij binnen de
     * cooldown-periode al eens is AFGEWEZEN met (vrijwel) dezelfde waarde.
     * Een significant gewijzigd voorstel (>10% andere proposedValue) komt
     * wel weer door — dat is immers een ander voorstel.
     */
    /**
     * Filtert nieuwe suggesties: laat een suggestie weg als hij binnen de
     * cooldown-periode al eens is AFGEWEZEN of GOEDGEKEURD met (vrijwel)
     * dezelfde waarde. Bugfix 01/07/2026: voorheen werden goedgekeurde
     * suggesties altijd doorgelaten (status != REJECTED), waardoor elke
     * dag opnieuw hetzelfde voorstel verscheen.
     */
    fun filterCooldown(suggestions: List<AiParamSuggestion>): List<AiParamSuggestion> =
        suggestions.filter { s ->
            val last = lastEntryFor(s.param) ?: return@filter true

            val daysSince = try {
                java.time.Duration.between(Instant.parse(last.tsUtc), Instant.now()).toDays()
            } catch (_: Exception) { Long.MAX_VALUE }

            val sameValue = last.proposedValue != 0.0 &&
                kotlin.math.abs(s.proposedValue - last.proposedValue) / kotlin.math.abs(last.proposedValue) < 0.10

            // Blokkeer als: nog binnen cooldown EN zelfde waarde, ongeacht of het APPROVED of REJECTED was.
            // Een significant ander voorstel (>10% verschil) komt wel door — dat is immers een nieuw inzicht.
            !(daysSince < COOLDOWN_DAYS && sameValue)
        }

    /**
     * Verwijdert alle history-entries voor één parameter uit het log-bestand.
     * Gebruik: schonen na handmatige reset, of om een foutief goedgekeurd voorstel
     * (bijv. peakIobBrakeSuppressThreshold vóór die parameter eruit werd gehaald)
     * te verwijderen zodat hij niet meer in de geschiedeniskaart verschijnt.
     */
    fun deleteParam(param: String) {
        val f = file()
        if (!f.exists()) return
        try {
            val lines = f.readLines().filter { line ->
                try { JSONObject(line).optString("param") != param }
                catch (_: Exception) { true }
            }
            f.writeText(lines.joinToString("\n").let { if (it.isNotBlank()) it + "\n" else "" })
        } catch (_: Exception) { }
    }

    /** Geeft het history-bestand terug — voor gebruik in de UI (bijv. reset-flow). */
    fun historyFile(): java.io.File = file()
}