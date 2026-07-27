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
 *     voorstel gelogd (zie applyPending()/rejectPending() hieronder), nooit
 *     automatisch toegepast.
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

    // 27/07/2026 (Ecko) — wachtperiode na een wijziging, op verzoek: "ik heb
    // gister de basaal aangepast en nu na 1 nacht komt hij weer met een
    // voorstel om te verlagen". nightsAnalyzed hierboven is een ROLLEND
    // venster over meerdere dagen data (voor de AI-zekerheid); het zegt
    // niets over hoe lang het HUIDIGE profiel al loopt. Zonder aparte gate
    // kan het systeem dus al na de eerstvolgende nacht opnieuw een
    // verschuiving voorstellen op een profiel dat net is neergezet en nog
    // nauwelijks is waargenomen. MANUAL: het voorstel/de tabel blijft gewoon
    // zichtbaar (nuttige info), maar Accepteren verschijnt pas na
    // MANUAL_COOLDOWN_NIGHTS nachten — zie isPending-berekening in
    // Advisorscreen.kt. AUTOMATISCH schrijft sowieso al zonder tussenkomst,
    // dus daar geldt bewust een langere, extra voorzichtige
    // AUTO_COOLDOWN_NIGHTS (Ecko's eigen suggestie: "misschien is dan 3
    // nachten wachten zelfs wel beter").
    const val MANUAL_COOLDOWN_NIGHTS = 2
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
            // 26/07/2026 (Ecko) — deze rij (applied=false, skipReason="") IS
            // het openstaande voorstel. Advisorscreen.kt herkent 'm zo via
            // getLatest() en toont Accepteren/Afwijzen; die knoppen roepen
            // applyPending()/rejectPending() hieronder aan, die een NIEUWE
            // rij toevoegen (applied=true, resp. skipReason="AFGEWEZEN...")
            // zodat deze rij niet meer als "pending" herkend wordt.
            logRow(db, now, today, mode, applied = false, skipReason = "",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        // ── mode == AUTO: wachtperiode-gate vóór daadwerkelijk toepassen ──
        // (27/07/2026, Ecko) — zie kdoc bij nightsSinceLastChange()/
        // AUTO_COOLDOWN_NIGHTS hierboven.
        val nightsSince = nightsSinceLastChange(context, db)
        if (nightsSince < AUTO_COOLDOWN_NIGHTS) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "wachtperiode: nog ${AUTO_COOLDOWN_NIGHTS - nightsSince} nacht(en) sinds de " +
                    "laatste wijziging (min $AUTO_COOLDOWN_NIGHTS)",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        // ── mode == AUTO: daadwerkelijk toepassen ──
        val newSingleProfile = current.deepClone().apply { basal = buildBasalJsonArray(newHourly) }

        val errors = profileRepository.validateStructured(newSingleProfile)
        if (errors.isNotEmpty()) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "validatie geweigerd: " + errors.joinToString { it.message },
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val replaceResult = profileRepository.replace(index, newSingleProfile)
        if (replaceResult.isFailure) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "ProfileRepository.replace() mislukt: ${replaceResult.exceptionOrNull()?.message}",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        val newProfileStore = profileRepository.profile.value
        if (newProfileStore == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen ProfileStore na replace()",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
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
            note = "FCL nacht-auto: " + shiftByHour.entries
                .sortedBy { it.key }
                .joinToString { (h, pct) -> "%02d:00 %+.1f%%".format(h, pct) },
            listValues = emptyList(),
            iCfg = effectiveProfile.iCfg
        )
        if (ps == null) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "createProfileSwitch() geweigerd (validatie/pompcompatibiliteit)",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
            return
        }

        logRow(db, now, today, mode, applied = true, skipReason = "",
            oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
            hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = nightsAnalyzed, avgConfidence = avgConfidence)
    }

    /**
     * Accepteren van een openstaand MANUAL-voorstel (26/07/2026, Ecko) — zie
     * kdoc bij de MANUAL-tak in applyInternal(). Herberekent newHourly VERS
     * tegen het NU actuele profiel (niet de mogelijk verouderde newBasalJson-
     * snapshot in de pending-rij) — als de gebruiker sindsdien handmatig iets
     * aan het profiel wijzigde, telt dat gewoon mee, precies zoals bij AUTO.
     * Retourneert false als er niets openstond of het schrijven mislukte.
     */
    suspend fun applyPending(
        context: Context,
        profileFunction: ProfileFunction,
        profileRepository: ProfileRepository
    ): Boolean {
        val db = FCLAnalyzerDatabase.getInstance(context)
        val pending = db.profileAutoAdjustLogDao().getLatest()
            ?.takeIf { it.mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL.name && !it.applied && it.skipReason.isEmpty() }
            ?: return false

        val shiftByHour = HashMap<Int, Double>()
        try {
            val obj = JSONObject(pending.perHourShiftJson)
            obj.keys().forEach { k -> shiftByHour[k.toInt()] = obj.getDouble(k) }
        } catch (_: Exception) { return false }
        if (shiftByHour.isEmpty()) return false

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
        val shiftJson = pending.perHourShiftJson

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
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = pending.nightsAnalyzed, avgConfidence = pending.avgConfidence)
            return false
        }

        val replaceResult = profileRepository.replace(index, newSingleProfile)
        if (replaceResult.isFailure) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "ProfileRepository.replace() mislukt: ${replaceResult.exceptionOrNull()?.message}",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = pending.nightsAnalyzed, avgConfidence = pending.avgConfidence)
            return false
        }

        val newProfileStore = profileRepository.profile.value
        if (newProfileStore == null) {
            logRow(db, now, today, mode, applied = false, skipReason = "geen ProfileStore na replace()",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = pending.nightsAnalyzed, avgConfidence = pending.avgConfidence)
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
            note = "FCL nacht-auto (geaccepteerd): " + shiftByHour.entries
                .sortedBy { it.key }
                .joinToString { (h, pct) -> "%02d:00 %+.1f%%".format(h, pct) },
            listValues = emptyList(),
            iCfg = effectiveProfile.iCfg
        )
        if (ps == null) {
            logRow(db, now, today, mode, applied = false,
                skipReason = "createProfileSwitch() geweigerd (validatie/pompcompatibiliteit)",
                oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
                hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = pending.nightsAnalyzed, avgConfidence = pending.avgConfidence)
            return false
        }

        logRow(db, now, today, mode, applied = true, skipReason = "",
            oldJson = oldJson, newJson = newJson, shiftJson = shiftJson,
            hoursAtCapCount = hoursAtCap.size, nightsAnalyzed = pending.nightsAnalyzed, avgConfidence = pending.avgConfidence)
        return true
    }

    /**
     * Afwijzen van een openstaand MANUAL-voorstel — voegt een nieuwe rij toe
     * (skipReason gevuld) zodat de pending-rij niet meer als "openstaand"
     * wordt herkend. Er is niets om terug te draaien: er is nooit iets
     * naar AAPS geschreven (zie kdoc bij de MANUAL-tak in applyInternal()).
     */
    suspend fun rejectPending(context: Context): Boolean {
        val db = FCLAnalyzerDatabase.getInstance(context)
        val pending = db.profileAutoAdjustLogDao().getLatest()
            ?.takeIf { it.mode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL.name && !it.applied && it.skipReason.isEmpty() }
            ?: return false
        logRow(
            db, System.currentTimeMillis(), LocalDate.now(AMSTERDAM).toString(),
            app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL, applied = false,
            skipReason = "AFGEWEZEN (handmatig)",
            oldJson = pending.oldBasalJson, newJson = pending.newBasalJson, shiftJson = pending.perHourShiftJson,
            hoursAtCapCount = pending.hoursAtCapCount, nightsAnalyzed = pending.nightsAnalyzed, avgConfidence = pending.avgConfidence
        )
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
