package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode

/**
 * FclModeStatusLine (10/07/2026, Ecko) — klein, ingetogen statusregeltje voor
 * op de Learner-/AI-tabbladen zelf (niet in Settings, waar de bediening
 * staat) — puur ter info, geen interactie. Kleurnuance per stand: grijs
 * (uit), groen (automatisch), amber (handmatig — er staat mogelijk iets op
 * je te wachten).
 */
@Composable
fun FclModeStatusLine(mode: FclSystemMode) {
    val (text, color) = when (mode) {
        FclSystemMode.OFF    -> "Uitgeschakeld" to MaterialTheme.colorScheme.onSurfaceVariant
        FclSystemMode.AUTO   -> "Ingeschakeld — Automatisch" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
        FclSystemMode.MANUAL -> "Ingeschakeld — Handmatig" to androidx.compose.ui.graphics.Color(0xFFE08A2E)
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * FclModeSelectorCard (10/07/2026, Ecko) — herbruikbare aan/uit +
 * automatisch/handmatig-schakelaar. Gebruikt door zowel de Learner
 * (DFControlTab) als de AI-adviseur (FclAiAdvisorScreen) — zelfde concept,
 * zelfde bediening, zie FclSystemMode.kt voor de volledige toelichting.
 *
 * Bewust: de automatisch/handmatig-subkeuze is UITSLUITEND zichtbaar als de
 * schakelaar aanstaat — bij uit is er niets te kiezen, conform "doet dan
 * echt niks meer, en toont de submodus dan ook niet".
 *
 * Zet je de schakelaar AAN vanuit UIT, dan is de standaardkeuze MANUAL —
 * de veiligste optie (eerst bekijken/goedkeuren voordat er iets verandert),
 * nooit stilzwijgend meteen AUTO.
 */
@Composable
fun FclModeSelectorCard(
    title: String,
    initialMode: FclSystemMode,
    autoDescription: String,
    manualDescription: String,
    onModeChange: (FclSystemMode) -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        when (mode) {
                            FclSystemMode.OFF    -> "Uitgeschakeld — doet niets"
                            FclSystemMode.AUTO   -> autoDescription
                            FclSystemMode.MANUAL -> manualDescription
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mode != FclSystemMode.OFF,
                    onCheckedChange = { checked ->
                        mode = if (checked) FclSystemMode.MANUAL else FclSystemMode.OFF
                        onModeChange(mode)
                    }
                )
            }

            if (mode != FclSystemMode.OFF) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == FclSystemMode.AUTO,
                        onClick = { mode = FclSystemMode.AUTO; onModeChange(mode) }
                    )
                    Text(
                        "Automatisch",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    RadioButton(
                        selected = mode == FclSystemMode.MANUAL,
                        onClick = { mode = FclSystemMode.MANUAL; onModeChange(mode) }
                    )
                    Text("Handmatig", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
