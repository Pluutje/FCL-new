package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import android.content.Context
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.ProfileAutoAdjustLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sign

/**
 * ============================================================================
 * FCL Nacht-basaal — automatisch bijstellen naar het échte AAPS-profiel
 * ============================================================================
 *
 * 24/07/2026 (Ecko). Ontstaan uit de vraag of het AI-nachtadvies (per-uur
 * %-verschuivingen, al getaperd/geclamped in FclNightAiAdvisorResponseParser)
 * automatisch in het echte pompprofiel kan worden doorgevoerd, i.p.v. steeds
 * handmatig — met als motivatie dat AAPS's eigen IOB/autosens-berekening het
 * profiel als "nul-lijn" gebruikt (bevestigd in IobCobCalculatorPlugin.kt:
 * calculateAbsoluteIobFromBaseBasals()/getBasalData() lezen profile.getBasal()
 * rechtstreeks), dus een nauwkeuriger profiel de hele keten ten goede komt —
 * ook als FCLvNext zelf al zijn eigen, actuele temp-basal aanstuurt.
 *
 * Alleen aangeroepen vanuit de DAGELIJKSE, nacht-rand-getriggerde route in
 * FclNightAiAdvisorScheduler (nooit vanuit "Nu vernieuwen") — zie kdoc daar.
 *
 * Veiligheidslagen, van klein naar groot:
 *  1. Per-uur-clamp (al gedaan door de AI-parser, hier defensief herhaald).
 *  2. Confidence-gate: te weinig nachten of te lage gemiddelde AI-confidence
 *     → helemaal niet toepassen (een advies TONEN bij twijfel is prima, het
 *     automatisch DOORVOEREN niet).
 *  3. Dagtotaal-cap: de optelling van alle uur-wijzigingen samen mag niet
 *     meer dan [DAILY_TOTAL_CAP_FRAC] van het huidige dagtotaal verschuiven.
 *  4. Cumulatieve-drift-cap: geen enkel uur mag ooit meer dan
 *     [CUMULATIVE_DRIFT_CAP_FRAC] afwijken van een vast, persistent
 *     basisprofiel (FclNightBasalAutoAdjustStore) — bewust NIET t.o.v.
 *     "gisteren", anders betekent de cap na verloop van tijd niets meer.
 *     Basisprofiel wordt bij de eerste run automatisch vastgelegd, en
 *     daarna alleen nog via een expliciete, handmatige "opnieuw
 *     vastleggen"-actie in de UI gewijzigd.
 *  5. Max 1x per dag voor de AUTOMATISCHE route (via
 *     ProfileAutoAdjustLogDao.existsForDate()) — een handmatige "Nu
 *     vernieuwen"-trigger negeert deze cap bewust (26/07/2026, Ecko: anders
 *     blokkeert een eerdere run van vandaag, ongeacht de uitkomst, elke
 *     latere handmatige verversing tot middernacht), wat geen
 *     veiligheidsrisico geeft omdat een handmatige trigger sowieso nooit
 *     zelf naar de pomp schrijft (zie punt 6).
 *  6. Modus UIT/AUTOMATISCH/HANDMATIG (gedeelde FclSystemMode, zie
 *     FclNightBasalAutoAdjustStore) — alleen bij AUTOMATISCH wordt er
 *     daadwerkelijk iets naar ProfileRepository/ProfileFunction geschreven;
 *     bij HANDMATIG wordt precies hetzelfde berekend en als openstaand
 *     voorstel gelogd (zie applyPending()/computeCurrentProposal()
 *     hieronder), nooit automatisch toegepast. Er is bewust geen
 *     "Afwijzen"-actie meer: niet activeren betekent al niet toegepast, en
 *     "Basisprofiel opnieuw vastleggen" is het expliciete reset-mechanisme
 *     (27/07/2026, Ecko: een aparte afwijsknop zou het gemiddelde-venster
 *     onduidelijk kunnen resetten).
 *  7. Best-effort: elke fout (geen profiel, validatie geweigerd,
 *     ProfileRepository/ProfileFunction faalt) wordt gelogd met reden en
 *     stopt daar — nooit een crash, nooit het AI-rapport zelf beïnvloed.
 *
 * 24/07/2026, correctie n.a.v. compilefout: `ProfileSwitchSilentGate` bleek
 * te leven in `app.aaps.implementation.profile` — een module waar de
 * openAPSFCL-plugin (dit bestand) geen compile-afhankelijkheid van heeft
 * (bevestigd: geen enkele van de zusterplugins AIMI/Boost/SMB gebruikt ooit
 * iets uit `app.aaps.implementation.*`). Verwijderd. Gevolg: een automatisch
 * toegepaste profielwijziging toont voortaan gewoon de standaard "Basal
 * profile in pump updated"-melding, net als elke andere profielwijziging —
 * geen functioneel of veiligheidsverlies, alleen geen stille toepassing.
 * `Sources.Automation` (eerder een gok) is vervangen door `Sources.Aaps`,
 * bevestigd via bestaand gebruik in openAPSBoost/OpenAPSBoostPlugin.kt en
 * openAPSAIMI/context/ContextManager.kt — beide in `app.aaps.core.data.ue`,
 * wat de plugin wél kan zien.
 */
object FclNightBasalAutoAdjuster {

    private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")

    private const val MAX_HOURLY_SHIFT_PCT = 8.0
    private const val DAILY_TOTAL_CAP_FRAC = 0.15
    private const val CUMULATIVE_DRIFT_CAP_FRAC = 0.25
    private const val MIN_NIGHTS_ANALYZED = 5
    private const val MIN_AVG_CONFIDENCE = 0.55
    private const val BASAL_ROUND_STEP = 0.05

    // 27/07/2026 (Ecko) — wachtperiode + gemiddelde over meerdere nachten,
    // op verzoek: "ik heb gister de basaal aangepast en nu na 1 nacht komt
    // hij weer met een voorstel om te verlagen". nightsAnalyzed hierboven is
    // een ROLLEND venster over meerdere dagen data (voor de AI-zekerheid);
    // het zegt niets over hoe lang het HUIDIGE profiel al loopt.
    //
    // Ontwerp (herzien 27/07/2026, n.a.v. verdere terugkoppeling): niet
    // langer "laat hetzelfde losse nachtvoorstel zien totdat de wachttijd
    // om is", maar een écht (gewogen) GEMIDDELDE over de nachten sinds de
    // laatste wijziging:
    //  - HANDMATIG: elke nacht wordt gewoon los gelogd (ongewijzigd, zie de
    //    MANUAL-tak hieronder). Accepteren verschijnt pas vanaf
    //    MANUAL_COOLDOWN_NIGHTS nachten, en toont dan het gewogen gemiddelde
    //    over ALLE beschikbare nachten sinds de wijziging — dat venster
    //    groeit vanzelf mee (2, 3, 4, ... ) tot een rollend maximum van
    //    MANUAL_MAX_WINDOW_NIGHTS (de oudste nacht valt er dan weer af).
    //  - AUTOMATISCH: schrijft niet meer elke nacht, maar precies 1x per
    //    AUTO_COOLDOWN_NIGHTS nachten, en dan met het gemiddelde van EXACT
    //    die laatste AUTO_COOLDOWN_NIGHTS nachten (Ecko's eigen suggestie:
    //    "misschien is dan 3 nachten wachten zelfs wel beter"). Na een
    //    toepassing begint de teller vanzelf weer op 0 (zie
    //    nightsSinceLastChange()), dus dit herhaalt zich vanzelf.
    //
    // Zie collectRecentNightlyShifts()/weightedAverageShift() hieronder voor
    // de daadwerkelijke verzameling/middeling, en computeCurrentProposal()
    // voor de UI-laag (Advisorscreen.kt).
    const val MANUAL_COOLDOWN_NIGHTS = 2
    const val MANUAL_MAX_WINDOW_NIGHTS = 7
    const val AUTO_COOLDOWN_NIGHTS = 3

    /**
     * Aantal volle kalendernachten sinds de laatste écht doorgevoerde
     * wijziging — het latere van (a) het moment waarop het basisprofiel
     * voor het laatst is vastgelegd (initieel, of via "Basisprofiel
     * opnieuw vastleggen") en (b) de laatste daadwerkelijk toegepaste rij
     * (Accepteren bij MANUAL, of een automatische toepassing). Beide zijn
     * een signaal van "dit profiel is bewust net zo neergezet" en resetten
     * dus de wachtperiode. Nooit een wijziging geweest → 0 (geen wachttijd,
     * dan is er ook niets om tegen te wachten).
     */
    suspend fun nightsSinceLastChange(context: Context, db: FCLAnalyzerDatabase): Int {
        val baselineSetAt = FclNightBasalAutoAdjustStore.getBaselineSetAt(context)
        val lastAppliedAt = db.profileAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L
        val lastChangeAt = maxOf(baselineSetAt, lastAppliedAt)
        if (lastChangeAt <= 0L) return 0
        val changeDate = java.time.Instant.ofEpochMilli(lastChangeAt).atZone(AMSTERDAM).toLocalDate()
        val today = LocalDate.now(AMSTERDAM)
        return java.time.temporal.ChronoUnit.DAYS.between(changeDate, today).toInt().coerceAtLeast(0)
    }

    private fun lastChangeDate(context: Context, db: FCLAnalyzerDatabase, lastAppliedAt: Long): LocalDate {
        val baselineSetAt = FclNightBasalAutoAdjustStore.getBaselineSetAt(context)
        val lastChangeAt = maxOf(baselineSetAt, lastAppliedAt)
        return if (lastChangeAt > 0L) java.time.Instant.ofEpochMilli(lastChangeAt).atZone(AMSTERDAM).toLocalDate()
        else LocalDate.MIN
    }

    /** Eén nacht se ruwe AI-voorstel, zoals gelogd (dus vóór middeling). */
    private data class NightlyShift(
        val localDate: String,
        val oldHourly: Map<Int, Double>,
        val shiftByHour: Map<Int, Double>,
        val nightsAnalyzed: Int,
        val avgConfidence: Double
    )

    /**
     * 27/07/2026 (Ecko) — verzamelt de rijen NA [since] (exclusief) die een
     * echte berekening bevatten (perHourShiftJson != "{}" — skip-rijen zoals
     * confidence-gate/geen-profiel tellen dus niet mee), reduceert tot de
     * MEEST RECENTE rij per kalenderdag (meerdere "Nu vernieuwen"-klikken op
     * dezelfde dag tellen dus als 1 nacht), en houdt van de nieuwste
     * [maxNights] dagen over. Retourneert chronologisch OPLOPEND (oudste
     * eerst) — handig voor recency-weging in weightedAverageShift().
     */
    private suspend fun collectRecentNightlyShifts(
        db: FCLAnalyzerDatabase,
        since: LocalDate,
        maxNights: Int
    ): List<NightlyShift> {
        val rows = db.profileAutoAdjustLogDao().getSinceDateDesc(since.toString())
            .filter { it.perHourShiftJson != "{}" }
        val byDate = LinkedHashMap<String, ProfileAutoAdjustLogEntity>()
        for (row in rows) {
            // rows is al nieuwste-eerst, dus de EERSTE keer dat een datum
            // voorkomt is meteen de meest recente rij van die datum.
            if (!byDate.containsKey(row.localDate)) byDate[row.localDate] = row
        }
        return byDate.entries.take(maxNights).reversed().map { (date, row) ->
            NightlyShift(
                localDate = date,
                oldHourly = jsonToHourlyMap(row.oldBasalJson),
                shiftByHour = jsonToHourlyMap(row.perHourShiftJson),
                nightsAnalyzed = row.nightsAnalyzed,
                avgConfidence = row.avgConfidence
            )
        }
    }

    /**
     * (Eventueel) gewogen gemiddelde per uur over [nights] — chronologisch
     * oplopend verwacht (oudste eerst, zie collectRecentNightlyShifts()).
     * Recency-weging: lineair, oudste nacht weegt 1x, nieuwste weegt Nx —
     * een nacht die net iets langer geleden is telt dus nog steeds mee, maar
     * de recentste nacht(en) hebben het meeste gewicht. Bij 1 nacht is dit
     * triviaal gelijk aan die ene nacht.
     */
    private fun weightedAverageShift(nights: List<NightlyShift>): Map<Int, Double> {
        val result = HashMap<Int, Double>()
        for (h in 0..23) {
            var weightedSum = 0.0
            var weightTotal = 0.0
            nights.forEachIndexed { idx, night ->
                val weight = (idx + 1).toDouble()
                weightedSum += (night.shiftByHour[h] ?: 0.0) * weight
                weightTotal += weight
            }
            result[h] = if (weightTotal > 0.0) weightedSum / weightTotal else 0.0
        }
        return result
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

    /** Het (gewogen) gemiddelde voorstel over de laatste [maxNights] nachten
     *  sinds de laatste wijziging — de UI-laag (Advisorscreen.kt) gebruikt
     *  dit rechtstreeks voor de tabel/statustekst, zodat daar en bij
     *  applyPending()/de AUTO-toepassing hieronder altijd exact dezelfde
     *  berekening gebruikt wordt. Null als er nog geen enkele kwalificerende
     *  nacht is sinds de wijziging. */
    data class NightlyProposal(
        val nightsUsed: Int,
        val newestLocalDate: String,
        val oldHourly: Map<Int, Double>,
        val newHourly: Map<Int, Double>,
        val shiftByHour: Map<Int, Double>,
        val hoursAtCap: Set<Int>
    )

    suspend fun computeCurrentProposal(context: Context, maxNights: Int = MANUAL_MAX_WINDOW_NIGHTS): NightlyProposal? {
        val db = FCLAnalyzerDatabase.getInstance(context)
        val lastAppliedAt = db.profileAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L
        val since = lastChangeDate(context, db, lastAppliedAt)
        val nights = collectRecentNightlyShifts(db, since, maxNights)
        if (nights.isEmpty()) return null
        val avgShift = weightedAverageShift(nights)
        val oldHourly = nights.last().oldHourly
        val baseline = FclNightBasalAutoAdjustStore.getBaseline(context) ?: oldHourly
        val (newHourly, hoursAtCap) = computeNewHourly(oldHourly, avgShift, baseline)
        return NightlyProposal(nights.size, nights.last().localDate, oldHourly, newHourly, avgShift, hoursAtCap)
    }

    fun maybeApply(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository,
        payload: FclNightReportPayload?,
        result: NightAiAdvisorRunResult,
        // 26/07/2026 (Ecko) — zie kdoc bij de AUTO-kortsluiting hieronder.
        isManualTrigger: Boolean = false
    ) {
        try {
            runBlocking {
                applyInternal(context, profileFunction, profileRepository, payload, result, isManualTrigger)
            }
        } catch (_: Exception) {
            // best-effort — zie kdoc hierboven
        }
    }

    private suspend fun applyInternal(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository,
        payload: FclNightReportPayload?,
        result: NightAiAdvisorRunResult,
        isManualTrigger: Boolean
    ) {
        val mode = FclNightBasalAutoAdjustStore.getMode(context)
        if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) return
        // 26/07/2026 (Ecko) — de oorspronkelijke veiligheidsafspraak bij "Nu
        // vernieuwen" was: nooit ongevraagd echt op de pomp schrijven. Nu dat
        // ook via de handmatige knop een berekening kan triggeren (zie kdoc
        // bij FclNightAiAdvisorScheduler.forceRunNow()), blijft die afspraak
        // hier expliciet gewaarborgd: bij AUTO slaat een handmatige trigger
        // volledig over (geen berekening, geen log — exact het oude gedrag).
        // MANUAL mag wél, want dat schrijft nooit vanzelf naar de pomp — de
        // gebruiker moet hoe dan ook eerst expliciet Accepteren.
        if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.AUTO && isManualTrigger) return
        if (result.suggestions.isEmpty()) return

        val db = FCLAnalyzerDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val today = LocalDate.now(AMSTERDAM).toString()
        // 26/07/2026 (Ecko) — bugfix: deze cap was bedoeld om de
        // AUTOMATISCHE, nachtelijke route tegen dubbel toepassen op dezelfde
        // dag te beschermen (zie kdoc-item 5 bovenaan). Hij ving in de
        // praktijk óók elke latere "Nu vernieuwen"-klik diezelfde dag af,
        // zelfs als de bestaande rij van vandaag een oude/mislukte/
        // afgewezen poging was — of, zoals bleek, een rij die dateert van
        // vóór de FclSystemMode-herstructurering (nog met de letterlijke
        // oude waarde "DRY_RUN"). "Nu vernieuwen" léék dan te werken (het
        // AI-rapport ververste wél) maar leverde nooit een nieuw
        // Accepteren/Afwijzen-voorstel op. Een handmatige trigger schrijft
        // nooit vanzelf naar de pomp (zie de AUTO-kortsluiting hierboven),
        // dus er is geen veiligheidsrisico om 'm de dagcap te laten
        // negeren — alleen de automatische route blijft aan 1x per dag
        // gebonden.
        if (db.profileAutoAdjustLogDao().existsForDate(today) && !isManualTrigger) return

        val nightsAnalyzed = payload?.nightsAnalyzed ?: 0
        val avgConfidence = result.suggestions.map { it.confidence }.average()

        // ── Confidence-gate ──
        if (nightsAnalyzed < MIN_NIGHTS_ANALYZED || avgConfidence < MIN_AVG_CONFIDENCE) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "confidence-gate: nachten=$nightsAnalyzed (min $MIN_NIGHTS_ANALYZED) " +
                    "gem.confidence=${"%.2f".format(avgConfidence)} (min $MIN_AVG_CONFIDENCE)",
                oldJson = "{}", newJson = "{}", shiftJson = "{}",
                hoursAtCapCount = 0, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        // ── Huidig profiel + index in ProfileRepository ophalen ──
        val effectiveProfile = profileFunction.getProfile()
        if (effectiveProfile == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen actief profiel",
                oldJson = "{}", newJson = "{}", shiftJson = "{}",
                hoursAtCapCount = 0, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }
        val originalName = profileFunction.getOriginalProfileName()
        val profiles = profileRepository.profiles.value
        val index = profiles.indexOfFirst { it.name == originalName }
        if (index == -1) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "profiel '$originalName' niet gevonden in ProfileRepository",
                oldJson = "{}", newJson = "{}", shiftJson = "{}",
                hoursAtCapCount = 0, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }
        val current = profiles[index]

        // Huidige uurwaarden via getBasalTimeFromMidnight() — leest het EFFECTIEVE
        // profiel (dus incl. eventuele percentage/timeshift van een actieve
        // profile-switch), niet de ruwe opgeslagen JSON.
        val currentHourly = HashMap<Int, Double>()
        for (h in 0..23) currentHourly[h] = effectiveProfile.getBasalTimeFromMidnight(h * 3600)

        // ── Basisprofiel: vastleggen bij de allereerste run, daarna nooit meer
        //    automatisch wijzigen (zie kdoc bovenaan dit bestand). ──
        var baseline = FclNightBasalAutoAdjustStore.getBaseline(context)
        if (baseline == null) {
            FclNightBasalAutoAdjustStore.setBaseline(context, currentHourly, source = "initial", nowMs = now)
            baseline = currentHourly
        }

        // ── AI-suggesties → per-uur %-shift, defensief nogmaals geclamped ──
        val shiftByHour = HashMap<Int, Double>()
        result.suggestions.forEach { s ->
            val hour = s.hourLabel.substringBefore(":").toIntOrNull()?.takeIf { it in 0..23 } ?: return@forEach
            shiftByHour[hour] = s.suggestedShiftPct.coerceIn(-MAX_HOURLY_SHIFT_PCT, MAX_HOURLY_SHIFT_PCT)
        }
        if (shiftByHour.isEmpty()) return

        // ── Nieuwe uurwaarden berekenen, met cumulatieve-drift-cap per uur ──
        // (26/07/2026, Ecko) — uitbesteed aan computeNewHourly() zodat
        // applyPending() hieronder (het "Accepteren" pad bij MANUAL) exact
        // dezelfde cap-logica hergebruikt bij het VERS herberekenen tegen
        // het op dát moment actuele profiel.
        val (newHourly, hoursAtCap) = computeNewHourly(currentHourly, shiftByHour, baseline)
        FclNightBasalAutoAdjustStore.updateCapHitCounters(context, hoursAtCap, shiftByHour.keys)

        val oldJson = hourlyToJson(currentHourly)
        val newJson = hourlyToJson(newHourly)
        val shiftJson = JSONObject().apply { shiftByHour.forEach { (h, v) -> put(h.toString(), v) } }.toString()

        if (mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL) {
            // 27/07/2026 (Ecko) — deze rij is puur de RUWE data van vannacht;
            // ze wordt niet meer 1-op-1 als "het voorstel" getoond. Advisor-
            // screen.kt (via computeCurrentProposal()) middelt zelf over alle
            // beschikbare nachten sinds de laatste wijziging. Geen Afwijzen
            // meer nodig/aanwezig: niet-Accepteren betekent al vanzelf "niet
            // toegepast", en het venster groeit gewoon door met meer nachten.
            logRow(db, now, today, mode, applied = false, skipReason = "",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        // ── mode == AUTO: eerst ALTIJD de ruwe data van vannacht loggen ──
        // (27/07/2026, Ecko) — nodig als datapunt voor de 3-nachten-middeling
        // hieronder, ongeacht of dit de nacht is waarop ook echt geschreven
        // wordt. Dit was voorheen een losse skip-rij zonder de echte
        // berekening (oldJson/shiftJson="{}"); nu bevat elke nacht altijd de
        // ruwe cijfers, precies zoals bij MANUAL hierboven.
        logRow(db, now, today, mode, applied = false, skipReason = "",
            oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
            hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)

        // ── mode == AUTO: 1x per AUTO_COOLDOWN_NIGHTS nachten toepassen,
        //    met het gemiddelde van EXACT die nachten (Ecko's suggestie) ──
        val nightsSince = nightsSinceLastChange(context, db)
        if (nightsSince < AUTO_COOLDOWN_NIGHTS) return
        val window = collectRecentNightlyShifts(db, lastChangeDate(context, db, db.profileAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L), AUTO_COOLDOWN_NIGHTS)
        if (window.size < AUTO_COOLDOWN_NIGHTS) return // nog niet elke nacht een echte berekening gehad
        val avgShift = weightedAverageShift(window)
        val (avgNewHourly, avgHoursAtCap) = computeNewHourly(currentHourly, avgShift, baseline)
        FclNightBasalAutoAdjustStore.updateCapHitCounters(context, avgHoursAtCap, avgShift.keys)
        val avgOldJson = oldJson
        val avgNewJson = hourlyToJson(avgNewHourly)
        val avgShiftJson = JSONObject().apply { avgShift.forEach { (h, v) -> put(h.toString(), v) } }.toString()

        // ── daadwerkelijk toepassen (met het gemiddelde, niet de losse nacht) ──
        val newSingleProfile = current.deepClone().apply { basal = buildBasalJsonArray(avgNewHourly) }

        val errors = profileRepository.validateStructured(newSingleProfile)
        if (errors.isNotEmpty()) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "validatie geweigerd: " + errors.joinToString { it.message },
                oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                hoursAtCapCount = avgHoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val replaceResult = profileRepository.replace(index, newSingleProfile)
        if (replaceResult.isFailure) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "ProfileRepository.replace() mislukt: ${replaceResult.exceptionOrNull()?.message}",
                oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                hoursAtCapCount = avgHoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val newProfileStore = profileRepository.profile.value
        if (newProfileStore == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen ProfileStore na replace()",
                oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                hoursAtCapCount = avgHoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        // Geen stille toepassing (mogelijk gemaakt via ProfileSwitchSilentGate) —
        // die klasse is niet bereikbaar vanuit deze module, zie kdoc bovenaan.
        // De gebruikelijke "Basal profile in pump updated"-melding verschijnt dus
        // gewoon, ook bij een automatische toepassing.
        val ps = profileFunction.createProfileSwitch(
            profileStore = newProfileStore,
            profileName = current.name,
            durationInMinutes = 0,
            percentage = 100,
            timeShiftInHours = 0,
            timestamp = now,
            action = Action.PROFILE_SWITCH,
            source = Sources.Aaps,
            note = "FCL nacht-auto (gemiddelde over ${window.size} nachten): " + avgShift.entries
                .sortedBy { it.key }
                .joinToString { (h, pct) -> "%02d:00 %+.1f%%".format(h, pct) },
            listValues = emptyList(),
            iCfg = effectiveProfile.iCfg
        )
        if (ps == null) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "createProfileSwitch() geweigerd (validatie/pompcompatibiliteit)",
                oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
                hoursAtCapCount = avgHoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        logRow(db, now, today, mode, applied = true, skipReason = "",
            oldJson = avgOldJson, newJson = avgNewJson, shiftJson = avgShiftJson,
            hoursAtCapCount = avgHoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
    }

    /**
     * Accepteren van het openstaande MANUAL-voorstel (26/07/2026, Ecko;
     * herzien 27/07/2026 voor het gemiddelde-over-meerdere-nachten-ontwerp).
     * Gebruikt dezelfde vensterverzameling/-middeling als
     * computeCurrentProposal() (UI-laag) — zodat wat je ziet exact is wat er
     * wordt toegepast — maar herberekent newHourly VERS tegen het NU actuele
     * profiel (niet een mogelijk verouderde snapshot): als de gebruiker
     * sindsdien handmatig iets aan het profiel wijzigde, telt dat gewoon mee,
     * precies zoals bij AUTO. Retourneert false als er nog geen kwalificerend
     * venster is (minder dan MANUAL_COOLDOWN_NIGHTS nachten) of het schrijven
     * mislukte.
     */
    suspend fun applyPending(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository
    ): Boolean {
        val db = FCLAnalyzerDatabase.getInstance(context)
        val lastAppliedAt = db.profileAutoAdjustLogDao().getLatestApplied()?.timestampMs ?: 0L
        val since = lastChangeDate(context, db, lastAppliedAt)
        val window = collectRecentNightlyShifts(db, since, MANUAL_MAX_WINDOW_NIGHTS)
        if (window.size < MANUAL_COOLDOWN_NIGHTS) return false
        val shiftByHour = weightedAverageShift(window)
        val newest = window.last()

        val effectiveProfile = profileFunction.getProfile() ?: return false
        val originalName = profileFunction.getOriginalProfileName()
        val profiles = profileRepository.profiles.value
        val index = profiles.indexOfFirst { it.name == originalName }
        if (index == -1) return false
        val current = profiles[index]

        val currentHourly = HashMap<Int, Double>()
        for (h in 0..23) currentHourly[h] = effectiveProfile.getBasalTimeFromMidnight(h * 3600)
        val baseline = FclNightBasalAutoAdjustStore.getBaseline(context) ?: currentHourly

        val (newHourly, hoursAtCap) = computeNewHourly(currentHourly, shiftByHour, baseline)
        FclNightBasalAutoAdjustStore.updateCapHitCounters(context, hoursAtCap, shiftByHour.keys)

        val now = System.currentTimeMillis()
        val today = LocalDate.now(AMSTERDAM).toString()
        val mode = app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL
        val oldJson = hourlyToJson(currentHourly)
        val newJson = hourlyToJson(newHourly)
        val shiftJson = JSONObject().apply { shiftByHour.forEach { (h, v) -> put(h.toString(), v) } }.toString()

        // (26/07/2026, Ecko) — zelfde validate→replace→createProfileSwitch-pad
        // als de AUTO-tak in applyInternal() hierboven (bewust gedupliceerd
        // i.p.v. via een gedeelde functie met een expliciet AAPS-profieltype
        // in de signatuur — de exacte klassenaam van `current`/`profiles[i]`
        // is buiten dit uploadpakket niet te verifiëren, en lokale
        // type-inferentie (`val`, geen expliciete typeannotatie) is hier de
        // veilige keuze om geen gok-type te introduceren dat de build breekt).
        val newSingleProfile = current.deepClone().apply { basal = buildBasalJsonArray(newHourly) }

        val errors = profileRepository.validateStructured(newSingleProfile)
        if (errors.isNotEmpty()) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "validatie geweigerd: " + errors.joinToString { it.message },
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = newest.nightsAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        val replaceResult = profileRepository.replace(index, newSingleProfile)
        if (replaceResult.isFailure) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "ProfileRepository.replace() mislukt: ${replaceResult.exceptionOrNull()?.message}",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = newest.nightsAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        val newProfileStore = profileRepository.profile.value
        if (newProfileStore == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen ProfileStore na replace()",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = newest.nightsAnalyzed, avgConfidence = newest.avgConfidence)
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
            note = "FCL nacht-auto (geaccepteerd, gemiddelde over ${window.size} nachten): " + shiftByHour.entries
                .sortedBy { it.key }
                .joinToString { (h, pct) -> "%02d:00 %+.1f%%".format(h, pct) },
            listValues = emptyList(),
            iCfg = effectiveProfile.iCfg
        )
        if (ps == null) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "createProfileSwitch() geweigerd (validatie/pompcompatibiliteit)",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = newest.nightsAnalyzed, avgConfidence = newest.avgConfidence)
            return false
        }

        logRow(db, now, today, mode, applied = true, skipReason = "",
            oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
            hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = newest.nightsAnalyzed, avgConfidence = newest.avgConfidence)
        return true
    }

    /**
     * Gedeelde cap-berekening (26/07/2026, Ecko) — zie kdoc bij de aanroep in
     * applyInternal(). Zuiver functioneel, geen DB/AAPS-toegang, dus veilig
     * te hergebruiken vanuit zowel de nachtelijke pipeline als applyPending().
     */
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
            var newVal = if (shiftPct != null) roundToStep(curVal * (1.0 + shiftPct / 100.0)) else curVal
            if (shiftPct != null) {
                val clamped = clampToBaseline(newVal, baseline[h] ?: curVal)
                if (clamped != newVal) hoursAtCap.add(h)
                newVal = clamped
            }
            newHourly[h] = newVal
            totalNew += newVal
        }

        // ── Dagtotaal-cap: alleen de AANGERAAKTE uren se delta terugschalen,
        //    niet alle 24 uur uniform knijpen — en na het schalen nogmaals
        //    tegen de drift-cap aan houden (defensief, randgeval). ──
        if (totalOld > 0.0 && abs(totalNew - totalOld) / totalOld > DAILY_TOTAL_CAP_FRAC) {
            val allowedTotal = totalOld * (1.0 + DAILY_TOTAL_CAP_FRAC * sign(totalNew - totalOld))
            val deltaWanted = totalNew - totalOld
            val deltaAllowed = allowedTotal - totalOld
            val scale = if (deltaWanted != 0.0) deltaAllowed / deltaWanted else 0.0
            for (h in 0..23) {
                val curVal = currentHourly[h] ?: 0.0
                val delta = (newHourly[h] ?: curVal) - curVal
                var scaledVal = roundToStep(curVal + delta * scale)
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
        val low = roundToStep(baselineValue * (1.0 - CUMULATIVE_DRIFT_CAP_FRAC)).coerceAtLeast(0.01)
        val high = roundToStep(baselineValue * (1.0 + CUMULATIVE_DRIFT_CAP_FRAC))
        return value.coerceIn(low, high)
    }

    private fun roundToStep(value: Double): Double {
        val stepped = Math.round(value / BASAL_ROUND_STEP) * BASAL_ROUND_STEP
        // Nette 2 decimalen — voorkomt drijvendekomma-restjes zoals 0.9199999999.
        return Math.round(stepped * 100.0) / 100.0
    }

    private fun hourlyToJson(hourly: Map<Int, Double>): String {
        val obj = JSONObject()
        for (h in 0..23) obj.put(h.toString(), hourly[h] ?: 0.0)
        return obj.toString()
    }

    private fun buildBasalJsonArray(hourly: Map<Int, Double>): JSONArray {
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
        nightsAnalyzed: Int,
        avgConfidence: Double
    ) {
        db.profileAutoAdjustLogDao().insert(
            ProfileAutoAdjustLogEntity(
                timestampMs = now,
                localDate = today,
                mode = mode.name,
                applied = applied,
                skipReason = skipReason,
                oldBasalJson = oldJson,
                newBasalJson = newJson,
                perHourShiftJson = shiftJson,
                hoursAtCapCount = hoursAtCapCount,
                nightsAnalyzed = nightsAnalyzed,
                avgConfidence = avgConfidence
            )
        )
    }
}
