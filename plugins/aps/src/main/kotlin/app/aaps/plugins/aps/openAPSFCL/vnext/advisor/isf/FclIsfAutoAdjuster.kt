package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf

import android.content.Context
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.IsfAutoAdjustLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sign

/**
 * ============================================================================
 * FCL ISF — automatisch bijstellen naar het échte AAPS-profiel
 * ============================================================================
 *
 * 16/08/2026. 1-op-1 gespiegeld aan FclNightBasalAutoAdjuster.kt (zie de
 * uitgebreide kdoc daar voor de volledige achtergrond van dit patroon) —
 * zelfde veiligheidslagen, zelfde OFF/AUTO/MANUAL-mechaniek, zelfde
 * validate→replace→createProfileSwitch-schrijfpad, EN dezelfde bewuste
 * code-duplicatie tussen de AUTO-tak (evaluateInternal) en applyPending()
 * i.p.v. een gedeelde schrijf-helper — precies om dezelfde reden als daar
 * gedocumenteerd: een gedeelde functie zou een expliciet AAPS-profieltype
 * in de signatuur nodig hebben, en die exacte klassenaam (van `current`/
 * `profiles[index]`/`effectiveProfile`) is buiten dit uploadpakket niet te
 * verifiëren. Lokale `val`-type-inferentie (geen signatuur, geen gok-type)
 * is hier de veilige keuze.
 *
 * Twee verschillen met de nacht-basaal-variant:
 *  A. De bron van de suggesties is hier IsfLearner (deterministisch, uit
 *     PersistentCorrectionController-fires — zie kdoc daar) i.p.v. een
 *     AI-adviseur. Confidence komt dus van IsfLearner.HourSuggestion, niet
 *     van een taalmodel. Een latere AI-verrijking kan zonder structuur-
 *     wijziging naast of bovenop deze suggesties landen (zelfde
 *     suggestion-vorm: uur/%-shift/confidence).
 *  B. Er is geen aparte async scheduler/pipeline nodig (geen HTTP-aanroep
 *     zoals bij de AI) — dit bestand doet zijn eigen dagelijkse dedup via
 *     IsfAutoAdjustLogDao.existsForDate(), zie evaluateInternal() hieronder.
 *
 * ── Profiel-accessors: geverifieerd tegen profile.zip (16/08/2026) ──────
 * Bij de eerste versie van dit bestand waren er twee onbevestigde aannames
 * over de AAPS-profiel-ISF-accessors (de basaal-varianten stonden al
 * elders in de codebase bevestigd, ISF nergens). Na een terechte
 * controlevraag zijn beide nagetrokken tegen de echte AAPS core
 * profile-interfaces (Profile.kt/SingleProfile.kt):
 *  1. `effectiveProfile.getIsfMgdlTimeFromMidnight(h * 3600)` — KLOPTE
 *     precies zoals afgeleid (Profile.kt regel 118).
 *  2. `.sens = ...` — KLOPTE NIET. SingleProfile.kt gebruikt `.isf`
 *     (JSONArray), niet `.sens` — die laatste naam hoort bij het
 *     losstaande, interne OapsProfileFCL/OapsProfileAutoIsf-datamodel
 *     (het oref0-porteringspad), niet bij AAPS's eigen profielopslag.
 *     Gecorrigeerd naar `.isf` op beide plekken (evaluateInternal()/
 *     applyPending()) vóórdat dit gecompileerd is — dus zonder dat dit
 *     ooit als foutieve build naar de gebruiker is gegaan.
 * Standaard nog altijd UIT (zie FclIsfAutoAdjustStore, default
 * FclSystemMode.OFF) — deze verificatie maakt AAN zetten nu wel veilig.
 */
object FclIsfAutoAdjuster {

    private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")

    private const val MAX_HOURLY_SHIFT_PCT = 10.0
    private const val DAILY_TOTAL_CAP_FRAC = 0.15
    private const val CUMULATIVE_DRIFT_CAP_FRAC = 0.20

    // ── Twee aparte drempels (16/08/2026, op verzoek — dev-fase) ───────────
    // Aanleiding: tijdens het ontwikkelen/testen duurt het met de "echte"
    // (productie-)drempel te lang voordat er überhaupt iets te zien is —
    // "deste eerder zien we of en hoe de code werkt". MIN_SAMPLES_TOTAL_
    // DISPLAY/MIN_AVG_CONFIDENCE_DISPLAY bepalen alleen of een dag wordt
    // GELOGD/GETOOND (tabel + grafiek in IsfAutoAdjustCard) — bewust laag,
    // zodat het eerste bruikbare uur al zichtbaar wordt.
    //
    // MIN_SAMPLES_TOTAL/MIN_AVG_CONFIDENCE blijven de ORIGINELE, strengere
    // waarden en zijn nu de "productie"-schrijfdrempel: een EXTRA, aparte
    // check vlak vóór de daadwerkelijke pomp-schrijfactie (zowel de AUTO-tak
    // in evaluateInternal() als, ter info in de UI, bij MANUAL's Accepteren).
    // Bewuste asymmetrie tussen AUTO en MANUAL: AUTO schrijft zonder mens
    // ertussen, dus AUTO mag NOOIT onder de productie-drempel schrijven, punt
    // uit — geen "extra waarschuwing" mogelijk zoals bij MANUAL, want er is
    // niemand om te waarschuwen. MANUAL heeft altijd al een mens die op
    // Accepteren klikt; daar volstaat een extra, expliciete waarschuwing in
    // de bevestigingsdialoog (zie IsfAutoAdjustCard.kt) i.p.v. een harde
    // blokkade — de gebruiker gaf zelf aan bewust te willen testen met lagere
    // drempels en eventuele voorstellen zelf handmatig terug te zetten.
    const val MIN_SAMPLES_TOTAL_DISPLAY = 4          // = IsfLearner.MIN_SAMPLES_PER_HOUR (één uur is al genoeg om te tonen)
    const val MIN_AVG_CONFIDENCE_DISPLAY = 0.30      // = ondergrens van IsfLearner's eigen confidence-clip
    const val MIN_SAMPLES_TOTAL = 20                 // productie-drempel — som van sampleCount over alle aangeraakte uren
    const val MIN_AVG_CONFIDENCE = 0.55              // productie-drempel

    // Zelfde gemiddelde-over-meerdere-dagen-ontwerp als FclNightBasalAutoAdjuster
    // (zie de uitgebreide kdoc daar, 27/07/2026) — hier "dagen" i.p.v. "nachten",
    // want ISF-metingen zijn niet aan nacht gebonden.
    const val MANUAL_COOLDOWN_DAYS = 3
    const val MANUAL_MAX_WINDOW_DAYS = 10
    const val AUTO_COOLDOWN_DAYS = 5

    // ── Interpolatie voor structureel maaltijd-geblokkeerde uren (16/08/2026) ──
    // Aanleiding: een terechte vraag — bepaalde uren (bijv. 13-14u, 21u bij
    // een eigen datacheck) zijn 88-94% van de tijd binnen een maaltijd-
    // episode, dus die krijgen nooit of nauwelijks een DIRECTE meting via
    // IsfLearner (zie de meal-uitsluiting daar). Zonder interpolatie blijven
    // zulke uren stilzwijgend op 0%-wijziging staan — dat oogt als "hier is
    // niets mis", terwijl het gewoon "hier is nooit gemeten" betekent.
    // In plaats daarvan: lineair interpoleren tussen de dichtstbijzijnde
    // uren mét een directe meting (cyclisch over 24 uur), MAAR gedempt in
    // sterkte — dit is een inschatting, geen meting. Bewust GEEN
    // parametrische dagcurve (bijv. een cosinor-fit met één vast piek/dal)
    // over het hele etmaal: dat legt een vorm op die niet per se bij ieders
    // patroon past (bijv. een aparte lunch- én avondpiek in plaats van één
    // gladde curve) — interpoleren tussen ECHTE metingen maakt de minste
    // aannames en blijft voor de gebruiker te herleiden naar concrete data.
    const val INTERPOLATION_MAX_GAP_HOURS = 4     // groter gat dan dit = te onzeker, blijft op 0%
    const val INTERPOLATION_SHIFT_DAMPING = 0.6   // geïnterpoleerde shift telt voor 60% mee t.o.v. een echte meting
    const val INTERPOLATION_CONFIDENCE_DAMPING = 0.7

    suspend fun daysSinceLastChange(context: Context, db: FCLAnalyzerDatabase): Int {
        val baselineSetAt = FclIsfAutoAdjustStore.getBaselineSetAt(context)
        val lastAppliedAt = db.isfAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L
        val lastChangeAt = maxOf(baselineSetAt, lastAppliedAt)
        if (lastChangeAt <= 0L) return 0
        val changeDate = java.time.Instant.ofEpochMilli(lastChangeAt).atZone(AMSTERDAM).toLocalDate()
        val today = LocalDate.now(AMSTERDAM)
        return java.time.temporal.ChronoUnit.DAYS.between(changeDate, today).toInt().coerceAtLeast(0)
    }

    private fun lastChangeDate(context: Context, lastAppliedAt: Long): LocalDate {
        val baselineSetAt = FclIsfAutoAdjustStore.getBaselineSetAt(context)
        val lastChangeAt = maxOf(baselineSetAt, lastAppliedAt)
        return if (lastChangeAt > 0L) java.time.Instant.ofEpochMilli(lastChangeAt).atZone(AMSTERDAM).toLocalDate()
        else LocalDate.MIN
    }

    private data class DailyShift(
        val localDate: String,
        val oldHourly: Map<Int, Double>,
        val shiftByHour: Map<Int, Double>,
        val samplesAnalyzed: Int,
        val avgConfidence: Double
    )

    private suspend fun collectRecentDailyShifts(
        db: FCLAnalyzerDatabase,
        since: LocalDate,
        maxDays: Int
    ): List<DailyShift> {
        val rows = db.isfAutoAdjustLogDao().getSinceDateDesc(since.toString())
            .filter { it.perHourShiftJson != "{}" }
        val byDate = LinkedHashMap<String, IsfAutoAdjustLogEntity>()
        for (row in rows) {
            if (!byDate.containsKey(row.localDate)) byDate[row.localDate] = row
        }
        return byDate.entries.take(maxDays).reversed().map { (date, row) ->
            DailyShift(
                localDate = date,
                oldHourly = jsonToHourlyMap(row.oldIsfJson),
                shiftByHour = jsonToHourlyMap(row.perHourShiftJson),
                samplesAnalyzed = row.samplesAnalyzed,
                avgConfidence = row.avgConfidence
            )
        }
    }

    private fun weightedAverageShift(days: List<DailyShift>): Map<Int, Double> {
        val result = HashMap<Int, Double>()
        for (h in 0..23) {
            var weightedSum = 0.0
            var weightTotal = 0.0
            days.forEachIndexed { idx, day ->
                val weight = (idx + 1).toDouble()
                weightedSum += (day.shiftByHour[h] ?: 0.0) * weight
                weightTotal += weight
            }
            result[h] = if (weightTotal > 0.0) weightedSum / weightTotal else 0.0
        }
        return result
    }

    /** Unie van alle uren die in minstens één dag in [days] een ECHTE,
     *  directe meting hadden (dus als key voorkwamen in dat dag's eigen,
     *  sparse shiftByHour-JSON) — nodig om "0% want nooit gemeten" te
     *  kunnen onderscheiden van "0% want gemeten en geen wijziging nodig"
     *  vóórdat weightedAverageShift() alles al tot een dichte 0..23-map
     *  heeft platgeslagen. */
    private fun touchedHoursOf(days: List<DailyShift>): Set<Int> {
        val touched = mutableSetOf<Int>()
        days.forEach { touched.addAll(it.shiftByHour.keys) }
        return touched
    }

    /** Vult uren zonder directe meting (zie [touchedHoursOf]) in door
     *  lineair te interpoleren tussen de dichtstbijzijnde WEL-gemeten
     *  buur-uren, cyclisch over de 24-uursklok — zie de uitgebreide kdoc
     *  bij INTERPOLATION_MAX_GAP_HOURS hierboven voor de onderbouwing.
     *  Uren waarvan beide buren verder dan INTERPOLATION_MAX_GAP_HOURS weg
     *  liggen (of waarvoor helemaal geen enkele meting bestaat) blijven op
     *  hun oorspronkelijke waarde (normaliter 0%) staan — bij te weinig
     *  houvast liever geen uitspraak dan een te ver doorgetrokken gok. */
    private fun interpolateGaps(
        directShift: Map<Int, Double>,
        touchedHours: Set<Int>
    ): Pair<Map<Int, Double>, Set<Int>> {
        if (touchedHours.isEmpty()) return directShift to emptySet()
        val result = HashMap<Int, Double>(directShift)
        val interpolated = mutableSetOf<Int>()
        val sortedTouched = touchedHours.sorted()
        for (h in 0..23) {
            if (h in touchedHours) continue
            val before = sortedTouched.filter { it < h }.maxOrNull() ?: (sortedTouched.last() - 24)
            val after = sortedTouched.filter { it > h }.minOrNull() ?: (sortedTouched.first() + 24)
            val gapBefore = h - before
            val gapAfter = after - h
            if (gapBefore > INTERPOLATION_MAX_GAP_HOURS || gapAfter > INTERPOLATION_MAX_GAP_HOURS) continue
            val beforeHour = ((before % 24) + 24) % 24
            val afterHour = ((after % 24) + 24) % 24
            val beforeVal = directShift[beforeHour] ?: 0.0
            val afterVal = directShift[afterHour] ?: 0.0
            val frac = gapBefore.toDouble() / (gapBefore + gapAfter)
            val interpolatedVal = (beforeVal + (afterVal - beforeVal) * frac) * INTERPOLATION_SHIFT_DAMPING
            result[h] = interpolatedVal
            interpolated.add(h)
        }
        return result to interpolated
    }

    private fun jsonToHourlyMap(json: String): Map<Int, Double> {
        val map = HashMap<Int, Double>()
        return try {
            val obj = JSONObject(json)
            obj.keys().forEach { k -> k.toIntOrNull()?.let { map[it] = obj.getDouble(k) } }
            map
        } catch (_: Exception) {
            map
        }
    }

    data class DailyProposal(
        val daysUsed: Int,
        val newestLocalDate: String,
        val oldHourly: Map<Int, Double>,
        val newHourly: Map<Int, Double>,
        val shiftByHour: Map<Int, Double>,
        val hoursAtCap: Set<Int>,
        // 16/08/2026 — uren waarvan de shift NIET uit een directe meting
        // komt maar via interpolateGaps() is afgeleid van de dichtstbijzijnde
        // gemeten buur-uren (zie kdoc bij INTERPOLATION_MAX_GAP_HOURS). Puur
        // voor UI-weergave ("afgeleid" i.p.v. "gemeten") — telt verder gewoon
        // mee in newHourly/shiftByHour en dezelfde caps als elk ander uur.
        val interpolatedHours: Set<Int> = emptySet(),
        // 16/08/2026 — uren met minstens één ECHTE directe meting in het
        // venster (ongeacht of die meting toevallig 0% shift opleverde) —
        // puur voor UI: onderscheidt "gemeten, geen wijziging nodig" van
        // "nooit gemeten en ook niet interpoleerbaar" (gat > MAX_GAP_HOURS).
        val touchedHours: Set<Int> = emptySet(),
        // 16/08/2026 — cijfers van de MEEST RECENTE dag in het venster, puur
        // voor UI: laat zien of het huidige voorstel de productie-schrijf-
        // drempel (MIN_SAMPLES_TOTAL/MIN_AVG_CONFIDENCE) al haalt, of dat
        // het alleen zichtbaar is dankzij de verlaagde weergave-gate
        // (dev-fase) — zie kdoc bij MIN_SAMPLES_TOTAL_DISPLAY.
        val latestSamplesAnalyzed: Int = 0,
        val latestAvgConfidence: Double = 0.0
    )

    suspend fun computeCurrentProposal(context: Context, maxDays: Int = MANUAL_MAX_WINDOW_DAYS): DailyProposal? {
        val db = FCLAnalyzerDatabase.getInstance(context)
        val lastAppliedAt = db.isfAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L
        val since = lastChangeDate(context, lastAppliedAt)
        val days = collectRecentDailyShifts(db, since, maxDays)
        if (days.isEmpty()) return null
        val directShift = weightedAverageShift(days)
        val touchedHours = touchedHoursOf(days)
        val (avgShift, interpolatedHours) = interpolateGaps(directShift, touchedHours)
        val oldHourly = days.last().oldHourly
        val baseline = FclIsfAutoAdjustStore.getBaseline(context) ?: oldHourly
        val (newHourly, hoursAtCap) = computeNewHourly(oldHourly, avgShift, baseline)
        return DailyProposal(
            days.size, days.last().localDate, oldHourly, newHourly, avgShift, hoursAtCap,
            interpolatedHours, touchedHours,
            latestSamplesAnalyzed = days.last().samplesAnalyzed,
            latestAvgConfidence = days.last().avgConfidence
        )
    }

    /**
     * Aan te roepen elke cyclus vanuit DetermineBasalFCL, net als
     * FclNightAiAdvisorScheduler.onCycle() — de dagelijkse dedup gebeurt
     * hieronder zelf (existsForDate), dus dit is verder een goedkope no-op
     * op elke cyclus behalve de eerste van een nieuwe dag.
     */
    fun onCycle(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository,
        cycleLogRepository: FCLCycleLogRepository
    ) {
        try {
            runBlocking {
                evaluateInternal(context, profileFunction, profileRepository, cycleLogRepository, isManualTrigger = false)
            }
        } catch (_: Exception) {
            // best-effort — zelfde filosofie als FclNightBasalAutoAdjuster: nooit een crash,
            // nooit de rest van de dosis-cyclus beïnvloeden.
        }
    }

    /** Voor een handmatige "Nu vernieuwen"-knop, negeert de dagelijkse dedup. */
    fun forceRunNow(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository,
        cycleLogRepository: FCLCycleLogRepository
    ) {
        if (FclIsfAutoAdjustStore.getMode(context) == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) return
        try {
            runBlocking {
                evaluateInternal(context, profileFunction, profileRepository, cycleLogRepository, isManualTrigger = true)
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    private suspend fun evaluateInternal(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository,
        cycleLogRepository: FCLCycleLogRepository,
        isManualTrigger: Boolean
    ) {
        val mode = FclIsfAutoAdjustStore.getMode(context)
        if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) return
        // Zelfde afspraak als bij de nacht-basaal: AUTO + handmatige trigger slaat
        // altijd over (nooit ongevraagd op de pomp schrijven via een "Nu vernieuwen"-knop).
        if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.AUTO && isManualTrigger) return

        val db = FCLAnalyzerDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val today = LocalDate.now(AMSTERDAM).toString()
        if (db.isfAutoAdjustLogDao().existsForDate(today) && !isManualTrigger) return

        // ── Huidig profiel + index in ProfileRepository ophalen ──
        val effectiveProfile = profileFunction.getProfile()
        if (effectiveProfile == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen actief profiel",
                   oldJson = "{}", newJson = "{}", shiftJson = "{}",
                   hoursAtCapCount = 0, samplesAnalyzed = 0, avgConfidence = 0.0)
            return
        }
        val originalName = profileFunction.getOriginalProfileName()
        val profiles = profileRepository.profiles.value
        val index = profiles.indexOfFirst { it.name == originalName }
        if (index == -1) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "profiel '$originalName' niet gevonden in ProfileRepository",
                   oldJson = "{}", newJson = "{}", shiftJson = "{}",
                   hoursAtCapCount = 0, samplesAnalyzed = 0, avgConfidence = 0.0)
            return
        }
        val current = profiles[index]

        // getIsfMgdlTimeFromMidnight() — geverifieerd correct, zie kdoc bovenaan dit bestand.
        val currentHourly = HashMap<Int, Double>()
        for (h in 0..23) currentHourly[h] = effectiveProfile.getIsfMgdlTimeFromMidnight(h * 3600)

        var baseline = FclIsfAutoAdjustStore.getBaseline(context)
        if (baseline == null) {
            FclIsfAutoAdjustStore.setBaseline(context, currentHourly, source = "initial", nowMs = now)
            baseline = currentHourly
        }

        // ── IsfLearner-suggesties ophalen ──
        val persistEvents = app.aaps.plugins.aps.openAPSFCL.vnext.persist.FCLPersistDatabase.getInstance(context)
            .persistEventDao().getSince(now - IsfLearner.LOOKBACK_DAYS.toLong() * 24 * 60 * 60 * 1000L)
        val suggestions = IsfLearner.computeSuggestions(
            repository = cycleLogRepository,
            persistEvents = persistEvents,
            currentIsfMgdlByHour = currentHourly,
            nowMs = now
        )
        if (suggestions.isEmpty()) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen bruikbare suggesties (te weinig schone correcties)",
                   oldJson = "{}", newJson = "{}", shiftJson = "{}",
                   hoursAtCapCount = 0, samplesAnalyzed = 0, avgConfidence = 0.0)
            return
        }

        val samplesAnalyzed = suggestions.sumOf { it.sampleCount }
        val avgConfidence = suggestions.map { it.confidence }.average()

        // ── Weergave-gate (dev-fase, verlaagd t.o.v. de productie-drempel —
        //    zie kdoc bij MIN_SAMPLES_TOTAL_DISPLAY hierboven) ──
        if (samplesAnalyzed < MIN_SAMPLES_TOTAL_DISPLAY || avgConfidence < MIN_AVG_CONFIDENCE_DISPLAY) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "weergave-gate: samples=$samplesAnalyzed (min $MIN_SAMPLES_TOTAL_DISPLAY) " +
                       "gem.confidence=${"%.2f".format(avgConfidence)} (min $MIN_AVG_CONFIDENCE_DISPLAY)",
                   oldJson = "{}", newJson = "{}", shiftJson = "{}",
                   hoursAtCapCount = 0, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val shiftByHour = HashMap<Int, Double>()
        suggestions.forEach { s -> shiftByHour[s.hour] = s.suggestedShiftPct.coerceIn(-MAX_HOURLY_SHIFT_PCT, MAX_HOURLY_SHIFT_PCT) }

        val (newHourly, hoursAtCap) = computeNewHourly(currentHourly, shiftByHour, baseline)
        FclIsfAutoAdjustStore.updateCapHitCounters(context, hoursAtCap, shiftByHour.keys)

        val oldJson = hourlyToJson(currentHourly)
        val newJson = hourlyToJson(newHourly)
        val shiftJson = JSONObject().apply { shiftByHour.forEach { (h, v) -> put(h.toString(), v) } }.toString()

        if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL) {
            logRow(db, now, today, mode, applied = false, skipReason = "",
                   oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                   hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        // ── mode == AUTO: eerst altijd de ruwe data van vandaag loggen ──
        logRow(db, now, today, mode, applied = false, skipReason = "",
               oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
               hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)

        // ── mode == AUTO: 1x per AUTO_COOLDOWN_DAYS dagen toepassen, met het
        //    gemiddelde van EXACT die dagen ──
        val daysSince = daysSinceLastChange(context, db)
        if (daysSince < AUTO_COOLDOWN_DAYS) return
        val window = collectRecentDailyShifts(db, lastChangeDate(context, db.isfAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L), AUTO_COOLDOWN_DAYS)
        if (window.size < AUTO_COOLDOWN_DAYS) return

        // ── AUTO-schrijf-drempel (productie, NIET de verlaagde weergave-
        //    gate hierboven) — AUTO schrijft zonder mens ertussen, dus deze
        //    check is hard en zonder uitzondering, ongeacht hoe laag de
        //    weergave-gate tijdens de dev-fase staat. ──
        val windowAvgSamples = window.map { it.samplesAnalyzed }.average()
        val windowAvgConfidence = window.map { it.avgConfidence }.average()
        if (windowAvgSamples < MIN_SAMPLES_TOTAL || windowAvgConfidence < MIN_AVG_CONFIDENCE) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "AUTO-schrijf-drempel (productie) niet gehaald: gem.samples=" +
                       "${"%.1f".format(windowAvgSamples)} (min $MIN_SAMPLES_TOTAL) gem.confidence=" +
                       "${"%.2f".format(windowAvgConfidence)} (min $MIN_AVG_CONFIDENCE) — de weergave-gate " +
                       "staat lager (dev-fase), maar AUTO schrijft alleen op de originele, strengere drempel.",
                   oldJson = oldJson, newJson = oldJson, shiftJson = "{}",
                   hoursAtCapCount = 0, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val directAvgShift = weightedAverageShift(window)
        val (avgShift, _) = interpolateGaps(directAvgShift, touchedHoursOf(window))
        val (avgNewHourly, avgHoursAtCap) = computeNewHourly(currentHourly, avgShift, baseline)
        FclIsfAutoAdjustStore.updateCapHitCounters(context, avgHoursAtCap, avgShift.keys)
        val avgOldJson = oldJson
        val avgNewJson = hourlyToJson(avgNewHourly)
        val avgShiftJson = JSONObject().apply { avgShift.forEach { (h, v) -> put(h.toString(), v) } }.toString()

        // ── daadwerkelijk toepassen (met het gemiddelde, niet losse dag) ──
        // .isf hieronder — geverifieerd tegen SingleProfile.kt (profile.zip, 16/08/2026): bevestigd correct.
        val newSingleProfile = current.deepClone().apply { isf = buildIsfJsonArray(avgNewHourly) }

        val errors = profileRepository.validateStructured(newSingleProfile)
        if (errors.isNotEmpty()) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "validatie geweigerd: " + errors.joinToString { it.message },
                   oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                   hoursAtCapCount = avgHoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val replaceResult = profileRepository.replace(index, newSingleProfile)
        if (replaceResult.isFailure) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "ProfileRepository.replace() mislukt: ${replaceResult.exceptionOrNull()?.message}",
                   oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                   hoursAtCapCount = avgHoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val newProfileStore = profileRepository.profile.value
        if (newProfileStore == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen ProfileStore na replace()",
                   oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                   hoursAtCapCount = avgHoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val ps = profileFunction.createProfileSwitch(
            profileStore = newProfileStore,
            profileName = current.name,
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Aaps,
            note = "FCL ISF-auto (gemiddelde over ${window.size} dagen): " + avgShift.entries
                .sortedBy { it.key }
                .joinToString { (h, pct) -> "%02d:00 %+.1f%%".format(h, pct) },
            listValues = emptyList(),
            iCfg = effectiveProfile.iCfg
        )
        if (ps == null) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "createProfileSwitch() geweigerd (validatie/pompcompatibiliteit)",
                   oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                   hoursAtCapCount = avgHoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
            return
        }

        logRow(db, now, today, mode, applied = true, skipReason = "",
               oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
               hoursAtCapCount = avgHoursAtCap.size, samplesAnalyzed = samplesAnalyzed, avgConfidence = avgConfidence)
    }

    /**
     * Accepteren van het openstaande MANUAL-voorstel — zelfde rol als
     * FclNightBasalAutoAdjuster.applyPending(). Bewust NIET via de gedeelde
     * schrijf-logica van evaluateInternal() (zie kdoc bovenaan dit bestand).
     */
    suspend fun applyPending(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository
    ): Boolean {
        val db = FCLAnalyzerDatabase.getInstance(context)
        val lastAppliedAt = db.isfAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L
        val since = lastChangeDate(context, lastAppliedAt)
        val window = collectRecentDailyShifts(db, since, MANUAL_MAX_WINDOW_DAYS)
        if (window.size < MANUAL_COOLDOWN_DAYS) return false
        val directShift = weightedAverageShift(window)
        val (shiftByHour, _) = interpolateGaps(directShift, touchedHoursOf(window))
        val newest = window.last()

        val effectiveProfile = profileFunction.getProfile() ?: return false
        val originalName = profileFunction.getOriginalProfileName()
        val profiles = profileRepository.profiles.value
        val index = profiles.indexOfFirst { it.name == originalName }
        if (index == -1) return false
        val current = profiles[index]

        // getIsfMgdlTimeFromMidnight() — geverifieerd correct, zie kdoc bovenaan dit bestand.
        val currentHourly = HashMap<Int, Double>()
        for (h in 0..23) currentHourly[h] = effectiveProfile.getIsfMgdlTimeFromMidnight(h * 3600)
        val baseline = FclIsfAutoAdjustStore.getBaseline(context) ?: currentHourly

        val (newHourly, hoursAtCap) = computeNewHourly(currentHourly, shiftByHour, baseline)
        FclIsfAutoAdjustStore.updateCapHitCounters(context, hoursAtCap, shiftByHour.keys)

        val now = System.currentTimeMillis()
        val today = LocalDate.now(AMSTERDAM).toString()
        val mode = app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL
        val oldJson = hourlyToJson(currentHourly)
        val newJson = hourlyToJson(newHourly)
        val shiftJson = JSONObject().apply { shiftByHour.forEach { (h, v) -> put(h.toString(), v) } }.toString()

        // .isf hieronder — geverifieerd tegen SingleProfile.kt (profile.zip, 16/08/2026): bevestigd correct.
        val newSingleProfile = current.deepClone().apply { isf = buildIsfJsonArray(newHourly) }

        val errors = profileRepository.validateStructured(newSingleProfile)
        if (errors.isNotEmpty()) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "validatie geweigerd: " + errors.joinToString { it.message },
                   oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                   hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = newest.samplesAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        val replaceResult = profileRepository.replace(index, newSingleProfile)
        if (replaceResult.isFailure) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "ProfileRepository.replace() mislukt: ${replaceResult.exceptionOrNull()?.message}",
                   oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                   hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = newest.samplesAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        val newProfileStore = profileRepository.profile.value
        if (newProfileStore == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen ProfileStore na replace()",
                   oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                   hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = newest.samplesAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        val ps = profileFunction.createProfileSwitch(
            profileStore = newProfileStore,
            profileName = current.name,
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Aaps,
            note = "FCL ISF-auto (geaccepteerd, gemiddelde over ${window.size} dagen): " + shiftByHour.entries
                .sortedBy { it.key }
                .joinToString { (h, pct) -> "%02d:00 %+.1f%%".format(h, pct) },
            listValues = emptyList(),
            iCfg = effectiveProfile.iCfg
        )
        if (ps == null) {
            logRow(db, now, today, mode, applied = false,
                   skipReason = "createProfileSwitch() geweigerd (validatie/pompcompatibiliteit)",
                   oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                   hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = newest.samplesAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        logRow(db, now, today, mode, applied = true, skipReason = "",
               oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
               hoursAtCapCount = hoursAtCap.size, samplesAnalyzed = newest.samplesAnalyzed, avgConfidence = newest.avgConfidence)
        return true
    }

    private fun computeNewHourly(
        currentHourly: Map<Int, Double>,
        shiftByHour: Map<Int, Double>,
        baseline: Map<Int, Double>
    ): Pair<Map<Int, Double>, Set<Int>> {
        val hoursAtCap = mutableSetOf<Int>()
        val newHourly = HashMap<Int, Double>()
        var totalOld = 0.0
        var totalNew = 0.0
        for (h in 0..23) {
            val curVal = currentHourly[h] ?: 0.0
            totalOld += curVal
            val shiftPct = shiftByHour[h]
            var newVal = if (shiftPct != null) cleanPrecision(curVal * (1.0 + shiftPct / 100.0)) else curVal
            if (shiftPct != null) {
                val clamped = clampToBaseline(newVal, baseline[h] ?: curVal)
                if (clamped != newVal) hoursAtCap.add(h)
                newVal = clamped
            }
            newHourly[h] = newVal
            totalNew += newVal
        }

        if (totalOld > 0.0 && abs(totalNew - totalOld) / totalOld > DAILY_TOTAL_CAP_FRAC) {
            val allowedTotal = totalOld * (1.0 + DAILY_TOTAL_CAP_FRAC * sign(totalNew - totalOld))
            val deltaWanted = totalNew - totalOld
            val deltaAllowed = allowedTotal - totalOld
            val scale = if (deltaWanted != 0.0) deltaAllowed / deltaWanted else 0.0
            for (h in 0..23) {
                val curVal = currentHourly[h] ?: 0.0
                val delta = (newHourly[h] ?: curVal) - curVal
                var scaledVal = cleanPrecision(curVal + delta * scale)
                if (shiftByHour.containsKey(h)) {
                    val clamped = clampToBaseline(scaledVal, baseline[h] ?: curVal)
                    if (clamped != scaledVal) hoursAtCap.add(h)
                    scaledVal = clamped
                }
                newHourly[h] = scaledVal
            }
        }
        return newHourly to hoursAtCap
    }

    private fun clampToBaseline(value: Double, baselineValue: Double): Double {
        val low = cleanPrecision(baselineValue * (1.0 - CUMULATIVE_DRIFT_CAP_FRAC)).coerceAtLeast(1.0)
        val high = cleanPrecision(baselineValue * (1.0 + CUMULATIVE_DRIFT_CAP_FRAC))
        return value.coerceIn(low, high)
    }

    private fun cleanPrecision(value: Double): Double {
        return Math.round(value * 1000.0) / 1000.0
    }

    private fun hourlyToJson(hourly: Map<Int, Double>): String {
        val obj = JSONObject()
        for (h in 0..23) obj.put(h.toString(), hourly[h] ?: 0.0)
        return obj.toString()
    }

    /** JSON-arrayvorm is hetzelfde als buildBasalJsonArray() in
     *  FclNightBasalAutoAdjuster.kt (time/timeAsSeconds/value); de
     *  doelveld-naam (.isf) is geverifieerd, zie kdoc bovenaan dit bestand. */
    private fun buildIsfJsonArray(hourly: Map<Int, Double>): JSONArray {
        val arr = JSONArray()
        for (h in 0..23) {
            arr.put(
                JSONObject()
                    .put("time", "%02d:00".format(h))
                    .put("timeAsSeconds", h * 3600)
                    .put("value", hourly[h] ?: 0.0)
            )
        }
        return arr
    }

    private suspend fun logRow(
        db: FCLAnalyzerDatabase,
        now: Long,
        today: String,
        mode: app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode,
        applied: Boolean,
        skipReason: String,
        oldJson: String,
        newJson: String,
        shiftJson: String,
        hoursAtCapCount: Int,
        samplesAnalyzed: Int,
        avgConfidence: Double
    ) {
        db.isfAutoAdjustLogDao().insert(
            IsfAutoAdjustLogEntity(
                timestampMs = now,
                localDate = today,
                mode = mode.name,
                applied = applied,
                skipReason = skipReason,
                oldIsfJson = oldJson,
                newIsfJson = newJson,
                perHourShiftJson = shiftJson,
                hoursAtCapCount = hoursAtCapCount,
                samplesAnalyzed = samplesAnalyzed,
                avgConfidence = avgConfidence
            )
        )
    }
}
