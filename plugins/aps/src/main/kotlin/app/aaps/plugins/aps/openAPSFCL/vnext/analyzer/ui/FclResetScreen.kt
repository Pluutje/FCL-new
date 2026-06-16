package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner

/**
 * Reset-scherm: afzonderlijke reset per leer-domein, plus een "Reset alles"-knop.
 * Elke reset vraagt bevestiging via een AlertDialog.
 */
@Composable
fun FclResetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var bevestigReset by remember { mutableStateOf<ResetActie?>(null) }

    // Bevestigingsdialog
    if (bevestigReset != null) {
        val actie = bevestigReset!!
        AlertDialog(
            onDismissRequest = { bevestigReset = null },
            title = { Text(actie.titel, fontWeight = FontWeight.SemiBold) },
            text  = { Text(actie.bevestigingsTekst) },
            confirmButton = {
                Button(
                    onClick = {
                        actie.uitvoeren(context)
                        bevestigReset = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (actie.isAlles)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                ) { Text("Ja, resetten") }
            },
            dismissButton = {
                OutlinedButton(onClick = { bevestigReset = null }) { Text("Annuleren") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← Terug") }
            Text("Reset leerdata",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.width(80.dp))
        }

        Text(
            "Kies welk onderdeel je wilt resetten. " +
            "Elke reset is onomkeerbaar — de automaat begint dan " +
            "opnieuw te leren voor dat onderdeel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // Individuele resetknoppen
        val acties = listOf(
            ResetActie(
                titel = "Sterkte & Timing (D/F)",
                omschrijving = "Reset de geleerde dosissterkte (S) en timing (T) " +
                    "terug naar de basiswaarden. Aanpassingshistorie wordt gewist.",
                bevestigingsTekst = "Sterkte en Timing worden gereset naar de " +
                    "standaard basiswaarden (S≈92%, T≈117%). Doorgaan?",
                uitvoeren = { DFLearner.resetTypeData(it) }
            ),
            ResetActie(
                titel = "Vasthoudendheid (V)",
                omschrijving = "Reset de geleerde vasthoudendheid-offset terug naar nul.",
                bevestigingsTekst = "Vasthoudendheid wordt gereset naar de standaardwaarde. Doorgaan?",
                uitvoeren = { DFLearner.resetV(it) }
            ),
            ResetActie(
                titel = "Frontload-timing (WMD, WFF, EB)",
                omschrijving = "Reset de geleerde frontload-trigger, commit-grootte " +
                    "en early boost terug naar de fabriekswaarden.",
                bevestigingsTekst = "Frontload-leerparameters (REF_WMD, REF_WFF, REF_EB) " +
                    "worden gereset naar de standaardwaarden. Doorgaan?",
                uitvoeren = { DFLearner.resetFrontload(it) }
            ),
            ResetActie(
                titel = "Early Boost-learner",
                omschrijving = "Reset de early boost factor en watching-fractie.",
                bevestigingsTekst = "De Early Boost-learner wordt volledig gereset. Doorgaan?",
                uitvoeren = { DFLearner.resetEarlyBoost(it) }
            ),
            ResetActie(
                titel = "CGP/PGR-history",
                omschrijving = "Wis de opgeslagen dagpunten van de CGP-scorekaart. " +
                    "De backfill herberekent automatisch bij de volgende cyclus.",
                bevestigingsTekst = "De CGP-trendlijn wordt gewist en bij de volgende " +
                    "cyclus opnieuw berekend. Doorgaan?",
                uitvoeren = { CgpHistory.clearHistory(it) }
            ),
        )

        acties.forEach { actie ->
            ResetKaart(actie = actie, onClick = { bevestigReset = actie })
        }

        HorizontalDivider()

        // Reset alles
        val resetAlles = ResetActie(
            titel = "⚠️ Reset alles",
            omschrijving = "Reset alle leerassen (D/F/V/frontload/earlyboost) " +
                "én de CGP-history. De automaat begint volledig opnieuw.",
            bevestigingsTekst = "Alle leerdata wordt gewist — D/F/V, frontload, " +
                "early boost, VLearner, FrontloadLearner én de CGP-history. " +
                "Dit kan niet ongedaan worden gemaakt. Zeker weten?",
            uitvoeren = {
                DFLearner.resetAll(it)
                CgpHistory.clearHistory(it)
            },
            isAlles = true
        )
        ResetKaart(actie = resetAlles, onClick = { bevestigReset = resetAlles }, isGefocust = true)
    }
}

@Composable
private fun ResetKaart(
    actie: ResetActie,
    onClick: () -> Unit,
    isGefocust: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGefocust)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(actie.titel,
                     style = MaterialTheme.typography.labelLarge,
                     fontWeight = FontWeight.SemiBold)
                Text(actie.omschrijving,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onClick,
                colors = if (isGefocust) ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.outlinedButtonColors()
            ) { Text("Reset") }
        }
    }
}

private data class ResetActie(
    val titel: String,
    val omschrijving: String,
    val bevestigingsTekst: String,
    val uitvoeren: (android.content.Context) -> Unit,
    val isAlles: Boolean = false
)
