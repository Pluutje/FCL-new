package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
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

    LaunchedEffect(refreshTrigger, mode) {
        capHits = Store.getCapHitCounters(context)
        val windowCap = if (mode == FclSystemMode.AUTO) Adjuster.AUTO_COOLDOWN_DAYS else Adjuster.MANUAL_MAX_WINDOW_DAYS
        proposal = if (mode == FclSystemMode.OFF) null else Adjuster.computeCurrentProposal(context, windowCap)
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
                        "uit zuivere correctiemomenten (geen actieve maaltijd) — zie de Learner-tab " +
                        "voor de onderliggende metingen."
                else
                    "Berekent elke dag een voorstel op basis van zuivere correctiemomenten (geen " +
                        "actieve maaltijd) — zie de Learner-tab voor de onderliggende metingen. Na " +
                        "acceptatie wordt dat toegepast op het echte pompprofiel — er wordt nooit " +
                        "automatisch iets doorgevoerd.",
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

                    IsfAutoAdjustTable(
                        oldHourly = p.oldHourly,
                        shiftByHour = p.shiftByHour,
                        hoursAtCap = p.hoursAtCap,
                        capHits = capHits,
                        mgdl = mgdl
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
                    if (pendingProposal != null) {
                        IsfAutoAdjustTable(
                            oldHourly = pendingProposal.oldHourly,
                            shiftByHour = pendingProposal.shiftByHour,
                            hoursAtCap = pendingProposal.hoursAtCap,
                            capHits = capHits,
                            mgdl = mgdl
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
            val status = if (atCap) "tegen grens" + (if (hitCount > 0) " (${hitCount}d)" else "") else "voorstel"
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
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}
