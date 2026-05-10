package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.*
import app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * DFControlTab — primaire interface voor het zelflerend systeem.
 *
 * Toont drie parameters in FCLvNext-eenheden (S/T/V):
 *   💊 Insulinesterkte (S) — 80–125%, stap 5
 *   ⏱  Timing         (T) — 80–120%, stap 4
 *   🔁 Volhoudendheid  (V) — 70–130%, stap 5
 *
 * Alle drie worden intern vertaald via DFMapping(D, F):
 *   S = round(95 * D)
 *   T = round(106 + (F − 0.5) * 40)
 *   V = round(95 + (D − 1.0) * 50)
 *
 * De gebruiker past S, T of V aan → D en F worden terug berekend →
 * DFMapping genereert een volledige param_overrides set → één JSON naar AAPS.
 *
 * Garantie: elke "Toepassen in AAPS" schrijft altijd BEIDE blokken:
 *   stv { sterkte, timing, volhoudendheid, nacht_factor }
 *   param_overrides { alle 16 DFMapping-params }
 */
@Composable
fun DFControlTab(
    episodes: List<Episode> = emptyList(),
    metrics: List<EpisodeMetrics> = emptyList(),
    allRows: List<LogRow> = emptyList(),
    onApplyToAaps: ((ConfigOverrideWriter.ParamOverrides, Map<String, Int>) -> Boolean)? = null,
    nachtFactor: Int = 85
) {
    val context = LocalContext.current

    var d by remember { mutableStateOf(DFLearner.getD(context)) }
    var f by remember { mutableStateOf(DFLearner.getF(context)) }
    var tempo by remember { mutableStateOf(DFLearner.getTempo(context)) }
    var autoEnabled by remember { mutableStateOf(DFLearner.isAutoEnabled(context)) }
    var history by remember { mutableStateOf(DFLearner.getHistory(context)) }
    var showExpert by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<String?>(null) }
    var applyTs by remember { mutableStateOf(0L) }

    // ── Kalibratie-state ─────────────────────────────────────────────────
    var refWmd by remember { mutableStateOf(DFLearner.getRefWmd(context)) }
    var refWff by remember { mutableStateOf(DFLearner.getRefWff(context)) }
    var refEb by remember { mutableStateOf(DFLearner.getRefEb(context)) }

    // S/T/V in FCLvNext-eenheden — enige zichtbare schaal
    val stv = DFMapping.toStvMap(d, f, nachtFactor)
    val sNu = stv["sterkte"] ?: 95
    val tNu = stv["timing"] ?: 100
    val vNu = stv["volhoudendheid"] ?: 95

    // Stapsgroottes in S/T/V-eenheden
    // S-stap 5: D-stap = 5/95 ≈ 0.053
    // T-stap 4: F-stap = 4/40 = 0.10
    // V-stap 5: D-stap = 5/50 = 0.10 (maar V deelt D met S → stap via D)
    val sStap = 5
    val tStap = 4
    val vStap = 5

    // Grenzen in S/T/V-eenheden (afgeleid van D/F grenzen)
    val sMin = DFMapping.toStvMap(DFMapping.D_MIN, f, nachtFactor)["sterkte"] ?: 80
    val sMax = DFMapping.toStvMap(DFMapping.D_MAX, f, nachtFactor)["sterkte"] ?: 128
    val tMin = 80   // F=0.20 → T = 106 + (0.20-0.5)*40 = 94 afgerond naar UI-min
    val tMax = 120  // F=0.80 → T = 106 + (0.30)*40    = 118 afgerond naar UI-max
    val vMin = 70
    val vMax = 130

    // Conversiefuncties: S/T/V terug naar D/F
    fun sNaarD(s: Int): Double = (s.toDouble() / 95.0).coerceIn(DFMapping.D_MIN, DFMapping.D_MAX)
    fun tNaarF(t: Int): Double = (0.5 + (t.toDouble() - 106.0) / 40.0).coerceIn(DFMapping.F_MIN, DFMapping.F_MAX)

    // V deelt D met S: als V verandert bij gelijkblijvende F, past D aan
    fun vNaarD(v: Int): Double = (1.0 + (v.toDouble() - 95.0) / 50.0).coerceIn(DFMapping.D_MIN, DFMapping.D_MAX)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Verloopgrafiek ────────────────────────────────────────────────
        if (history.size >= 2) {
            STVVerloopGrafiek(history = history, nachtFactor = nachtFactor)
        }

        // ── Per maaltijdtype tabel + grafieken ───────────────────────────
        MaaltijdTypeOverzicht(nachtFactor = nachtFactor)

        // ── Uitleg ────────────────────────────────────────────────────────
        Text(
            "Pas aan hoeveel insuline gegeven wordt (S), hoe vroeg dat " +
                "gebeurt (T) en hoe vasthoudend het systeem is (V). " +
                "Het systeem vertaalt dit automatisch naar alle FCLvNext-instellingen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── 💊 Insulinesterkte (S) ────────────────────────────────────────
        StvKaart(
            emoji = "💊",
            titel = "Insulinesterkte (S)",
            omschrijving = "Totale dosis per maaltijd  •  100% = standaard",
            waarde = sNu,
            waardeSuffix = "%",
            eenheid100Label = "standaard",
            stapMinus = sStap,
            stapPlus = sStap,
            min = sMin,
            max = sMax,
            onMinus = {
                val nieuwS = (sNu - sStap).coerceIn(sMin, sMax)
                val nieuwD = sNaarD(nieuwS)
                d = nieuwD; DFLearner.setD(context, nieuwD)
            },
            onPlus = {
                val nieuwS = (sNu + sStap).coerceIn(sMin, sMax)
                val nieuwD = sNaarD(nieuwS)
                d = nieuwD; DFLearner.setD(context, nieuwD)
            }
        )

        // ── ⏱ Timing (T) ─────────────────────────────────────────────────
        StvKaart(
            emoji = "⏱",
            titel = "Timing (T)",
            omschrijving = "Hoe vroeg de insuline vrijkomt  •  100% = neutraal",
            waarde = tNu,
            waardeSuffix = "%",
            eenheid100Label = "neutraal",
            stapMinus = tStap,
            stapPlus = tStap,
            min = tMin,
            max = tMax,
            onMinus = {
                val nieuwT = (tNu - tStap).coerceIn(tMin, tMax)
                val nieuwF = tNaarF(nieuwT)
                f = nieuwF; DFLearner.setF(context, nieuwF)
            },
            onPlus = {
                val nieuwT = (tNu + tStap).coerceIn(tMin, tMax)
                val nieuwF = tNaarF(nieuwT)
                f = nieuwF; DFLearner.setF(context, nieuwF)
            },
            // Extra toelichting: earlyBoost en lateDecay activatiestatus
            extraToelichting = run {
                val po = DFMapping.toParamOverrides(d, f, refWmd, refWff, refEb)
                val eb = po.earlyBoostFactor ?: 1.0
                val lcd = po.lateCommitDecayFactor ?: 0.0
                buildList {
                    if (eb > 1.01) add("vroeg ×${"%.2f".format(eb)}")
                    if (lcd > 0.01) add("laat −${(lcd * 100).toInt()}%")
                    if (eb <= 1.01 && lcd <= 0.01) add("lineaire verdeling")
                }.joinToString("  ")
            }
        )

        // ── 🔁 Volhoudendheid (V) ─────────────────────────────────────────
        StvKaart(
            emoji = "🔁",
            titel = "Volhoudendheid (V)",
            omschrijving = "Hoe vasthoudend bijgestuurd wordt  •  100% = standaard",
            waarde = vNu,
            waardeSuffix = "%",
            eenheid100Label = "standaard",
            stapMinus = vStap,
            stapPlus = vStap,
            min = vMin,
            max = vMax,
            onMinus = {
                val nieuwV = (vNu - vStap).coerceIn(vMin, vMax)
                val nieuwD = vNaarD(nieuwV)
                // V aanpassen via D — herbereken S zodat S consistent blijft
                // V = 95 + (D-1)*50, maar S = 95*D → als D verandert voor V,
                // verandert S mee. Dat is gewenst: V en S zijn beide D-afhankelijk.
                d = nieuwD; DFLearner.setD(context, nieuwD)
            },
            onPlus = {
                val nieuwV = (vNu + vStap).coerceIn(vMin, vMax)
                val nieuwD = vNaarD(nieuwV)
                d = nieuwD; DFLearner.setD(context, nieuwD)
            },
            // Toon ook de bijbehorende S-waarde als toelichting
            extraToelichting = "S wordt ook: ${DFMapping.toStvMap(vNaarD(vNu), f, nachtFactor)["sterkte"] ?: sNu}%"
        )

        // ── Toepassen in AAPS ─────────────────────────────────────────────
        if (onApplyToAaps != null) {
            Button(
                onClick = {
                    // Schrijft ALTIJD beide blokken: stv + volledige param_overrides
                    val po = DFMapping.toParamOverrides(d, f, refWmd, refWff, refEb)
                    val stvMap = DFMapping.toStvMap(d, f, nachtFactor)
                    val ok = onApplyToAaps(po, stvMap)
                    applyResult = if (ok) "✓ Verzonden naar AAPS" else "✗ Verzenden mislukt"
                    applyTs = System.currentTimeMillis()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    "Toepassen in AAPS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        applyResult?.let { msg ->
            if (System.currentTimeMillis() - applyTs < 20_000L) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── Automaat + tempo ──────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🤖  Automaat leert",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (autoEnabled) "Past S, T en V automatisch aan na elke episode"
                            else "Handmatig — automaat berekent maar past niet aan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = {
                            autoEnabled = it
                            DFLearner.setAutoEnabled(context, it)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DFLearner.Tempo.entries.forEach { t ->
                        FilterChip(
                            selected = t == tempo,
                            onClick = { tempo = t; DFLearner.setTempo(context, t) },
                            label = {
                                Text(
                                    when (t) {
                                        DFLearner.Tempo.LANGZAAM -> "Langzaam"
                                        DFLearner.Tempo.NORMAAL  -> "Normaal"
                                        DFLearner.Tempo.SNEL     -> "Snel"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── Kalibratie ────────────────────────────────────────────────────
        KalibratieSectie(
            refWmd = refWmd,
            refWff = refWff,
            refEb = refEb,
            onWmdChange = { refWmd = it; DFLearner.setRefWmd(context, it) },
            onWffChange = { refWff = it; DFLearner.setRefWff(context, it) },
            onEbChange = { refEb = it; DFLearner.setRefEb(context, it) }
        )

        // ── Laatste aanpassingen door automaat ────────────────────────────        if (history.isNotEmpty()) {
        if (history.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Laatste aanpassingen door automaat",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider()
                    history.takeLast(5).reversed().forEach { step ->
                        // Toon alles in S/T/V-eenheden
                        val oldStv = DFMapping.toStvMap(step.oldD, step.oldF, 85)
                        val newStv = DFMapping.toStvMap(step.newD, step.newF, 85)
                        val oldS = oldStv["sterkte"] ?: 0
                        val newS = newStv["sterkte"] ?: 0
                        val oldT = oldStv["timing"] ?: 0
                        val newT = newStv["timing"] ?: 0
                        val oldV = oldStv["volhoudendheid"] ?: 0
                        val newV = newStv["volhoudendheid"] ?: 0

                        val sStr = if (newS != oldS) "S: ${oldS}→${newS}%  " else ""
                        val tStr = if (newT != oldT) "T: ${oldT}→${newT}%  " else ""
                        val vStr = if (newV != oldV) "V: ${oldV}→${newV}%" else ""

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "$sStr$tStr$vStr".trim().ifBlank { "Geen wijziging" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                val diagnoseKleur = when {
                                    step.diagnose.contains("HYPO") -> MaterialTheme.colorScheme.error
                                    step.diagnose.contains("TIMING") || step.diagnose.contains("FRONTLOAD") ->
                                        MaterialTheme.colorScheme.primary

                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    step.reason.take(60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = diagnoseKleur
                                )
                            }
                            Text(
                                fmtTs(step.tsUtc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (step != history.takeLast(5).reversed().last())
                            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        }

        // ── Expert-view: alle 17 afgeleide params ─────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showExpert = !showExpert }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Technische parameters (expert)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (showExpert) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showExpert) {
                    Divider(modifier = Modifier.padding(horizontal = 14.dp))
                    val po = DFMapping.toParamOverrides(d, f, refWmd, refWff, refEb)
                    val stvMap = DFMapping.toStvMap(d, f, nachtFactor)
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ExpertRij("sterkte (S)", "${stvMap["sterkte"] ?: "—"}%")
                        ExpertRij("timing (T)", "${stvMap["timing"] ?: "—"}%")
                        ExpertRij("volhoudendheid (V)", "${stvMap["volhoudendheid"] ?: "—"}%")
                        ExpertRij("D (intern)", "${"%.3f".format(d)}")
                        ExpertRij("F (intern)", "${"%.3f".format(f)}")
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        ExpertRij("earlyBoostFactor", fmtD2(po.earlyBoostFactor))
                        ExpertRij("earlyBoostMinConf", fmtD2(po.earlyBoostMinConfidence))
                        ExpertRij("earlyBoostMaxCom", fmtInt(po.earlyBoostMaxCommits))
                        ExpertRij("lateDecayFactor", fmtD2(po.lateCommitDecayFactor))
                        ExpertRij("lateDecayThresh", fmtD2(po.lateCommitDecayThreshold))
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        ExpertRij("commitCooldown", fmtInt(po.commitCooldownMinutes) + "m")
                        ExpertRij("watchingFrontload", fmtD2(po.watchingFrontloadFrac))
                        ExpertRij("watchingMinDelta", fmtD2(po.watchingMinDeltaToTarget) + " mmol")
                        ExpertRij("peakThreshold", fmtD1(po.peakPredictionThreshold) + " mmol")
                        ExpertRij("peakHorizon", fmtD2(po.peakPredictionHorizonH) + " uur")
                        ExpertRij("iobStart", fmtD2(po.iobStart))
                        ExpertRij("peakIobBrake", fmtD2(po.peakIobBrakeSuppressThreshold))
                        ExpertRij("earlyRiseFracMin", fmtD2(po.earlyRiseFracMin))
                        ExpertRij("peakMaxSlope", fmtD2(po.peakMaxSlopeWeight))
                        ExpertRij("sustainedSlope", fmtD2(po.sustainedRiseSlopeMin))
                        ExpertRij("sustainedTarget", fmtInt(po.sustainedRiseMinTarget) + "m")
                    }
                }
            }
        }
    }
}

// ── StvKaart ──────────────────────────────────────────────────────────────
// Generieke kaart voor S, T of V met uniforme opmaak.

@Composable
private fun StvKaart(
    emoji: String,
    titel: String,
    omschrijving: String,
    waarde: Int,
    waardeSuffix: String,
    eenheid100Label: String,
    stapMinus: Int,
    stapPlus: Int,
    min: Int,
    max: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    extraToelichting: String? = null
) {
    val afwijking = waarde - 100
    val kleur = when {
        afwijking > 4  -> MaterialTheme.colorScheme.primary
        afwijking < -4 -> MaterialTheme.colorScheme.tertiary
        else           -> MaterialTheme.colorScheme.onSurface
    }
    val indicator = when {
        afwijking > 4  -> "↑"
        afwijking < -4 -> "↓"
        else           -> "="
    }

    val waardeNaMinus = (waarde - stapMinus).coerceIn(min, max)
    val waardeNaPlus  = (waarde + stapPlus).coerceIn(min, max)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$emoji  $titel",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        omschrijving,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            indicator, color = kleur,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$waarde$waardeSuffix",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = kleur
                        )
                    }
                    if (extraToelichting != null) {
                        Text(
                            extraToelichting,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onMinus,
                    enabled = waarde > min,
                    modifier = Modifier.weight(1f)
                ) { Text("−  ${waardeNaMinus}$waardeSuffix", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = onPlus,
                    enabled = waarde < max,
                    modifier = Modifier.weight(1f)
                ) { Text("+  ${waardeNaPlus}$waardeSuffix", fontSize = 13.sp) }
            }
        }
    }
}

// ── STVVerloopGrafiek ─────────────────────────────────────────────────────
// Toont het verloop van S (groen), T (roze) en V (blauw) over de tijd.
// Alle drie in FCLvNext-eenheden (%) op dezelfde Y-schaal (80–130%).

@Composable
private fun STVVerloopGrafiek(
    history: List<DFLearner.LearningStep>,
    nachtFactor: Int
) {
    if (history.size < 2) return

    val kleurS   = MaterialTheme.colorScheme.primary
    val kleurT   = MaterialTheme.colorScheme.tertiary
    val kleurV   = MaterialTheme.colorScheme.secondary
    val kleurGrid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    data class Punt(val tsMs: Long, val s: Int, val t: Int, val v: Int)

    val punten: List<Punt> = buildList {
        val eersteTs = runCatching {
            Instant.parse(history.first().tsUtc).toEpochMilli()
        }.getOrDefault(0L)
        val oudeStv = DFMapping.toStvMap(history.first().oldD, history.first().oldF, nachtFactor)
        add(Punt(
            eersteTs - 1,
            oudeStv["sterkte"] ?: 95,
            oudeStv["timing"] ?: 100,
            oudeStv["volhoudendheid"] ?: 95
        ))
        history.forEach { step ->
            val ms  = runCatching { Instant.parse(step.tsUtc).toEpochMilli() }.getOrDefault(0L)
            val stv = DFMapping.toStvMap(step.newD, step.newF, nachtFactor)
            add(Punt(
                ms,
                stv["sterkte"] ?: 95,
                stv["timing"] ?: 100,
                stv["volhoudendheid"] ?: 95
            ))
        }
    }.sortedBy { it.tsMs }

    val msNu     = System.currentTimeMillis()
    val ms7Dagen = 7L * 24 * 3_600_000
    val tijdMin  = minOf(punten.first().tsMs, msNu - ms7Dagen)
    val tijdMax  = maxOf(punten.last().tsMs, msNu)
    val tijdSpan = (tijdMax - tijdMin).coerceAtLeast(1L).toDouble()

    // Gemeenschappelijke Y-schaal voor S, T en V (80–130%)
    val alleWaarden = punten.flatMap { listOf(it.s, it.t, it.v) }
    val yMin = (alleWaarden.min() - 4).coerceAtLeast(75)
    val yMax = (alleWaarden.max() + 4).coerceAtMost(135)
    val ySpan = (yMax - yMin).coerceAtLeast(10).toDouble()

    val datumFmt = DateTimeFormatter.ofPattern("dd/MM").withZone(ZoneId.systemDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Text(
                "Verloop S / T / V",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Y-as labels (links)
                Column(
                    modifier = Modifier.width(34.dp).height(130.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text("${yMax}%", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    Text("${((yMin + yMax) / 2)}%", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    Text("${yMin}%", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                }

                Canvas(modifier = Modifier.weight(1f).height(130.dp)) {
                    val w = size.width
                    val h = size.height

                    fun xOf(ms: Long) = ((ms - tijdMin) / tijdSpan * w).toFloat().coerceIn(0f, w)
                    fun yOf(pct: Int) = (h * (1.0 - (pct - yMin) / ySpan)).toFloat().coerceIn(0f, h)

                    // Gridlijnen
                    for (i in 0..2) {
                        drawLine(kleurGrid, Offset(0f, h * i / 2f), Offset(w, h * i / 2f), strokeWidth = 1f)
                    }

                    // 100%-referentielijn (stippel)
                    val y100 = yOf(100)
                    if (y100 in 0f..h) {
                        drawLine(
                            kleurGrid.copy(alpha = 0.4f),
                            Offset(0f, y100), Offset(w, y100),
                            strokeWidth = 1.5f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                        )
                    }

                    // S lijn
                    val pathS = Path()
                    punten.forEachIndexed { i, pt ->
                        if (i == 0) pathS.moveTo(xOf(pt.tsMs), yOf(pt.s))
                        else pathS.lineTo(xOf(pt.tsMs), yOf(pt.s))
                    }
                    drawPath(pathS, kleurS, style = Stroke(width = 2.5f))
                    punten.forEach { drawCircle(kleurS, radius = 3.5f, center = Offset(xOf(it.tsMs), yOf(it.s))) }

                    // T lijn
                    val pathT = Path()
                    punten.forEachIndexed { i, pt ->
                        if (i == 0) pathT.moveTo(xOf(pt.tsMs), yOf(pt.t))
                        else pathT.lineTo(xOf(pt.tsMs), yOf(pt.t))
                    }
                    drawPath(pathT, kleurT, style = Stroke(width = 2.5f))
                    punten.forEach { drawCircle(kleurT, radius = 3.5f, center = Offset(xOf(it.tsMs), yOf(it.t))) }

                    // V lijn
                    val pathV = Path()
                    punten.forEachIndexed { i, pt ->
                        if (i == 0) pathV.moveTo(xOf(pt.tsMs), yOf(pt.v))
                        else pathV.lineTo(xOf(pt.tsMs), yOf(pt.v))
                    }
                    drawPath(pathV, kleurV, style = Stroke(width = 2.0f))
                    punten.forEach { drawCircle(kleurV, radius = 3.0f, center = Offset(xOf(it.tsMs), yOf(it.v))) }

                    // Nu-lijn
                    if (msNu > punten.last().tsMs + 300_000L) {
                        val xNu = xOf(msNu)
                        drawLine(
                            kleurGrid.copy(alpha = 0.3f),
                            Offset(xNu, 0f), Offset(xNu, h),
                            strokeWidth = 1f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                        )
                    }
                }
            }

            // X-as datums
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Spacer(Modifier.width(34.dp))
                Text(
                    datumFmt.format(Instant.ofEpochMilli(tijdMin)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    datumFmt.format(Instant.ofEpochMilli(punten.last().tsMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp
                )
            }

            // Legenda
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendaPunt(kleurS, "Sterkte (S)")
                LegendaPunt(kleurT, "Timing (T)")
                LegendaPunt(kleurV, "Volhoudenheid (V)")
                Spacer(Modifier.weight(1f))
                Text(
                    "${history.size} aanpassing${if (history.size != 1) "en" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun LegendaPunt(kleur: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(8.dp)) { drawCircle(kleur) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = kleur, fontSize = 9.sp)
    }
}

// ── KalibratieSectie ──────────────────────────────────────────────────────
// Drie gebruikersstuurbare basisinstellingen die de DFMapping-berekeningen
// verschuiven zonder de coherentie te breken.

@Composable
private fun KalibratieSectie(
    refWmd: Double,
    refWff: Double,
    refEb:  Double,
    onWmdChange: (Double) -> Unit,
    onWffChange: (Double) -> Unit,
    onEbChange:  (Double) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "🎛  Kalibratie",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Pas het basisgedrag aan op jouw maaltijdpatroon",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (open) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (open) {
                Divider(modifier = Modifier.padding(horizontal = 14.dp))
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Stijgingsdrempel frontload (REF_WMD) ────────────────
                    KalibratieParm(
                        emoji = "📏",
                        naam = "Stijgingsdrempel frontload",
                        uitleg = "Hoe ver BG boven target moet stijgen voordat het systeem een frontload-puls geeft. " +
                            "Lager = eerder reageren bij maaltijden die laag starten. " +
                            "Hoger = alleen reageren bij duidelijkere stijgingen.",
                        waarde = refWmd,
                        eenheid = "mmol",
                        stap = 0.10,
                        min = DFMapping.REF_WMD_MIN,
                        max = DFMapping.REF_WMD_MAX,
                        defaultWaarde = DFMapping.REF_WMD_DEFAULT,
                        formatFn = { "%.2f".format(it) },
                        onMinus = { onWmdChange((refWmd - 0.10).coerceIn(DFMapping.REF_WMD_MIN, DFMapping.REF_WMD_MAX)) },
                        onPlus  = { onWmdChange((refWmd + 0.10).coerceIn(DFMapping.REF_WMD_MIN, DFMapping.REF_WMD_MAX)) },
                        onReset = { onWmdChange(DFMapping.REF_WMD_DEFAULT) }
                    )

                    // ── Frontload grootte (REF_WFF) ─────────────────────────
                    KalibratieParm(
                        emoji = "💉",
                        naam = "Frontload grootte",
                        uitleg = "Hoe groot de frontload-puls is als hij triggert, als percentage van de maximale SMB. " +
                            "Hoger = grotere puls bij stijgingsdetectie. " +
                            "Lager = voorzichtiger eerste reactie.",
                        waarde = refWff * 100,
                        eenheid = "%",
                        stap = 5.0,
                        min = DFMapping.REF_WFF_MIN * 100,
                        max = DFMapping.REF_WFF_MAX * 100,
                        defaultWaarde = DFMapping.REF_WFF_DEFAULT * 100,
                        formatFn = { "%.0f".format(it) },
                        onMinus = { onWffChange(((refWff * 100 - 5.0) / 100).coerceIn(DFMapping.REF_WFF_MIN, DFMapping.REF_WFF_MAX)) },
                        onPlus  = { onWffChange(((refWff * 100 + 5.0) / 100).coerceIn(DFMapping.REF_WFF_MIN, DFMapping.REF_WFF_MAX)) },
                        onReset = { onWffChange(DFMapping.REF_WFF_DEFAULT) }
                    )

                    // ── Vroege boost (REF_EB) ───────────────────────────────
                    KalibratieParm(
                        emoji = "🚀",
                        naam = "Vroege boost",
                        uitleg = "Versterking van de eerste 1-2 commits bij een duidelijk stijgingssignaal. " +
                            "1.0 = uit (standaard). " +
                            "Hoger = meer insuline vroeg in de maaltijd, lagere piek, maar hogere kans op late dip.",
                        waarde = refEb,
                        eenheid = "×",
                        stap = 0.10,
                        min = DFMapping.REF_EB_MIN,
                        max = DFMapping.REF_EB_MAX,
                        defaultWaarde = DFMapping.REF_EB_DEFAULT,
                        formatFn = { "%.1f".format(it) },
                        onMinus = { onEbChange((refEb - 0.10).coerceIn(DFMapping.REF_EB_MIN, DFMapping.REF_EB_MAX)) },
                        onPlus  = { onEbChange((refEb + 0.10).coerceIn(DFMapping.REF_EB_MIN, DFMapping.REF_EB_MAX)) },
                        onReset = { onEbChange(DFMapping.REF_EB_DEFAULT) }
                    )

                    Text(
                        "⚠ Kalibratie-wijzigingen zijn pas actief na 'Toepassen in AAPS' hierboven.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun KalibratieParm(
    emoji: String,
    naam: String,
    uitleg: String,
    waarde: Double,
    eenheid: String,
    stap: Double,
    min: Double,
    max: Double,
    defaultWaarde: Double,
    formatFn: (Double) -> String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onReset: () -> Unit
) {
    val isDefault = kotlin.math.abs(waarde - defaultWaarde) < 0.001
    val kleur = when {
        waarde > defaultWaarde + stap * 0.5 -> MaterialTheme.colorScheme.primary
        waarde < defaultWaarde - stap * 0.5 -> MaterialTheme.colorScheme.tertiary
        else                                -> MaterialTheme.colorScheme.onSurface
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$emoji  $naam",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${formatFn(waarde)} $eenheid",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = kleur
            )
        }
        Text(
            uitleg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onMinus,
                enabled = waarde > min + 0.001,
                modifier = Modifier.weight(1f)
            ) { Text("−  ${formatFn((waarde - stap).coerceIn(min, max))} $eenheid", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onReset,
                enabled = !isDefault,
                modifier = Modifier.weight(1f)
            ) { Text("↺  ${formatFn(defaultWaarde)}", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onPlus,
                enabled = waarde < max - 0.001,
                modifier = Modifier.weight(1f)
            ) { Text("+  ${formatFn((waarde + stap).coerceIn(min, max))} $eenheid", fontSize = 12.sp) }
        }
    }
}

// ── MaaltijdTypeOverzicht ─────────────────────────────────────────────────────
// Tabel met huidige D/F/S/T/V per type + drie aparte verloopgrafieken.

@Composable
private fun MaaltijdTypeOverzicht(nachtFactor: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current

    data class TypeRij(
        val label: String,
        val emoji: String,
        val type: MealTypeBridge.MealType,
        val kleur: Color
    )

    val types = listOf(
        TypeRij("Gemengd", "🔀", MealTypeBridge.MealType.GEMENGD, Color(0xFF9E9E9E)),
        TypeRij("Snel",    "⚡", MealTypeBridge.MealType.SNEL,    Color(0xFFFF9800)),
        TypeRij("Traag",   "🐢", MealTypeBridge.MealType.TRAAG,   Color(0xFF4CAF50))
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text(
                "📊 D/F per maaltijdtype",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            // ── Tabel ─────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Header
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Type", modifier = Modifier.weight(1.8f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (h in listOf("D", "F", "S", "T", "V")) {
                        Text(h, modifier = Modifier.weight(1f),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                Divider()

                // Rijen
                types.forEach { tr ->
                    val d   = DFLearner.getDForType(context, tr.type)
                    val f   = DFLearner.getFForType(context, tr.type)
                    val stv = DFMapping.toStvMap(d, f, nachtFactor)
                    val cnt = when (tr.type) {
                        MealTypeBridge.MealType.SNEL  ->
                            context.getSharedPreferences("df_learner_prefs", 0)
                                .getInt("df_count_snel", 0)
                        MealTypeBridge.MealType.TRAAG ->
                            context.getSharedPreferences("df_learner_prefs", 0)
                                .getInt("df_count_traag", 0)
                        else -> null
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1.8f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr.emoji, fontSize = 11.sp)
                            Text(tr.label, style = MaterialTheme.typography.labelSmall,
                                 color = tr.kleur, fontWeight = FontWeight.SemiBold)
                            cnt?.let {
                                Text("($it)", style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                     fontSize = 9.sp)
                            }
                        }
                        for (w in listOf(
                            "%.3f".format(d),
                            "%.3f".format(f),
                            "${stv["sterkte"]}%",
                            "${stv["timing"]}%",
                            "${stv["volhoudendheid"]}%"
                        )) {
                            Text(w, modifier = Modifier.weight(1f),
                                 style = MaterialTheme.typography.labelSmall,
                                 textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }

            // ── Drie grafieken ────────────────────────────────────────────
            types.forEach { tr ->
                val hist = DFLearner.getHistoryForType(context, tr.type)
                if (hist.size >= 2) {
                    STVVerloopGrafiekKlein(
                        history     = hist,
                        nachtFactor = nachtFactor,
                        titel       = "${tr.emoji} ${tr.label}",
                        accentKleur = tr.kleur
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${tr.emoji} ${tr.label}",
                             style = MaterialTheme.typography.labelSmall,
                             color = tr.kleur, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (tr.type == MealTypeBridge.MealType.GEMENGD)
                                "Zie verloop hierboven"
                            else "Nog te weinig data (min. 2 aanpassingen)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── STVVerloopGrafiekKlein ────────────────────────────────────────────────────
// Compacte variant van STVVerloopGrafiek voor per-type weergave.

@Composable
private fun STVVerloopGrafiekKlein(
    history: List<DFLearner.LearningStep>,
    nachtFactor: Int,
    titel: String,
    accentKleur: Color
) {
    if (history.size < 2) return

    val kleurS    = MaterialTheme.colorScheme.primary
    val kleurT    = MaterialTheme.colorScheme.tertiary
    val kleurV    = MaterialTheme.colorScheme.secondary
    val kleurGrid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    data class Punt(val tsMs: Long, val s: Int, val t: Int, val v: Int)

    val punten: List<Punt> = buildList {
        val eersteTs = runCatching {
            java.time.Instant.parse(history.first().tsUtc).toEpochMilli()
        }.getOrDefault(0L)
        val oudeStv = DFMapping.toStvMap(history.first().oldD, history.first().oldF, nachtFactor)
        add(Punt(eersteTs - 1, oudeStv["sterkte"] ?: 95, oudeStv["timing"] ?: 100, oudeStv["volhoudendheid"] ?: 95))
        history.forEach { step ->
            val ms  = runCatching { java.time.Instant.parse(step.tsUtc).toEpochMilli() }.getOrDefault(0L)
            val stv = DFMapping.toStvMap(step.newD, step.newF, nachtFactor)
            add(Punt(ms, stv["sterkte"] ?: 95, stv["timing"] ?: 100, stv["volhoudendheid"] ?: 95))
        }
    }.sortedBy { it.tsMs }

    val tijdMin  = punten.first().tsMs
    val tijdMax  = maxOf(punten.last().tsMs, System.currentTimeMillis())
    val tijdSpan = (tijdMax - tijdMin).coerceAtLeast(1L).toDouble()

    val alleWaarden = punten.flatMap { listOf(it.s, it.t, it.v) }
    val yMin = (alleWaarden.min() - 4).coerceAtLeast(75)
    val yMax = (alleWaarden.max() + 4).coerceAtMost(135)
    val ySpan = (yMax - yMin).coerceAtLeast(10).toDouble()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(titel, style = MaterialTheme.typography.labelSmall,
                 color = accentKleur, fontWeight = FontWeight.SemiBold)
            Text("${history.size} aanp.", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.width(28.dp).height(90.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text("${yMax}%", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
                Text("${yMin}%", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
            }

            Canvas(modifier = Modifier.weight(1f).height(90.dp)) {
                val w = size.width; val h = size.height
                fun xOf(ms: Long) = ((ms - tijdMin) / tijdSpan * w).toFloat().coerceIn(0f, w)
                fun yOf(pct: Int) = (h * (1.0 - (pct - yMin) / ySpan)).toFloat().coerceIn(0f, h)

                // Grid + 100% lijn
                drawLine(kleurGrid, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
                val y100 = yOf(100)
                if (y100 in 0f..h) drawLine(
                    kleurGrid.copy(alpha = 0.4f), Offset(0f, y100), Offset(w, y100),
                    strokeWidth = 1.5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 3f))
                )

                listOf(
                    kleurS to { p: Punt -> p.s },
                    kleurT to { p: Punt -> p.t },
                    kleurV to { p: Punt -> p.v }
                ).forEach { (kleur, getter) ->
                    val path = Path()
                    punten.forEachIndexed { i, pt ->
                        if (i == 0) path.moveTo(xOf(pt.tsMs), yOf(getter(pt)))
                        else path.lineTo(xOf(pt.tsMs), yOf(getter(pt)))
                    }
                    drawPath(path, kleur, style = Stroke(width = 2f))
                    punten.forEach { drawCircle(kleur, radius = 3f, center = Offset(xOf(it.tsMs), yOf(getter(it)))) }
                }
            }
        }
    }
}

@Composable
private fun ExpertRij(naam: String, waarde: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(naam, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(waarde, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

private fun fmtD1(v: Double?) = if (v == null) "—" else String.format("%.1f", v)
private fun fmtD2(v: Double?) = if (v == null) "—" else String.format("%.2f", v)
private fun fmtInt(v: Int?) = v?.toString() ?: "—"

private fun fmtTs(tsUtc: String): String = try {
    val instant = Instant.parse(tsUtc)
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(instant)
} catch (_: Exception) { tsUtc.take(10) }