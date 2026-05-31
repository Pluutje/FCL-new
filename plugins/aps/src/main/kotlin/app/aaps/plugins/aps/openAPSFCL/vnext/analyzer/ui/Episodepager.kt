package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.*
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

@Composable
fun EpisodePager(
    allRows: List<LogRow>,
    episodes: List<Episode>,
    episodeEntities: List<EpisodeEntity>,
    onPageChanged: (Int) -> Unit,
    onRescueUserConfirmed: (startTs: String, confirmed: String) -> Unit
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

    val pagerState = rememberPagerState(
        initialPage = episodes.size - 1,
        pageCount = { episodes.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) { page ->

        val episode = episodes[page]
        val entity = episodeEntities.find { it.startTs == episode.start.toString() }

        val windowRows = EpisodeWindowBuilder.sliceWithContext(
            allRows = allRows,
            episodeStart = episode.start,
            episodeEnd = episode.end
        )

        Column(modifier = Modifier.fillMaxWidth()) {

            // ── Episode header ────────────────────────────────────────────
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Episode ${episode.id}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!episode.isComplete) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text(
                            s.live,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                // Maaltijdtype badge
                entity?.let { e ->
                    val (emoji, kleur) = when (e.mealType) {
                        "SNEL"  -> "⚡ Snel"  to Color(0xFFFF9800)
                        "TRAAG" -> "🐢 Traag" to Color(0xFF4CAF50)
                        else    -> "🔀 Gemengd" to Color(0xFF9E9E9E)
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = kleur.copy(alpha = 0.15f)
                    ) {
                        Text(
                            emoji,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = kleur,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ── Rescue sectie ─────────────────────────────────────────────
            if (entity != null) {
                RescueSectie(
                    entity = entity,
                    onUserConfirmed = { confirmed ->
                        onRescueUserConfirmed(entity.startTs, confirmed)
                    }
                )
            }

            // ── Grafiek ───────────────────────────────────────────────────
            EpisodeChart(
                rows = windowRows,
                episodeStart = episode.start,
                episodeEnd = episode.end
            )
        }
    }
}

// ── RescueSectie ─────────────────────────────────────────────────────────────

@Composable
private fun RescueSectie(
    entity: EpisodeEntity,
    onUserConfirmed: (String) -> Unit
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val autoState     = entity.rescueAutoState
    val autoConf      = entity.rescueAutoConfidence
    val userConfirmed = entity.rescueUserConfirmed

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ── Titel + auto-detectie badge op één rij ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🧃 Reddingskoolhydraten",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${s.auto}:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AutoDetectieBadge(autoState, autoConf)
                }
            }

            // ── Handmatige bevestiging — één regel ───────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    s.daadwerkelijkGenomen,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                KeuzeVakje(
                    label = "Ja",
                    geselecteerd = userConfirmed == "YES",
                    accentKleur = Color(0xFFFF9800),
                    onClick = { onUserConfirmed(if (userConfirmed == "YES") "UNSET" else "YES") }
                )
                KeuzeVakje(
                    label = "Nee",
                    geselecteerd = userConfirmed == "NO",
                    accentKleur = MaterialTheme.colorScheme.primary,
                    onClick = { onUserConfirmed(if (userConfirmed == "NO") "UNSET" else "NO") }
                )
            }
        }
    }
}

// ── AutoDetectieBadge ─────────────────────────────────────────────────────────

@Composable
private fun AutoDetectieBadge(autoState: String, autoConf: Double) {
    val (tekst, kleur) = when (autoState) {
        "CONFIRMED" -> "Ja (${(autoConf * 100).toInt()}%)" to Color(0xFF00C853)
        "ARMED"     -> "Mogelijk"                          to Color(0xFFFFD600)
        else        -> "Nee"                               to Color(0xFF757575)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = kleur.copy(alpha = 0.15f)
    ) {
        Text(
            tekst,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = kleur,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── KeuzeVakje ───────────────────────────────────────────────────────────────

@Composable
private fun KeuzeVakje(
    label: String,
    geselecteerd: Boolean,
    accentKleur: Color = Color(0xFF757575),
    onClick: () -> Unit
) {
    val containerKleur = if (geselecteerd) accentKleur.copy(alpha = 0.2f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val tekstKleur     = if (geselecteerd) accentKleur
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = containerKleur
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tekstKleur,
            fontWeight = if (geselecteerd) FontWeight.Bold else FontWeight.Normal
        )
    }
}