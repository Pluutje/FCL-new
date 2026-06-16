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
            val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000L
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
