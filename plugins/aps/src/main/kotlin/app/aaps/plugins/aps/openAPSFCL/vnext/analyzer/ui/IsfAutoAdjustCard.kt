package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits
import app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode
import kotlinx.coroutines.launch

// ── IsfAutoAdjustCard (16/08/2026) ──────────────────────────────────────────
// UI-tegenhanger van ProfileAutoAdjustCard (Advisorscreen.kt), nu voor de
// ISF-auto-adjust (FclIsfAutoAdjuster/FclIsfAutoAdjustStore) — zelfde
// concept, bewust vereenvoudigd:
//  - Geen apart AI-nacht-scherm dat dit kaartje "voedt": de aan/uit +
//    Automatisch/Handmatig-keuze staat rechtstreeks op dit kaartje via
//    FclModeSelectorCard (ISF is geen dag/nacht-as, dus de simpele variant
//    volstaat), i.p.v. verwezen te worden naar een instellingen-scherm.
//  - Geen grafiek — zelfde afweging als destijds bij de basaal-kaart ("eerst
//    de tabel + backend in de praktijk laten bewijzen"); hier bovendien nog
//    geen enkele week productie-ervaring, dus des te meer reden om klein te
//    beginnen.
//  - Basisprofiel-reset leest het LIVE profiel rechtstreeks via
//    FclProfileBridge (geen aparte ISF-geschiedenis-tabel zoals
//    basalProfileHistoryDao() bij de basaal-variant — die bestaat voor ISF
//    niet en is voor deze eerste versie ook niet nodig: "huidig profiel"
//    is altijd exact wat er nu op de pomp staat).
@Composable
fun IsfAutoAdjustCard(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val Adjuster = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf.FclIsfAutoAdjuster
    val Store = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf.FclIsfAutoAdjustStore
    val mgdl = BgUnits.isMgdl(context)

    val mode = Store.getMode(context)
    var capHits by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var baselineSetAt by remember { mutableStateOf(Store.getBaselineSetAt(context)) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var isActing by remember { mutableStateOf(false) }
    var actionResult by remember { mutableStateOf<String?>(null) }
    var showAcceptConfirmDialog by remember { mutableStateOf(false) }
    var proposal by remember {
        mutableStateOf<app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf.FclIsfAutoAdjuster.DailyProposal?>(null)
    }
    // 31/08/2026 — laatste-cyclus voortgangs-snapshot per uur (zie kdoc bij
    // FclIsfAutoAdjustStore.KEY_PROGRESS_JSON), los van [proposal]: blijft
    // gevuld ook als nog geen enkel uur de suggestie-drempel haalt, dus ook
    // vóórdat [proposal] voor het eerst niet-null wordt.
    var hourProgress by remember {
        mutableStateOf<Map<Int, app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner.HourProgress>>(emptyMap())
    }

    LaunchedEffect(refreshTrigger, mode) {
        capHits = Store.getCapHitCounters(context)
        val windowCap = if (mode == FclSystemMode.AUTO) Adjuster.AUTO_COOLDOWN_DAYS else Adjuster.MANUAL_MAX_WINDOW_DAYS
        proposal = if (mode == FclSystemMode.OFF) null else Adjuster.computeCurrentProposal(context, windowCap)
        hourProgress = if (mode == FclSystemMode.OFF) emptyMap() else Store.getProgress(context)
    }

    if (mode == FclSystemMode.OFF) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "⚖️ ISF bijstellen — Uit",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Uitgeschakeld — zet \"ISF-auto-adjust\" aan bij Instellingen → " +
                        "Analyser Automaat / AI Advisor om ISF-voorstellen te krijgen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (mode == FclSystemMode.AUTO) "⚖️ ISF bijstellen — Automatisch" else "⚖️ ISF bijstellen — Handmatig",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (mode == FclSystemMode.AUTO)
                    "Past het gemiddelde ISF-voorstel automatisch toe op het echte pompprofiel, " +
                        "in kleine stapjes met een harde grens en een terugvalmogelijkheid. Leert " +
                        "uit zuivere correctiemomenten (geen actieve maaltijd). Uren die vrijwel " +
                        "altijd door een maaltijd bezet zijn (bijv. rond lunch of avondeten) krijgen " +
                        "geen eigen meting maar een voorzichtige, gedempte schatting op basis van de " +
                        "dichtstbijzijnde wél-gemeten uren — hieronder gemarkeerd als \"afgeleid\"."
                else
                    "Berekent elke dag een voorstel op basis van zuivere correctiemomenten (geen " +
                        "actieve maaltijd). Uren die vrijwel altijd door een maaltijd bezet zijn " +
                        "(bijv. rond lunch of avondeten) krijgen geen eigen meting maar een " +
                        "voorzichtige, gedempte schatting op basis van de dichtstbijzijnde wél-" +
                        "gemeten uren — hieronder gemarkeerd als \"afgeleid\". Na acceptatie wordt " +
                        "dat toegepast op het echte pompprofiel — er wordt nooit automatisch iets " +
                        "doorgevoerd.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()
            Text(
                "ISF-voorstel",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            run {
                val p = proposal
                if (p == null) {
                    Text(
                        "Nog geen gegevens sinds de laatste wijziging — verschijnt hier zodra er " +
                            "genoeg zuivere correctiemomenten zijn geweest (minstens " +
                            "${Adjuster.MANUAL_COOLDOWN_DAYS} dag/dagen).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // 31/08/2026, op verzoek — ook zolang GEEN ENKEL uur de
                    // suggestie-drempel haalt (dus [proposal] nog steeds null
                    // is) is er per uur al een gewogen telling bekend (zie
                    // kdoc bij IsfLearner.HourProgress). Zonder dit bleef de
                    // tekst hierboven wekenlang de enige informatie, zonder
                    // enig zicht op hoever elk uur al staat.
                    if (hourProgress.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        IsfProgressTable(hourProgress)
                    }
                } else {
                    val statusText = when {
                        mode == FclSystemMode.MANUAL && p.daysUsed < Adjuster.MANUAL_COOLDOWN_DAYS ->
                            "Voorlopige inschatting — gebaseerd op nog maar ${p.daysUsed} dag(en) " +
                                "sinds de laatste wijziging (t/m ${p.newestLocalDate}). Accepteren komt " +
                                "beschikbaar over ${Adjuster.MANUAL_COOLDOWN_DAYS - p.daysUsed} dag(en)."
                        mode == FclSystemMode.MANUAL ->
                            "Gewogen gemiddelde over de laatste ${p.daysUsed} dag(en) sinds de " +
                                "laatste wijziging (t/m ${p.newestLocalDate}) — klaar om te accepteren. " +
                                "Dit venster groeit door tot maximaal ${Adjuster.MANUAL_MAX_WINDOW_DAYS} dagen."
                        p.daysUsed < Adjuster.AUTO_COOLDOWN_DAYS ->
                            "Verzamelt gegevens: dag ${p.daysUsed} van ${Adjuster.AUTO_COOLDOWN_DAYS}. " +
                                "Bij dag ${Adjuster.AUTO_COOLDOWN_DAYS} wordt het gewogen gemiddelde " +
                                "automatisch op de pomp toegepast."
                        else ->
                            "Gewogen gemiddelde over de laatste ${Adjuster.AUTO_COOLDOWN_DAYS} dagen " +
                                "wordt bij de eerstvolgende cyclus automatisch toegepast."
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 16/08/2026, op verzoek — dev-fase: de weergave-gate
                    // (MIN_SAMPLES_TOTAL_DISPLAY/MIN_AVG_CONFIDENCE_DISPLAY)
                    // staat lager dan de productie-schrijfdrempel (MIN_
                    // SAMPLES_TOTAL/MIN_AVG_CONFIDENCE), zodat er sneller iets
                    // te zien is tijdens het testen. AUTO respecteert altijd
                    // de hogere productiedrempel (zie FclIsfAutoAdjuster), dus
                    // dit label is puur relevant voor MANUAL/Accepteren.
                    val belowProductionThreshold = p.latestSamplesAnalyzed < Adjuster.MIN_SAMPLES_TOTAL ||
                        p.latestAvgConfidence < Adjuster.MIN_AVG_CONFIDENCE
                    if (belowProductionThreshold) {
                        Text(
                            "🧪 Ontwikkelmodus: dit voorstel haalt de productie-drempel nog niet " +
                                "(${p.latestSamplesAnalyzed} metingen, min. ${Adjuster.MIN_SAMPLES_TOTAL}; " +
                                "confidence ${"%.2f".format(p.latestAvgConfidence)}, min. ${"%.2f".format(Adjuster.MIN_AVG_CONFIDENCE)}) " +
                                "— alleen zichtbaar dankzij de verlaagde weergavedrempel om het testen te " +
                                "versnellen. AUTO past dit nooit toe onder de productiedrempel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    IsfAutoAdjustTable(
                        oldHourly = p.oldHourly,
                        shiftByHour = p.shiftByHour,
                        hoursAtCap = p.hoursAtCap,
                        capHits = capHits,
                        interpolatedHours = p.interpolatedHours,
                        touchedHours = p.touchedHours,
                        hourProgress = hourProgress,
                        mgdl = mgdl
                    )

                    Spacer(Modifier.height(4.dp))
                    IsfProfileChart(
                        oldHourly = p.oldHourly,
                        newHourly = p.newHourly,
                        interpolatedHours = p.interpolatedHours
                    )

                    if (mode == FclSystemMode.MANUAL && p.daysUsed >= Adjuster.MANUAL_COOLDOWN_DAYS) {
                        Button(
                            enabled = !isActing,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showAcceptConfirmDialog = true }
                        ) { Text("Accepteren") }
                        actionResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Divider()
                Text(
                    "Basisprofiel resetten",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Elk uur mag maximaal ±20% afwijken van een vast referentiepunt " +
                        "(niet van gisteren, anders zou die grens langzaam betekenisloos " +
                        "worden). Deze knop legt het HUIDIGE profiel vast als dat nieuwe " +
                        "referentiepunt — en telt bovendien als het startpunt van een NIEUW " +
                        "voorstel-venster hierboven (dag 0). Alleen nodig als een uur " +
                        "herhaaldelijk tegen de oude grens aanloopt, of als je bewust opnieuw " +
                        "wilt beginnen met tellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Basisprofiel opnieuw vastleggen")
                }
                if (baselineSetAt > 0) {
                    Text(
                        "Huidig basisprofiel vastgelegd op " +
                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                                .withZone(java.time.ZoneId.of("Europe/Amsterdam"))
                                .format(java.time.Instant.ofEpochMilli(baselineSetAt)) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { if (!isResetting) showResetDialog = false },
            title = { Text("Basisprofiel opnieuw vastleggen?") },
            text = {
                Text(
                    "Het huidige, actieve profiel wordt het nieuwe ankerpunt waar de ±20%-grens " +
                        "voortaan tegen wordt afgemeten, én het startpunt (dag 0) van een nieuw " +
                        "voorstel-venster. Doe dit alleen bewust, bijvoorbeeld nadat je hebt gezien " +
                        "dat een uur herhaaldelijk tegen de oude grens aanliep, of na een paar " +
                        "dagen waarvan je de gegevens niet wilt laten meetellen."
                )
            },
            confirmButton = {
                Button(
                    enabled = !isResetting,
                    onClick = {
                        isResetting = true
                        scope.launch {
                            val effectiveProfile = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge
                                .getProfileFunction()?.getProfile()
                            if (effectiveProfile != null) {
                                val hourly = HashMap<Int, Double>()
                                for (h in 0..23) hourly[h] = effectiveProfile.getIsfMgdlTimeFromMidnight(h * 3600)
                                Store.setBaseline(
                                    context, hourly, source = "manual-reset", nowMs = System.currentTimeMillis()
                                )
                                baselineSetAt = Store.getBaselineSetAt(context)
                            }
                            isResetting = false
                            showResetDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text(if (isResetting) "Bezig…" else "Bevestigen") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }, enabled = !isResetting) { Text("Annuleren") }
            }
        )
    }

    if (showAcceptConfirmDialog) {
        val pendingProposal = proposal
        AlertDialog(
            onDismissRequest = { if (!isActing) showAcceptConfirmDialog = false },
            title = { Text("Voorstel toepassen op de pomp?") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Dit schrijft de nieuwe ISF-waarden hieronder direct naar het " +
                            "actieve pompprofiel. Controleer de vergelijking en bevestig " +
                            "pas als je het ermee eens bent."
                    )
                    // 16/08/2026, op verzoek — extra, expliciete waarschuwing
                    // wanneer dit voorstel onder de productie-schrijfdrempel
                    // zit (alleen zichtbaar dankzij de verlaagde weergave-
                    // gate tijdens de dev-fase, zie kdoc bij
                    // MIN_SAMPLES_TOTAL_DISPLAY in FclIsfAutoAdjuster.kt).
                    // Bewust GEEN blokkade — de gebruiker gaf zelf aan dit
                    // te willen kunnen testen en voorstellen desnoods zelf
                    // weer handmatig terug te zetten.
                    if (pendingProposal != null &&
                        (pendingProposal.latestSamplesAnalyzed < Adjuster.MIN_SAMPLES_TOTAL ||
                            pendingProposal.latestAvgConfidence < Adjuster.MIN_AVG_CONFIDENCE)
                    ) {
                        Text(
                            "⚠️ Let op: dit voorstel zit onder de productie-drempel " +
                                "(${pendingProposal.latestSamplesAnalyzed} metingen, min. ${Adjuster.MIN_SAMPLES_TOTAL}; " +
                                "confidence ${"%.2f".format(pendingProposal.latestAvgConfidence)}, " +
                                "min. ${"%.2f".format(Adjuster.MIN_AVG_CONFIDENCE)}). Dit is dev-fase-data, bedoeld " +
                                "om te testen of de code/UI werkt — controleer zelf of de voorgestelde " +
                                "ISF-waarden hieronder logisch ogen voordat je bevestigt. Je kunt dit na " +
                                "toepassen altijd handmatig terugzetten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (pendingProposal != null) {
                        IsfAutoAdjustTable(
                            oldHourly = pendingProposal.oldHourly,
                            shiftByHour = pendingProposal.shiftByHour,
                            hoursAtCap = pendingProposal.hoursAtCap,
                            capHits = capHits,
                            interpolatedHours = pendingProposal.interpolatedHours,
                            touchedHours = pendingProposal.touchedHours,
                            hourProgress = hourProgress,
                            mgdl = mgdl
                        )
                        Spacer(Modifier.height(4.dp))
                        IsfProfileChart(
                            oldHourly = pendingProposal.oldHourly,
                            newHourly = pendingProposal.newHourly,
                            interpolatedHours = pendingProposal.interpolatedHours,
                            heightDp = 100
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isActing,
                    onClick = {
                        isActing = true
                        actionResult = null
                        scope.launch {
                            val pf = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge.getProfileFunction()
                            val pr = app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge.getProfileRepository()
                            val ok = if (pf != null && pr != null)
                                Adjuster.applyPending(context, pf, pr)
                            else false
                            actionResult = if (ok) "Toegepast." else "Mislukt, probeer het later opnieuw."
                            isActing = false
                            showAcceptConfirmDialog = false
                            refreshTrigger++
                        }
                    }
                ) { Text(if (isActing) "Bezig..." else "Accepteren") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isActing,
                    onClick = { showAcceptConfirmDialog = false }
                ) { Text("Annuleren") }
            }
        )
    }
}

/** ISF-waarden staan intern altijd in mg/dl per U (zelfde eenheid als
 *  profile.getIsfMgdl()) — voor weergave in mmol/L per U omgerekend met
 *  dezelfde factor als BgUnits, want de conversiefactor is lineair en dus
 *  identiek voor een BG-waarde en een ISF-waarde (mg/dl per U = mmol/L per
 *  U × 18.0182). BgUnits.fromMmol() zelf is niet herbruikt omdat die van
 *  mmol naar mg/dl gaat, hier is het net andersom (opslag is al mg/dl). */
private fun mgdlPerUToDisplay(mgdlPerU: Double, mgdl: Boolean): Double =
    if (mgdl) mgdlPerU else mgdlPerU / 18.0182

@Composable
private fun IsfAutoAdjustTable(
    oldHourly: Map<Int, Double>,
    shiftByHour: Map<Int, Double>,
    hoursAtCap: Set<Int>,
    capHits: Map<Int, Int>,
    interpolatedHours: Set<Int>,
    touchedHours: Set<Int>,
    hourProgress: Map<Int, app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner.HourProgress> = emptyMap(),
    mgdl: Boolean
) {
    val unit = BgUnits.unitShort(mgdl)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Uur", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
            Text("Huidig", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Voorstel", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Status", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Divider()
        shiftByHour.keys.sorted().forEach { hour ->
            val oldValMgdl = oldHourly[hour] ?: 0.0
            val shiftPct = shiftByHour[hour] ?: 0.0
            val voorstelValMgdl = (oldValMgdl * (1.0 + shiftPct / 100.0)).coerceAtLeast(0.0)
            val atCap = hour in hoursAtCap
            val hitCount = capHits[hour] ?: 0
            val isInterpolated = hour in interpolatedHours
            val hasDirectData = hour in touchedHours
            // 16/08/2026 — drie statussen i.p.v. twee: "afgeleid" (geen
            // eigen meting, wel dicht genoeg bij gemeten buren om te
            // interpoleren) gaat vóór "tegen grens" — het belangrijkste om
            // hier te laten zien is dat dit uur geen eigen meting heeft, de
            // grens-status blijft af te lezen aan de tabel-waarde zelf. Een
            // uur dat NOCH gemeten NOCH interpoleerbaar is (buiten bereik,
            // zie INTERPOLATION_MAX_GAP_HOURS) krijgt "geen data" i.p.v.
            // stilzwijgend "voorstel" te tonen bij een ongewijzigde 0%.
            // "Al optimaal" (18/08/2026): een uur met eigen, directe data
            // waarvan de gemeten afwijking verwaarloosbaar is (zie kdoc bij
            // IsfLearner.ALREADY_OPTIMAL_THRESHOLD_PCT) — vroeger stilzwijgend
            // weggelaten uit de resultatenlijst, nu expliciet zichtbaar als
            // bevestiging i.p.v. onderscheidbaar van "nog geen data". Gaat
            // vóór "tegen grens": bij een verwaarloosbare shift wordt de
            // drift-cap sowieso nooit geraakt, maar de volgorde maakt de
            // bedoeling expliciet.
            val isAlreadyOptimal = hasDirectData && !isInterpolated &&
                kotlin.math.abs(shiftPct) < app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner.ALREADY_OPTIMAL_THRESHOLD_PCT
            // 31/08/2026, op verzoek — "geen data" toont er nu een voortgangs-
            // indicatie bij (gewogen telling / drempel), zie kdoc bij
            // IsfLearner.HourProgress: zonder dit was "geen data" een
            // doodlopend statisch label, ongeacht of dit uur al bijna
            // genoeg metingen had of nog helemaal niets.
            val progressSuffix = hourProgress[hour]?.let { prog ->
                " (${"%.1f".format(prog.weightedCount)}/" +
                    "${app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner.MIN_SAMPLES_PER_HOUR})"
            } ?: ""
            val status = when {
                isInterpolated -> "afgeleid" + (if (atCap) " (tegen grens)" else "")
                !hasDirectData -> "geen data" + progressSuffix
                isAlreadyOptimal -> "al optimaal"
                atCap -> "tegen grens" + (if (hitCount > 0) " (${hitCount}d)" else "")
                else -> "voorstel"
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("%02d:00".format(hour), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
                Text(
                    "%.2f %s".format(mgdlPerUToDisplay(oldValMgdl, mgdl), unit),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                Text(
                    "%.2f (%+.0f%%)".format(mgdlPerUToDisplay(voorstelValMgdl, mgdl), shiftPct),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isInterpolated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * IsfProgressTable (31/08/2026) — compacte tabel met de rauwe dataverzamelings-
 * voortgang per uur (zie kdoc bij IsfLearner.HourProgress), gesorteerd van
 * dichtst-bij-de-drempel naar verst weg. Alleen gebruikt zolang [proposal]
 * nog null is (nog geen enkel uur haalt de suggestie-drempel) — zodra er wél
 * een voorstel is, toont IsfAutoAdjustTable dezelfde voortgang al per "geen
 * data"-rij, dus dan is deze aparte tabel niet meer nodig.
 */
@Composable
private fun IsfProgressTable(
    hourProgress: Map<Int, app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner.HourProgress>
) {
    val minSamples = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.IsfLearner.MIN_SAMPLES_PER_HOUR
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Uur", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
            Text("Voortgang", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
        }
        Divider()
        hourProgress.values.sortedByDescending { it.weightedCount }.forEach { prog ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "%02d:00".format(prog.hour),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.8f)
                )
                Text(
                    "${"%.1f".format(prog.weightedCount)} / $minSamples" +
                        (if (prog.rawCount > 0) " (${prog.rawCount} metingen)" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.2f)
                )
            }
        }
    }
}

/**
 * IsfProfileChart (16/08/2026) — 24-uurs trapvormige grafiek, zelfde
 * opzet als BasalProfileChart (Advisorscreen.kt): Voorstel als brede,
 * effen band, Huidig eroverheen als smallere gestreepte lijn in een vaste,
 * thema-onafhankelijke kleur. Extra t.o.v. de basaal-variant: uren in
 * [interpolatedHours] worden in de Voorstel-band met een lagere dekking
 * (alpha) getekend, zodat in één oogopslag te zien is welk deel van de
 * curve op een ECHTE meting berust en welk deel een afgeleide schatting is
 * (zie kdoc bij FclIsfAutoAdjuster.INTERPOLATION_MAX_GAP_HOURS).
 */
@Composable
private fun IsfProfileChart(
    oldHourly: Map<Int, Double>,
    newHourly: Map<Int, Double>,
    interpolatedHours: Set<Int>,
    heightDp: Int = 140
) {
    val oldColor = ISF_CHART_HUIDIG_COLOR
    val newColor = MaterialTheme.colorScheme.primary
    val newColorInterpolated = newColor.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
                drawLine(
                    oldColor,
                    Offset(0f, size.height / 2f),
                    Offset(size.width, size.height / 2f),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect
                )
            }
            Text("Huidig", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(newColor)
            )
            Text("Voorstel (gemeten)", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(newColorInterpolated)
            )
            Text("Voorstel (afgeleid)", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
        ) {
            val w = size.width
            val h = size.height
            val maxVal = (oldHourly.values + newHourly.values).filter { it.isFinite() }
                .maxOrNull()?.let { it * 1.15 } ?: 1.0
            val safeMax = if (maxVal <= 0.0) 1.0 else maxVal

            fun x(hour: Int): Float = w * (hour / 24f)
            fun y(v: Double): Float = h - (h * (v / safeMax)).toFloat()

            listOf(0, 6, 12, 18, 24).forEach { hr ->
                val xg = x(hr)
                drawLine(gridColor, Offset(xg, 0f), Offset(xg, h), strokeWidth = 1.5f)
            }
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)

            // Voorstel-band per uur apart getekend (i.p.v. één doorlopende
            // trap zoals bij Huidig) — nodig om per uur een andere kleur/
            // dekking te kunnen geven al naar gelang interpolatedHours.
            // Geen cyclische verbinding tussen uur 23 en uur 0 (zelfde
            // gedrag als drawStairs() in BasalProfileChart) — de linkerrand
            // van de grafiek begint altijd "vers".
            var prevNewY: Float? = null
            for (hour in 0..23) {
                val v = newHourly[hour] ?: continue
                val x0 = x(hour)
                val x1 = x(hour + 1)
                val yv = y(v)
                val color = if (hour in interpolatedHours) newColorInterpolated else newColor
                if (prevNewY != null && prevNewY != yv) {
                    drawLine(color, Offset(x0, prevNewY), Offset(x0, yv), strokeWidth = 7f, cap = StrokeCap.Round)
                }
                drawLine(color, Offset(x0, yv), Offset(x1, yv), strokeWidth = 7f, cap = StrokeCap.Round)
                prevNewY = yv
            }

            var prevY: Float? = null
            for (hour in 0..23) {
                val v = oldHourly[hour] ?: continue
                val x0 = x(hour)
                val x1 = x(hour + 1)
                val yv = y(v)
                if (prevY != null && prevY != yv) {
                    drawLine(oldColor, Offset(x0, prevY), Offset(x0, yv), strokeWidth = 3.5f, cap = StrokeCap.Round, pathEffect = dashEffect)
                }
                drawLine(oldColor, Offset(x0, yv), Offset(x1, yv), strokeWidth = 3.5f, cap = StrokeCap.Round, pathEffect = dashEffect)
                prevY = yv
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 24).forEach { hr ->
                Text("%02d".format(hr % 24), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}

// Zelfde vaste, thema-onafhankelijke amberkleur als FCL_CHART_HUIDIG_COLOR
// in Advisorscreen.kt (BasalProfileChart) — hier onder een eigen naam,
// bewust niet hergebruikt (private in dat bestand, en losse eenheden/
// schaal rechtvaardigen een eigen definitie i.p.v. een gedeelde constante).
private val ISF_CHART_HUIDIG_COLOR = Color(0xFFFFA000)
