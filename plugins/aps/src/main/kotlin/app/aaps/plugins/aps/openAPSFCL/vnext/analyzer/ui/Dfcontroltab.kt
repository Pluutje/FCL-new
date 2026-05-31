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
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FrontloadLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

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
 * Garantie: elke s.dfControlToepassen schrijft altijd BEIDE blokken:
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
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

    val context = LocalContext.current

    // ── Maaltijdtype selector ─────────────────────────────────────────────
    // Vereenvoudigd: alleen SNEL en TRAAG, geen GEMENGD meer
    var geselecteerdType by remember { mutableStateOf(MealTypeBridge.MealType.SNEL) }

    // ── D/F/vExtra state per type ─────────────────────────────────────────
    fun dVoorType()      = DFLearner.getDForType(context, geselecteerdType)
    fun fVoorType()      = DFLearner.getFForType(context, geselecteerdType)
    fun vExtraVoorType() = DFLearner.getVExtraForType(context, geselecteerdType)

    var d      by remember(geselecteerdType) { mutableStateOf(dVoorType()) }
    var f      by remember(geselecteerdType) { mutableStateOf(fVoorType()) }
    var vExtra by remember(geselecteerdType) { mutableStateOf(vExtraVoorType()) }

    // Algemene state
    var tempo by remember { mutableStateOf(DFLearner.getTempo(context)) }
    var autoEnabled by remember { mutableStateOf(DFLearner.isAutoEnabled(context)) }
    var aggressiveness by remember { mutableStateOf(DFLearner.getAggressiveness(context)) }
    var history by remember { mutableStateOf(DFLearner.getHistory(context)) }
    var showGeavanceerd by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<String?>(null) }
    var applyTs by remember { mutableStateOf(0L) }

    // Frontload timing (REF_WMD) — zichtbaar als s.dfControlHoeVroeg
    var refWmd by remember { mutableStateOf(DFLearner.getRefWmd(context)) }
    var refWff by remember { mutableStateOf(DFLearner.getRefWff(context)) }
    var refEb  by remember { mutableStateOf(DFLearner.getRefEb(context)) }

    // S/T/V voor geselecteerd type
    // S en T worden berekend via D en F (zoals voorheen).
    // V wordt berekend via D + vExtra — onafhankelijk van S.
    val stv  = DFMapping.toStvMap(d, f, nachtFactor, vExtra)
    val sNu  = stv["sterkte"]        ?: 95
    val tNu  = stv["timing"]         ?: 100
    val vNu  = stv["volhoudendheid"] ?: 95

    // Bereken ook S en V puur op basis van D (zonder vExtra) voor de S-kaart.
    // Zo ziet de gebruiker dat S alleen via D beweegt, en V via vExtra.
    val stvZonderVExtra = DFMapping.toStvMap(d, f, nachtFactor, 0.0)
    val sViaD           = stvZonderVExtra["sterkte"] ?: 95

    val sStap = 5; val tStap = 4; val vStap = 5
    val sMin = DFMapping.toStvMap(DFMapping.D_MIN, f, nachtFactor)["sterkte"] ?: 80
    val sMax = DFMapping.toStvMap(DFMapping.D_MAX, f, nachtFactor)["sterkte"] ?: 128
    val tMin = 80; val tMax = 120
    // V-grenzen op basis van vExtra-bereik (D-component is constant bij V-aanpassing)
    val vBasisViaDOnly = stvZonderVExtra["volhoudendheid"] ?: 95
    val vMin = (vBasisViaDOnly + (-0.5 * 30).toInt()).coerceAtLeast(70)
    val vMax = (vBasisViaDOnly + (0.5 * 30).toInt()).coerceAtMost(125)

    fun sNaarD(s: Int) = (s.toDouble() / 95.0).coerceIn(DFMapping.D_MIN, DFMapping.D_MAX)
    fun tNaarF(t: Int) = (0.5 + (t.toDouble() - 106.0) / 40.0).coerceIn(DFMapping.F_MIN, DFMapping.F_MAX)
    // V → vExtra: V = vBasisViaDOnly + vExtra*30  →  vExtra = (V - vBasisViaDOnly) / 30
    fun vNaarVExtra(v: Int) = ((v.toDouble() - vBasisViaDOnly) / 30.0).coerceIn(-0.5, 0.5)

    fun slaTypeOp(nieuweD: Double, nieuweF: Double = f) {
        DFLearner.setDForType(context, geselecteerdType, nieuweD)
        if (nieuweF != f) DFLearner.setFForType(context, geselecteerdType, nieuweF)
    }

    fun slaVExtraOp(nieuweVExtra: Double) {
        DFLearner.setVExtraForType(context, geselecteerdType, nieuweVExtra)
    }

    // Frontload timing omrekening: REF_WMD → begrijpelijk label
    fun frontloadLabel(wmd: Double): String = when {
        wmd <= 0.90 -> s.zeervroeg
        wmd <= 1.10 -> s.vroeg
        wmd <= 1.30 -> s.normaal
        wmd <= 1.60 -> s.laat
        else        -> s.zeerlaat
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── 1. Agressiviteitsschaal ────────────────────────────────────────
        AggressiviteitsKaart(
            niveau = aggressiveness,
            onChanged = { nieuw ->
                aggressiveness = nieuw
                DFLearner.setAggressiveness(context, nieuw)
            }
        )

                // Actieve S/T/V samenvatting
        // Effectieve waarden incl. agressiviteitsmultiplier -- consistent met StatusFormatter
        val stvEffectief = DFMapping.toStvMap(d, f, nachtFactor, vExtra, aggLevel = aggressiveness)
        val sEff = stvEffectief["sterkte"] ?: sViaD
        val tEff = stvEffectief["timing"] ?: tNu
        val vEff = stvEffectief["volhoudendheid"] ?: vNu
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp),
                   verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.actuelParams,
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (aggressiveness != 5) {
                        Text("agressiviteit $aggressiveness",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💊 Sterkte",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${sEff}%",
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⏱ Timing",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${tEff}%",
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔁 Vasthoudend",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${vEff}%",
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.Bold)
                    }
                }
                if (aggressiveness != 5) {
                    Text("Geleerde basis: S=${sViaD}% T=${tNu}% V=${vNu}%",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

// ── 2. Toepassen in AAPS ──────────────────────────────────────────
        if (onApplyToAaps != null) {
            Button(
                onClick = {
                    val dApply = DFLearner.getDForType(context, MealTypeBridge.MealType.SNEL)
                    val fApply = DFLearner.getFForType(context, MealTypeBridge.MealType.SNEL)
                    val veApply = DFLearner.getVExtraForType(context, MealTypeBridge.MealType.SNEL)
                    val po = DFMapping.toParamOverrides(dApply, fApply, DFLearner.getRefWmd(context),
                        DFLearner.getRefWff(context), DFLearner.getRefEb(context), veApply,
                        aggLevel = aggressiveness)
                    val stvMap = DFMapping.toStvMap(dApply, fApply, nachtFactor, veApply,
                        aggLevel = aggressiveness)
                    val ok = onApplyToAaps(po, stvMap)
                    applyResult = if (ok) "Toegepast" else "Fout"
                    applyTs = System.currentTimeMillis()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(s.dfControlToepassen, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
            }
            val msg = applyResult
            if (msg != null && System.currentTimeMillis() - applyTs < 4000) {
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }

        // ── 3. Automaat leert ─────────────────────────────────────────────
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
                        Text("🤖  Automaat leert", style = MaterialTheme.typography.bodyMedium,
                             fontWeight = FontWeight.SemiBold)
                        Text(
                            if (autoEnabled) "Past instellingen automatisch aan na elke maaltijd"
                            else "Handmatig — automaat berekent maar past niet aan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoEnabled, onCheckedChange = {
                        autoEnabled = it; DFLearner.setAutoEnabled(context, it)
                    })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DFLearner.Tempo.entries.forEach { t ->
                        FilterChip(
                            selected = t == tempo,
                            onClick = { tempo = t; DFLearner.setTempo(context, t) },
                            label = {
                                Text(when (t) {
                                         DFLearner.Tempo.LANGZAAM -> "Langzaam"
                                         DFLearner.Tempo.NORMAAL  -> s.normaal
                                         DFLearner.Tempo.SNEL     -> "Snel"
                                     }, style = MaterialTheme.typography.labelSmall)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── 4. Compacte leer-status ───────────────────────────────────────
        // Toont hoeveel maaltijden het systeem heeft geleerd zonder details
        val snelCount = DFLearner.getCountForType(context, MealTypeBridge.MealType.SNEL)
        val traagCount = DFLearner.getCountForType(context, MealTypeBridge.MealType.TRAAG)
        if (snelCount + traagCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Leergeschiedenis",
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (snelCount > 0) append("⚡ $snelCount snelle maaltijden  ")
                            if (traagCount > 0) append("🐢 $traagCount trage maaltijden")
                        }.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 5. Laatste aanpassingen door automaat ─────────────────────────
        if (history.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Laatste aanpassingen door automaat",
                         style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Divider()
                    history.takeLast(5).reversed().forEach { step ->
                        val oldStv = DFMapping.toStvMap(step.oldD, step.oldF, 85)
                        val newStv = DFMapping.toStvMap(step.newD, step.newF, 85)
                        val sStr = if (newStv["sterkte"] != oldStv["sterkte"]) "S: ${oldStv["sterkte"]}→${newStv["sterkte"]}%  " else ""
                        val tStr = if (newStv["timing"] != oldStv["timing"]) "T: ${oldStv["timing"]}→${newStv["timing"]}%  " else ""
                        val vStr = if (newStv["volhoudendheid"] != oldStv["volhoudendheid"]) "V: ${oldStv["volhoudendheid"]}→${newStv["volhoudendheid"]}%" else ""
                        val typeEmoji = when (step.mealType) { "SNEL" -> "⚡"; "TRAAG" -> "🐢"; else -> "🔀" }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(typeEmoji, fontSize = 11.sp)
                                    Text("$sStr$tStr$vStr".trim().ifBlank { "Geen wijziging" },
                                         style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Text(step.reason.take(60), style = MaterialTheme.typography.labelSmall,
                                     color = when {
                                         step.diagnose.contains("HYPO") -> MaterialTheme.colorScheme.error
                                         step.diagnose.contains("TIMING") || step.diagnose.contains("FRONTLOAD") -> MaterialTheme.colorScheme.primary
                                         else -> MaterialTheme.colorScheme.onSurfaceVariant
                                     })
                            }
                            Text(fmtTs(step.tsUtc), style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (step != history.takeLast(5).reversed().last())
                            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        }

        // ── 6. Reset (onopvallend onderaan) ───────────────────────────────
        var showResetBevestiging by remember { mutableStateOf(false) }
        if (!showResetBevestiging) {
            androidx.compose.material3.TextButton(
                onClick = { showResetBevestiging = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Leerdata wissen en opnieuw beginnen",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth(),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Weet je het zeker? Alle geleerde waarden worden gewist.",
                         style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                DFLearner.resetTypeData(context)
                                history = DFLearner.getHistory(context)
                                showResetBevestiging = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Wissen") }
                        OutlinedButton(onClick = { showResetBevestiging = false }) {
                            Text("Annuleren")
                        }
                    }
                }
            }
        }
    }
}

// ── MaaltijdTypeSelector ──────────────────────────────────────────────────────

@Composable
private fun MaaltijdTypeSelector(
    geselecteerd: MealTypeBridge.MealType,
    onSelect: (MealTypeBridge.MealType) -> Unit
) {
    val types = listOf(
        Triple(MealTypeBridge.MealType.SNEL,    "⚡", "Snel"),
        Triple(MealTypeBridge.MealType.TRAAG,   "🐢", "Traag")
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Maaltijdtype", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Text("Stel per maaltijdtype aparte waarden in — het systeem herkent het type automatisch.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { (type, emoji, label) ->
                    val geselecteerdKleur = when (type) {
                        MealTypeBridge.MealType.SNEL  -> Color(0xFFFF9800)
                        MealTypeBridge.MealType.TRAAG -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val isGeselecteerd = geselecteerd == type
                    Surface(
                        onClick = { onSelect(type) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isGeselecteerd) geselecteerdKleur.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isGeselecteerd)
                            androidx.compose.foundation.BorderStroke(1.5.dp, geselecteerdKleur)
                        else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(emoji, fontSize = 16.sp)
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                 color = if (isGeselecteerd) geselecteerdKleur
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                                 fontWeight = if (isGeselecteerd) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

// ── FrontloadKaart ────────────────────────────────────────────────────────────

@Composable
private fun FrontloadKaart(
    refWmd: Double,
    label: String,
    onEerder: () -> Unit,
    onLater: () -> Unit
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    val gemiddeldeMarge = remember { FrontloadLearner.getGemiddeldeMarge(context) }
    val evalCount       = remember { FrontloadLearner.getEvalCount(context) }
    val flHistory       = remember { FrontloadLearner.getHistory(context) }

    val kleur = when (label) {
        s.zeervroeg, s.vroeg -> MaterialTheme.colorScheme.primary
        s.laat, s.zeerlaat  -> MaterialTheme.colorScheme.tertiary
        else                  -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Header
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🚀  Wanneer reageert het systeem",
                         style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Text("Hoe snel na het begin van een stijging de eerste insulinepuls gegeven wordt",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(label, style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold, color = kleur)
            }

            // Marge indicator (alleen als we data hebben)
            if (gemiddeldeMarge >= 0 && evalCount >= 3) {
                val margeKleur = when {
                    gemiddeldeMarge < 20 -> MaterialTheme.colorScheme.error
                    gemiddeldeMarge > 50 -> MaterialTheme.colorScheme.tertiary
                    else                 -> MaterialTheme.colorScheme.primary
                }
                val margeOmschrijving = when {
                    gemiddeldeMarge < 20 -> "Te laat — systeem reageert te lang na de stijging"
                    gemiddeldeMarge > 50 -> "Erg vroeg — systeem reageert eerder dan nodig"
                    else                 -> "Goed — systeem reageert op het juiste moment"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gemiddeld $gemiddeldeMarge min voor de piek",
                             style = MaterialTheme.typography.bodySmall,
                             fontWeight = FontWeight.SemiBold,
                             color = margeKleur)
                        Text(margeOmschrijving,
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("$evalCount ep.", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontSize = 9.sp)
                }
            } else if (evalCount in 1 until 3) {
                Text("Nog ${ 3 - evalCount} episodes nodig voor automatisch advies ($evalCount/3 bruikbaar)",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Nog geen data — wordt automatisch geleerd na 3 maaltijden met frontload",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Knoppen
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEerder, enabled = refWmd > DFMapping.REF_WMD_MIN,
                               modifier = Modifier.weight(1f)) {
                    Text("← Eerder", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onLater, enabled = refWmd < DFMapping.REF_WMD_MAX,
                               modifier = Modifier.weight(1f)) {
                    Text("Later →", fontSize = 13.sp)
                }
            }

            // Leergeschiedenis (laatste 3)
            if (flHistory.isNotEmpty()) {
                Divider()
                Text("Automatische aanpassingen:",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                flHistory.takeLast(3).reversed().forEach { step ->
                    val richting = when (step.richting) {
                        "EERDER" -> "← eerder (marge was ${step.gemiddeldeMarge} min)"
                        "LATER"  -> "→ later (marge was ${step.gemiddeldeMarge} min)"
                        else     -> "geen wijziging"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(richting, style = MaterialTheme.typography.labelSmall,
                             color = if (step.richting == "EERDER") MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.tertiary)
                        Text(fmtTs(step.tsUtc), style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             fontSize = 9.sp)
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
        TypeRij("Snel",    "⚡", MealTypeBridge.MealType.SNEL,    Color(0xFFFF9800)),
        TypeRij("Traag",   "🐢", MealTypeBridge.MealType.TRAAG,   Color(0xFF4CAF50))
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text(
                "📊 S/T/V per maaltijdtype",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            // ── Tabel ─────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Header
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Type", modifier = Modifier.weight(2.5f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (h in listOf("S", "T", "V*")) {
                        Text(h, modifier = Modifier.weight(1f),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                Text(
                    "* V is onafhankelijk van S — aparte instelling per maaltijdtype",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
                Divider()

                // Rijen
                types.forEach { tr ->
                    val d   = DFLearner.getDForType(context, tr.type)
                    val f   = DFLearner.getFForType(context, tr.type)
                    val ve  = DFLearner.getVExtraForType(context, tr.type)
                    val stv = DFMapping.toStvMap(d, f, nachtFactor, ve)
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
                            modifier = Modifier.weight(2.5f),
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
                val hist   = DFLearner.getHistoryForType(context, tr.type)
                val veType = DFLearner.getVExtraForType(context, tr.type)
                if (hist.size >= 2) {
                    STVVerloopGrafiekKlein(
                        history     = hist,
                        nachtFactor = nachtFactor,
                        titel       = "${tr.emoji} ${tr.label}",
                        accentKleur = tr.kleur,
                        vExtra      = veType
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
                            "Nog te weinig data (min. 2 aanpassingen)",
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
    accentKleur: Color,
    vExtra: Double = 0.0   // huidige vExtra voor dit type — gebruikt voor V-lijn
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
        val oudeStv = DFMapping.toStvMap(history.first().oldD, history.first().oldF, nachtFactor, vExtra)
        add(Punt(eersteTs - 1, oudeStv["sterkte"] ?: 95, oudeStv["timing"] ?: 100, oudeStv["volhoudendheid"] ?: 95))
        history.forEach { step ->
            val ms  = runCatching { java.time.Instant.parse(step.tsUtc).toEpochMilli() }.getOrDefault(0L)
            val stv = DFMapping.toStvMap(step.newD, step.newF, nachtFactor, vExtra)
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

// AggressiviteitsKaart: Stap 1 van de vereenvoudiging.
// Schuif is zichtbaar en opgeslagen, nog niet gekoppeld aan params.
// Stap 2 koppelt de waarde aan S/T/V/refWmd/refWff/refEb tegelijk.
@androidx.compose.runtime.Composable
private fun AggressiviteitsKaart(
    niveau: Int,
    onChanged: (Int) -> Unit
) {
    val labels = mapOf(
        1 to "Zeer voorzichtig",
        2 to "Voorzichtig",
        3 to "Iets voorzichtig",
        4 to "Licht conservatief",
        5 to "Standaard",
        6 to "Licht agressief",
        7 to "Agressief",
        8 to "Zeer agressief",
        9 to "Maximaal agressief"
    )
    val accentKleur = when {
        niveau <= 2 -> androidx.compose.ui.graphics.Color(0xFF4FC3F7)
        niveau <= 4 -> androidx.compose.ui.graphics.Color(0xFF81C784)
        niveau == 5 -> androidx.compose.material3.MaterialTheme.colorScheme.primary
        niveau <= 7 -> androidx.compose.ui.graphics.Color(0xFFFFB74D)
        else        -> androidx.compose.ui.graphics.Color(0xFFE57373)
    }
    androidx.compose.material3.Card(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = accentKleur.copy(alpha = 0.10f)
        )
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.Text(
                    "Agressiviteit",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                androidx.compose.material3.Text(
                    "$niveau  —  ${labels[niveau] ?: "Standaard"}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = accentKleur
                )
            }
            androidx.compose.material3.Slider(
                value = niveau.toFloat(),
                onValueChange = { onChanged(it.toInt()) },
                valueRange = 1f..9f,
                steps = 7,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = accentKleur,
                    activeTrackColor = accentKleur
                )
            )
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                androidx.compose.material3.Text("Voorzichtig",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Text("Agressief",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Text(
                "Verschuift de geleerde S/T/V en frontload-timing — niveau 5 = geen effect",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
