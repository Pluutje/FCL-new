package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.pickers.TimeWheelPicker
import app.aaps.core.ui.compose.pickers.WeekDaySelector
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

@Composable
fun FCLSettingsScreen(preferences: Preferences, sp: SP) {

    // Link naar het externe FCLvNext-handboek (Google Doc) — los bestand,
    // zodat het bijwerken van de uitleg geen nieuwe app-build vereist.
    //  val FCL_DOCS_URL = "https://docs.google.com/document/d/14iTVM0uW8aYZgQYYRJaJ9O7XyiN9ZbAy/edit"
    val FCL_DOCS_URL = "https://docs.google.com/document/d/1bexT8FFEA0SGP7-P13pL25iXvgkfH_yA/edit"

    fun String.toHour(): Int = split(":").getOrNull(0)?.toIntOrNull() ?: 7
    fun String.toMinute(): Int = split(":").getOrNull(1)?.toIntOrNull() ?: 0

    fun weekendDagenToBoolArray(csv: String): BooleanArray {
        val selected = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return BooleanArray(7) { i -> (i + 1) in selected }
    }
    fun boolArrayToWeekendDagen(arr: BooleanArray): String =
        arr.indices.filter { arr[it] }.map { it + 1 }.joinToString(",")

    var maxBolusDay       by remember { mutableStateOf(preferences.get(DoubleKey.max_bolus_day)) }
    var maxBolusNight     by remember { mutableStateOf(preferences.get(DoubleKey.max_bolus_night)) }
    var maxIob            by remember { mutableStateOf(preferences.get(DoubleKey.fcl_vnext_MaxIOB)) }
    var doseStyle         by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_dose_distribution_style)) }
    // nightStyle / resBehavior / resStability verwijderd (18/06/2026)
    var actBehavior       by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_activity_behavior)) }
    var weekendDagen      by remember { mutableStateOf(preferences.get(StringKey.WeekendDagen)) }
    var ochtendStart      by remember { mutableStateOf(preferences.get(StringKey.OchtendStart)) }
    var ochtendWeekend    by remember { mutableStateOf(preferences.get(StringKey.OchtendStartWeekend)) }
    var nachtStart        by remember { mutableStateOf(preferences.get(StringKey.NachtStart)) }

    var expandedDosering  by remember { mutableStateOf(true) }
    var expandedAnalyserAutomaat by remember { mutableStateOf(false) }
    var expandedContext   by remember { mutableStateOf(false) }
    var expandedAutosens  by remember { mutableStateOf(false) }

    var showExpertSection   by remember { mutableStateOf(false) }
    var expertPinInput      by remember { mutableStateOf("") }
    var expertPinError      by remember { mutableStateOf(false) }
    var expertPinDialogOpen by remember { mutableStateOf(false) }
    val EXPERT_PIN = "0000"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var selectedLocale by remember {
        mutableStateOf(app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings.loadLocale(ctx))
    }
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

    var showOchtendPicker        by remember { mutableStateOf(false) }
    var showOchtendWeekendPicker by remember { mutableStateOf(false) }
    var showNachtPicker          by remember { mutableStateOf(false) }

    var infoDialogTitle   by remember { mutableStateOf("") }
    var infoDialogText    by remember { mutableStateOf("") }
    var showInfoDialog    by remember { mutableStateOf(false) }

    fun showInfo(title: String, text: String) {
        infoDialogTitle = title
        infoDialogText  = text
        showInfoDialog  = true
    }

    val doseOptions = listOf(
        "VERY_SMOOTH" to s.doseStyleLabel("VERY_SMOOTH"),
        "SMOOTH"      to s.doseStyleLabel("SMOOTH"),
        "BALANCED"    to s.doseStyleLabel("BALANCED"),
        "PULSED"      to s.doseStyleLabel("PULSED"),
        "VERY_PULSED" to s.doseStyleLabel("VERY_PULSED")
    )
    // nightOptions/resBehaviorOptions/resStabilityOptions verwijderd (18/06/2026)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ── Documentatie: link naar het volledige FCLvNext-handboek ───────
        // Externe (Google Doc) link i.p.v. ingebouwde tekst — zelfde opzet
        // als het oude systeem: één centraal, makkelijk bij te werken
        // document i.p.v. uitleg die in de app zelf verspreid staat.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📘", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hoe werkt FCLvNext?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Text(
                    "Uitleg van het hele algoritme — wat het doet, hoe het leert " +
                        "(indien ingeschakeld) en wat elke instelling betekent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(FCL_DOCS_URL)
                        )
                        ctx.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open handleiding")
                }
            }
        }

        FCLSection(
            title = s.settingsDosisBehavior,
            expanded = expandedDosering,
            onToggle = { expandedDosering = !expandedDosering }
        ) {
            FCLDoubleRow(
                label = s.settingsMaxBolusDay,
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
                            "Stel dit in op wat je normaal als maximale maaltijdbolus zou nemen."
                    )
                },
                onValueChange = {
                    maxBolusDay = it
                    sp.putDouble(DoubleKey.max_bolus_day.key, it)
                }
            )

            FCLDoubleRow(
                label = s.settingsMaxBolusNight,
                summary = "Maximale SMB 's nachts in één actie.",
                value = maxBolusNight,
                min = 0.1, max = 8.0, step = 0.05, unit = "U",
                onInfo = {
                    showInfo(
                        s.settingsMaxBolusNight,
                        "Maximale hoeveelheid insuline die FCL vNext 's nachts in één actie " +
                            "mag toedienen.\n\nExtra bescherming tegen nachtelijke hypo's."
                    )
                },
                onValueChange = {
                    maxBolusNight = it
                    sp.putDouble(DoubleKey.max_bolus_night.key, it)
                }
            )

            FCLDoubleRow(
                label = s.settingsMaxIob,
                summary = "Maximaal actief insuline in het lichaam.",
                value = maxIob,
                min = 1.0, max = 25.0, step = 0.5, unit = "U",
                onInfo = {
                    showInfo(
                        s.settingsMaxIob,
                        "FCL vNext levert geen extra insuline als het actieve insuline (IOB) " +
                            "boven deze waarde komt.\n\nStel dit in op ongeveer 2–3× je gemiddelde " +
                            "maaltijdbolus."
                    )
                },
                onValueChange = {
                    maxIob = it
                    sp.putDouble(DoubleKey.fcl_vnext_MaxIOB.key, it)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            FCLListRow(
                label = "💉 Insulineverdeling",
                summary = "Hoe insuline wordt verdeeld: meer basaal (smooth) of meer SMB (pulsen).",
                options = doseOptions,
                selected = doseStyle,
                onInfo = {
                    showInfo(
                        "Insulineverdeling",
                        "Bepaalt de verhouding tussen tijdelijke basaalraten en SMB-bolussen.\n\n" +
                            "⚖️ Balanced: standaard mix — aanbevolen startpunt."
                    )
                },
                onSelect = {
                    doseStyle = it
                    sp.putString(StringKey.fcl_vnext_dose_distribution_style.key, it)
                }
            )

        }

        FCLSection(
            title = "🤖 Analyser Automaat / AI Advisor",
            expanded = expandedAnalyserAutomaat,
            onToggle = { expandedAnalyserAutomaat = !expandedAnalyserAutomaat }
        ) {
            // 10/07/2026 (Ecko) — beide aan/uit + automatisch/handmatig-
            // schakelaars samengevoegd op één plek. Voorheen stond hier alleen
            // de oude "Automaat leert"-knop (nu vervangen door de Learner-kaart
            // hieronder), en stonden de nieuwe kaarten los in het Learner- resp.
            // AI-tabblad zelf — daar staat nu alleen nog de inhoud (het
            // openstaande voorstel), de bediening staat hier bij elkaar.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclModeSelectorCard(
                    title = "Learner",
                    initialMode = DFLearner.getMode(ctx),
                    autoDescription = "Past D/F/timing automatisch aan na elke maaltijdepisode",
                    manualDescription = "Berekent een voorstel, past pas toe na jouw goedkeuring",
                    onModeChange = { DFLearner.setMode(ctx, it) }
                )
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclModeSelectorCard(
                    title = "AI-adviseur",
                    initialMode = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.getMode(ctx),
                    autoDescription = "Past goedgekeurde parameters automatisch toe, geen melding",
                    manualDescription = "Meldt nieuwe voorstellen, past pas toe na jouw goedkeuring",
                    onModeChange = { app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.setMode(ctx, it) }
                )
            }
        }

        FCLSection(
            title = s.settingsDagNacht,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.settingsWeekendDagen, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    showInfo(
                        s.settingsWeekendDagen,
                        "Selecteer de dagen die als weekend worden behandeld."
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

            FCLTimeRow(
                label = "Ochtendstart (doordeweeks)",
                summary = "Tijdstip waarop FCL overschakelt naar daginstellingen.",
                value = ochtendStart,
                onInfo = {
                    showInfo("Ochtendstart (doordeweeks)",
                             "Vanaf dit tijdstip gelden de daglimiet en de dag-nachtfactor.")
                },
                onWijzig = { showOchtendPicker = true }
            )

            FCLTimeRow(
                label = "Ochtendstart (weekend)",
                summary = "Tijdstip waarop FCL in het weekend overschakelt naar daginstellingen.",
                value = ochtendWeekend,
                onInfo = {
                    showInfo("Ochtendstart (weekend)",
                             "Dezelfde functie als doordeweeks, maar voor weekenddagen.")
                },
                onWijzig = { showOchtendWeekendPicker = true }
            )

            FCLTimeRow(
                label = "Nachtstart",
                summary = "Tijdstip waarop FCL overschakelt naar nachtinstellingen.",
                value = nachtStart,
                onInfo = {
                    showInfo("Nachtstart",
                             "Vanaf dit tijdstip gelden de nachtlimiet en de nachtrespons-instelling.")
                },
                onWijzig = { showNachtPicker = true }
            )
        }

        FCLSection(
            title = "🚶 Activiteit",
            expanded = expandedAutosens,
            onToggle = { expandedAutosens = !expandedAutosens }
        ) {
            // AutoSens-sectie verwijderd (18/06/2026)
            // Vereenvoudigd van 4 standen (UIT/LICHT/NORMAAL/STERK) naar AAN/UIT
            // (29/06/2026): intensiteitsdetectie op basis van stappenaantal regelt
            // het effect automatisch — aparte standen voegden geen meerwaarde toe.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "🚶 Activiteit",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (actBehavior != "OFF")
                            "Past insuline en target aan op basis van stappenteller."
                        else
                            "Activiteitsdetectie uitgeschakeld.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = actBehavior != "OFF",
                    onCheckedChange = { aan ->
                        actBehavior = if (aan) "ON" else "OFF"
                        sp.putString(StringKey.fcl_vnext_activity_behavior.key, actBehavior)
                    }
                )
            }
        }

        // ── Expert modus ──────────────────────────────────────────────────
        if (!showExpertSection) {
            OutlinedButton(
                onClick = { expertPinDialogOpen = true; expertPinInput = ""; expertPinError = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(s.expertModus)
            }
        } else {
            FCLSection(
                title = s.expertModus,
                expanded = true,
                onToggle = { showExpertSection = false }
            ) {
                // ── Taalinstelling ───────────────────────────────────
                Text(
                    if (selectedLocale.code == "nl") "Taalinstelling" else "Language",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclLocale.values().forEach { locale ->
                        FilterChip(
                            selected = selectedLocale == locale,
                            onClick = {
                                selectedLocale = locale
                                app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings.saveLocale(ctx, locale)
                            },
                            label = { Text(locale.displayName) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ── Expert modus aan/uit toggle ──────────────────────
                var expertModeActive by remember {
                    mutableStateOf(
                        ctx.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                            .getBoolean("expert_mode_active", false)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (expertModeActive) s.expertModeAan else s.expertModeUit,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            s.fijnafstemming,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = expertModeActive,
                        onCheckedChange = { active ->
                            expertModeActive = active
                            ctx.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("expert_mode_active", active).apply()
                        }
                    )
                }

                // ── Fijnafstemming blok (alleen zichtbaar als expert mode aan) ──
                if (expertModeActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                s.fijnafstemming,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                s.expertModusMeerOpties,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

    } // einde Column

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
                    Text(s.close)
                }
            }
        )
    }

    // ── Pincode dialoog voor expert modus ─────────────────────────────────
    if (expertPinDialogOpen) {
        AlertDialog(
            onDismissRequest = { expertPinDialogOpen = false },
            title = { Text(s.expertModus) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Voer de pincode in (standaard: 0000).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = expertPinInput,
                        onValueChange = { if (it.length <= 4) { expertPinInput = it; expertPinError = false } },
                        label = { Text(s.pincode) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = expertPinError,
                        singleLine = true
                    )
                    if (expertPinError) {
                        Text(
                            s.expertPinFout,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (expertPinInput == EXPERT_PIN) {
                        showExpertSection = true
                        expertPinDialogOpen = false
                    } else expertPinError = true
                }) { Text(s.openen) }
            },
            dismissButton = {
                TextButton(onClick = { expertPinDialogOpen = false }) { Text(s.annuleren) }
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
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
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
            TextButton(onClick = { expanded = true }) { Text(s.change) }
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
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
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
        TextButton(onClick = onWijzig) { Text(s.change) }
    }
}