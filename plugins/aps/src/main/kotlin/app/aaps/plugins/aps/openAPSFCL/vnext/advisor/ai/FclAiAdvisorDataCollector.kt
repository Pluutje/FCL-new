package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ============================================================================
 * FCL AI-Advisor — DataCollector
 * ============================================================================
 *
 * Bouwt het compacte dagrapport (FclDailyReportPayload) uit de bestanden die
 * FCLvNext toch al schrijft naar Documents/AAPS/ANALYSE/:
 *  - FCLvNext_active_params.json   (huidige geleerde parameterwaarden)
 *  - FCLvNext_LearnerLog_v1.csv    (wat de learners vandaag besloten/waarom)
 *  - FCLvNext_Log_v7.csv           (5-min cyclus-log, alléén voor TIR/hypo/
 *                                    piek-statistiek — ruwe rijen gaan NOOIT
 *                                    naar het model, alleen de samenvatting)
 *
 * Bewust GEEN ruwe CSV-rijen in de payload (zie eerdere discussie): een
 * samengevat rapport is goedkoper, minder gevoelig voor hallucinatie op ruis,
 * en kleiner qua prompt.
 */
object FclAiAdvisorDataCollector {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val ACTIVE_PARAMS_FILE = "FCLvNext_active_params.json"
    private const val LEARNER_LOG_FILE = "FCLvNext_LearnerLog_v1.csv"
    private const val CYCLE_LOG_FILE = "FCLvNext_Log_v7.csv"
    private const val MAX_NOTABLE_EPISODES = 5

    private val isoFmt = DateTimeFormatter.ISO_INSTANT

    private fun analyseDir(): File =
        File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)

    /**
     * @param periodHours hoeveel uur terug te kijken (standaard 24, dus "vandaag").
     * @param metrics     vandaag se EpisodeMetrics (uit dezelfde bron als AdvisorScreen/
     *                    Analyzescreen) — gebruikt voor tijd-tot-piek, post-piek-drop
     *                    en voorspelfout. Lege lijst → die drie velden blijven null,
     *                    de rest van het rapport (TIR/hypo/learner-events) werkt nog steeds.
     */
    fun collect(
        periodHours: Int = 24,
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics> = emptyList()
    ): FclDailyReportPayload {
        val dir = analyseDir()
        val nowUtc = Instant.now()
        val cutoff = nowUtc.minusSeconds(periodHours * 3600L)

        val activeParams = readActiveParams(File(dir, ACTIVE_PARAMS_FILE))
        val (tir, hypoCount, hypoMinutes, _, _, notable) =
            summariseCycleLog(File(dir, CYCLE_LOG_FILE), cutoff)
        val learnerSummary = summariseLearnerLog(File(dir, LEARNER_LOG_FILE), cutoff)
        val episodeStats = summariseEpisodeMetrics(metrics, cutoff)

        return FclDailyReportPayload(
            dateUtc = isoFmt.format(nowUtc),
            periodHours = periodHours,
            timeInRangePct = tir,
            hypoCount = hypoCount,
            hypoMinutesTotal = hypoMinutes,
            avgTimeToPeakMin = episodeStats.avgTimeToPeakMin,
            avgOvershootAfterPeakMmol = episodeStats.avgPostPeakDropMmol,
            avgPredictionErrorMmol = episodeStats.avgPredictionErrorMmol,
            activeParams = activeParams,
            learnerEventsSummary = learnerSummary,
            notableEpisodes = notable
        )
    }

    // ── EpisodeMetrics → tijd-tot-piek / post-piek-drop / voorspelfout ─────

    private data class EpisodeStats(
        val avgTimeToPeakMin: Double?,
        val avgPostPeakDropMmol: Double?,
        val avgPredictionErrorMmol: Double?
    )

    private fun summariseEpisodeMetrics(
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics>,
        cutoff: Instant
    ): EpisodeStats {
        val recent = metrics.filter { it.start.isAfter(cutoff) }
        if (recent.isEmpty()) return EpisodeStats(null, null, null)

        val timeToPeak = recent.mapNotNull { it.timeToPeakMinutes?.toDouble() }
        val avgTimeToPeak = if (timeToPeak.isNotEmpty()) timeToPeak.average() else null

        // Post-piek-drop: hoeveel BG na de piek terugviel binnen het post-window —
        // proxy voor "te veel insuline rond de piek", precies het 14:15-incidentpatroon.
        val postPeakDrops = recent.map { it.peakBg - it.minBgInWindow }
        val avgPostPeakDrop = if (postPeakDrops.isNotEmpty()) postPeakDrops.average() else null

        // Voorspelfout: gemiddelde |predFout0_20| en |predFout20_40| samen —
        // directe evidence voor peakPredictionThreshold/peakPredictionHorizonH/earlyPeakBiasMmol.
        val predErrors = recent.flatMap { listOfNotNull(it.predFout0_20, it.predFout20_40) }
        val avgPredError = if (predErrors.isNotEmpty()) predErrors.map { kotlin.math.abs(it) }.average() else null

        return EpisodeStats(avgTimeToPeak, avgPostPeakDrop, avgPredError)
    }

    // ── active_params.json ────────────────────────────────────────────────

    private fun readActiveParams(file: File): Map<String, ActiveParamSnapshot> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            val paramsObj = json.optJSONObject("params") ?: return emptyMap()
            val result = LinkedHashMap<String, ActiveParamSnapshot>()
            paramsObj.keys().forEach { key ->
                val entry = paramsObj.getJSONObject(key)
                result[key] = ActiveParamSnapshot(
                    active = entry.optDouble("active", Double.NaN),
                    default = entry.optDouble("default", Double.NaN),
                    src = entry.optString("src", "unknown")
                )
            }
            result
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    // ── FCLvNext_Log_v7.csv → TIR / hypo / piek-statistiek ─────────────────

    private data class CycleSummary(
        val timeInRangePct: Double,
        val hypoCount: Int,
        val hypoMinutes: Int,
        val avgTimeToPeakMin: Double?,
        val avgOvershootAfterPeakMmol: Double?,
        val notableEpisodes: List<String>
    )

    private fun summariseCycleLog(file: File, cutoff: Instant): CycleSummary {
        if (!file.exists()) return CycleSummary(0.0, 0, 0, null, null, emptyList())

        data class Row(val ts: Instant, val bg: Double, val target: Double, val iobRatio: Double, val bolus: Double)

        val rows = mutableListOf<Row>()
        file.forEachLine { line ->
            if (line.startsWith("schema_version")) return@forEachLine
            val cols = line.split(";")
            if (cols.size < 20) return@forEachLine
            try {
                val ts = Instant.parse(cols[1])
                if (ts.isBefore(cutoff)) return@forEachLine
                rows += Row(
                    ts = ts,
                    bg = cols[9].toDouble(),
                    target = cols[10].toDouble(),
                    iobRatio = cols[13].toDoubleOrNull() ?: 0.0,
                    bolus = cols[19].toDoubleOrNull() ?: 0.0
                )
            } catch (_: Exception) { /* corrupte/onvolledige regel overslaan */ }
        }
        if (rows.isEmpty()) return CycleSummary(0.0, 0, 0, null, null, emptyList())

        // TIR: 3.9–10.0 mmol, vaste klinische range (bewust niet target-relatief — TIR is per definitie absoluut)
        val inRange = rows.count { it.bg in 3.9..10.0 }
        val tir = 100.0 * inRange / rows.size

        // Hypo episodes: aaneengesloten reeksen met bg < 3.9
        var hypoCount = 0
        var hypoMinutes = 0
        var inHypo = false
        var prevTs: Instant? = null
        for (r in rows.sortedBy { it.ts }) {
            if (r.bg < 3.9) {
                if (!inHypo) { hypoCount++; inHypo = true }
                hypoMinutes += 5 // cyclus ≈ 5 min; grove schatting, voldoende voor een dagsamenvatting
            } else {
                inHypo = false
            }
            prevTs = r.ts
        }

        // Notable episodes: cycli met bolus > 1.0U terwijl iobRatio al >= 0.50 op het moment van doseren
        // (precies het patroon van het 14:15-incident) — max MAX_NOTABLE_EPISODES, meest recent eerst.
        val notable = rows.sortedByDescending { it.ts }
            .filter { it.bolus > 1.0 && it.iobRatio >= 0.50 }
            .take(MAX_NOTABLE_EPISODES)
            .map { "tijd=${it.ts} bg=${"%.1f".format(it.bg)} bolus=${"%.2f".format(it.bolus)}U iobRatio=${"%.2f".format(it.iobRatio)}" }

        // Tijd-tot-piek / overshoot / voorspelfout worden NIET hier berekend — die komen
        // uit summariseEpisodeMetrics() (EpisodeMetrics, dezelfde bron als AdvisorScreen),
        // niet gedupliceerd vanuit de ruwe CSV.
        return CycleSummary(tir, hypoCount, hypoMinutes, null, null, notable)
    }

    // ── FCLvNext_LearnerLog_v1.csv → korte samenvattingsregels ─────────────

    private fun summariseLearnerLog(file: File, cutoff: Instant): List<String> {
        if (!file.exists()) return emptyList()
        val lines = mutableListOf<String>()
        var header: List<String>? = null
        file.forEachLine { line ->
            val cols = line.split(";")
            if (header == null) { header = cols; return@forEachLine }
            val h = header ?: return@forEachLine
            val tsIdx = h.indexOf("ts_eval_utc")
            val typeIdx = h.indexOf("type")
            if (tsIdx < 0 || typeIdx < 0 || cols.size <= maxOf(tsIdx, typeIdx)) return@forEachLine
            val ts = try { Instant.parse(cols[tsIdx]) } catch (_: Exception) { return@forEachLine }
            if (ts.isBefore(cutoff)) return@forEachLine

            val blokIdx = h.indexOf("aanpassing_geblokt")
            val blocked = blokIdx in cols.indices && cols[blokIdx].isNotBlank()
            if (blocked) return@forEachLine // alleen daadwerkelijke aanpassingen samenvatten, geen ruis

            val type = cols[typeIdx]
            val summary = when (type) {
                "EB" -> summariseField(h, cols, "eb_old_boost", "eb_new_boost")?.let { "EarlyBoost-factor: $it" }
                "FRONTLOAD" -> summariseField(h, cols, "fl_oude_wff", "fl_nieuwe_wff")?.let { "WatchingFrontloadFrac: $it" }
                "EPISODE" -> summariseField(h, cols, "old_d", "new_d")?.let { "D-factor (sterkte): $it" }
                "V" -> summariseField(h, cols, "v_old_extra", "v_new_extra")?.let { "V-extra (volhoudendheid): $it" }
                else -> null
            }
            if (summary != null) lines += "$ts: $summary"
        }
        return lines
    }

    private fun summariseField(header: List<String>, cols: List<String>, oldKey: String, newKey: String): String? {
        val oldIdx = header.indexOf(oldKey)
        val newIdx = header.indexOf(newKey)
        if (oldIdx < 0 || newIdx < 0 || cols.size <= maxOf(oldIdx, newIdx)) return null
        val oldVal = cols[oldIdx].toDoubleOrNull() ?: return null
        val newVal = cols[newIdx].toDoubleOrNull() ?: return null
        if (oldVal == newVal) return null
        return "${"%.3f".format(oldVal)} → ${"%.3f".format(newVal)}"
    }

    private fun String.toDoubleOrNull(): Double? = try { this.toDouble() } catch (_: Exception) { null }
}
