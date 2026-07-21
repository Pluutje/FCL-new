package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOCAL_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault())

private fun utcToLocal(utc: String): String = try {
    LOCAL_FMT.format(Instant.parse(utc))
} catch (_: Exception) { utc }
/**
 * ============================================================================
 *
 * Eén kaart per parametersuggestie, los goed te keuren of af te wijzen.
 * Provider-keuze (Claude / Gemini) + API-sleutel-invoer in een inklapbaar
 * blok — ingeklapt zodra er al een sleutel is opgeslagen, zodat het maar
 * eenmalig in het oog springt (30/06/2026, Ecko).
 */
@Composable
fun FclAiAdvisorScreen(
    runResult: AiAdvisorRunResult?,
    onBack: () -> Unit,
    onRefreshNow: () -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(!FclAiAdvisorSettingsStore.isConfigured(context)) }

    // 05/07/2026 (Ecko): NIET meer automatisch dismissen bij het openen van dit
    // scherm — alleen bekijken is niet hetzelfde als beoordelen. De melding
    // blijft nu terugkomen (elke cyclus, via FclAiAdvisorScheduler.runIfDue())
    // zolang er nog voorstellen openstaan, en verdwijnt vanzelf zodra alles
    // is goed- of afgekeurd (stillPendingCount bereikt 0 → showPendingAdvice(0)
    // dismisst 'm). De gebruiker beslist zelf of hij negeert of afwijst.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Titelbalk: Terug links, ⚙️ rechts op dezelfde regel ──────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("← Terug") }
            TextButton(onClick = { showSettings = !showSettings }) {
                val modelName = FclAiAdvisorSettingsStore.getSelectedModel(context).displayName
                    .take(16)  // afkappen zodat het op één regel past
                Text("⚙️ $modelName", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("AI Parameter-adviseur", style = MaterialTheme.typography.headlineMedium)
        app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclModeStatusLine(
            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.getMode(context)
        )

        // Aan/uit + automatisch/handmatig staat sinds 10/07/2026 bij
        // Settings → Analyser Automaat / AI Advisor, niet meer hier.

        // ── Inklapbaar instellingenblok ──────────────────────────────────────
        AnimatedVisibility(visible = showSettings) {
            SettingsSection(context, onSaved = { showSettings = false })
        }

        // 13/07/2026 (Ecko) — directe klik-bevestiging voor "Nu vernieuwen". De
        // knoptekst hieronder ("⏳ Bezig…") leunt op FclAiAdvisorScheduler.isRunning(),
        // een plain AtomicBoolean, geen Compose-observable State — die wijzigt in de
        // praktijk niet betrouwbaar mee in de UI. En bij een eerdere foutmelding
        // (zoals hierboven bij runResult.parseError) blijft die foutmelding gewoon
        // ongewijzigd zichtbaar totdat de nieuwe aanvraag klaar is — die kan bij een
        // 503/timeout lang duren. Resultaat: na klikken lijkt er niets te gebeuren.
        // Fix: een losse, lokale Compose-state die het moment van klikken vastlegt,
        // onafhankelijk van hoe lang de aanvraag duurt of hoe hij afloopt. Het
        // succesvolle-resultaat-scherm zelf blijft ongewijzigd.
        var lastRequestedAt by remember { mutableStateOf<String?>(null) }
        // 21/07/2026 (Ecko): naast de weergavetekst hierboven ook het echte
        // Instant bijhouden — nodig om zo dadelijk te kunnen bepalen of
        // runResult (van de VORIGE aanvraag) inmiddels verouderd is t.o.v.
        // deze nieuwe klik. Zie kdoc bij isRefreshing hieronder.
        var lastRequestedAtInstant by remember { mutableStateOf<Instant?>(null) }

        val ready = FclAiAdvisorSettingsStore.isConfigured(context)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val nu = Instant.now()
                    lastRequestedAtInstant = nu
                    lastRequestedAt = LOCAL_FMT.format(nu)
                    onRefreshNow()
                },
                enabled = ready
            ) {
                Text(if (FclAiAdvisorScheduler.isRunning()) "⏳ Bezig…" else "Nu vernieuwen")
            }
        }
        lastRequestedAt?.let {
            Text(
                "Advies aangevraagd: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!ready) {
            Text(
                "Vul eerst een API-sleutel in (⚙️ rechtsboven) om de adviseur te draaien.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 21/07/2026 (Ecko) — BUGFIX: het rapport hieronder toonde na een klik op
        // "Nu vernieuwen" gewoon het VORIGE resultaat door totdat het nieuwe
        // binnen was — geen enkel verschil te zien tussen "nog bezig" en "dit IS
        // het nieuwe advies". runResult komt van buiten (de host bewaart alleen
        // het laatst VOLTOOIDE rapport) en heeft dus geen eigen "in behandeling"-
        // status; die wordt hier afgeleid door lastRequestedAtInstant (het moment
        // van de klik) te vergelijken met runResult.generatedAtUtc (het moment
        // waarop het GETOONDE rapport is gemaakt). Is dat laatste ouder dan de
        // klik (of nog helemaal niet aanwezig), dan is het zichtbare rapport
        // stale — verbergen en een duidelijke wacht-status tonen in plaats van
        // net te doen of het al het nieuwe antwoord is.
        val getoondRapportInstant = runResult?.let {
            runCatching { Instant.parse(it.generatedAtUtc) }.getOrNull()
        }
        val isRefreshing = lastRequestedAtInstant != null &&
            (getoondRapportInstant == null || getoondRapportInstant.isBefore(lastRequestedAtInstant))
        if (isRefreshing) {
            Text(
                "⏳ Nieuw advies wordt opgehaald — het rapport hieronder verschijnt zodra dit klaar is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            when {
                runResult == null -> Text(
                    "Nog geen rapport beschikbaar. Druk op \"Nu vernieuwen\" om de eerste analyse te draaien.",
                    style = MaterialTheme.typography.bodyMedium
                )
                runResult.parseError != null -> Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Adviseur kon dit rapport niet voltooien", fontWeight = FontWeight.Bold)
                        Text(runResult.parseError, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    val accepted = runResult.suggestions.filter { !it.rejected }
                    val rejected = runResult.suggestions.filter { it.rejected }

                    Text(
                        "Rapport van ${utcToLocal(runResult.generatedAtUtc)} — ${accepted.size} voorstel(len)" +
                            if (rejected.isNotEmpty()) ", ${rejected.size} automatisch verworpen" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // ── Informatieve constatering (21/07/2026, Ecko) ──────────────
                    // Zie kdoc bij AiAdvisorRunResult.advisoryNoteNl: GEEN voorstel — niet
                    // goed/af te keuren, dus bewust niet als SuggestionCard maar als losse,
                    // visueel duidelijk andere info-kaart. Bedoeld voor de "timing al op
                    // hardMax, probleem blijft bestaan, geen parameter kan het oplossen"-
                    // situatie (zie FclAiAdvisorPromptBuilder) — voorheen greep het model
                    // hier terug op lateCommitDecayFactor verlagen, wat nu expliciet
                    // verboden is (zie "Wat je NIET mag" in de prompt).
                    runResult.advisoryNoteNl?.let { note ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "ℹ️ Constatering (geen voorstel)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (accepted.isEmpty()) {
                        Text(
                            "Geen voorstellen vandaag — alles blijft binnen verwachting, of er is te weinig bewijs.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    accepted.forEach { SuggestionCard(it, context) }

                    if (rejected.isNotEmpty()) {
                        Text("Automatisch verworpen (buiten bereik of onvoldoende onderbouwing):",
                             fontWeight = FontWeight.Bold)
                        rejected.forEach { s ->
                            Text("• ${s.param}: ${s.rejectionReasonNl}",
                                 style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // ── Geschiedenis van AI-aanpassingen ──────────────────────────
                    AiHistorySection()
                }
            }
        } // einde isRefreshing-else (zie kdoc hierboven bij getoondRapportInstant)

        // einde inhoud
    }
}

@Composable
private fun AiHistorySection() {
    var expanded by remember { mutableStateOf(false) }

    Divider()
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (expanded) "▲ Verberg aanpassingsgeschiedenis (AI)"
            else "▼ Toon aanpassingsgeschiedenis (AI)",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (!expanded) return

    val approved = remember { FclAiAdvisorHistoryRepository.readApproved() }

    if (approved.isEmpty()) {
        Text("Nog geen goedgekeurde aanpassingen geregistreerd.",
             style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.padding(horizontal = 4.dp))
        return
    }

    val byParam = approved.groupBy { it.param }
        .mapValues { (_, e) -> e.sortedBy { it.tsUtc } }

    byParam.forEach { (param, entries) ->
        val spec = FclAiAdvisorRanges.byKey[param]
        val label = spec?.labelNl ?: param
        val latest = entries.last()
        val first = entries.first()
        val direction = when {
            latest.proposedValue > first.proposedValue + 0.001 -> "↑ omhoog"
            latest.proposedValue < first.proposedValue - 0.001 -> "↓ omlaag"
            else -> "→ stabiel"
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(label,
                 style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            Text(
                "${"%.4f".format(latest.proposedValue)}  $direction",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    direction.startsWith("↑") -> MaterialTheme.colorScheme.primary
                    direction.startsWith("↓") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        val values = entries.map { it.proposedValue.toFloat() }
        val minV = (values.min() * 0.95f).coerceAtLeast(spec?.min?.toFloat() ?: 0f)
        val maxV = (values.max() * 1.05f).coerceAtMost(spec?.max?.toFloat() ?: Float.MAX_VALUE)
        val range = (maxV - minV).coerceAtLeast(0.001f)
        val lineColor = androidx.compose.ui.graphics.Color(0xFF7C4DFF)

        Canvas(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp)
        ) {
            val w = size.width; val h = size.height; val n = values.size
            if (n < 1) return@Canvas
            val pts = values.mapIndexed { i, v ->
                Offset(
                    x = if (n == 1) w / 2f else i * w / (n - 1).toFloat(),
                    y = h - (v - minV) / range * h
                )
            }
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h); close()
                }
                drawPath(path, color = lineColor.copy(alpha = 0.15f))
                for (i in 0 until pts.size - 1) {
                    drawLine(lineColor, pts[i], pts[i + 1], strokeWidth = 3f, cap = StrokeCap.Round)
                }
            }
            drawCircle(lineColor, radius = 5f, center = pts.last())
        }

        Text(
            "${utcToLocal(latest.tsUtc)}  ${"%.4f".format(latest.currentValue)} → ${"%.4f".format(latest.proposedValue)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

// ── Inklapbaar instellingenblok ───────────────────────────────────────────────

@Composable
private fun SettingsSection(context: Context, onSaved: () -> Unit = {}) {
    var selectedModel by remember { mutableStateOf(FclAiAdvisorSettingsStore.getSelectedModel(context)) }
    var key1 by remember(selectedModel.id) { mutableStateOf(FclAiAdvisorSettingsStore.getKey1(context, selectedModel.id)) }
    var key2 by remember(selectedModel.id) { mutableStateOf(FclAiAdvisorSettingsStore.getKey2(context, selectedModel.id)) }
    var expanded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text(
                "Sleutels worden alleen lokaal op dit toestel opgeslagen (eigen FCLvNext-opslag). Eenmalig invullen.",
                style = MaterialTheme.typography.bodySmall
            )

            // Model-dropdown
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("${selectedModel.displayName}  ▾", style = MaterialTheme.typography.bodyMedium)
            }
            if (expanded) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        FclAiAdvisorSettingsStore.MODELS.forEach { model ->
                            TextButton(
                                onClick = {
                                    selectedModel = model
                                    key1 = FclAiAdvisorSettingsStore.getKey1(context, model.id)
                                    key2 = FclAiAdvisorSettingsStore.getKey2(context, model.id)
                                    expanded = false
                                    saved = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    model.displayName,
                                    color = if (model.id == selectedModel.id)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Sleutel-instructie per provider
            val hint = when (selectedModel.provider) {
                FclAiAdvisorSettingsStore.Provider.GEMINI -> "Sleutel aanmaken: aistudio.google.com → Get API key"
                FclAiAdvisorSettingsStore.Provider.CLAUDE -> "Sleutel aanmaken: console.anthropic.com → API Keys"
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            // Sleutel 1
            OutlinedTextField(
                value = key1,
                onValueChange = { key1 = it; saved = false },
                label = { Text("API-sleutel 1 (primair)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            // Sleutel 2 (fallback)
            OutlinedTextField(
                value = key2,
                onValueChange = { key2 = it; saved = false },
                label = { Text("API-sleutel 2 (fallback bij fout — mag leeg)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    FclAiAdvisorSettingsStore.setSelectedModel(context, selectedModel.id)
                    FclAiAdvisorSettingsStore.setKey1(context, selectedModel.id, key1)
                    FclAiAdvisorSettingsStore.setKey2(context, selectedModel.id, key2)
                    saved = true
                    onSaved()
                }) { Text("Opslaan") }
                if (saved) Text("✅ Opgeslagen", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── Reset-kaart (voor per ongeluk goedgekeurde waarden) ──────────────────────

@Composable
// ── Suggestiekaart ────────────────────────────────────────────────────────────

private fun SuggestionCard(suggestion: AiParamSuggestion, context: Context) {
    // Pre-populeer status vanuit history zodat goedkeuring/afwijzing zichtbaar
    // blijft na navigeren — de Composable wordt anders bij terugkeren opnieuw
    // aangemaakt met status=PENDING (UI-state is niet persistent).
    var status by remember(suggestion.param, suggestion.proposedValue) {
        val lastEntry = FclAiAdvisorHistoryRepository.lastEntryFor(suggestion.param)
        val initial = if (lastEntry != null &&
            kotlin.math.abs(lastEntry.proposedValue - suggestion.proposedValue) < 0.001) {
            lastEntry.status
        } else {
            AiSuggestionStatus.PENDING
        }
        mutableStateOf(initial)
    }
    var resultMessage by remember(suggestion.param, suggestion.proposedValue) {
        mutableStateOf<String?>(null)
    }

    val spec  = FclAiAdvisorRanges.byKey[suggestion.param]
    val label = spec?.labelNl ?: suggestion.param

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(suggestion.param, style = MaterialTheme.typography.labelSmall)

            if (spec != null) {
                Text(
                    "Wat doet dit: ${spec.effectNl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Text(
                "Huidig: ${"%.4f".format(suggestion.currentValue)}  →  Voorgesteld: ${"%.4f".format(suggestion.proposedValue)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Confidence: ${(suggestion.confidence * 100).toInt()}%",
                 style = MaterialTheme.typography.bodySmall)
            Text("Reden: ${suggestion.reasonNl}", style = MaterialTheme.typography.bodyMedium)
            if (suggestion.evidenceFields.isNotEmpty()) {
                Text("Bewijs: " + suggestion.evidenceFields.joinToString(", "),
                     style = MaterialTheme.typography.bodySmall)
            }

            // Korte waarschuwing (18/07/2026, Ecko) — bewust beknopt, geen lang
            // betoog: bij elke kaart opnieuw zichtbaar, vlak boven de knoppen.
            Text(
                "\u26A0\uFE0F Controleer advies en confidence goed voor je goedkeurt \u2014 bij twijfel afwijzen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            when (status) {
                AiSuggestionStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val r = FclAiAdvisorApplier.approve(suggestion, context)
                        if (r is FclAiAdvisorApplier.ApplyResult.Applied) {
                            resultMessage = "Toegepast — actief in de eerstvolgende FCLvNext-cyclus."
                            status = AiSuggestionStatus.APPROVED
                        } else {
                            resultMessage = (r as FclAiAdvisorApplier.ApplyResult.Rejected).reasonNl
                            status = AiSuggestionStatus.REJECTED
                        }
                    }) { Text("Goedkeuren") }

                    OutlinedButton(onClick = {
                        FclAiAdvisorApplier.reject(suggestion)
                        status = AiSuggestionStatus.REJECTED
                        resultMessage = "Afgewezen — niet teruggestuurd de komende 2 dagen bij zelfde waarde."
                    }) { Text("Afwijzen") }
                }
                AiSuggestionStatus.APPROVED ->
                    Text("✅ ${resultMessage ?: "Goedgekeurd"}", style = MaterialTheme.typography.bodySmall)
                AiSuggestionStatus.REJECTED ->
                    Text("✖️ ${resultMessage ?: "Afgewezen"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}