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

        // BUGFIX (23/07/2026): effectHourLabel is een BEREIK-notatie
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

                // Teken normaliseren op basis van direction (23/07/2026) —
                // i.p.v. het teken van suggestedShiftPct zelf te vertrouwen. Het
                // model kan in theorie een positief getal geven bij LOWER (als
                // "percentage" i.p.v. "signed shift" geïnterpreteerd) — direction
                // is de expliciete, verplichte enum-waarde en dus de betrouwbaardere
                // bron. Nodig geworden nu suggestedShiftPct ook als spreidingsinput
                // voor buururen dient (zie applySpread hieronder): een inconsistent
                // teken zou daar een verkeerde kant op spreiden.
                // Geleidelijke-opbouw-fix (23/07/2026): de AI kreeg al de
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

    // ── Geleidelijke opbouw/afbouw rondom het probleempunt (23/07/2026) ──
    // De AI beoordeelt elk uur onafhankelijk, wat een grillige sprong tussen
    // buururen kan geven (bv. wel -15% om 00:00, niets om 01:00, dan weer -8%
    // om 02:00). Zelfde probleem, zelfde oplossing als de bestaande
    // regel-gebaseerde adviseur (zie gaussWeightForOffset/computeSpreadAdvice in
    // Advisorscreen.kt): een Gauss-gewicht vult uren aan die het model zelf niet
    // beoordeeld heeft.
    //
    // HERZIEN (30/07/2026, op verzoek — "de sprong tussen 23:00 en 00:00
    // is er nog steeds"): de kdoc hierboven beloofde "vloeiend", maar de
    // implementatie vulde tot nu toe UITSLUITEND uren aan waar de AI zelf geen
    // oordeel over gaf — twee buururen die de AI allebei ONAFHANKELIJK van
    // elkaar beoordeelde (bv. 23:00 -1%, 00:00 -4%) werden nooit onderling
    // gladgestreken. Dat was dus een TWEEDE, nooit gedichte opening voor
    // precies dezelfde sprong als de 29/07-afrondingsfix (roundToStep() →
    // cleanPrecision() in FclNightBasalAutoAdjuster.kt) al deels oploste. Fix:
    // blendCoveredHour() hieronder mengt nu ook een eigen AI-oordeel voorzichtig
    // (30%) met het Gauss-gewogen gemiddelde van de buururen — het eigen oordeel
    // blijft dominant (70%), maar een geïsoleerde uitschieter naast rustige
    // buururen wordt niet langer onaangeroerd doorgezet. Bij een geblende uur
    // toont de UI het gewoon als het definitieve advies; de kdoc-belofte
    // "afgezwakt door een buurvenster" hieronder klopt dus niet meer letterlijk
    // en is bijgewerkt.
    //
    // DERDE HERZIENING (24/08/2026, op verzoek — "22:00 moet ook worden
    // meegenomen, met een vloeiend verloop, ongeacht waar/hoe lang het blok
    // duurt"): de asymmetrische, DIA-gekoppelde versie hierboven (30/07)
    // bleek niet meer de bedoeling — expliciet nagevraagd, en het antwoord
    // was dat niet de DIA maar de veel kortere piektijd/halfwaardetijd van de
    // insuline (bv. ~40 min bij Lyumjev) bepalend is, én dat de kernwens
    // eigenlijk is: NOOIT een sprong van 0% direct naar het volle kern-
    // percentage in één stap tussen buururen — zoals in de screenshot van
    // 24/8 gebeurde toen alleen 23:00 (-5%) een aanpassing kreeg en het uur
    // ervoor onaangeroerd bleef.
    // Vervangen door een vaste, SYMMETRISCHE, korte trap: 1u afstand → 50%,
    // 2u afstand → 20%, 3u+ afstand → 0%. Getoetst tegen beide door de
    // gebruiker gegeven voorbeelden: kern om 23:00 (-5%) geeft 22:00 exact
    // -2,5% (50%); kern om 01:00 (-5%) geeft 00:00 -2,5% (50%, 1u), 23:00 -1%
    // (20%, 2u) en 22:00 blijft ongewijzigd (0%, 3u) — beide kloppen exact.
    // Symmetrisch toegepast (zelfde tabel voor zowel anticipatie als nasleep)
    // zodat óók het einde van een aanpassingsblok nu op dezelfde manier
    // vloeiend afbouwt i.p.v. de vorige, kortere en asymmetrische
    // nasleep-tabel (was ±1..3u met 0,55/0,20/0,08). De BASAL_SHIFT_MINUTES=
    // 75min-correctie in Nightwindowanalyzer.kt blijft ongewijzigd (dat is een
    // vaste onset-vertraging, geen instelbare marge) — deze trap werkt daar
    // bovenop.
    private fun gaussWeight(offset: Int): Double = when (kotlin.math.abs(offset)) {
        0 -> 1.0
        1 -> 0.5
        2 -> 0.2
        else -> 0.0
    }

    private data class SpreadCore(
        val hour: Int,
        val signedShiftPct: Double,
        val confidence: Double,
        val label: String
    )

    // 30/07/2026 — hoeveel gewicht een eigen AI-oordeel behoudt bij het
    // blenden met de buururen hieronder. Bewust ruim aan de kant van "het
    // eigen oordeel telt het meest" (70/30) — dit mag nooit een uur dat de AI
    // zelf sterk en met citaten onderbouwde laten verdwijnen in het gemiddelde
    // van zwakkere buren, alleen de hardste sprongen afvlakken.
    private const val OWN_JUDGMENT_WEIGHT = 0.7
    private const val NEIGHBOR_BLEND_WEIGHT = 1.0 - OWN_JUDGMENT_WEIGHT

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

        // ── Stap 1: eigen AI-oordelen licht blenden met hun buururen ────────
        // (30/07/2026) — zie de herziene kdoc bij gaussWeight() hierboven.
        // Alleen toegepast als er daadwerkelijk buur-kernen binnen bereik zijn
        // (weightSum>0); anders blijft het oorspronkelijke, ongewijzigde
        // voorstel gewoon staan.
        val blendedCore = rawSuggestions.map { s ->
            val hour = s.hourLabel.substringBefore(":").toIntOrNull() ?: return@map s

            var weightedNeighborSum = 0.0
            var weightSum = 0.0
            val neighborSources = mutableListOf<String>()
            for (core in cores) {
                if (core.hour == hour) continue   // alleen ándere kernen tellen als "buur"
                var offset = hour - core.hour
                if (offset > 12) offset -= 24
                if (offset < -12) offset += 24
                val w = gaussWeight(offset)
                if (w > 0.0) {
                    weightedNeighborSum += w * core.signedShiftPct
                    weightSum += w
                    neighborSources += core.label
                }
            }
            if (weightSum <= 0.0) return@map s   // geen buren binnen bereik — onaangeroerd

            val neighborAvg = weightedNeighborSum / weightSum
            val blended = OWN_JUDGMENT_WEIGHT * s.suggestedShiftPct + NEIGHBOR_BLEND_WEIGHT * neighborAvg
            // Te verwaarlozen verschil (<0,3 procentpunt) niet apart vermelden —
            // dan was de AI toch al vrijwel in lijn met de buururen.
            if (kotlin.math.abs(blended - s.suggestedShiftPct) < 0.3) return@map s

            s.copy(
                direction = if (blended < 0) "LOWER" else "HIGHER",
                suggestedShiftPct = blended,
                reasonNl = s.reasonNl + " (licht bijgesteld richting buururen " +
                    neighborSources.distinct().joinToString(", ") + " voor een vloeiender verloop.)"
            )
        }

        // ── Stap 2: overgebleven, nog niet beoordeelde uren aanvullen ───────
        // (ongewijzigd t.o.v. vóór 30/07/2026, behalve dat gaussWeight()
        // hierboven nu asymmetrisch is — zie kdoc daar voor de anticipatie-
        // uitbreiding naar 4u terug in de tijd.)
        val derived = mutableListOf<NightBasalSuggestion>()
        for (targetHour in 0..23) {
            if (targetHour in coveredHours) continue   // eigen AI-oordeel — al verwerkt in stap 1

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

            // Taper-fix (24/07/2026): delen door weightSum (een GEMIDDELDE
            // van de omliggende kernen) bleek geen taper op te leveren — die
            // genormaliseerde waarde bleef altijd rond dezelfde orde van grootte
            // als de kernen zelf, ongeacht de afstand (dus bv. -5% op zowel 1 als
            // 3 uur van de kern, terwijl de tekst wél "geleidelijke overloop"
            // beloofde). Wat wél echt afneemt met de afstand: de SOM van elk
            // kern-aandeel maal zijn eigen Gauss-gewicht, zonder die te delen door
            // het totaalgewicht — verder van de kern(en) af, hoe kleiner elke
            // bijdrage, hoe dichter de som vanzelf naar nul zakt. Gecapt op de
            // sterkste bijdragende kern zelf, zodat meerdere overlappende kernen de
            // uitkomst nooit verder kunnen opstuwen dan wat één kern al aangaf.
            val maxCoreMagnitude = cores
                .filter { core ->
                    var o = targetHour - core.hour
                    if (o > 12) o -= 24
                    if (o < -12) o += 24
                    gaussWeight(o) > 0.0
                }
                .maxOfOrNull { kotlin.math.abs(it.signedShiftPct) } ?: 0.0
            val blendedShift = weightedShiftSum.coerceIn(-maxCoreMagnitude, maxCoreMagnitude)
            if (kotlin.math.abs(blendedShift) < 1.0) continue   // te klein om zinvol te tonen

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

        // Nacht-volgorde i.p.v. kale numerieke sort (23/07/2026) — zelfde
        // reden/aanpak als de rule-based lijst in Advisorscreen.kt: een
        // nachtvenster loopt over middernacht heen, dus 22:00/23:00 horen vóór
        // 00:00 te staan, niet erna.
        return (blendedCore + derived)
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