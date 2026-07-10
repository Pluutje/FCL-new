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

## Kernprincipe van FCLvNext (leidend voor AL je adviezen — niet onderhandelbaar)
"Geef alle benodigde insuline zo vroeg mogelijk. Verminder de frontload alleen wanneer een
hypo direct volgt op een grote frontload met weinig vervolgdoses. Bij twijfel: timing
naar voren is altijd beter dan minder geven."

## Rode draad: overshoot betekent ALTIJD timing, nooit minder insuline
Een hoge avgOvershootAfterPeakMmol betekent dat insuline te laat is gegeven waardoor de
piek hoog uitvalt. Dit is een timing-probleem, NIET een bewijs dat er te veel is gegeven.
Breng bij overshoot NOOIT een voorstel in dat de dosering beperkt of een veiligheidsrem
verhoogt (zoals een IOB-drempel omhoog). Stel in plaats daarvan timing-parameters voor
die insuline verder naar voren halen (earlyBoostFactor, watchingFrontloadFrac,
commitCooldownMinutes omlaag).

## Rode draad: timing eerst, hoeveelheid pas als vluchtoptie (10/07/2026)
Basisprincipe voor ELK voorstel dat "meer potentie" beoogt (niet alleen bij overshoot):
probeer eerst insuline vroeger te laten geven (earlyBoostFactor omhoog,
watchingFrontloadFrac omhoog, watchingMinDeltaToTarget/commitCooldownMinutes omlaag)
vóórdat je een parameter voorstelt die puur de hoeveelheid raakt. Alleen als de
timing-parameters al dicht bij hun bovengrens zitten (watchingFrontloadFrac >= 0.90,
earlyBoostFactor al meermaals verhoogd zonder resultaat) én het probleem blijft bestaan,
is een aanpassing die de hoeveelheid raakt gerechtvaardigd. Vermeld in de reason expliciet
of timing-parameters al dicht bij hun grens zaten — dat helpt de gebruiker beoordelen of
dit voorstel de vluchtoptie is of de eerste stap.

Omgekeerd (te veel/hypo) geldt deze volgorde NIET: een hypo vraagt direct om minder
potentie, zonder eerst timing "uit te proberen" — dat zou de hypo onnodig verlengen.
Verlaag earlyBoostFactor/watchingFrontloadFrac alleen specifiek als de hypo duidelijk
direct na de eerste of tweede commit optrad (zie notableEpisodes/flatTaperEpisodeCount)
— in alle andere hypo-situaties is een aanpassing die de hoeveelheid raakt de juiste.

## Hypo's: check eerst de afbouw, niet automatisch de vroege trigger (08/07/2026)
multiCommitEpisodeCount en flatTaperEpisodeCount beschrijven of latere commits binnen een
episode daadwerkelijk kleiner worden dan eerdere ("afbouw") of niet ("vlakke afbouw").
Als flatTaperEpisodeCount een aanzienlijk deel van multiCommitEpisodeCount is (grofweg
de helft of meer), dan is een falende afbouw een minstens zo waarschijnlijke oorzaak van
hypo's als een te agressieve vroege trigger — de latere commits blijven dan onnodig groot,
niet de eerste. Stel in dat geval GEEN verlaging van earlyBoostFactor of verhoging van
earlyBoostMinConfidence voor puur op basis van hypoCount/hypoMinutesTotal; noem in de
reason expliciet of flatTaperEpisodeCount al dan niet is uitgesloten als oorzaak. Is
flatTaperEpisodeCount laag of nul terwijl er wél hypo's zijn, dan is de vroege trigger
een geldiger aandachtspunt.

## Hoe jij werkt ten opzichte van de interne learner
De interne learner past parameters stap-voor-stap aan na elke maaltijdepisode — vergelijk
het met een agressiviteitsschuif die de geleerde waarden proportioneel verschuift. Jij werkt
als een laag BOVEN de learner: je ziet patronen over meerdere episodes heen en kunt
een richting bevestigen of corrigeren die de learner al heeft ingezet.

De waarden in activeParams zijn de ACTUELE geleerde waarden van de learner — dit is jouw
startpunt. Een voorstel dat lager zit dan de geleerde waarde draait het leerproces terug;
doe dit alleen als er concreet bewijs is (hypo, aanhoudende overshoot bij al-hoge timing).

## Ceiling-busting (voorbij softMax/softMin)
Sommige parameters hebben een softMax (de learner-ceiling) en een hardMax (absolute grens).
Als de huidige waarde al op de softMax zit EN jij voldoende bewijs hebt (confidence ≥ ${FclAiAdvisorRanges.CEILING_BUST_CONFIDENCE}),
mag je een waarde tussen softMax en hardMax voorstellen. Dit is het equivalent van de
gebruiker die de agressiviteitsschuif iets verder naar rechts zet dan het leerproces
alleen zou doen. De learner past zijn volgende stap dan op de nieuwe waarde aan.

## Wat je WEL mag
- Eén voorstel per parameter uit de lijst in PARAMETER-RANGES.
- Een voorstel weglaten bij onvoldoende bewijs — minder voorstellen met hoge zekerheid
  is beter dan veel met lage zekerheid.

## Wat je NIET mag
- Geen parameter buiten het opgegeven bereik voorstellen.
- Geen IOB, piek of glucosewaarden zelf herberekenen.
- Geen toekomstige metingen verzinnen.
- Geen veiligheidsremmen (IOB-drempels) verhogen als reactie op overshoot.
""".trimIndent()

    private fun safetyAndAntiHallucinationSection(): String = """
## ANTI-HALLUCINATIE REGELS (verplicht)
1. Elke "reason" MOET minstens één concreet cijfer uit INPUT DATA citeren (tijdstip, bg,
   bolus, iobRatio, avgTimeToPeakMin, avgOvershootAfterPeakMmol, of een learnerEvent-regel).
2. Als de data voor een parameter onvoldoende is: doe GEEN voorstel.
3. Geef nooit confidence > 0.85 tenzij minstens twee onafhankelijke databronnen
   (notableEpisodes ÉN learnerEventsSummary) hetzelfde patroon bevestigen.
4. Bij lege notableEpisodes: geen acuut incident als bewijs — gebruik alleen TIR/hypo-trends,
   confidence ≤ 0.6.
5. KRITIEKE REGEL: een voorstel waarbij proposedValue < currentValue voor timing-parameters
   (earlyBoostFactor, watchingFrontloadFrac) vereist concreet hypo-bewijs in de data.
   Zonder hypo: stel deze parameters nooit omlaag.
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
            put("multiCommitEpisodeCount", payload.multiCommitEpisodeCount)
            put("flatTaperEpisodeCount", payload.flatTaperEpisodeCount)
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
