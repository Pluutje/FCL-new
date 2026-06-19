package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.plugins.aps.openAPSFCL.vnext.FclOverrideBridge



/**
 * Leest FCLvNext_config_override.json (schema 3) uit Documents/AAPS/ANALYSE/.
 *
 * Schema 3 formaat:
 * {
 *   "schema": 3,
 *   "stv": { "sterkte": 95, "timing": 108, "volhoudendheid": 88, "nf_level": 6.0 },
 *   "param_overrides": { ... },
 *   "reason": "..."
 * }
 *
 * Veiligheidsgaranties:
 * - Bestand niet aanwezig → null → loadFCLvNextConfig valt terug op prefs/defaults
 * - Corrupt bestand → null → idem
 * - Alle waarden worden geclamped op veilige grenzen in loadFCLvNextConfig()
 */
object FCLvNextConfigOverride {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val FILENAME       = "FCLvNext_config_override.json"

    data class Override(
        val sterkte:         Int? = null,
        val timing:          Int? = null,
        val volhoudendheid:  Int? = null,
        val nfLevel:         Double? = null,  // vervangt nachtFactor (18/06/2026)

        val writtenAt:       String? = null,
        val basedOnEpisodes: Int?    = null,
        val reason:          String? = null,

        val paramOverrides:  ParamOverrides? = null,

        // MaxSmbLearner geleerde veiligheidsmarges.
        // null = gebruik handmatige prefs (max_bolus_day / fcl_vnext_peak_iob_brake_suppress)
            val iobBrakeLearned:  Double? = null   // 0.35–0.55
    )

    /**
     * Groep-A param fijnafstelling.
     * Worden ALTIJD toegepast NA applySTVModel() — winnen dus van S/T/V voor deze params.
     */
    data class ParamOverrides(
        val peakPredictionThreshold:       Double? = null,  // 9.5 – 14.0 mmol
        val watchingFrontloadFrac:         Double? = null,  // 0.40 – 0.90
        val watchingMinDeltaToTarget:      Double? = null,  // 0.5 – 3.5 mmol
        val commitCooldownMinutes:         Int?    = null,  // 5 – 25 min
        val peakPredictionHorizonH:        Double? = null,  // 0.8 – 1.8 uur
        val iobStart:                      Double? = null,  // 0.25 – 0.55
        val peakIobBrakeSuppressThreshold: Double? = null,  // 0.28 – 0.60: eerder/later remmen voor piek
        // Early Confidence Boost — bijstuurbaar door de Analyzer
        val earlyBoostFactor:              Double? = null,  // 1.0–2.0: vermenigvuldiger op earlyTargetU
        val earlyBoostMinConfidence:       Double? = null,  // 0.40–0.85: minimale confidence om boost te activeren
        val earlyBoostMaxCommits:          Int?    = null,
        val earlyRiseFracMin:              Double? = null,  // 0.35–0.85
        val peakMaxSlopeWeight:            Double? = null,  // 0.0–0.60
        val lateCommitDecayFactor:         Double? = null,  // 0.0–1.0
        val lateCommitDecayThreshold:      Double? = null,  // 0.30–0.70
        val sustainedRiseSlopeMin:         Double? = null,  // 0.15–0.80 mmol/min
        val sustainedRiseMinTarget:        Int?    = null,  // 5–20 minuten
        val earlyPeakBiasMmol:             Double? = null   // 0.0–1.5 mmol, vroege piek-correctie
    )

    /**
     * Haalt de pending override op van de geïntegreerde Analyzer via FclOverrideBridge.
     * consume() is atomisch en wist de override direct — eenmalig gebruik,
     * identiek aan het vroegere consume_after_use: true gedrag van het JSON-bestand.
     * Geeft null terug als er geen pending override is.
     */
    fun load(): Override? = FclOverrideBridge.consume()

    /**
     * Leest het "consume_after_use" vlag uit de JSON.
     * Aanwezig en true → bestand mag na gebruik verwijderd worden.
     * Afwezig of false → bestand blijft staan (legacy compatibiliteit).
     */
    private fun extractConsumeAfterUse(json: String): Boolean =
        """"consume_after_use"\s*:\s*true""".toRegex().containsMatchIn(json)

    private fun parse(json: String): Override? {
        return try {
            val stvBlock       = extractBlock(json, "stv")
            val paramOverBlock = extractBlock(json, "param_overrides")

            Override(
                sterkte        = stvBlock?.let { extractInt(it, "sterkte") },
                timing         = stvBlock?.let { extractInt(it, "timing") },
                volhoudendheid = stvBlock?.let { extractInt(it, "volhoudendheid") },
                nfLevel        = stvBlock?.let { extractDouble(it, "nf_level") },

                writtenAt       = extractString(json, "written_at"),
                basedOnEpisodes = extractInt(json, "based_on_episodes"),
                reason          = extractString(json, "reason"),
                paramOverrides  = paramOverBlock?.let { parseParamOverrides(it) },

                            iobBrakeLearned  = stvBlock?.let { extractDouble(it, "iob_brake_learned") }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseParamOverrides(block: String): ParamOverrides? {
        return try {
            ParamOverrides(
                peakPredictionThreshold       = extractDouble(block, "peakPredictionThreshold"),
                watchingFrontloadFrac         = extractDouble(block, "watchingFrontloadFrac"),
                watchingMinDeltaToTarget      = extractDouble(block, "watchingMinDeltaToTarget"),
                commitCooldownMinutes         = extractInt(block, "commitCooldownMinutes"),
                peakPredictionHorizonH        = extractDouble(block, "peakPredictionHorizonH"),
                iobStart                      = extractDouble(block, "iobStart"),
                peakIobBrakeSuppressThreshold = extractDouble(block, "peakIobBrakeSuppressThreshold"),
                earlyBoostFactor              = extractDouble(block, "earlyBoostFactor"),
                earlyBoostMinConfidence       = extractDouble(block, "earlyBoostMinConfidence"),
                earlyBoostMaxCommits          = extractInt(block, "earlyBoostMaxCommits"),
                earlyRiseFracMin              = extractDouble(block, "earlyRiseFracMin"),
                peakMaxSlopeWeight            = extractDouble(block, "peakMaxSlopeWeight"),
                lateCommitDecayFactor         = extractDouble(block, "lateCommitDecayFactor"),
                lateCommitDecayThreshold      = extractDouble(block, "lateCommitDecayThreshold"),
                sustainedRiseSlopeMin         = extractDouble(block, "sustainedRiseSlopeMin"),
                sustainedRiseMinTarget        = extractInt(block, "sustainedRiseMinTarget"),
                earlyPeakBiasMmol             = extractDouble(block, "earlyPeakBiasMmol")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractInt(json: String, key: String): Int? =
        """"$key"\s*:\s*(\d+)""".toRegex().find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractDouble(json: String, key: String): Double? =
        """"$key"\s*:\s*([\d.]+)""".toRegex().find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun extractString(json: String, key: String): String? =
        """"$key"\s*:\s*"?([^",}\n]+)"?""".toRegex().find(json)
            ?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")

    private fun extractBlock(json: String, key: String): String? {
        val start = json.indexOf("\"$key\""); if (start < 0) return null
        val blockStart = json.indexOf('{', start); if (blockStart < 0) return null
        var depth = 0; var i = blockStart
        while (i < json.length) {
            when (json[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return json.substring(blockStart, i + 1) } }
            i++
        }
        return null
    }
}
