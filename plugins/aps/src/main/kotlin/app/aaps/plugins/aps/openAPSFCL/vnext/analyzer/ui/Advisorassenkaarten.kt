package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FrontloadLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings
import app.aaps.plugins.aps.openAPSFCL.vnext.persist.VLearner
import java.time.Instant

/**
 * De vier Advisor-assenkaarten: Sterkte, Timing, Vasthoudendheid en
 * Frontload-timing. Elke kaart toont de huidige waarde, een relatieve
 * toelichting t.o.v. de standaardwaarde (100%), een 14-dagen sparkline
 * van het verloop, en de toelichting bij de laatst gevonden diagnose
 * (via FclStrings.diagnoseTekst / frontloadTekst / vSignaalTekst).
 *
 * Bewuste keuze: alle vier kaarten gebruiken dezelfde tijdas (14 dagen),
 * zodat de relatieve leersnelheid tussen de assen direct vergelijkbaar is.
 * De exacte historische getallen zijn minder relevant dan het zichtbare
 * verloop — vandaar geen y-as-labels met veel decimalen, wel een duidelijk
 * min/max-bereik.
 */

private const val HISTORY_DAYS = 14

@Composable
fun AdvisorAssenKaarten(
    d: Double,
    f: Double,
    nachtFactor: Int,
    vExtra: Double,
    aggressiveness: Int
) {
    val s = FclStrings.get(LocalContext.current)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SterkteKaart(s, d, f, nachtFactor, vExtra, aggressiveness)
        TimingKaart(s, d, f, nachtFactor, vExtra, aggressiveness)
        VasthoudendheidKaart(s, d, f, nachtFactor, vExtra, aggressiveness)
        FrontloadTimingKaart(s)
    }
}

// ── Sterkte ──────────────────────────────────────────────────────────────

@Composable
private fun SterkteKaart(
    s: FclStrings, d: Double, f: Double, nachtFactor: Int, vExtra: Double, agg: Int
) {
    val context = LocalContext.current
    val history = DFLearner.getHistorySince(context, HISTORY_DAYS)

    val huidig = DFMapping.toStvMap(d, f, nachtFactor, vExtra, aggLevel = agg)["sterkte"] ?: 100

    // Tijdreeks: voor elk historiepunt het sterkte-percentage bij die D/F-waarde,
    // met de huidige nachtFactor/vExtra/agg als constante (we tonen het verloop
    // van D, niet van de andere factoren).
    val punten: List<Pair<Instant, Double>> = history.mapNotNull { step ->
        runCatching {
            val ts = Instant.parse(step.tsUtc)
            val pct = DFMapping.toStvMap(step.newD, step.newF, nachtFactor, vExtra, aggLevel = agg)["sterkte"]!!
            ts to pct.toDouble()
        }.getOrNull()
    } + listOf(Instant.now() to huidig.toDouble())

    val toelichting = s.relatieveToelichting(huidig - 100)
    val laatsteDiagnose = history.lastOrNull()?.diagnose?.takeIf { it.isNotBlank() }
        ?.let { s.diagnoseTekst(it) }

    AsKaart(
        icoon = "💊",
        titel = s.sterkteTitel,
        waarde = "$huidig%",
        toelichting = toelichting,
        punten = punten,
        eenheid = "%",
        diagnoseRegel = laatsteDiagnose,
        lastAanpassingTs = history.lastOrNull()?.tsUtc
    )
}

// ── Timing ───────────────────────────────────────────────────────────────

@Composable
private fun TimingKaart(
    s: FclStrings, d: Double, f: Double, nachtFactor: Int, vExtra: Double, agg: Int
) {
    val context = LocalContext.current
    val history = DFLearner.getHistorySince(context, HISTORY_DAYS)

    val huidig = DFMapping.toStvMap(d, f, nachtFactor, vExtra, aggLevel = agg)["timing"] ?: 100

    val punten: List<Pair<Instant, Double>> = history.mapNotNull { step ->
        runCatching {
            val ts = Instant.parse(step.tsUtc)
            val pct = DFMapping.toStvMap(step.newD, step.newF, nachtFactor, vExtra, aggLevel = agg)["timing"]!!
            ts to pct.toDouble()
        }.getOrNull()
    } + listOf(Instant.now() to huidig.toDouble())

    val toelichting = s.relatieveToelichting(huidig - 100)

    // Alleen tonen als de laatste stap ook daadwerkelijk F heeft aangepast.
    // De diagnose is een episode-diagnose die primair D beschrijft —
    // zonder F-aanpassing is die tekst misleidend onder de Timing-kaart.
    val laatsteStapMetFwijziging = history.lastOrNull { step ->
        kotlin.math.abs(step.newF - step.oldF) > 0.001
    }
    val diagnoseRegel = laatsteStapMetFwijziging?.diagnose?.takeIf { it.isNotBlank() }
        ?.let { s.timingDiagnoseTekst(it) }
    val tsVoorDiagnose = laatsteStapMetFwijziging?.tsUtc

    AsKaart(
        icoon = "⏱",
        titel = s.timingTitel,
        waarde = "$huidig%",
        toelichting = toelichting,
        punten = punten,
        eenheid = "%",
        diagnoseRegel = diagnoseRegel,
        lastAanpassingTs = tsVoorDiagnose ?: history.lastOrNull()?.tsUtc
    )
}

// ── Vasthoudendheid ──────────────────────────────────────────────────────

@Composable
private fun VasthoudendheidKaart(
    s: FclStrings, d: Double, f: Double, nachtFactor: Int, vExtra: Double, agg: Int
) {
    val context = LocalContext.current
    val history = VLearner.getHistory(context)
    val cutoff = Instant.now().minusSeconds(HISTORY_DAYS.toLong() * 24 * 3600)
    val recent = history.filter { p ->
        runCatching { Instant.parse(p.tsUtc).isAfter(cutoff) }.getOrDefault(false)
    }

    val huidig = DFMapping.toStvMap(d, f, nachtFactor, vExtra, aggLevel = agg)["volhoudendheid"] ?: 100

    // Voor elk historiepunt: het volhoudendheid-percentage bij die vExtra-waarde,
    // met de huidige D/F/nachtFactor/agg als constante.
    val punten: List<Pair<Instant, Double>> = recent.mapNotNull { p ->
        runCatching {
            val ts = Instant.parse(p.tsUtc)
            val pct = DFMapping.toStvMap(d, f, nachtFactor, p.vExtra, aggLevel = agg)["volhoudendheid"]!!
            ts to pct.toDouble()
        }.getOrNull()
    } + listOf(Instant.now() to huidig.toDouble())

    val toelichting = s.relatieveToelichting(huidig - 100)
    val laatsteSignaal = recent.lastOrNull { it.signal != "NONE" }?.signal
        ?: recent.lastOrNull()?.signal
    val diagnoseRegel = laatsteSignaal?.let { s.vSignaalTekst(it) }

    AsKaart(
        icoon = "🔁",
        titel = s.vasthoudendheidTitel,
        waarde = "$huidig%",
        toelichting = toelichting,
        punten = punten,
        eenheid = "%",
        diagnoseRegel = diagnoseRegel,
        lastAanpassingTs = recent.lastOrNull { it.signal != "NONE" }?.tsUtc
    )
}

// ── Frontload-timing ───────────────────────────────────────────────────────

@Composable
private fun FrontloadTimingKaart(s: FclStrings) {
    val context = LocalContext.current
    val history = FrontloadLearner.getHistorySince(context, HISTORY_DAYS)

    val huidigWmd = DFLearner.getRefWmd(context)

    // Lagere WMD = vroeger triggeren = "meer naar voren". We tonen de
    // ruwe WMD-waarde (mmol boven target nodig om te triggeren) met
    // aslabels die de richting verduidelijken: lager = naar voren.
    val punten: List<Pair<Instant, Double>> = history.mapNotNull { step ->
        runCatching {
            val ts = Instant.parse(step.tsUtc)
            ts to step.nieuweWmd
        }.getOrNull()
    } + listOf(Instant.now() to huidigWmd)

    val laatsteStap = history.lastOrNull()
    val statusTekst = s.frontloadStatusTekst(frontloadStatusKey(huidigWmd))
    val diagnoseRegel = laatsteStap?.let {
        s.frontloadTekst(it.richting, it.gemiddeldeMarge)
    }

    AsKaart(
        icoon = "🚀",
        titel = s.frontloadTimingTitel,
        waarde = statusTekst,
        toelichting = null,
        punten = punten,
        eenheid = "mmol",
        diagnoseRegel = diagnoseRegel,
        lastAanpassingTs = laatsteStap?.tsUtc,
        omgekeerdeAs = true
    )
}

private fun frontloadStatusKey(wmd: Double): String {
    val default = DFMapping.REF_WMD_DEFAULT
    return when {
        wmd <= default - 0.20 -> "STERK_VOOR"
        wmd <= default - 0.05 -> "LICHT_VOOR"
        wmd >= default + 0.20 -> "STERK_TERUG"
        wmd >= default + 0.05 -> "LICHT_TERUG"
        else                  -> "STANDAARD"
    }
}

private fun fmtTs(tsUtc: String): String = try {
    val instant = java.time.Instant.parse(tsUtc)
    java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
} catch (_: Exception) { tsUtc.take(10) }

// ── Gedeelde toelichting ────────────────────────────────────────────────

// ── (relatieveToelichting verplaatst naar FclStrings.relatieveToelichting) ──

// ── Gedeelde kaart + sparkline ──────────────────────────────────────────

@Composable
private fun AsKaart(
    icoon: String,
    titel: String,
    waarde: String,
    toelichting: String?,
    punten: List<Pair<Instant, Double>>,
    eenheid: String,
    diagnoseRegel: String?,
    lastAanpassingTs: String? = null,
    omgekeerdeAs: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    "$icoon $titel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    waarde,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (toelichting != null) {
                Text(
                    toelichting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Sparkline(
                punten = punten,
                omgekeerd = omgekeerdeAs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )

            if (diagnoseRegel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        diagnoseRegel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f)
                    )
                    if (lastAanpassingTs != null) {
                        Text(
                            fmtTs(lastAanpassingTs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Eenvoudige lijngrafiek over [punten] (tijdstip → waarde), x-as = laatste
 * HISTORY_DAYS dagen, y-as = min..max van de waarden (met kleine marge).
 *
 * [omgekeerd]: als true wordt de y-as verticaal gespiegeld getekend —
 * gebruikt voor de frontload-as waar een lagere waarde ("naar voren")
 * visueel bovenaan moet staan, consistent met de andere kaarten waar
 * "hoger" altijd boven staat.
 *
 * Geen aslabels met exacte getallen: het gaat om het zichtbare verloop,
 * niet om de precieze historische waarden (zie kaart-toelichting daarvoor).
 */
@Composable
private fun Sparkline(
    punten: List<Pair<Instant, Double>>,
    omgekeerd: Boolean,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.10f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    if (punten.size < 2) {
        Canvas(modifier = modifier) {
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1f
            )
        }
        return
    }

    val nu = Instant.now()
    val vanaf = nu.minusSeconds(HISTORY_DAYS.toLong() * 24 * 3600)
    val sorted = punten.sortedBy { it.first }

    val xMin = vanaf.epochSecond.toDouble()
    val xMax = nu.epochSecond.toDouble().coerceAtLeast(xMin + 1.0)

    val yValues = sorted.map { it.second }
    val rawMin = yValues.min()
    val rawMax = yValues.max()
    val pad = ((rawMax - rawMin) * 0.15).coerceAtLeast(0.5)
    val yMin = rawMin - pad
    val yMax = (rawMax + pad).coerceAtLeast(yMin + 0.001)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun xOf(t: Instant): Float {
            val frac = ((t.epochSecond.toDouble() - xMin) / (xMax - xMin)).coerceIn(0.0, 1.0)
            return (frac * w).toFloat()
        }
        fun yOf(v: Double): Float {
            val frac = ((v - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
            val visualFrac = if (omgekeerd) frac else 1.0 - frac
            return (visualFrac * h).toFloat()
        }

        // Horizontale referentielijn op 100% (of, voor de frontload-as,
        // op de standaard-WMD) als die binnen het bereik valt.
        val referentie = if (omgekeerd) DFMapping.REF_WMD_DEFAULT else 100.0
        if (referentie in yMin..yMax) {
            drawLine(
                color = gridColor,
                start = Offset(0f, yOf(referentie)),
                end = Offset(w, yOf(referentie)),
                strokeWidth = 1f
            )
        }

        // Lijn + vlak onder de lijn
        val linePath = Path()
        val fillPath = Path()
        sorted.forEachIndexed { i, (t, v) ->
            val x = xOf(t)
            val y = yOf(v)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, if (omgekeerd) 0f else h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val (lastX, _) = sorted.last().let { xOf(it.first) to it.second }
        fillPath.lineTo(lastX, if (omgekeerd) 0f else h)
        fillPath.close()

        drawPath(fillPath, color = fillColor)
        drawPath(linePath, color = lineColor, style = Stroke(width = 2f))

        // Eindpunt-marker
        val (laatsteT, laatsteV) = sorted.last()
        drawCircle(
            color = lineColor,
            radius = 3f,
            center = Offset(xOf(laatsteT), yOf(laatsteV))
        )
    }
}
