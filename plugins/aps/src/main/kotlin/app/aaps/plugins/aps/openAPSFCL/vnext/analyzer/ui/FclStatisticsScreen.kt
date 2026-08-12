package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.toLogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Statistics-tabblad: TIR-gegevens + CGP/PGR scorekaart.
 */
@Composable
fun FclStatisticsScreen() {
    val context = LocalContext.current
    val db = FCLAnalyzerDatabase.getInstance(context)

    var allRows by remember { mutableStateOf<List<LogRow>>(emptyList()) }
    var lastSyncTs by remember { mutableStateOf<Instant?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 12/08/2026 (Ecko): was 14 dagen — TimeInRangeCard's "7/14/30 dagen"-
            // knop (zie Timeinrangecard.kt, dayWindow) kan tot 30 dagen opvragen,
            // maar deze query haalt maar één keer data op (LaunchedEffect(Unit),
            // draait niet opnieuw als dayWindow wijzigt) en met een vaste grens van
            // 14 dagen. Gevolg: bij "30 dagen" waren de oudste ~16 dagen altijd leeg,
            // ongeacht hoeveel historie er in de database zit. buildDailyRangeStats()/
            // computeAverageBg() in Timeinrangecard.kt filteren zelf al correct op
            // dayWindow (rijen buiten het venster worden genegeerd) — dus meer rijen
            // ophalen dan een kleiner venster nodig heeft is veilig, geen dubbeltelling.
            // 30 dagen = het grootste venster dat de kaart kan tonen; als dat ooit
            // wijzigt, hier meenemen.
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L
            val entities = db.cycleLogDao().getSince(cutoff)
            allRows = entities.map { it.toLogRow() }
            lastSyncTs = entities.lastOrNull()
                ?.timestampMs
                ?.let { Instant.ofEpochMilli(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimeInRangeCard(
            rows       = allRows,
            lastSyncTs = lastSyncTs
        )

        CgpScoreKaart(context = context)
    }
}
