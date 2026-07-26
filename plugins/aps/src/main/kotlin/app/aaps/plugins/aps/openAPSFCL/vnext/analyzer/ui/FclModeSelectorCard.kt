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
 *
 * [label] (26/07/2026, Ecko) — optioneel voorvoegsel, bijv. "Dag" / "Nacht",
 * nu de Learner een dag- en een nacht-as apart bijhoudt. Leeg (default) geeft
 * exact het oude, ongeprefixte gedrag voor bestaande aanroepplekken.
 */
@Composable
fun FclModeStatusLine(mode: FclSystemMode, label: String = "") {
    val (text, color) = when (mode) {
        FclSystemMode.OFF    -> "Uitgeschakeld" to MaterialTheme.colorScheme.onSurfaceVariant
        FclSystemMode.AUTO   -> "Ingeschakeld — Automatisch" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
        FclSystemMode.MANUAL -> "Ingeschakeld — Handmatig" to androidx.compose.ui.graphics.Color(0xFFE08A2E)
    }
    val prefixed = if (label.isNotEmpty()) "$label: $text" else text
    Text(prefixed, style = MaterialTheme.typography.labelSmall, color = color)
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

/**
 * FclDayNightModeSelectorCard (26/07/2026, Ecko) — variant van
 * FclModeSelectorCard voor assen die zelf weer in Dag/Nacht uiteenvallen
 * (Learner, AI-adviseur). Aanleiding: met 2× FclModeSelectorCard per as
 * (Dag apart, Nacht apart) stonden er 4 losse aan/uit-schakelaars in
 * Instellingen — Ecko's terugkoppeling: "dat moet maar 1 keer voor de
 * analyzer en 1 keer voor de AI zijn. Als ik hem uit wil zetten dan geldt
 * dat zowel voor dag als nacht en als ik dag wel automatisch zou willen
 * maar nacht niet dan zet ik nacht wel op handmatig."
 *
 * Ontwerp: ÉÉN aan/uit-schakelaar (aan zodra minstens één van beide assen
 * niet-OFF is), die bij omzetten BEIDE assen tegelijk raakt. Zodra aan,
 * verschijnen twee onafhankelijke Automatisch/Handmatig-keuzes (Dag/Nacht) —
 * die twee kunnen best van elkaar verschillen, zoals in het voorbeeld
 * hierboven. Uitzetten zet ALTIJD beide assen op OFF; aanzetten vanuit OFF
 * zet een as die op OFF stond terug op MANUAL (nooit stilzwijgend AUTO,
 * zelfde veiligheidsprincipe als FclModeSelectorCard) — een as die al een
 * niet-OFF stand had (bijv. alleen Nacht stond uit, Dag stond op AUTO)
 * behoudt die stand gewoon.
 */
@Composable
fun FclDayNightModeSelectorCard(
    title: String,
    initialDayMode: FclSystemMode,
    initialNightMode: FclSystemMode,
    dayAutoDescription: String,
    dayManualDescription: String,
    nightAutoDescription: String,
    nightManualDescription: String,
    onDayModeChange: (FclSystemMode) -> Unit,
    onNightModeChange: (FclSystemMode) -> Unit
) {
    var dayMode by remember { mutableStateOf(initialDayMode) }
    var nightMode by remember { mutableStateOf(initialNightMode) }
    val isOn = dayMode != FclSystemMode.OFF || nightMode != FclSystemMode.OFF

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
                        if (isOn) "Aan — dag en nacht apart in te stellen hieronder" else "Uitgeschakeld — doet niets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isOn,
                    onCheckedChange = { checked ->
                        if (checked) {
                            if (dayMode == FclSystemMode.OFF) {
                                dayMode = FclSystemMode.MANUAL
                                onDayModeChange(dayMode)
                            }
                            if (nightMode == FclSystemMode.OFF) {
                                nightMode = FclSystemMode.MANUAL
                                onNightModeChange(nightMode)
                            }
                        } else {
                            dayMode = FclSystemMode.OFF
                            nightMode = FclSystemMode.OFF
                            onDayModeChange(FclSystemMode.OFF)
                            onNightModeChange(FclSystemMode.OFF)
                        }
                    }
                )
            }

            if (isOn) {
                FclDayNightSubAxisRow(
                    label = "Dag",
                    mode = dayMode,
                    autoDescription = dayAutoDescription,
                    manualDescription = dayManualDescription,
                    onModeChange = { dayMode = it; onDayModeChange(it) }
                )
                FclDayNightSubAxisRow(
                    label = "Nacht",
                    mode = nightMode,
                    autoDescription = nightAutoDescription,
                    manualDescription = nightManualDescription,
                    onModeChange = { nightMode = it; onNightModeChange(it) }
                )
            }
        }
    }
}

/** Eén Dag- of Nacht-subrij binnen FclDayNightModeSelectorCard: label,
 *  bijbehorende toelichting, en de Automatisch/Handmatig-radiobuttons.
 *  Nooit OFF hier — de OFF-stand wordt uitsluitend door de gedeelde
 *  schakelaar in FclDayNightModeSelectorCard bepaald. */
@Composable
private fun FclDayNightSubAxisRow(
    label: String,
    mode: FclSystemMode,
    autoDescription: String,
    manualDescription: String,
    onModeChange: (FclSystemMode) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        Text(
            if (mode == FclSystemMode.AUTO) autoDescription else manualDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = mode == FclSystemMode.AUTO,
                onClick = { onModeChange(FclSystemMode.AUTO) }
            )
            Text(
                "Automatisch",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 16.dp)
            )
            RadioButton(
                selected = mode == FclSystemMode.MANUAL,
                onClick = { onModeChange(FclSystemMode.MANUAL) }
            )
            Text("Handmatig", style = MaterialTheme.typography.bodySmall)
        }
    }
}
