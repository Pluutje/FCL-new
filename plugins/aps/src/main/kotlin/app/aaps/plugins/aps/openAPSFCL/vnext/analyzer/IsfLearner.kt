package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import app.aaps.plugins.aps.openAPSFCL.vnext.persist.FCLPersistEventEntity
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import kotlin.math.abs

/**
 * IsfLearner (16/08/2026) — berekent per uur-van-de-dag (0-23, zelfde
 * blokgrootte als een AAPS-ISF-profiel) een voorgestelde %-verschuiving op
 * de profiel-ISF, puur datagedreven uit de eigen cyclus-historie. Geen AI:
 * dit is bewust de "NachtLearner-achtige" deterministische eerste stap —
 * FclIsfAutoAdjuster consumeert het resultaat in exact dezelfde vorm
 * (uur, %-shift, confidence) als FclNightBasalAutoAdjuster van de AI-
 * adviseur verwacht, zodat een latere AI-verrijking (zoals bij de nacht-
 * basaal) er zonder structuurwijziging naast of bovenop kan.
 *
 * ── Waarom PersistentCorrectionController-fires, niet maaltijd-episodes ──
 * EpisodeMetrics (de bron van DFLearner) mengt insuline- én koolhydraat-
 * effect door elkaar: tijdens een maaltijd daalt/stijgt BG door BEIDE
 * tegelijk, dus je kunt er geen betrouwbare ISF uit terugrekenen.
 * BELANGRIJKE CORRECTIE (16/08/2026, n.a.v. een terechte controlevraag):
 * een PersistentCorrectionController-fire is GEEN garantie op een maaltijd-
 * vrij moment. tickAndMaybeFire() wordt elke cyclus onvoorwaardelijk
 * aangeroepen (FCLvNext.kt, "PERSISTENT CORRECTION LOOP"), zonder enige
 * check op een actieve maaltijd-episode — hij vuurt puur op BG-kinetiek
 * (delta/slope/acceleratie/consistentie). Als BG tijdens een technisch nog
 * actieve episode toch even afvlakt (plateau), kan de controller daar
 * gewoon vuren, terwijl de koolhydraten van die maaltijd de BG-respons
 * erna nog beïnvloeden. Dat maakt zo'n fire ONGESCHIKT als "zuivere"
 * ISF-meting. evaluateFire() hieronder controleert dit daarom expliciet en
 * apart, via de bestaande mealEpisode-velden in FCLCycleLogEntity
 * (mealEpisodeId/minutesSinceMealStart, -1 = geen actieve episode):
 *   (a) op het fire-moment zelf mag er geen actieve episode lopen — TENZIJ
 *       die episode al minstens LATE_EPISODE_MIN_MINUTES loopt (verruimd
 *       18/08/2026, zie kdoc daar: zonder deze uitzondering overleefde in
 *       de praktijk vrijwel geen enkele fire deze check, want "persistent
 *       hoog en stabiel" is bijna per definitie ook "nog in de staart van
 *       een maaltijd"), EN
 *   (b) gedurende het hele antwoordvenster ná de fire (RESPONSE_WINDOW_
 *       MINUTES) mag geen ANDERE, nieuwe episode starten dan die van (a) —
 *       anders wordt de gemeten BG-daling mede door koolhydraten van die
 *       nieuwe maaltijd veroorzaakt, niet alleen door de dosis.
 * Kan het fire-moment niet met voldoende zekerheid worden gekoppeld aan een
 * cyclus-rij (geen rij dicht genoeg bij het fire-tijdstip), dan wordt de
 * meting ook verworpen — bij twijfel niet meetellen, in plaats van een
 * ongecontroleerd risico op een besmette meting te nemen.
 *
 * ── Methode per fire ─────────────────────────────────────────────────────
 *  1. BG vlak vóór de fire (al in FCLPersistEventEntity.bgMmol).
 *  2. BG rond RESPONSE_WINDOW_MINUTES ná de fire — opgezocht in de
 *     hoofd-cyclus-log (FCLCycleLogRepository), niet in fcl_persist_event
 *     zelf: een persist-cluster kan intussen allang zijn afgelopen
 *     (persistentie is niet vereist om de BG-respons te meten), dus de
 *     volledige cyclus-log dekt dat venster altijd, ongeacht clusterduur.
 *  3. "Schoon"-check: de som van alle commandedDose in het venster ná de
 *     fire (exclusief de fire's eigen dosis) mag niet meer zijn dan
 *     CONTAMINATION_MAX_U — anders heeft een latere, aparte dosis de
 *     BG-daling mede veroorzaakt en is deze meting niet bruikbaar.
 *  4. iobRatio op het moment van de fire mag niet al hoog zijn (te veel
 *     nog-actieve IOB van eerdere doses vertekent hoeveel van de daling aan
 *     ÉÉN dosis is toe te schrijven).
 *  5. impliedIsf = (bgVoor - bgNa) / doseU, alleen gebruikt als doseU groot
 *     genoeg is (ruis bij een piepklein dosisje) en de uitkomst binnen een
 *     fysiologisch plausibele bandbreedte valt (anders weggegooid als
 *     uitschieter, niet geclipt — een geclipte uitschieter zou de mediaan
 *     alsnog vertekenen).
 *
 * Per uur: bij minstens MIN_SAMPLES_PER_HOUR bruikbare metingen wordt de
 * MEDIAAN (robuust tegen uitschieters) vergeleken met de huidige profiel-ISF
 * voor dat uur. Confidence is datagedreven (aantal metingen × spreiding),
 * geen AI-zekerheid — zie kdoc bij HourSuggestion.
 */
object IsfLearner {

    private val AMSTERDAM = DateTimeZone.forID("Europe/Amsterdam")

    // ── Tunable grenzen (allemaal bewust behoudend gekozen; zie kdoc
    //    hierboven voor de redenering — bijstellen kan hier, geen effect op
    //    de rest van FCLvNext) ──────────────────────────────────────────
    const val RESPONSE_WINDOW_MINUTES = 60         // was 90 (18/08/2026, zie LATE_EPISODE_MIN_MINUTES
    // hieronder voor de volledige aanleiding) — korter venster = kleinere kans dat een VOLGENDE
    // maaltijd er nog in valt, ten koste van iets minder volledig uitgewerkte BG-respons. Bij een
    // fire-dosis van 0,5-0,9U (MIN_DOSE_U) is een groot deel van het effect binnen 60 min al zichtbaar.
    const val RESPONSE_WINDOW_SLACK_MINUTES = 15   // marge om een cyclus-rij dicht genoeg bij het venster te vinden
    const val CONTAMINATION_MAX_U = 0.30           // som overige dosis in het venster die nog als "schoon" telt
    const val MIN_DOSE_U = 0.50                    // te kleine fires geven te ruizige impliedIsf
    const val MAX_IOB_RATIO_AT_FIRE = 0.35         // hogere IOB-ratio = te veel nog-werkende insuline van eerder
    const val MIN_PLAUSIBLE_ISF = 0.8              // mmol/L per U — alles daarbuiten is een verworpen uitschieter,
    const val MAX_PLAUSIBLE_ISF = 12.0             // niet geclipt (zie kdoc)
    const val MIN_SAMPLES_PER_HOUR = 4
    const val LOOKBACK_DAYS = 14
    const val PRE_FIRE_LOOKUP_MARGIN_MINUTES = 6   // ~1 cyclus + jitter; te ver weg = meal-status op het fire-moment niet betrouwbaar vast te stellen
    const val MAX_SUGGESTED_SHIFT_PCT = 10.0       // lokale sanity-clamp; FclIsfAutoAdjuster past zijn eigen,
    // onafhankelijke caps daar nog eens overheen (drift-cap t.o.v. basisprofiel, dagtotaal-cap) — dit is bewust
    // dubbel: deze clamp voorkomt al dat één extreme uitschieteruur een absurde suggestie doorgeeft, de caps in
    // de adjuster bewaken vervolgens het PROFIEL als geheel, ook als hier per ongeluk toch iets groters doorkomt.

    // ── Late-episode uitzondering op de maaltijd-uitsluiting (18/08/2026) ──
    // AANLEIDING: PersistentCorrectionController vuurt per definitie tijdens
    // "BG persistent hoog en stabiel/langzaam dalend" — precies het profiel
    // van de staart van een maaltijd-episode, niet los daarvan. Backtest op
    // 19/8 liet zien dat over 2¾ dag ~16 fires plausibel waren, maar ALLE 16
    // door de toenmalige, strikte "geen enkele actieve episode toegestaan"-
    // regel werden verworpen (11 omdat de episode op het vuurmoment zelf al
    // 60-135 min liep, 5 omdat er binnen het antwoordvenster een NIEUWE
    // maaltijd startte) — netto 0 bruikbare metingen, structureel, niet
    // toevallig. Voor een gebruiker die regelmatig eet (ontbijt/lunch/
    // avondeten) vallen "persistent hoog" en "nog een beetje maaltijd-
    // effect" bijna altijd samen.
    //
    // Oplossing: een fire tijdens een AL LANG lopende episode (>=
    // LATE_EPISODE_MIN_MINUTES sinds maaltijdstart) telt niet meer als
    // "besmet", op voorwaarde dat de vuurcondities zelf al aantonen dat BG
    // niet meer actief door de maaltijd wordt opgestuwd (persistentCandidate
    // vereist immers al slope<=stableSlopeAbs, |accel|<=stableAccelAbs — een
    // bevestigd plateau, geen stijging). 105 minuten is bewust ruimer dan
    // het (nu kortere) RESPONSE_WINDOW_MINUTES: het late-genoeg-punt wordt
    // op het VUURMOMENT getoetst, niet op het einde van het antwoordvenster.
    // Blijft strikt voor verse/nog actief stijgende maaltijden — alleen de
    // reeds afgevlakte staart van een oude episode komt hierdoor in
    // aanmerking, nooit een episode die nog actief oploopt.
    const val LATE_EPISODE_MIN_MINUTES = 105

    // ── "Al optimaal"-drempel (18/08/2026, gedeeld met de UI-laag) ────────
    // Voorheen intern in computeSuggestions(): uren met een verwaarloosbaar
    // verschil (<1%) werden stilzwijgend WEGGELATEN uit het resultaat — geen
    // onderscheid zichtbaar tussen "nog geen data" en "data bevestigt dat
    // dit uur al goed staat". Op uitdrukkelijk verzoek (18/08/2026): dat
    // onderscheid is zelf nuttige informatie, dus deze uren blijven nu IN
    // de resultatenlijst staan (met een shift dicht bij 0%) en de UI-laag
    // (IsfAutoAdjustCard) gebruikt deze drempel om ze als "al optimaal" te
    // labelen i.p.v. als "voorstel" — publiek zodat beide lagen exact
    // dezelfde grens gebruiken, niet twee losse magic numbers.
    const val ALREADY_OPTIMAL_THRESHOLD_PCT = 1.0

    /** Eén schone correctie-meting, per uur gebucket. */
    private data class CleanReading(
        val hour: Int,
        val impliedIsf: Double
    )

    /** Eén uur-suggestie, zelfde vorm als een AI-suggestie bij de nacht-basaal
     *  (hourLabel/suggestedShiftPct/confidence) zodat FclIsfAutoAdjuster geen
     *  onderscheid hoeft te maken tussen een deterministische en een
     *  toekomstige AI-bron.
     *
     *  [confidence] is hier GEEN AI-zekerheid maar datagedreven: begint bij
     *  0.5 op MIN_SAMPLES_PER_HOUR metingen, loopt op met meer metingen en
     *  met een kleinere relatieve spreiding (IQR/mediaan) tussen de
     *  metingen, geclipt op [0.3, 0.95] — bewust nooit 1.0, dit blijft een
     *  schatting op een beperkt aantal punten. */
    data class HourSuggestion(
        val hour: Int,
        val suggestedShiftPct: Double,
        val confidence: Double,
        val sampleCount: Int
    )

    /**
     * @param currentIsfMgdlByHour de NU geldende profiel-ISF per uur (mg/dl per U —
     *   zelfde eenheid als profile.getIsfMgdl(), de aanroeper haalt dit per uur op;
     *   zie kdoc bij FclIsfAutoAdjuster voor de precieze profiel-accessor).
     */
    suspend fun computeSuggestions(
        repository: FCLCycleLogRepository,
        persistEvents: List<FCLPersistEventEntity>,
        currentIsfMgdlByHour: Map<Int, Double>,
        nowMs: Long
    ): List<HourSuggestion> {
        val sinceMs = nowMs - LOOKBACK_DAYS.toLong() * 24 * 60 * 60 * 1000L
        val fires = persistEvents.filter {
            it.fired && it.timestampMs >= sinceMs && it.doseU >= MIN_DOSE_U && it.iobRatio <= MAX_IOB_RATIO_AT_FIRE
        }
        if (fires.isEmpty()) return emptyList()

        val readings = mutableListOf<CleanReading>()
        for (fire in fires) {
            val reading = evaluateFire(repository, fire) ?: continue
            readings.add(reading)
        }
        if (readings.isEmpty()) return emptyList()

        val byHour = readings.groupBy { it.hour }
        val suggestions = mutableListOf<HourSuggestion>()
        for ((hour, group) in byHour) {
            if (group.size < MIN_SAMPLES_PER_HOUR) continue
            val currentIsf = currentIsfMgdlToMmol(currentIsfMgdlByHour[hour]) ?: continue
            if (currentIsf <= 0.0) continue

            val values = group.map { it.impliedIsf }.sorted()
            val median = medianOf(values)
            val iqr = iqrOf(values)
            val relSpread = if (median > 0.0) iqr / median else 1.0

            var shiftPct = (median / currentIsf - 1.0) * 100.0
            shiftPct = shiftPct.coerceIn(-MAX_SUGGESTED_SHIFT_PCT, MAX_SUGGESTED_SHIFT_PCT)

            // Confidence: start 0.5 bij precies MIN_SAMPLES_PER_HOUR, +0.05 per extra
            // meting (max +0.3), −tot 0.3 naarmate relSpread groter is (0 spreiding = geen
            // aftrek, spreiding >= 1.0x mediaan = volledige aftrek). Geclipt op [0.3, 0.95].
            val sampleBonus = (0.05 * (group.size - MIN_SAMPLES_PER_HOUR)).coerceIn(0.0, 0.30)
            val spreadPenalty = relSpread.coerceIn(0.0, 1.0) * 0.30
            val confidence = (0.5 + sampleBonus - spreadPenalty).coerceIn(0.3, 0.95)

            // Was: `if (abs(shiftPct) < 1.0) continue` — een uur met een
            // verwaarloosbaar verschil werd stilzwijgend weggelaten. Nu
            // bewust WEL toegevoegd (zie kdoc bij ALREADY_OPTIMAL_THRESHOLD_
            // PCT hierboven): "dit uur is al goed, bevestigd door data" is
            // net zo nuttig als een echte suggestie, en de UI-laag maakt het
            // onderscheid zichtbaar i.p.v. dit hier al te verbergen.
            suggestions.add(HourSuggestion(hour, shiftPct, confidence, group.size))
        }
        return suggestions
    }

    /** mg/dl per U → mmol/L per U (AAPS-profielen bewaren ISF altijd in mg/dl,
     *  ongeacht de weergave-eenheid van de gebruiker). */
    private fun currentIsfMgdlToMmol(mgdlPerU: Double?): Double? =
        mgdlPerU?.takeIf { it > 0.0 }?.div(18.0182)

    private suspend fun evaluateFire(
        repository: FCLCycleLogRepository,
        fire: FCLPersistEventEntity
    ): CleanReading? {
        val windowStart = fire.timestampMs
        val windowEnd = fire.timestampMs + RESPONSE_WINDOW_MINUTES * 60 * 1000L
        val slackMs = RESPONSE_WINDOW_SLACK_MINUTES * 60 * 1000L
        val preFireMarginMs = PRE_FIRE_LOOKUP_MARGIN_MINUTES * 60 * 1000L

        // Query start iets vóór windowStart, puur om ook de cyclus-rij op/vlak
        // vóór het fire-moment zelf te pakken te krijgen (voor de meal-check
        // hieronder) — verandert niets aan de respons-/besmettingslogica die
        // nog altijd strikt vanaf windowStart rekent.
        val rows = repository.getRowsInRange(windowStart - preFireMarginMs, windowEnd + slackMs)
        if (rows.isEmpty()) return null

        // Dichtstbijzijnde cyclus rond het volle venster (kan iets vóór of ná
        // windowEnd liggen door de 5-minuten-cyclusraster) — buiten de marge
        // om is er geen bruikbaar punt voor dit venster.
        val responseRow = rows.minByOrNull { abs(it.timestampMs - windowEnd) } ?: return null
        if (abs(responseRow.timestampMs - windowEnd) > slackMs) return null

        // ── Maaltijd-uitsluiting (16/08/2026, verruimd 18/08/2026) ──────
        // (a) Meal-status exact op het fire-moment: de dichtstbijzijnde rij
        //     rond windowStart moet binnen de opzoek-marge liggen — anders is
        //     er geen betrouwbaar antwoord op "was er toen een maaltijd
        //     actief" en wordt de meting bij twijfel verworpen.
        val fireRow = rows.minByOrNull { abs(it.timestampMs - windowStart) } ?: return null
        if (abs(fireRow.timestampMs - windowStart) > preFireMarginMs) return null
        val fireEpisodeId = fireRow.mealEpisode.mealEpisodeId
        val fireIsCleanNoMeal = fireEpisodeId == -1L
        // Late-episode uitzondering (zie kdoc bij LATE_EPISODE_MIN_MINUTES
        // hierboven): een fire tijdens een AL LANG lopende, afgevlakte
        // episode telt niet meer als besmet — de vuurcondities zelf tonen
        // al aan dat BG niet meer actief door de maaltijd wordt opgestuwd.
        val fireIsLateEnoughEpisode = !fireIsCleanNoMeal &&
            fireRow.mealEpisode.minutesSinceMealStart >= LATE_EPISODE_MIN_MINUTES
        if (!fireIsCleanNoMeal && !fireIsLateEnoughEpisode) return null

        // (b) Binnen het antwoordvenster (windowStart t/m windowEnd+slack)
        //     mag geen ANDERE (nieuwe) episode dan die van het fire-moment
        //     zelf verschijnen — de doorlopende, al-late episode van (a)
        //     hierboven is geen nieuw besmettingsrisico (die was al
        //     "toegestaan"), maar een écht NIEUWE maaltijd die tijdens het
        //     venster start (ander mealEpisodeId, altijd oplopend en dus
        //     uniek toegekend bij elke nieuwe episode — zie activeMeal-
        //     EpisodeId/mealEpisodeCounter in FCLvNext.kt) maakt de gemeten
        //     BG-daling niet meer zuiver aan de dosis toe te schrijven.
        val newMealDuringWindow = rows.any {
            it.timestampMs >= windowStart &&
                it.mealEpisode.mealEpisodeId != -1L &&
                it.mealEpisode.mealEpisodeId != fireEpisodeId
        }
        if (newMealDuringWindow) return null

        // Besmetting: alle dosis in het venster NA de fire zelf (dus rijen
        // strikt na windowStart) mag niet meer dan CONTAMINATION_MAX_U zijn.
        val laterDoseSum = rows.filter { it.timestampMs > windowStart }
            .sumOf { it.delivery.commandedDose }
        if (laterDoseSum > CONTAMINATION_MAX_U) return null

        val bgVoor = fire.bgMmol
        val bgNa = responseRow.glucoseIob.bg
        val impliedIsf = (bgVoor - bgNa) / fire.doseU

        if (!impliedIsf.isFinite()) return null
        if (impliedIsf < MIN_PLAUSIBLE_ISF || impliedIsf > MAX_PLAUSIBLE_ISF) return null

        val hour = DateTime(fire.timestampMs, AMSTERDAM).hourOfDay().get()
        return CleanReading(hour, impliedIsf)
    }

    private fun medianOf(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun iqrOf(sorted: List<Double>): Double {
        val n = sorted.size
        if (n < 4) return 0.0
        val q1 = sorted[n / 4]
        val q3 = sorted[(3 * n) / 4]
        return q3 - q1
    }
}
