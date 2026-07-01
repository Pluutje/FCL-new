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

/**
 * ============================================================================
 * FCL AI-Advisor — Scherm
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = onBack) { Text("← Terug") }

        Text("AI Parameter-adviseur", style = MaterialTheme.typography.headlineMedium)

        Text("AI Parameter-adviseur", style = MaterialTheme.typography.headlineMedium)

        val ready = FclAiAdvisorSettingsStore.isConfigured(context)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onRefreshNow, enabled = ready) {
                Text(if (FclAiAdvisorScheduler.isRunning()) "⏳ Bezig…" else "Nu vernieuwen")
            }
        }
        if (!ready) {
            Text(
                "Vul eerst een API-sleutel in (zie ⚙️ Instellingen onderaan) om de adviseur te kunnen draaien.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
                    "Rapport van ${runResult.generatedAtUtc} — ${accepted.size} voorstel(len)" +
                        if (rejected.isNotEmpty()) ", ${rejected.size} automatisch verworpen" else "",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (accepted.isEmpty()) {
                    Text(
                        "Geen voorstellen vandaag — alles blijft binnen verwachting, of er is te weinig bewijs.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                accepted.forEach { SuggestionCard(it) }

                if (rejected.isNotEmpty()) {
                    Text("Automatisch verworpen (buiten bereik of onvoldoende onderbouwing):",
                        fontWeight = FontWeight.Bold)
                    rejected.forEach { s ->
                        Text("• ${s.param}: ${s.rejectionReasonNl}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ── Instellingen onderaan — weinig opvallend na de eerste keer invullen ──
        SettingsSection(context)
    }
}

// ── Inklapbaar instellingenblok ───────────────────────────────────────────────

@Composable
private fun SettingsSection(context: Context) {
    // Start ingeklapt als er al een sleutel is; open als er nog niets is
    var expanded by remember { mutableStateOf(!FclAiAdvisorSettingsStore.isConfigured(context)) }

    var provider by remember { mutableStateOf(FclAiAdvisorSettingsStore.getProvider(context)) }
    var claudeKey   by remember { mutableStateOf(FclAiAdvisorSettingsStore.getClaudeKey(context)) }
    var claudeModel by remember { mutableStateOf(FclAiAdvisorSettingsStore.getClaudeModel(context)) }
    var geminiKey   by remember { mutableStateOf(FclAiAdvisorSettingsStore.getGeminiKey(context)) }
    var geminiModel by remember { mutableStateOf(FclAiAdvisorSettingsStore.getGeminiModel(context)) }
    var saved by remember { mutableStateOf(false) }

    val activeLabel = when (provider) {
        FclAiAdvisorSettingsStore.Provider.GEMINI -> "Gemini"
        FclAiAdvisorSettingsStore.Provider.CLAUDE -> "Claude"
    }
    val hasKey = FclAiAdvisorSettingsStore.isConfigured(context)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Titelbalk met klapknop
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (hasKey) "⚙️ Instellingen ($activeLabel)" else "⚙️ Instellingen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { expanded = !expanded; saved = false }) {
                    Text(if (expanded) "Inklappen" else "Wijzigen")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 8.dp)) {

                    Text(
                        "Kies je AI-provider. De sleutel wordt alleen lokaal op dit toestel opgeslagen " +
                            "(eigen FCLvNext-opslag, niet via AAPS). Eenmalig invullen is voldoende.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Provider-keuze
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FclAiAdvisorSettingsStore.Provider.entries.forEach { p ->
                            val label = if (p == FclAiAdvisorSettingsStore.Provider.GEMINI)
                                "Gemini (gratis tier)" else "Claude"
                            if (provider == p) {
                                Button(onClick = {}) { Text(label) }
                            } else {
                                OutlinedButton(onClick = {
                                    provider = p
                                    FclAiAdvisorSettingsStore.setProvider(context, p)
                                    saved = false
                                }) { Text(label) }
                            }
                        }
                    }

                    // Velden voor de actieve provider
                    when (provider) {
                        FclAiAdvisorSettingsStore.Provider.GEMINI -> {
                            Text("Gratis API-sleutel aanmaken: aistudio.google.com → Get API key",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                            OutlinedTextField(
                                value = geminiKey,
                                onValueChange = { geminiKey = it; saved = false },
                                label = { Text("Gemini API-sleutel") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = geminiModel,
                                onValueChange = { geminiModel = it; saved = false },
                                label = { Text("Model (standaard: ${FclAiAdvisorSettingsStore.DEFAULT_GEMINI_MODEL})") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FclAiAdvisorSettingsStore.Provider.CLAUDE -> {
                            Text("API-sleutel aanmaken: console.anthropic.com → API Keys",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                            OutlinedTextField(
                                value = claudeKey,
                                onValueChange = { claudeKey = it; saved = false },
                                label = { Text("Anthropic API-sleutel") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = claudeModel,
                                onValueChange = { claudeModel = it; saved = false },
                                label = { Text("Model (standaard: ${FclAiAdvisorSettingsStore.DEFAULT_CLAUDE_MODEL})") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            FclAiAdvisorSettingsStore.setProvider(context, provider)
                            FclAiAdvisorSettingsStore.setClaudeKey(context, claudeKey)
                            FclAiAdvisorSettingsStore.setClaudeModel(context, claudeModel)
                            FclAiAdvisorSettingsStore.setGeminiKey(context, geminiKey)
                            FclAiAdvisorSettingsStore.setGeminiModel(context, geminiModel)
                            saved = true
                            expanded = false   // inklappen na opslaan
                        }) { Text("Opslaan & inklappen") }
                        if (saved) Text("✅ Opgeslagen", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ── Suggestiekaart ────────────────────────────────────────────────────────────

@Composable
private fun SuggestionCard(suggestion: AiParamSuggestion) {
    var status by remember(suggestion.param, suggestion.proposedValue) {
        mutableStateOf(AiSuggestionStatus.PENDING)
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

            when (status) {
                AiSuggestionStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val r = FclAiAdvisorApplier.approve(suggestion)
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
