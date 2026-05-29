package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.pickers.TimeWheelPicker
import app.aaps.core.ui.compose.pickers.WeekDaySelector

// ─────────────────────────────────────────────────────────────────────────────
// FCL vNext — Instellingen scherm
//
// Drie groepen:
//   1. Dosering & gedrag  (limieten + insulineverdeling + nachtrespons)
//   2. Context            (dag/nacht tijden + weekenddagen)
//   3. AutoSens & activiteit
//
// Per instelling: korte toelichting zichtbaar, ℹ️-knop voor uitgebreide uitleg.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FCLSettingsScreen(preferences: Preferences, sp: SP) {

    // ── Hulpfuncties ─────────────────────────────────────────────────────
    fun String.toHour(): Int = split(":").getOrNull(0)?.toIntOrNull() ?: 7
    fun String.toMinute(): Int = split(":").getOrNull(1)?.toIntOrNull() ?: 0

    fun weekendDagenToBoolArray(csv: String): BooleanArray {
        val selected = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return BooleanArray(7) { i -> (i + 1) in selected }
    }
    fun boolArrayToWeekendDagen(arr: BooleanArray): String =
        arr.indices.filter { arr[it] }.map { it + 1 }.joinToString(",")

    // ── State ─────────────────────────────────────────────────────────────
    var maxBolusDay       by remember { mutableStateOf(preferences.get(DoubleKey.max_bolus_day)) }
    var maxBolusNight     by remember { mutableStateOf(preferences.get(DoubleKey.max_bolus_night)) }
    var maxIob            by remember { mutableStateOf(preferences.get(DoubleKey.fcl_vnext_MaxIOB)) }
    var doseStyle         by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_dose_distribution_style)) }
    var nightStyle        by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_night_response_style)) }
    var resBehavior       by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_resistance_behavior)) }
    var resStability      by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_resistance_stability)) }
    var actBehavior       by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_activity_behavior)) }
    var weekendDagen      by remember { mutableStateOf(preferences.get(StringKey.WeekendDagen)) }
    var ochtendStart      by remember { mutableStateOf(preferences.get(StringKey.OchtendStart)) }
    var ochtendWeekend    by remember { mutableStateOf(preferences.get(StringKey.OchtendStartWeekend)) }
    var nachtStart        by remember { mutableStateOf(preferences.get(StringKey.NachtStart)) }

    // ── Sectie-uitklapstatus ──────────────────────────────────────────────
    var expandedDosering  by remember { mutableStateOf(true) }
    var expandedContext   by remember { mutableStateOf(false) }
    var expandedAutosens  by remember { mutableStateOf(false) }

    // Expert modus: pincode "0000"
    var showExpertSection   by remember { mutableStateOf(false) }
    var expertPinInput      by remember { mutableStateOf("") }
    var expertPinError      by remember { mutableStateOf(false) }
    var expertPinDialogOpen by remember { mutableStateOf(false) }
    val EXPERT_PIN = "0000"

    // ── Picker-zichtbaarheid ──────────────────────────────────────────────
    var showOchtendPicker        by remember { mutableStateOf(false) }
    var showOchtendWeekendPicker by remember { mutableStateOf(false) }
    var showNachtPicker          by remember { mutableStateOf(false) }

    // ── Info-dialoog ──────────────────────────────────────────────────────
    var infoDialogTitle   by remember { mutableStateOf("") }
    var infoDialogText    by remember { mutableStateOf("") }
    var showInfoDialog    by remember { mutableStateOf(false) }

    fun showInfo(title: String, text: String) {
        infoDialogTitle = title
        infoDialogText  = text
        showInfoDialog  = true
    }

    // ── Keuzelijsten ─────────────────────────────────────────────────────
    val doseOptions = listOf(
        "VERY_SMOOTH" to "🌊 Ultra smooth",
        "SMOOTH"      to "🫧 Smooth",
        "BALANCED"    to "⚖️ Balanced (standaard)",
        "PULSED"      to "🔨 Pulsed",
        "VERY_PULSED" to "⚡ Ultra pulsed"
    )
    val nightOptions = listOf(
        "VERY_GUARDED" to "🛑 Zeer terughoudend",
        "GUARDED"      to "🧯 Terughoudend",
        "BALANCED"     to "⚖️ Gebalanceerd (standaard)",
        "RESPONSIVE"   to "🌙 Reageert eerder",
        "PROACTIVE"    to "🚀 Proactief"
    )
    val resBehaviorOptions = listOf(
        "OFF"        to "Uitgeschakeld",
        "LIGHT"      to "Licht",
        "NORMAL"     to "Normaal (aanbevolen)",
        "STRONG"     to "Sterk",
        "AGGRESSIVE" to "Agressief"
    )
    val resStabilityOptions = listOf(
        "VERY_STABLE" to "Zeer stabiel",
        "STANDARD"    to "Standaard",
        "RESPONSIVE"  to "Responsief"
    )
    val actOptions = listOf(
        "OFF"    to "Uit",
        "LIGHT"  to "Licht",
        "NORMAL" to "Normaal",
        "STRONG" to "Sterk"
    )

    // ── UI ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ══════════════════════════════════════════════════════════════════
        // GROEP 1 — Dosering & gedrag
        // ══════════════════════════════════════════════════════════════════
        FCLSection(
            title = "💉 Dosering & gedrag",
            expanded = expandedDosering,
            onToggle = { expandedDosering = !expandedDosering }
        ) {
            // ── Max bolus dag ────────────────────────────────────────────
            FCLDoubleRow(
                label = "Max bolus dag",
                summary = "Referentiewaarde voor de Analyzer (actieve maxSMB = 50–125% van deze waarde).",
                value = maxBolusDay,
                min = 0.1, max = 8.0, step = 0.05, unit = "U",
                onInfo = {
                    showInfo(
                        "Max bolus dag — referentiewaarde",
                        "Dit is de referentiewaarde die de Analyzer gebruikt om de actieve maxSMB " +
                            "bij te sturen.\n\n" +
                            "• De Analyzer mag de actieve maxSMB verhogen tot 125% van deze waarde\n" +
                            "• De Analyzer mag de actieve maxSMB verlagen tot 50% van deze waarde\n" +
                            "• De waarde die je hier instelt wordt zelf nooit gewijzigd\n\n" +
                            "Stel dit in op wat je normaal als maximale maaltijdbolus zou nemen. " +
                            "De actuele actieve maxSMB is zichtbaar in het FCL status-tabblad."
                    )
                },
                onValueChange = {
                    maxBolusDay = it
                    sp.putDouble(DoubleKey.max_bolus_day.key, it)
                }
            )

            // ── Max bolus nacht ──────────────────────────────────────────
            FCLDoubleRow(
                label = "Max bolus nacht",
                summary = "Maximale SMB 's nachts in één actie.",
                value = maxBolusNight,
                min = 0.1, max = 8.0, step = 0.05, unit = "U",
                onInfo = {
                    showInfo(
                        "Max bolus nacht",
                        "Maximale hoeveelheid insuline die FCL vNext 's nachts in één actie " +
                            "mag toedienen.\n\nExtra bescherming tegen nachtelijke hypo's. Stel dit " +
                            "lager in dan de dagwaarde — de meeste gebruikers zitten op 0,4–0,7 U."
                    )
                },
                onValueChange = {
                    maxBolusNight = it
                    sp.putDouble(DoubleKey.max_bolus_night.key, it)
                }
            )

            // ── Max IOB ──────────────────────────────────────────────────
            FCLDoubleRow(
                label = "Max IOB",
                summary = "Maximaal actief insuline in het lichaam.",
                value = maxIob,
                min = 1.0, max = 25.0, step = 0.5, unit = "U",
                onInfo = {
                    showInfo(
                        "Max IOB",
                        "FCL vNext levert geen extra insuline als het actieve insuline (IOB) " +
                            "boven deze waarde komt.\n\nStel dit in op ongeveer 2–3× je gemiddelde " +
                            "maaltijdbolus. Een te lage waarde kan ertoe leiden dat het systeem " +
                            "stopt met bijsturen terwijl dat nog nodig is."
                    )
                },
                onValueChange = {
                    maxIob = it
                    sp.putDouble(DoubleKey.fcl_vnext_MaxIOB.key, it)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Insulineverdeling ────────────────────────────────────────
            FCLListRow(
                label = "💉 Insulineverdeling",
                summary = "Hoe insuline wordt verdeeld: meer basaal (smooth) of meer SMB (pulsen).",
                options = doseOptions,
                selected = doseStyle,
                onInfo = {
                    showInfo(
                        "Insulineverdeling",
                        "Bepaalt de verhouding tussen tijdelijke basaalraten en SMB-bolussen.\n\n" +
                            "🌊 Ultra smooth: bijna uitsluitend basaal, minimale SMB's.\n" +
                            "🫧 Smooth: overwegend basaal.\n" +
                            "⚖️ Balanced: standaard mix — aanbevolen startpunt.\n" +
                            "🔨 Pulsed: meer SMB's, minder basaal.\n" +
                            "⚡ Ultra pulsed: korte, krachtige SMB's — voor systemen met snelle insuline."
                    )
                },
                onSelect = {
                    doseStyle = it
                    sp.putString(StringKey.fcl_vnext_dose_distribution_style.key, it)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Nachtrespons ─────────────────────────────────────────────
            FCLListRow(
                label = "🌙 Nachtrespons",
                summary = "Hoe vroeg en hoe gelijkmatig FCL 's nachts reageert bij aanhoudende stijging.",
                options = nightOptions,
                selected = nightStyle,
                onInfo = {
                    showInfo(
                        "Nachtrespons",
                        "Bepaalt hoe agressief FCL vNext 's nachts reageert op een stijgende " +
                            "glucosewaarde.\n\n" +
                            "🛑 Zeer terughoudend: pas actie bij duidelijke, langdurige stijging.\n" +
                            "🧯 Terughoudend: conservatief, weinig nacht-SMB's.\n" +
                            "⚖️ Gebalanceerd: standaard — aanbevolen voor de meeste gebruikers.\n" +
                            "🌙 Reageert eerder: eerder ingrijpen, minder hypo-buffer.\n" +
                            "🚀 Proactief: snel reageren — alleen voor stabiele nachten."
                    )
                },
                onSelect = {
                    nightStyle = it
                    sp.putString(StringKey.fcl_vnext_night_response_style.key, it)
                }
            )
        }

        // ══════════════════════════════════════════════════════════════════
        // GROEP 2 — Dag / nacht context
        // ══════════════════════════════════════════════════════════════════
        FCLSection(
            title = "⏰ Dag / nacht context",
            expanded = expandedContext,
            onToggle = { expandedContext = !expandedContext }
        ) {
            Text(
                text = "FCL vNext bepaalt automatisch of het dag of nacht is op basis van " +
                    "onderstaande tijden en weekenddagen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── Weekenddagen ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weekenddagen", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    showInfo(
                        "Weekenddagen",
                        "Selecteer de dagen die als weekend worden behandeld. Op weekenddagen " +
                            "geldt de latere ochtendstarttijd, zodat het systeem je niet onnodig " +
                            "vroeg wakker stuurt via insuline-aanpassingen."
                    )
                }) {
                    Icon(Icons.Default.Info, contentDescription = null,
                         tint = MaterialTheme.colorScheme.primary)
                }
            }
            WeekDaySelector(
                selectedDays = weekendDagenToBoolArray(weekendDagen),
                onDayToggle = { day, selected ->
                    val arr = weekendDagenToBoolArray(weekendDagen)
                    arr[day.ordinal] = selected
                    weekendDagen = boolArrayToWeekendDagen(arr)
                    sp.putString(StringKey.WeekendDagen.key, weekendDagen)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Tijden ───────────────────────────────────────────────────
            FCLTimeRow(
                label = "Ochtendstart (doordeweeks)",
                summary = "Tijdstip waarop FCL overschakelt naar daginstellingen.",
                value = ochtendStart,
                onInfo = {
                    showInfo(
                        "Ochtendstart (doordeweeks)",
                        "Vanaf dit tijdstip gelden de daglimiet (max bolus dag) en de " +
                            "dag-nachtfactor.\n\nKies een tijdstip waarop je normaal wakker bent " +
                            "en actief begint te worden — bijv. 07:00."
                    )
                },
                onWijzig = { showOchtendPicker = true }
            )

            FCLTimeRow(
                label = "Ochtendstart (weekend)",
                summary = "Tijdstip waarop FCL in het weekend overschakelt naar daginstellingen.",
                value = ochtendWeekend,
                onInfo = {
                    showInfo(
                        "Ochtendstart (weekend)",
                        "Dezelfde functie als doordeweeks, maar dan voor de weekenddagen " +
                            "die je hierboven hebt geselecteerd.\n\nStel dit later in als je " +
                            "in het weekend later opstaat — bijv. 08:00 of 09:00."
                    )
                },
                onWijzig = { showOchtendWeekendPicker = true }
            )

            FCLTimeRow(
                label = "Nachtstart",
                summary = "Tijdstip waarop FCL overschakelt naar nachtinstellingen.",
                value = nachtStart,
                onInfo = {
                    showInfo(
                        "Nachtstart",
                        "Vanaf dit tijdstip gelden de nachtlimiet (max bolus nacht), de " +
                            "nacht-factor en de nachtrespons-instelling.\n\nKies een tijdstip " +
                            "waarop je normaal naar bed gaat — bijv. 23:00."
                    )
                },
                onWijzig = { showNachtPicker = true }
            )
        }

        // ══════════════════════════════════════════════════════════════════
        // GROEP 3 — AutoSens & activiteit
        // ══════════════════════════════════════════════════════════════════
        FCLSection(
            title = "🛡️ AutoSens & activiteit",
            expanded = expandedAutosens,
            onToggle = { expandedAutosens = !expandedAutosens }
        ) {
            Text(
                text = "AutoSens past automatisch de insulinegevoeligheid aan op basis van je " +
                    "glucoseverloop over meerdere dagen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── AutoSens gedrag ──────────────────────────────────────────
            FCLListRow(
                label = "AutoSens – Gedrag",
                summary = "Hoe sterk AutoSens mag ingrijpen bij structurele afwijking van target.",
                options = resBehaviorOptions,
                selected = resBehavior,
                onInfo = {
                    showInfo(
                        "AutoSens – Gedrag",
                        "Bepaalt hoe sterk AutoSens mag ingrijpen wanneer je glucose " +
                            "structureel boven of onder target ligt.\n\n" +
                            "Uitgeschakeld: geen AutoSens-correctie.\n" +
                            "Licht: subtiele aanpassingen.\n" +
                            "Normaal: standaard — aanbevolen voor de meeste gebruikers.\n" +
                            "Sterk: grotere aanpassingen, voor uitgesproken resistentie.\n" +
                            "Agressief: maximale correctie — gebruik voorzichtig."
                    )
                },
                onSelect = {
                    resBehavior = it
                    sp.putString(StringKey.fcl_vnext_resistance_behavior.key, it)
                }
            )

            // ── AutoSens stabiliteit ─────────────────────────────────────
            FCLListRow(
                label = "AutoSens – Stabiliteit",
                summary = "Hoeveel dagen worden gebruikt om AutoSens te berekenen.",
                options = resStabilityOptions,
                selected = resStability,
                onInfo = {
                    showInfo(
                        "AutoSens – Stabiliteit",
                        "Bepaalt hoeveel dagen en uren worden gebruikt om de AutoSens-ratio " +
                            "te berekenen.\n\n" +
                            "Zeer stabiel: traag reageren op veranderingen — goed bij onregelmatige data.\n" +
                            "Standaard: aanbevolen balans.\n" +
                            "Responsief: snel aanpassen — goed als je leefpatroon regelmatig is."
                    )
                },
                onSelect = {
                    resStability = it
                    sp.putString(StringKey.fcl_vnext_resistance_stability.key, it)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Activiteit ───────────────────────────────────────────────
            FCLListRow(
                label = "🚶 Activiteit",
                summary = "Past insuline en target aan op basis van beweging via de stappenteller.",
                options = actOptions,
                selected = actBehavior,
                onInfo = {
                    showInfo(
                        "Activiteit",
                        "Bij activering wordt op basis van de stappenteller een tijdelijk " +
                            "target ingesteld en de ISF verhoogd. Dit resulteert in minder " +
                            "insuline tijdens activiteit.\n\n" +
                            "Uit: geen verandering.\n" +
                            "Licht: insuline 70%, TT +1,5 mmol, max 15 min naijl.\n" +
                            "Normaal: insuline 60%, TT +2,0 mmol, max 25 min naijl.\n" +
                            "Sterk: insuline 50%, TT +2,5 mmol, max 35 min naijl."
                    )
                },
                onSelect = {
                    actBehavior = it
                    sp.putString(StringKey.fcl_vnext_activity_behavior.key, it)
                }
            )
        }

        // Expert modus sectie (pincode: 0000)
        if (!showExpertSection) {
            androidx.compose.material3.OutlinedButton(
                onClick = { expertPinDialogOpen = true; expertPinInput = ""; expertPinError = false },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Text("Expert modus")
            }
        } else {
            FCLSection(
                title = "Expert modus",
                expanded = true,
                onToggle = { showExpertSection = false }
            ) {
                androidx.compose.material3.Text(
                    "Expertmodus actief. Meer opties volgen.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expertPinDialogOpen) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { expertPinDialogOpen = false },
                title = { androidx.compose.material3.Text("Expert modus") },
                text = {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Text(
                            "Voer de pincode in (standaard: 0000).",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = expertPinInput,
                            onValueChange = { if (it.length <= 4) { expertPinInput = it; expertPinError = false } },
                            label = { androidx.compose.material3.Text("Pincode") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                            ),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            isError = expertPinError,
                            singleLine = true
                        )
                        if (expertPinError) {
                            androidx.compose.material3.Text(
                                "Onjuiste pincode",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.Button(onClick = {
                        if (expertPinInput == EXPERT_PIN) {
                            showExpertSection = true; expertPinDialogOpen = false
                        } else expertPinError = true
                    }) { androidx.compose.material3.Text("Openen") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { expertPinDialogOpen = false }) {
                        androidx.compose.material3.Text("Annuleren")
                    }
                }
            )
        }
    }

    // ── Tijdpickers (buiten Column — zijn Popups) ─────────────────────────
    if (showOchtendPicker) {
        TimeWheelPicker(
            selectedHour = ochtendStart.toHour(),
            selectedMinute = ochtendStart.toMinute(),
            minHour = 4, maxHour = 11, minuteStep = 15,
            onTimeSelected = { h, m ->
                ochtendStart = "%02d:%02d".format(h, m)
                sp.putString(StringKey.OchtendStart.key, ochtendStart)
            },
            onDismiss = { showOchtendPicker = false }
        )
    }
    if (showOchtendWeekendPicker) {
        TimeWheelPicker(
            selectedHour = ochtendWeekend.toHour(),
            selectedMinute = ochtendWeekend.toMinute(),
            minHour = 4, maxHour = 12, minuteStep = 15,
            onTimeSelected = { h, m ->
                ochtendWeekend = "%02d:%02d".format(h, m)
                sp.putString(StringKey.OchtendStartWeekend.key, ochtendWeekend)
            },
            onDismiss = { showOchtendWeekendPicker = false }
        )
    }
    if (showNachtPicker) {
        TimeWheelPicker(
            selectedHour = nachtStart.toHour(),
            selectedMinute = nachtStart.toMinute(),
            minHour = 20, maxHour = 23, minuteStep = 15,
            onTimeSelected = { h, m ->
                nachtStart = "%02d:%02d".format(h, m)
                sp.putString(StringKey.NachtStart.key, nachtStart)
            },
            onDismiss = { showNachtPicker = false }
        )
    }

    // ── Info dialoog ──────────────────────────────────────────────────────
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(infoDialogTitle) },
            text = { Text(infoDialogText) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Sluiten")
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Herbruikbare componenten
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FCLSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun FCLDoubleRow(
    label: String,
    summary: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unit: String,
    onInfo: () -> Unit,
    onValueChange: (Double) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val raw = (value - step).coerceAtLeast(min)
                onValueChange((Math.round(raw / step) * step * 100.0).toLong() / 100.0)
            }) { Text("−", style = MaterialTheme.typography.titleMedium) }
            Text(
                text = "%.2f %s".format(value, unit),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 60.dp)
            )
            IconButton(onClick = {
                val raw = (value + step).coerceAtMost(max)
                onValueChange((Math.round(raw / step) * step * 100.0).toLong() / 100.0)
            }) { Text("+", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun FCLListRow(
    label: String,
    summary: String,
    options: List<Pair<String, String>>,
    selected: String,
    onInfo: () -> Unit,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "▸ $selectedLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) { Text("Wijzig") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun FCLTimeRow(
    label: String,
    summary: String,
    value: String,
    onInfo: () -> Unit,
    onWijzig: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = onWijzig) { Text("Wijzig") }
    }
}