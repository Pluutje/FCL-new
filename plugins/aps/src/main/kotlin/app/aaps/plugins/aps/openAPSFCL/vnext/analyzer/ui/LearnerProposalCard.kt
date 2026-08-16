package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerApplier
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerPendingProposal

/**
 * LearnerProposalCard (10/07/2026) — toont het openstaande MANUAL-
 * voorstel van de Learner, met dezelfde Goedkeuren/Afwijzen-bediening als
 * SuggestionCard.kt bij de AI-adviseur. Anders dan bij de AI (los per
 * parameter) is dit hier altijd ÉÉN samenhangend D/F/vExtra/ref-voorstel —
 * dat is nu eenmaal hoe DFMapping werkt (zie de kdoc daar).
 *
 * Toont niets als er geen voorstel openstaat (hasPending() == false) — de
 * aanroeper hoeft zelf niet te controleren of dit zichtbaar moet zijn.
 */
@Composable
fun LearnerProposalCard(context: Context) {
    var proposal by remember { mutableStateOf(FclLearnerPendingProposal.load(context)) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val p = proposal ?: return
    if (handled) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "🧠 Learner-voorstel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            // 10/07/2026 — leesbare uitleg i.p.v. kale getallen (zie
            // FclLearnerUitleg.kt). Was voorheen "D=1.023 F=0.818 wmd=1.00
            // wff=0.90 eb=1.14 peakBias=0.60 lcd=0.45" — niet te volgen voor
            // wie de code niet kent.
            Text(
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerUitleg.waarom(p.diagnose),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerUitleg.dRichting(p.oldD, p.d),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerUitleg.fRichting(p.oldF, p.f),
                style = MaterialTheme.typography.bodyMedium
            )
            // Technische details, klein en ondergeschikt — voor wie het precies wil weten.
            Text(
                "D=${"%.3f".format(p.d)}  F=${"%.3f".format(p.f)}  vExtra=${"%.2f".format(p.vExtra)}  " +
                    "wmd=${"%.2f".format(p.refWmd)}  wff=${"%.2f".format(p.refWff)}  " +
                    "eb=${"%.2f".format(p.refEb)}  peakBias=${"%.2f".format(p.refPeakBias)}  " +
                    "lcd=${"%.2f".format(p.refLcd)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (resultMessage == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val ok = FclLearnerApplier.approve(context)
                        resultMessage = if (ok) "Toegepast — actief in de eerstvolgende FCLvNext-cyclus."
                                        else "Kon niet worden toegepast — probeer opnieuw."
                        if (ok) handled = true
                    }) { Text("Goedkeuren") }

                    OutlinedButton(onClick = {
                        FclLearnerApplier.reject(context)
                        resultMessage = "Afgewezen."
                        handled = true
                    }) { Text("Afwijzen") }
                }
            } else {
                Text(resultMessage ?: "", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
