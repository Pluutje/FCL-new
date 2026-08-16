package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import org.json.JSONObject

/**
 * ============================================================================
 * FCL AI-Advisor — ResponseParser
 * ============================================================================
 *
 * Parseert de JSON van het model en valideert elk voorstel HARD tegen
 * FclAiAdvisorRanges. Een voorstel buiten bereik of met een onbekende
 * parameternaam wordt VERWORPEN (rejected=true + reden), niet geclampt —
 * een waarde buiten bereik duidt op een modelfout die zichtbaar moet blijven,
 * niet stilzwijgend gecorrigeerd.
 */
object FclAiAdvisorResponseParser {

    fun parse(rawResponse: String, payload: FclDailyReportPayload): AiAdvisorRunResult {
        val cleaned = stripMarkdownFences(rawResponse)
        val repaired = tryRepairTruncatedJson(cleaned)
        val json = try {
            JSONObject(repaired)
        } catch (t: Throwable) {
            // Geef de gebruiker een leesbare melding, niet een technische Java-stacktrace.
            val userMsg = when {
                cleaned.contains("\"suggestions\"") && cleaned.length < 200 ->
                    "Het model gaf een te kort antwoord terug — mogelijk token-limiet bereikt. " +
                        "Probeer 'Nu vernieuwen' opnieuw."
                cleaned.startsWith("{") ->
                    "Het antwoord van de AI was onvolledig (afgeknipte JSON). " +
                        "Technisch: ${t.message?.take(80)}"
                cleaned.isBlank() ->
                    "Het model gaf een leeg antwoord — controleer de API-sleutel en het model-veld."
                else ->
                    "Kon het antwoord niet verwerken. Probeer opnieuw of controleer het model-veld. " +
                        "Technisch: ${t.message?.take(80)}"
            }
            return AiAdvisorRunResult(
                generatedAtUtc = payload.dateUtc,
                rawModelResponse = rawResponse,
                suggestions = emptyList(),
                parseError = userMsg
            )
        }

        val suggestionsArr = json.optJSONArray("suggestions")
            ?: return AiAdvisorRunResult(
                generatedAtUtc = payload.dateUtc,
                rawModelResponse = rawResponse,
                suggestions = emptyList(),
                parseError = "Geen 'suggestions'-array in antwoord"
            )

        val results = mutableListOf<AiParamSuggestion>()
        for (i in 0 until suggestionsArr.length()) {
            val obj = suggestionsArr.optJSONObject(i) ?: continue
            results += validateOne(obj, payload)
        }

        // 21/07/2026 — zie kdoc bij AiAdvisorRunResult.advisoryNoteNl:
        // puur informatief, geen voorstel, dus geen validatie tegen
        // FclAiAdvisorRanges nodig — alleen leeg/blank normaliseren naar null.
        val advisoryNote = json.optString("advisoryNoteNl", "").trim().ifBlank { null }

        return AiAdvisorRunResult(
            generatedAtUtc = payload.dateUtc,
            rawModelResponse = rawResponse,
            suggestions = results,
            parseError = null,
            advisoryNoteNl = advisoryNote
        )
    }

    private fun validateOne(obj: JSONObject, payload: FclDailyReportPayload): AiParamSuggestion {
        val param = obj.optString("param", "")
        val proposedValue = obj.optDouble("proposedValue", Double.NaN)
        val confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        val reason = obj.optString("reason", "")
        val evidence = obj.optJSONArray("evidenceFields")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } ?: emptyList()

        val currentValueFromPayload = payload.activeParams[param]?.active
        val currentValue = currentValueFromPayload ?: obj.optDouble("currentValue", Double.NaN)

        val spec = FclAiAdvisorRanges.byKey[param]

        fun rejected(reasonNl: String) = AiParamSuggestion(
            param = param, currentValue = currentValue, proposedValue = proposedValue,
            confidence = confidence, reasonNl = reason, evidenceFields = evidence,
            rejected = true, rejectionReasonNl = reasonNl
        )

        if (spec == null) return rejected("Onbekende parameter '$param' — niet in toegestane lijst")
        if (proposedValue.isNaN()) return rejected("Ontbrekende of ongeldige proposedValue")
        // Confidence-vloer (18/07/2026): voorstellen onder deze drempel worden
        // nooit als actionable kaart getoond, ongeacht hoe goed de rest van de
        // validatie eruitziet — een correct-onderbouwd voorstel met lage confidence
        // is nog steeds een voorstel waar het model zelf niet zeker van is.
        if (confidence < FclAiAdvisorRanges.MIN_SUGGESTION_CONFIDENCE)
            return rejected(
                "Confidence (${(confidence * 100).toInt()}%) onder de drempel van " +
                    "${(FclAiAdvisorRanges.MIN_SUGGESTION_CONFIDENCE * 100).toInt()}%"
            )
        if (!FclAiAdvisorRanges.isInRange(spec, proposedValue))
            return rejected("Waarde $proposedValue buiten absoluut bereik [${spec.min}–${spec.max}]")
        if (currentValue.isNaN())
            return rejected("Geen bekende huidige waarde om tegen te valideren")

        // Ceiling-bust check: is de AI voorbij de softMax/softMin gegaan?
        // Dat mag alleen bij hoge confidence én de learner al op de soft-grens.
        if (!FclAiAdvisorRanges.isCeilingBustAllowed(spec, currentValue, proposedValue, confidence)) {
            val softNote = if (proposedValue > spec.softMax)
                "softMax=${spec.softMax} — learner nog niet op ceiling (huidig: ${"%.4f".format(currentValue)})"
            else
                "softMin=${spec.softMin} — learner nog niet op floor (huidig: ${"%.4f".format(currentValue)})"
            return rejected("Ceiling-bust niet toegestaan: $softNote of confidence (${(confidence * 100).toInt()}%) < ${(FclAiAdvisorRanges.CEILING_BUST_CONFIDENCE * 100).toInt()}%")
        }
        // Stap te groot: inkorten tot max toegestane stap in de juiste richting
        // (niet afwijzen — als de richting juist is, is de max-toegestane stap beter dan niets).
        if (!FclAiAdvisorRanges.withinMaxStep(spec, currentValue, proposedValue)) {
            val maxStep = spec.maxRelativeStep * kotlin.math.abs(currentValue)
            val direction = if (proposedValue > currentValue) 1.0 else -1.0
            val clamped = (currentValue + direction * maxStep).coerceIn(spec.min, spec.max)
            val clampNote = " [Stap ingekort van ${"%.4f".format(proposedValue)} naar ${"%.4f".format(clamped)} (max ${(spec.maxRelativeStep * 100).toInt()}% per voorstel)]"
            return AiParamSuggestion(
                param = param,
                currentValue = currentValue,
                proposedValue = clamped,
                confidence = confidence,
                reasonNl = reason + clampNote,
                evidenceFields = evidence,
                rejected = false,
                rejectionReasonNl = null
            )
        }
        if (reason.isBlank())
            return rejected("Geen onderbouwing opgegeven")
        if (evidence.isEmpty())
            return rejected("Geen evidenceFields opgegeven — onderbouwing niet te verifiëren")

        return AiParamSuggestion(
            param = param, currentValue = currentValue, proposedValue = proposedValue,
            confidence = confidence, reasonNl = reason, evidenceFields = evidence,
            rejected = false, rejectionReasonNl = null
        )
    }

    private fun stripMarkdownFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter("\n").substringBeforeLast("```").trim()
        }
        return t
    }

    /**
     * Probeert een afgeknipte JSON-string herstelbaar te maken door ontbrekende
     * sluitende haakjes toe te voegen. Dit is een best-effort repair voor het geval
     * het model zijn antwoord halverwege heeft afgekapt (bijv. bij Gemini met een te
     * klein MAX_TOKENS-budget). Geeft de originele string terug als reparatie niet
     * helpt — de JSONObject-parser geeft dan een normale foutmelding.
     *
     * Aanpak: tel open { en [ die nog niet gesloten zijn en voeg de benodigde sluiters toe.
     * Ongeldige trailing komma's (,]) worden ook opgeruimd.
     */
    private fun tryRepairTruncatedJson(s: String): String {
        if (s.isBlank()) return s
        val sb = StringBuilder(s.trimEnd().trimEnd(','))
        val stack = ArrayDeque<Char>()
        var inString = false
        var escape = false
        for (c in sb) {
            when {
                escape           -> escape = false
                c == '\\'        -> if (inString) escape = true
                c == '"'         -> inString = !inString
                inString         -> { /* skip */ }
                c == '{' || c == '[' -> stack.addLast(c)
                c == '}' && stack.lastOrNull() == '{' -> stack.removeLast()
                c == ']' && stack.lastOrNull() == '[' -> stack.removeLast()
            }
        }
        // Sluit eventueel een open string
        if (inString) sb.append('"')
        // Sluit resterende open objecten/arrays in omgekeerde volgorde
        while (stack.isNotEmpty()) {
            sb.append(if (stack.removeLast() == '{') '}' else ']')
        }
        return sb.toString()
    }
}