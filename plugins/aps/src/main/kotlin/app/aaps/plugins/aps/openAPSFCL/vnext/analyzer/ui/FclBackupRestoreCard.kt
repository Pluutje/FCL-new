package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

// ── FclBackupRestoreCard (26/08/2026) ────────────────────────────────────
// Aanleiding: de geleerde D/F/V-staat (sterkte/timing/volhoudendheid) bleek
// bij de overstap naar de nieuwe telefoon terug op de standaardwaarden te
// staan — een dagenlang merkbaar "minder responsief" algoritme, tot de
// learner het vanzelf had teruggeleerd (zie de diagnose van 25-26/08/2026).
// FclLearnerBackup schrijft sindsdien elke dag automatisch een
// datum-gestempeld JSON-bestand naar Documents/AAPS/FCL_BACKUP/. Dit
// kaartje (uitsluitend in Expert-modus, net als de andere technische
// tabbladen) laat een datum kiezen en die terugzetten.
//
// Bewust een MERGE bij herstellen (zie FclLearnerBackup.restoreBackup):
// een oudere backup kan nooit per ongeluk een sindsdien toegevoegde
// instelling wissen. Toch een expliciete bevestigingsdialoog, want dit
// raakt wel degelijk dosis-bepalende staat (sterkte/timing/volhoudendheid
// e.d.) — zelfde voorzichtigheidsprincipe als bij "Voorstel toepassen op de
// pomp?" in IsfAutoAdjustCard.
@Composable
fun FclBackupRestoreCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backups by remember { mutableStateOf(FclLearnerBackup.listAvailableBackups(context)) }
    var selectedFile by remember { mutableStateOf(backups.firstOrNull()?.file) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Expliciete Locale.forLanguageTag("nl") i.p.v. device-default: zelfde
    // categorie fout als de kommas-in-plaats-van-punten in de csv-export
    // (25-26/08/2026-diagnose) voorkomen één keer voor de deur.
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.forLanguageTag("nl")) }

    fun refresh() {
        backups = FclLearnerBackup.listAvailableBackups(context)
        if (selectedFile == null || backups.none { it.file == selectedFile }) {
            selectedFile = backups.firstOrNull()?.file
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Backup & herstel", style = MaterialTheme.typography.titleMedium)

            Text(
                "Elke dag wordt automatisch een backup gemaakt van de geleerde staat " +
                    "(sterkte/timing/volhoudendheid, ISF- en nacht-basaal-auto-adjust, " +
                    "AI-parameter-overrides). Bij een nieuwe telefoon of herinstallatie " +
                    "gaat deze staat anders verloren zonder dat AAPS' eigen " +
                    "instellingen-export dit meeneemt — kies hieronder een datum om " +
                    "terug te zetten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (backups.isEmpty()) {
                Text(
                    "Nog geen backups gevonden. De eerste wordt binnen een uur automatisch weggeschreven.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                ) {
                    items(backups) { backup ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedFile == backup.file,
                                onClick = { selectedFile = backup.file }
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    backup.date.format(dateFmt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedFile == backup.file) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (backup.writtenAt != null && backup.writtenAt.length >= 16) {
                                    Text(
                                        "weggeschreven om ${backup.writtenAt.substring(11, 16)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Divider()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        isBusy = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { FclLearnerBackup.writeBackupNow(context) }
                            }
                            refresh()
                            isBusy = false
                            statusMessage = "Backup van vandaag is (opnieuw) weggeschreven."
                        }
                    },
                    enabled = !isBusy
                ) { Text("Nu back-uppen") }

                Button(
                    onClick = { showConfirmDialog = true },
                    enabled = !isBusy && selectedFile != null
                ) { Text(if (isBusy) "Bezig…" else "Herstellen") }
            }

            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showConfirmDialog && selectedFile != null) {
        val fileToRestore = selectedFile!!
        val backupInfo = backups.firstOrNull { it.file == fileToRestore }
        AlertDialog(
            onDismissRequest = { if (!isBusy) showConfirmDialog = false },
            title = { Text("Backup terugzetten?") },
            text = {
                Text(
                    "De geleerde staat van ${backupInfo?.date?.format(dateFmt) ?: "de gekozen datum"} " +
                        "wordt teruggezet, inclusief sterkte/timing/volhoudendheid en de andere " +
                        "auto-adjust-instellingen. Dit raakt direct de doseeragressiviteit. " +
                        "Bestaande instellingen die niet in deze backup voorkomen blijven ongewijzigd " +
                        "(dit is een samenvoeging, geen volledige wissing)."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isBusy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FclLearnerBackup.restoreBackup(context, fileToRestore)
                            }
                            isBusy = false
                            showConfirmDialog = false
                            statusMessage = when (result) {
                                is FclLearnerBackup.RestoreResult.Success ->
                                    "Hersteld: ${result.storesRestored} instellingen-groepen, ${result.keysRestored} waarden. " +
                                        "Wordt vanaf de volgende cyclus gebruikt."
                                is FclLearnerBackup.RestoreResult.Failure ->
                                    "Herstellen mislukt: ${result.reason}"
                            }
                        }
                    },
                    enabled = !isBusy
                ) { Text(if (isBusy) "Bezig…" else "Bevestigen") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }, enabled = !isBusy) { Text("Annuleren") }
            }
        )
    }
}
