package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import org.json.JSONObject

/**
 * ============================================================================
 * FCL AI-Advisor — PromptBuilder
 * ============================================================================
 *
 * Bouwt de volledige prompt: rolomschrijving + kernprincipe van FCLvNext,
 * de geldige ranges per parameter (uit FclAiAdvisorRanges, dezelfde bron als
 * de validator), anti-hallucinatie-regels, en het verplichte JSON-schema.
 *
 * Filosofie-overeenkomst met de incident-discussie (30/06/2026): de AI mag
 * NOOIT een causale claim doen zonder een logveld te citeren, en moet bij
 * onzekerheid expliciet "onzeker" zeggen i.p.v. een plausibel klinkend
 * antwoord verzinnen.
 */
object FclAiAdvisorPromptBuilder {

    fun buildPrompt(payload: FclDailyReportPayload): String = """
${systemPrompt()}

${safetyAndAntiHallucinationSection()}

${parameterRangesSection()}

${inputDataSection(payload)}

${outputSchemaSection()}
""".trimIndent()

    private fun systemPrompt(): String = """
# FCLvNext Parameter-Adviseur

Je analyseert één dag aan gedrag van FCLvNext, een closed-loop insulinedoseringsalgoritme
(AAPS-plugin, Kotlin), en stelt — uitsluitend als VOORSTEL, nooit als directe actie —
aanpassingen voor aan de geleerde parameters.

## Kernprincipe van FCLvNext (leidend voor al je adviezen)
"Geef alle benodigde insuline zo vroeg mogelijk; verminder frontload alleen wanneer een
hypo volgt op een grote frontload met weinig vervolgdoses." Een voorstel dat dit principe
tegenspreekt zonder concreet bewijs (een hypo of overshoot in de data) moet je NIET doen.

## Wat je WEL mag
- Eén voorstel per parameter uit de lijst in PARAMETER-RANGES, met reden + bewijs.
- Een voorstel weglaten als je niet voldoende bewijs hebt — minder voorstellen met hoge
  zekerheid is beter dan veel voorstellen met lage zekerheid.

## Wat je NIET mag
- Geen parameter buiten het opgegeven bereik voorstellen.
- Geen IOB, piek of glucosewaarden zelf herberekenen — gebruik alleen de cijfers in INPUT DATA.
- Geen toekomstige metingen verzinnen.
- Geen parameter aanpassen die niet in de lijst staat.
""".trimIndent()

    private fun safetyAndAntiHallucinationSection(): String = """
## ANTI-HALLUCINATIE REGELS (verplicht)
1. Elke "reason" MOET minstens één concreet cijfer uit INPUT DATA citeren (tijdstip, bg,
   bolus, iobRatio, avgTimeToPeakMin, avgOvershootAfterPeakMmol, avgPredictionErrorMmol,
   of een learnerEvent-regel). Een reden zonder cijfer is ongeldig.
2. Als de data voor een parameter onvoldoende is (bijv. geen notable episodes, geen
   learner-events erover), doe dan GEEN voorstel voor die parameter.
3. Geef nooit confidence > 0.85 tenzij minstens twee onafhankelijke databronnen
   (notableEpisodes ÉN learnerEventsSummary) hetzelfde patroon bevestigen.
4. Als `notableEpisodes` leeg is, mag je geen voorstel doen dat een acuut incident als
   bewijs aanhaalt — gebruik dan alleen TIR/hypo-trends, en wees terughoudender (confidence ≤ 0.6).
""".trimIndent()

    private fun parameterRangesSection(): String = """
## PARAMETER-RANGES (enige toegestane parameters, enige toegestane bereiken)
${FclAiAdvisorRanges.rangesAsPromptBlock()}

Een voorstel met een waarde buiten dit bereik wordt automatisch verworpen — verspil er
geen voorstel aan.
""".trimIndent()

    private fun inputDataSection(payload: FclDailyReportPayload): String {
        val json = JSONObject().apply {
            put("dateUtc", payload.dateUtc)
            put("periodHours", payload.periodHours)
            put("timeInRangePct", payload.timeInRangePct)
            put("hypoCount", payload.hypoCount)
            put("hypoMinutesTotal", payload.hypoMinutesTotal)
            put("avgTimeToPeakMin", payload.avgTimeToPeakMin ?: JSONObject.NULL)
            put("avgOvershootAfterPeakMmol", payload.avgOvershootAfterPeakMmol ?: JSONObject.NULL)
            put("avgPredictionErrorMmol", payload.avgPredictionErrorMmol ?: JSONObject.NULL)
            put("activeParams", JSONObject().apply {
                payload.activeParams.forEach { (k, v) ->
                    put(k, JSONObject().apply {
                        put("active", v.active)
                        put("default", v.default)
                        put("src", v.src)
                    })
                }
            })
            put("learnerEventsSummary", payload.learnerEventsSummary)
            put("notableEpisodes", payload.notableEpisodes)
        }
        return """
# INPUT DATA

```json
${json.toString(2)}
```
""".trimIndent()
    }

    private fun outputSchemaSection(): String = """
# OUTPUT (uitsluitend JSON, geen andere tekst)

```json
{
  "suggestions": [
    {
      "param": "earlyBoostFactor",
      "currentValue": 1.8183,
      "proposedValue": 1.65,
      "confidence": 0.74,
      "reason": "Concrete onderbouwing met minstens één cijfer uit INPUT DATA.",
      "evidenceFields": ["notableEpisodes[0]", "learnerEventsSummary"]
    }
  ]
}
```

Als er geen verantwoorde voorstellen zijn: `{ "suggestions": [] }`.
Geen markdown, geen uitleg buiten de JSON.
""".trimIndent()
}
