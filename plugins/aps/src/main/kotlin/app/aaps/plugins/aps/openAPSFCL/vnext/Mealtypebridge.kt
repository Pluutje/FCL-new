package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter

/**
 * Maaltijdtype-herkenning en type-specifieke D/F parameters.
 *
 * Drie typen op basis van initiële stijging (0-30 min na maaltijdstart):
 *   SNEL    — snelle koolhydraten, vroege sterke stijging
 *   TRAAG   — vet/proteïne, late stijging
 *   GEMENGD — standaard / onbekend
 *
 * Architecture: volledig in-memory, geen bestanden, geen bridge-vertraging.
 */
object MealTypeBridge {

    enum class MealType { ONBEKEND, SNEL, TRAAG, GEMENGD }

    // Actieve type-specifieke D/F — bijgewerkt door FclAnalyzerScreen
    // Zodat loadFCLvNextConfig() ze zonder context kan lezen
    var activeTypeDOverride: Double? = null
    var activeTypeFOverride: Double? = null

    // Huidige episode-state
    private var currentType: MealType = MealType.ONBEKEND
    private var typeLocked: Boolean = false
    private var slopeHistory = mutableListOf<Double>()
    private var episodeMinutes: Int = 0

    // Drempels voor herkenning
    private const val SLOPE_SNEL_MIN    =  0.35
    private const val SLOPE_TRAAG_MAX   =  0.05
    private const val MIN_MINUTES_FIRST =  15
    private const val LOCK_MINUTES      =  35

    // ── Publieke API ───────────────────────────────────────────────────────

    fun getCurrentType(): MealType = currentType

    fun updateMealType(minutesSinceMealStart: Int, slope: Double) {
        episodeMinutes = minutesSinceMealStart
        if (typeLocked) return

        slopeHistory.add(slope)

        if (minutesSinceMealStart >= LOCK_MINUTES) {
            typeLocked = true
        }

        if (minutesSinceMealStart < MIN_MINUTES_FIRST) {
            currentType = MealType.ONBEKEND
            return
        }

        val samplesFirst15 = (MIN_MINUTES_FIRST / 5).coerceAtLeast(1)
        val avgSlope0_15 = if (slopeHistory.size >= samplesFirst15)
            slopeHistory.take(samplesFirst15).average() else slope

        val samples15_30 = slopeHistory.drop(samplesFirst15).take(3)
        val avgSlope15_30 = if (samples15_30.isNotEmpty())
            samples15_30.average() else slope

        currentType = when {
            avgSlope0_15 >= SLOPE_SNEL_MIN -> MealType.SNEL
            avgSlope0_15 <= SLOPE_TRAAG_MAX && avgSlope15_30 <= SLOPE_SNEL_MIN * 0.8 ->
                MealType.TRAAG
            else -> MealType.GEMENGD
        }
    }

    fun resetEpisode() {
        currentType    = MealType.ONBEKEND
        typeLocked     = false
        slopeHistory   = mutableListOf()
        episodeMinutes = 0
    }

    /**
     * Geeft type-specifieke ParamOverrides terug op basis van actieve D/F overrides.
     * Wordt aangeroepen vanuit loadFCLvNextConfig() zonder context.
     */
    fun getParamOverridesForCurrentType(
        baseD: Double,
        baseF: Double,
        refWmd: Double = DFMapping.REF_WMD_DEFAULT,
        refWff: Double = DFMapping.REF_WFF_DEFAULT,
        refEb:  Double = DFMapping.REF_EB_DEFAULT
    ): ConfigOverrideWriter.ParamOverrides? {
        val d = activeTypeDOverride ?: return null
        val f = activeTypeFOverride ?: return null
        if (currentType == MealType.ONBEKEND || currentType == MealType.GEMENGD) return null
        if (kotlin.math.abs(d - baseD) < 0.01 && kotlin.math.abs(f - baseF) < 0.01) return null
        return DFMapping.toParamOverrides(d, f, refWmd, refWff, refEb)
    }
}