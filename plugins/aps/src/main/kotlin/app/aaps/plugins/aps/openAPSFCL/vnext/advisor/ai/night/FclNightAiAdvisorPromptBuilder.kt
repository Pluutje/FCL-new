package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * FCL Nacht-AI-Adviseur — PromptBuilder
 * ============================================================================
 *
 * Bewust een STUK eenvoudiger dan FclAiAdvisorPromptBuilder (de dag-
 * adviseur): geen FclAiAdvisorRanges-achtige per-parameter grenzen nodig,
 * want er is niets om automatisch toe te passen — dit is puur tekstueel
 * advies over WELKE uren en IN WELKE RICHTING het basaalprofiel er,
 * volgens het model, mogelijk niet meer bij past.
 *
 * Scope is bewust smal en expliciet: ALLEEN het basaalprofiel gedurende de
 * nacht. FCLvNext zelf regelt geen basaal (alleen SMB-bolussen) — dit is
 * dus het enige AI-adviespad in de hele codebase dat wél over basaal mag
 * gaan, precies omdat de dag-adviseur dat expliciet verboden is (zie
 * FclAiAdvisorPromptBuilder: "Basale instellingen NOOIT noemen").
 */
object FclNightAiAdvisorPromptBuilder {

    fun buildPrompt(payload: FclNightReportPayload): String = """
${systemPrompt()}

${antiHallucinationSection()}

${inputDataSection(payload)}

${outputSchemaSection()}
""".trimIndent()

    private fun systemPrompt(): String = """
# Nacht-Basaal-Adviseur

Je analyseert meerdere nachten aan CGM/IOB-gedrag van iemand met type 1 diabetes die
een closed-loop systeem gebruikt (AndroidAPS + FCLvNext). Je taak is uitsluitend: per
klok-uur in de nacht beoordelen of het ingestelde basaalprofiel structureel te hoog of
te laag lijkt te staan, gebaseerd op het patroon van bloedglucose (BG) en insulin-on-
board (IOB) in dat uur, gemiddeld over meerdere nachten.

## Context die je moet begrijpen
- FCLvNext (het dosis-algoritme) regelt UITSLUITEND bolus-achtige SMB-doses op basis
  van maaltijden en actuele afwijkingen. Het basaalprofiel (de vaste U/h per uur) wordt
  volledig los daarvan door de gebruiker zelf ingesteld in AndroidAPS en NOOIT door
  FCLvNext aangepast.
- "IOB" hier is het REKENKUNDIGE insulin-on-board-model van AAPS — een dalende of
  negatieve IOB in een venster betekent dat er, rekenkundig, minder insuline actief is
  dan het model verwacht op basis van de laatst toegediende doses en de basale afgifte.
  Een aanhoudend negatieve of dalende IOB bij een verder stabiele BG duidt vaak (niet
  altijd — zie hieronder) op een te hoog ingesteld basaal in de uren ervoor: er wordt
  basaal "verondersteld" aanwezig te zijn dat feitelijk al is opgebruikt/overschat.
- Andersom: een structureel STIJGENDE BG bij een vlakke of oplopende IOB in een uur
  zonder maaltijdactiviteit kan duiden op een te LAAG ingesteld basaal in dat uur.

## Wat je NIET moet doen
- Geen uitspraken over losse, individuele nachten — alleen patronen die zich over
  meerdere nachten herhalen (zie nightsCount per uur in de databijlage) zijn de moeite
  van een advies waard. Eén incidentele afwijking is ruis (sensor, restjes van een
  laat avondmaal, activiteit), geen basaal-signaal.
- Geen paniek bij kleine afwijkingen — een gemiddelde bgSlopePerHour binnen ±0,3 mmol/L
  per uur en een gemiddelde iobDelta binnen ±0,1 zijn normale ruis, geen aanleiding
  voor een suggestie.
- Doe NOOIT een uitspraak over een uur waar nightsCount < 3 — te weinig data om een
  structureel patroon van toevalstreffers te onderscheiden.
""".trimIndent()

    private fun antiHallucinationSection(): String = """
## Verplichte werkwijze — voorkom verzonnen conclusies
1. Citeer voor ELKE suggestie minstens twee concrete velden uit de databijlage
   (bijv. "avgIobDelta -0.22 over 8 nachten, avgBg 6.1 stabiel t.o.v. target 6.5") in
   evidenceFields — een suggestie zonder citaten uit de data wordt genegeerd.
2. Als het patroon zwak, tegenstrijdig, of gebaseerd op te weinig nachten is: laat dat
   uur gewoon weg uit suggestions. Het is volkomen acceptabel om een lege
   suggestions-lijst terug te geven met een summaryNl die dat toelicht — verzin nooit
   een suggestie om iets te melden te hebben.
3. confidence is een eerlijke inschatting: gebruik 0.3-0.5 bij een zwak maar zichtbaar
   patroon, 0.5-0.75 bij een consistent patroon over voldoende nachten, en reserveer
   >0.75 voor een sterk, ondubbelzinnig en over veel nachten herhaald patroon.
4. suggestedShiftPct is een indicatie, geen precisievoorstel — houd 'm bescheiden
   (doorgaans 5-20%); dit is een startpunt voor de gebruiker om zelf verder te finetunen,
   geen exacte herberekening.
""".trimIndent()

    private fun inputDataSection(payload: FclNightReportPayload): String {
        val arr = JSONArray()
        payload.hourlyData.forEach { h ->
            arr.put(JSONObject().apply {
                put("effectHour", h.effectHour)
                put("effectHourLabel", h.effectHourLabel)
                put("nightsCount", h.nightsCount)
                put("avgBg", round2(h.avgBg))
                put("avgTarget", round2(h.avgTarget))
                put("avgBgSlopePerHour", round2(h.avgBgSlopePerHour))
                put("avgIob", round2(h.avgIob))
                put("avgIobDelta", round2(h.avgIobDelta))
                put("currentBasalUph", round2(h.currentBasalUph))
                put("classificationCounts", JSONObject(h.classificationCounts))
            })
        }
        val json = JSONObject().apply {
            put("generatedAtUtc", payload.generatedAtUtc)
            put("nightsAnalyzed", payload.nightsAnalyzed)
            put("hourlyData", arr)
        }
        return """
## Databijlage (per klok-uur, gemiddeld over de laatste ${payload.nightsAnalyzed} nachten)

```json
${json.toString(2)}
```
""".trimIndent()
    }

    private fun outputSchemaSection(): String = """
## Verplicht antwoordformaat — UITSLUITEND geldige JSON, geen omringende tekst

```json
{
  "summaryNl": "Korte samenvatting van het algehele beeld (1-3 zinnen), ook als suggestions leeg is",
  "suggestions": [
    {
      "hourLabel": "02:00",
      "direction": "LOWER",
      "suggestedShiftPct": -10.0,
      "confidence": 0.6,
      "reasonNl": "Uitleg in het Nederlands, concreet en beknopt",
      "evidenceFields": ["avgIobDelta -0.22 over 8 nachten", "avgBg 6.1 vs target 6.5, stabiel"]
    }
  ]
}
```

direction is altijd "LOWER" of "HIGHER". Geen andere velden toevoegen. Geen markdown-
opmaak buiten het JSON-blok zelf.
""".trimIndent()

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
