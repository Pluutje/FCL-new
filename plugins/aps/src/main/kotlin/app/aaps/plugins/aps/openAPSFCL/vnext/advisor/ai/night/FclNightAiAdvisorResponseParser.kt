package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import org.json.JSONObject

/**
 * ============================================================================
 * FCL Nacht-AI-Adviseur — ResponseParser
 * ============================================================================
 *
 * Eenvoudiger dan FclAiAdvisorResponseParser (dag-adviseur): geen
 * FclAiAdvisorRanges-achtige harde grenzen om tegen te valideren — dit is
 * puur adviserende tekst, er wordt niets automatisch toegepast. Validatie
 * hier is dus alleen "is het antwoord bruikbaar/leesbaar", niet "past dit
 * binnen een veilige range".
 */
object FclNightAiAdvisorResponseParser {

    private val VALID_DIRECTIONS = setOf("LOWER", "HIGHER")

    fun parse(rawResponse: String, payload: FclNightReportPayload): NightAiAdvisorRunResult {
        val cleaned = stripMarkdownFences(rawResponse)
        val json = try {
            JSONObject(cleaned)
        } catch (t: Throwable) {
            val userMsg = when {
                cleaned.isBlank() ->
                    "Het model gaf een leeg antwoord — controleer de API-sleutel en het model-veld."
                else ->
                    "Kon het antwoord niet verwerken. Technisch: ${t.message?.take(80)}"
            }
            return NightAiAdvisorRunResult(
                generatedAtUtc = payload.generatedAtUtc,
                rawModelResponse = rawResponse,
                suggestions = emptyList(),
                parseError = userMsg
            )
        }

        val summary = json.optString("summaryNl", "").trim().ifBlank { null }
        val suggestionsArr = json.optJSONArray("suggestions")

        // BUGFIX (23/07/2026, Ecko): effectHourLabel is een BEREIK-notatie
        // ("00:00–01:00", zie Nightwindowanalyzer.kt), maar de AI levert
        // hourLabel als kaal "00:00" (zie het schema in PromptBuilder). Die
        // twee matchten dus NOOIT — elk AI-voorstel viel terug op de 0.0-default
        // hieronder, vandaar de aanhoudende "nu 0.00 U/h" bij de eigen
        // AI-oordelen (de afgeleide/gespreide uren gebruikten toevallig al een
        // Int-lookup en hadden dit probleem niet). Fix: opzoeken op het
        // geparste uur-getal, net als applySpread() hieronder al deed.
        val hourlyByHour = payload.hourlyData.associateBy { it.effectHour }

        val suggestions = mutableListOf<NightBasalSuggestion>()
        if (suggestionsArr != null) {
            for (i in 0 until suggestionsArr.length()) {
                val obj = suggestionsArr.optJSONObject(i) ?: continue
                val hourLabel = obj.optString("hourLabel", "").trim()
                val direction = obj.optString("direction", "").trim().uppercase()
                val evidence = obj.optJSONArray("evidenceFields")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
                } ?: emptyList()
                val reason = obj.optString("reasonNl", "").trim()

                // Stille skip i.p.v. harde reject (11): dit is puur advies, geen
                // toe te passen waarde — een onbruikbaar los voorstel hoeft de
                // rest van het rapport niet te blokkeren, in tegenstelling tot
                // de dag-adviseur waar een afgewezen voorstel zichtbaar moet
                // blijven omdat de gebruiker het anders had kunnen goedkeuren.
                if (hourLabel.isBlank() || direction !in VALID_DIRECTIONS || reason.isBlank() || evidence.isEmpty()) continue

                val hourInt = hourLabel.substringBefore(":").toIntOrNull()
                val currentBasal = hourInt?.let { hourlyByHour[it]?.currentBasalUph }
                    ?: obj.optDouble("currentBasalUph", 0.0)

                // Teken normaliseren op basis van direction (23/07/2026, Ecko) —
                // i.p.v. het teken van suggestedShiftPct zelf te vertrouwen. Het
                // model kan in theorie een positief getal geven bij LOWER (als
                // "percentage" i.p.v. "signed shift" geïnterpreteerd) — direction
                // is de expliciete, verplichte enum-waarde en dus de betrouwbaardere
                // bron. Nodig geworden nu suggestedShiftPct ook als spreidingsinput
                // voor buururen dient (zie applySpread hieronder): een inconsistent
                // teken zou daar een verkeerde kant op spreiden.
                // Geleidelijke-opbouw-fix (23/07/2026, Ecko): de AI kreeg al de
                // instructie om een bescheiden EERSTE STAP te geven i.p.v. de volledige
                // geschatte correctie in één keer, maar dat is promptgedrag — geen garantie.
                // Harde klem hier als vangnet, zodat de kaart nooit een sprong als -10%
                // toont naast de regel-gebaseerde adviseur die voor hetzelfde signaal een
                // bescheiden ~3-8% geeft (zie computeAdvisedBasal in Nightwindowanalyzer.kt).
                // Bij een aanhoudend patroon volgt vanzelf een volgende, vergelijkbare stap
                // op een volgende ochtend, omdat currentBasalUph dan het inmiddels
                // aangepaste profiel weerspiegelt.
                val magnitude = kotlin.math.abs(obj.optDouble("suggestedShiftPct", 0.0)).coerceIn(3.0, 8.0)
                val signedShift = if (direction == "LOWER") -magnitude else magnitude

                suggestions += NightBasalSuggestion(
                    hourLabel = hourLabel,
                    direction = direction,
                    currentBasalUph = currentBasal,
                    suggestedShiftPct = signedShift,
                    confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                    reasonNl = reason,
                    evidenceFields = evidence
                )
            }
        }

        return NightAiAdvisorRunResult(
            generatedAtUtc = payload.generatedAtUtc,
            rawModelResponse = rawResponse,
            suggestions = applySpread(suggestions, payload),
            summaryNl = summary,
            parseError = null
        )
    }

    // ── Geleidelijke opbouw/afbouw rondom het probleempunt (23/07/2026, Ecko) ──
    // De AI beoordeelt elk uur onafhankelijk, wat een grillige sprong tussen
    // buururen kan geven (bv. wel -15% om 00:00, niets om 01:00, dan weer -8%
    // om 02:00). Zelfde probleem, zelfde oplossing als de bestaande
    // regel-gebaseerde adviseur (zie gaussWeightForOffset/computeSpreadAdvice in
    // Advisorscreen.kt): een Gauss-gewicht (kern 100%, buur ±1u 55%, ±2u 20%)
    // vult ALLEEN de uren aan die het model zelf niet beoordeeld heeft — een uur
    // waar de AI wél een eigen oordeel over gaf, wordt nooit overschreven of
    // afgezwakt door een buurvenster. Afgeleide (gespreide) uren krijgen een
    // duidelijk gemarkeerde reden/bewijs ("Afgeleid van...") zodat nooit de indruk
    // ontstaat dat de AI dat specifieke uur zelf heeft beoordeeld, en een iets
    // lager vertrouwen (×0.8) omdat er voor dát uur geen directe evidentie is.
    // UITBREIDING (23/07/2026, Ecko): offset ±3 toegevoegd, zelfde als de
    // rule-based gaussWeightForOffset() in Advisorscreen.kt — voorkomt een
    // harde knik aan de rand van het kernbereik (bv. -10% op 22:00 direct
    // gevolgd door 0% op 21:00).
    private fun gaussWeight(offset: Int): Double = when (kotlin.math.abs(offset)) {
        0 -> 1.0
        1 -> 0.55
        2 -> 0.20
        3 -> 0.08
        else -> 0.0
    }

    private data class SpreadCore(
        val hour: Int,
        val signedShiftPct: Double,
        val confidence: Double,
        val label: String
    )

    private fun applySpread(
        rawSuggestions: List<NightBasalSuggestion>,
        payload: FclNightReportPayload
    ): List<NightBasalSuggestion> {
        if (rawSuggestions.isEmpty()) return rawSuggestions

        val hourlyByHour = payload.hourlyData.associateBy { it.effectHour }
        val coveredHours = rawSuggestions
            .mapNotNull { it.hourLabel.substringBefore(":").toIntOrNull() }
            .toSet()

        val cores = rawSuggestions.mapNotNull { s ->
            val hour = s.hourLabel.substringBefore(":").toIntOrNull() ?: return@mapNotNull null
            SpreadCore(hour, s.suggestedShiftPct, s.confidence, s.hourLabel)
        }
        if (cores.isEmpty()) return rawSuggestions

        val derived = mutableListOf<NightBasalSuggestion>()
        for (targetHour in 0..23) {
            if (targetHour in coveredHours) continue   // eigen AI-oordeel — niet aanraken

            var weightedShiftSum = 0.0
            var weightSum = 0.0
            var weightedConfSum = 0.0
            val sources = mutableListOf<String>()

            for (core in cores) {
                var offset = targetHour - core.hour
                if (offset > 12) offset -= 24     // middernacht-overgang, zelfde als computeSpreadAdvice
                if (offset < -12) offset += 24
                val w = gaussWeight(offset)
                if (w > 0.0) {
                    weightedShiftSum += w * core.signedShiftPct
                    weightSum += w
                    weightedConfSum += w * core.confidence
                    sources += core.label
                }
            }
            if (weightSum <= 0.0) continue

            val blendedShift = weightedShiftSum / weightSum
            if (kotlin.math.abs(blendedShift) < 3.0) continue   // te klein om te tonen

            val agg = hourlyByHour[targetHour] ?: continue
            val blendedConf = (weightedConfSum / weightSum * 0.8).coerceIn(0.0, 1.0)
            val distinctSources = sources.distinct().joinToString(", ")

            derived += NightBasalSuggestion(
                hourLabel = agg.effectHourLabel,
                direction = if (blendedShift < 0) "LOWER" else "HIGHER",
                currentBasalUph = agg.currentBasalUph,
                suggestedShiftPct = blendedShift,
                confidence = blendedConf,
                reasonNl = "Geleidelijke overloop van de aanpassing bij $distinctSources, " +
                    "voor een vloeiender profiel rond dat uur.",
                evidenceFields = listOf(
                    "Afgeleid (gespreid) van $distinctSources — geen zelfstandig AI-oordeel voor dit uur"
                )
            )
        }

        // Nacht-volgorde i.p.v. kale numerieke sort (23/07/2026, Ecko) — zelfde
        // reden/aanpak als de rule-based lijst in Advisorscreen.kt: een
        // nachtvenster loopt over middernacht heen, dus 22:00/23:00 horen vóór
        // 00:00 te staan, niet erna.
        return (rawSuggestions + derived)
            .sortedBy { s ->
                val hour = s.hourLabel.substringBefore(":").toIntOrNull() ?: 99
                if (hour < 12) hour + 24 else hour
            }
    }

    private fun stripMarkdownFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter("\n").trim()
            if (t.endsWith("```")) t = t.substringBeforeLast("```").trim()
        }
        return t
    }
}
