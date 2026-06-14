package app.aaps.plugins.aps.openAPSFCL.vnext.persist

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger
import kotlin.math.abs

/**
 * VLearner — leert de "Volhoudendheid"-as (vExtra) op basis van
 * PersistentCorrectionController-fires.
 *
 * In tegenstelling tot DFLearner (per maaltijd-episode) werkt VLearner
 * op PERSIST-clusters: aaneengesloten reeksen cycli waarin de controller
 * `active==true` retourneert, ongeacht of er een maaltijd-episode loopt.
 * Dit dekt zowel postprandiale "vetrijke maaltijd"-plateaus als nachtelijke
 * persistente hoge BG bij lage IOB.
 *
 * ── Per-fire signaal (10 min na elke fire) ─────────────────────────────
 * Drempel afgeleid van persistCtrl's eigen detectiebanden, niet een losse
 * constante:
 *   - stableSlopeAbs (config.persistentSlopeAbs, default 0.32) en -0.60
 *     vormen samen de "persistent-band": binnen die band wordt BG als
 *     niet-zelfregulerend gezien.
 *   - responseFactor = (deltaToTarget / effectiveMinDelta).coerceIn(1.0, 3.0)
 *     hoe hoger BG boven target stond bij de fire, hoe meer daling
 *     redelijkerwijs verwacht mag worden.
 *   - vereisteSlope = -stableSlopeAbs * responseFactor
 *
 *   FORWARD (V te laag): slope_na10min > vereisteSlope
 *     → de bolus bracht de slope niet voorbij de eigen persistent-band,
 *       geschaald naar hoe hoog de BG stond.
 *   BACK (V te hoog): slope_na10min < -0.60
 *     → de bolus duwt de BG harder dan de detectie zelf als
 *       "niet-zelfregulerend" zou kwalificeren — overdosering.
 *   NONE: slope_na10min ∈ [-0.60, vereisteSlope]
 *     → de bolus werkte naar verwachting.
 *
 * ── Per-cluster signaal ──────────────────────────────────────────────
 * Eén cluster = aaneengesloten reeks events (geen gat groter dan
 * cooldownCycles+1 cycli zonder active==true). Per cluster wordt het
 * MEERDERHEIDSsignaal van de individuele fire-signalen genomen (bij
 * gelijke stand: NONE — veilige kant). Eén leerstap per cluster, niet
 * per fire, om dubbeltelling van escalatie binnen één fysiologische
 * situatie te voorkomen.
 *
 * ── Stappenmechanisme ────────────────────────────────────────────────
 * Zelfde stijl als earlyBoost: voorzichtige stap, bij FORWARD→BACK
 * oscillatie halveer de stap, vExtra geclipt op [-0.5, +0.5].
 */
object VLearner {

    private const val PREFS_NAME = "v_learner_prefs"

    private const val KEY_STEP        = "v_step_size"
    private const val KEY_LAST_SIG    = "v_last_signal"
    private const val KEY_PREV_SIG    = "v_prev_signal"
    private const val KEY_LAST_TS     = "v_last_eval_ts"
    private const val KEY_PROCESSED_UNTIL = "v_processed_until_ts"
    private const val KEY_HISTORY     = "v_history"

    /** Historie wordt op tijd beperkt (zie FCLHistoryUtil), niet op aantal. */
    private const val HISTORY_MAX_DAYS = 14

    /** Eén punt in de vExtra-geschiedenis, voor de 14-dagen lijngrafiek. */
    data class VHistoryPoint(
        val tsUtc: String,
        val vExtra: Double,
        val signal: String
    )

    private const val STEP_DEFAULT = 0.04   // vExtra-stap per cluster
    private const val STEP_MIN     = 0.01
    private const val STEP_MAX     = 0.10

    private const val VEXTRA_MIN = -0.5
    private const val VEXTRA_MAX =  0.5

    /** Cooldown van PersistentCorrectionController — zie FCLvNext.kt persistCtrl init. */
    private const val COOLDOWN_CYCLES = 2

    /** Responsvenster: 10 min = 2 cycli van 5 min na een fire. */
    private const val RESPONSE_CYCLES = 2
    private const val CYCLE_MS = 5L * 60 * 1000

    /** Gat (in cycli) groter dan dit sluit een cluster af. */
    private const val CLUSTER_GAP_CYCLES = COOLDOWN_CYCLES + 1

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getStepSize(context: Context): Double =
        prefs(context).getFloat(KEY_STEP, STEP_DEFAULT.toFloat()).toDouble()

    fun getLastSignal(context: Context): String =
        prefs(context).getString(KEY_LAST_SIG, "NONE") ?: "NONE"

    /**
     * Geschiedenis van vExtra-waarden voor de 14-dagen lijngrafiek.
     * Op tijd beperkt (niet op aantal): bij elke evaluate() worden punten
     * ouder dan HISTORY_MAX_DAYS verwijderd, ongeacht hoeveel punten dat zijn.
     */
    fun getHistory(context: Context): List<VHistoryPoint> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 3) return@mapNotNull null
            try {
                VHistoryPoint(tsUtc = p[0], vExtra = p[1].toDouble(), signal = p[2])
            } catch (_: Exception) { null }
        }
    }

    private fun appendHistory(context: Context, point: VHistoryPoint) {
        val cutoff = java.time.Instant.now().minus(
            HISTORY_MAX_DAYS.toLong(), java.time.temporal.ChronoUnit.DAYS
        )
        val existing = getHistory(context).filter { p ->
            try { java.time.Instant.parse(p.tsUtc).isAfter(cutoff) }
            catch (_: Exception) { false }
        }
        val all = (existing + point)
            .joinToString("\n") { "${it.tsUtc}|${it.vExtra}|${it.signal}" }
        prefs(context).edit().putString(KEY_HISTORY, all).apply()
    }

    /**
     * Evalueer alle PERSIST-events sinds de laatst verwerkte timestamp,
     * groepeer in clusters, bepaal per cluster het signaal, en pas
     * vExtra aan bij FORWARD/BACK. Retourneert een logregel-string of null.
     *
     * @param events Events gesorteerd op timestampMs ASC, uit FCLPersistEventDao.getSince().
     */
    fun evaluate(
        context: Context,
        events: List<FCLPersistEventEntity>,
        episodeMetrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics> = emptyList()
    ): String? {
        if (events.isEmpty()) return null

        val p = prefs(context)
        val processedUntil = p.getLong(KEY_PROCESSED_UNTIL, 0L)

        // Alleen nieuwe events sinds de laatste run
        val newEvents = events.filter { it.timestampMs > processedUntil }
        if (newEvents.isEmpty()) return null

        // ── Clustering ────────────────────────────────────────────────
        val clusters = mutableListOf<List<FCLPersistEventEntity>>()
        var current = mutableListOf<FCLPersistEventEntity>()
        var prevTs: Long? = null

        for (e in events) {
            if (prevTs != null) {
                val gapCycles = ((e.timestampMs - prevTs) / CYCLE_MS.toDouble())
                if (gapCycles > CLUSTER_GAP_CYCLES) {
                    if (current.isNotEmpty()) clusters.add(current)
                    current = mutableListOf()
                }
            }
            current.add(e)
            prevTs = e.timestampMs
        }
        if (current.isNotEmpty()) clusters.add(current)

        // Alleen clusters die volledig zijn afgesloten (er is een event NA de
        // cluster met een gap, OF de cluster eindigt ver genoeg vóór "nu" dat
        // het responsvenster van de laatste fire al verstreken is) EN waarvan
        // minstens één event nieuw is (timestampMs > processedUntil).
        val now = System.currentTimeMillis()
        val evaluableClusters = clusters.filter { cluster ->
            val lastTs = cluster.last().timestampMs
            val responseWindowPassed = (now - lastTs) >= RESPONSE_CYCLES * CYCLE_MS
            val hasNewEvent = cluster.any { it.timestampMs > processedUntil }
            val isLastCluster = cluster === clusters.last()
            // Een niet-laatste cluster is per definitie afgesloten (er volgde een gap).
            // De laatste cluster is alleen afgesloten als het responsvenster verstreken is.
            (!isLastCluster || responseWindowPassed) && hasNewEvent
        }

        if (evaluableClusters.isEmpty()) return null

        // Index alle events op timestamp voor snelle lookup van responsslope
        val byTs = events.associateBy { it.timestampMs }
        val sortedTs = events.map { it.timestampMs }.sorted()

        fun slopeAt(targetTs: Long): Double? {
            // Zoek het event op of net na targetTs (binnen 2 min marge)
            val candidate = sortedTs.firstOrNull { it >= targetTs }
                ?: return null
            if (candidate - targetTs > 2 * 60 * 1000) return null
            return byTs[candidate]?.slope
        }

        var lastProcessedTs = processedUntil
        var clusterSignal: String? = null
        var clusterStep: Double? = null
        var clusterDetail: FCLPersistEventEntity? = null

        for (cluster in evaluableClusters) {
            val fires = cluster.filter { it.fired }
            if (fires.isEmpty()) {
                lastProcessedTs = maxOf(lastProcessedTs, cluster.last().timestampMs)
                continue
            }

            val signals = fires.map { fire ->
                val responseTs = fire.timestampMs + RESPONSE_CYCLES * CYCLE_MS
                val slopeNa = slopeAt(responseTs)
                if (slopeNa == null) {
                    "NONE"
                } else {
                    val responseFactor = (fire.deltaToTarget / fire.effectiveMinDelta)
                        .coerceIn(1.0, 3.0)
                    val vereisteSlope = -fire.stableSlopeAbs * responseFactor
                    when {
                        slopeNa < -0.60         -> "BACK"
                        slopeNa > vereisteSlope -> "FORWARD"
                        else                    -> "NONE"
                    }
                }
            }

            // Meerderheidssignaal; bij gelijke stand → NONE
            val counts = signals.groupingBy { it }.eachCount()
            val maxCount = counts.values.maxOrNull() ?: 0
            val winners = counts.filterValues { it == maxCount }.keys
            val signal = if (winners.size == 1) winners.first() else "NONE"

            // Het laatste cluster met een niet-NONE signaal wint (meest recente,
            // meest representatieve situatie); bij gelijke "laatste" houden we de
            // eerste evaluatie aan om niet onnodig te wisselen tussen clusters
            // binnen dezelfde evaluate()-aanroep.
            if (clusterSignal == null || clusterSignal == "NONE") {
                clusterSignal = signal
                clusterDetail = fires.last()
            }

            lastProcessedTs = maxOf(lastProcessedTs, cluster.last().timestampMs)
        }

        // Sla processedUntil altijd op, ook als signaal NONE was
        p.edit().putLong(KEY_PROCESSED_UNTIL, lastProcessedTs).apply()

        if (clusterSignal == null || clusterSignal == "NONE") {
            val currentVExtra = DFLearner.getVExtra(context)
            FclLearnerLogger.logVLearner(
                signal     = "NONE",
                oldVExtra  = currentVExtra,
                newVExtra  = currentVExtra,
                step       = getStepSize(context),
                clusterFires = 0
            )
            appendHistory(context, VHistoryPoint(
                tsUtc  = java.time.Instant.now().toString(),
                vExtra = currentVExtra,
                signal = "NONE"
            ))
            p.edit().putString(KEY_PREV_SIG, getLastSignal(context))
                    .putString(KEY_LAST_SIG, "NONE")
                    .apply()
            return null
        }

        // ── Stap toepassen ────────────────────────────────────────────
        val lastSig = getLastSignal(context)
        var step = getStepSize(context)

        // Oscillatie: FORWARD direct gevolgd door BACK (of omgekeerd) → halveer stap
        if ((clusterSignal == "BACK" && lastSig == "FORWARD") ||
            (clusterSignal == "FORWARD" && lastSig == "BACK")) {
            step = (step / 2.0).coerceAtLeast(STEP_MIN)
        }

        val oldVExtra = DFLearner.getVExtra(context)
        val direction = if (clusterSignal == "FORWARD") 1.0 else -1.0
        val newVExtra = (oldVExtra + direction * step).coerceIn(VEXTRA_MIN, VEXTRA_MAX)

        DFLearner.setVExtra(context, newVExtra)

        p.edit()
            .putFloat(KEY_STEP, step.toFloat())
            .putString(KEY_PREV_SIG, lastSig)
            .putString(KEY_LAST_SIG, clusterSignal)
            .putLong(KEY_LAST_TS, now)
            .apply()

        FclLearnerLogger.logVLearner(
            signal       = clusterSignal,
            oldVExtra    = oldVExtra,
            newVExtra    = newVExtra,
            step         = step,
            clusterFires = evaluableClusters.lastOrNull { c -> c.any { it.fired } }
                ?.count { it.fired } ?: 0
        )

        appendHistory(context, VHistoryPoint(
            tsUtc  = java.time.Instant.now().toString(),
            vExtra = newVExtra,
            signal = clusterSignal
        ))

        // ── Aanvullend episodematig tbt-signaal ─────────────────────────────
        // Als de afterload-guard goed werkt, zijn er minder PERSIST-fires maar
        // kan de BG toch iets langer boven target blijven (tbt zonder hypo).
        // V kijkt daar normaal niet naar — aanvulling: als recente episodes
        // structureel tbtDetected=true zonder hypo, een zwak extra FORWARD-
        // signaal toevoegen (halve stap, geen oscillatie-check).
        if (episodeMetrics.isNotEmpty()) {
            val cutoff14 = java.time.Instant.now().minusSeconds(14L * 24 * 3600)
            val recentEpisodes = episodeMetrics.filter { m ->
                try { m.start.isAfter(cutoff14) } catch (_: Exception) { false }
            }
            val tbtEpisodes = recentEpisodes.count { it.tbtDetected && !it.hypoDetected }
            val tbtFrac = if (recentEpisodes.isNotEmpty())
                tbtEpisodes.toDouble() / recentEpisodes.size else 0.0

            // Meer dan 40% van de episodes had tbt zonder hypo → zwak FORWARD
            if (tbtFrac > 0.40 && recentEpisodes.size >= 3) {
                val currentV = DFLearner.getVExtra(context)
                val tbtStep  = (getStepSize(context) * 0.50).coerceAtLeast(STEP_MIN)
                val tbtV = (currentV + tbtStep).coerceIn(VEXTRA_MIN, VEXTRA_MAX)
                DFLearner.setVExtra(context, tbtV)
                appendHistory(context, VHistoryPoint(
                    tsUtc  = java.time.Instant.now().toString(),
                    vExtra = tbtV,
                    signal = "FORWARD"
                ))
            }
        }

        val richting = if (clusterSignal == "FORWARD") "→ hoger" else "← lager"
        return "VLEARNER $richting: vExtra ${"%.3f".format(oldVExtra)}" +
            "→${"%.3f".format(newVExtra)} step=${"%.4f".format(step)} [$clusterSignal]"
    }
}
