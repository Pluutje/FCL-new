package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DFMapping — vertaalt de twee leerbare grootheden D en F naar alle 17 FCLvNext-parameters.
 *
 * D (dosis-schaal, bereik 0.85–1.35): hoeveel insuline in totaal per maaltijdepisode.
 *   D=1.0 = referentie (huidige baseline).
 *
 * F (frontload-fractie, bereik 0.20–0.80): hoe vroeg de dosis geconcentreerd wordt.
 *   F=0.5 = neutraal. F=0.6 = startwaarde (iets vroeger dan neutraal).
 *
 * Referentiepunt: D=1.0, F=0.5 reproduceert exact de baseline-config:
 *   S=95, T=106, V=95, EB=1.35, LCD=0, CC=13, WFF=0.64, PIB=0.42, etc.
 *
 * Garantie: alle parameters bewegen coherent. Het is structureel onmogelijk
 * om earlyBoost te verhogen zonder dat lateDecay en kortere cooldown meegaan.
 */
object DFMapping {

    // ── Grenzen ───────────────────────────────────────────────────────────
    const val D_MIN = 0.85
    const val D_MAX = 1.35
    const val D_START = 1.00

    const val F_MIN = 0.20
    const val F_MAX = 0.80
    const val F_START = 0.60   // iets boven neutraal, gezien structureel hoge IOBratios

    // ── Referentiewaarden (bij D=1.0, F=0.5) ─────────────────────────────
    private const val REF_S   = 95
    private const val REF_T   = 106
    private const val REF_V   = 95
    private const val REF_EB  = 1.35
    private const val REF_WFF = 0.64
    private const val REF_CC  = 13
    private const val REF_PIB = 0.42
    private const val REF_WMD = 1.50

    /**
     * Bereken alle 17 parameters als ConfigOverrideWriter.ParamOverrides
     * op basis van D en F. Kan direct worden geschreven naar AAPS via
     * ConfigOverrideWriter.writeParamsOnly().
     */
    fun toParamOverrides(d: Double, f: Double): ConfigOverrideWriter.ParamOverrides {
        val dC = d.coerceIn(D_MIN, D_MAX)
        val fC = f.coerceIn(F_MIN, F_MAX)

        return ConfigOverrideWriter.ParamOverrides(
            // ── D-afhankelijk ───────────────────────────────────────────
            // sterkte en volhoudendheid gaan via STV-override in stv-blok,
            // maar worden hier ook berekend voor de JSON (als param_overrides
            // worden ze gebruikt als persistente override die applySTVModel
            // niet kan overschrijven via de DoubleKey-fix in FCLvNextConfig).
            // Zie ook DFMapping.toStvMap() voor de stv-blok waarden.

            // Piek-drempel: D hoog = agressiever = eerder remmen gewenst
            peakPredictionThreshold       = max(10.5, 12.5 - (dC - 1.0) * 2.0),

            // Actiedrempel: D hoog = systeem activeert bij kleinere stijging
            // F hoog = eerder frontload gewenst = ook lagere activatiedrempel
            // Gecombineerd: D verlaagt (meer insuline nodig), F verlaagt (eerder actief)
            watchingMinDeltaToTarget      = max(0.5, REF_WMD - (dC - 1.0) * 0.5 - (fC - 0.5) * 0.8),

            // ── F-afhankelijk ───────────────────────────────────────────

            // Frontload dosis bij stijgingsdetectie
            watchingFrontloadFrac         = min(0.90, REF_WFF + (fC - 0.5) * 0.40),

            // Commit pauze: F hoog = frequenter committen
            commitCooldownMinutes         = max(5, (REF_CC - (fC - 0.5) * 10).roundToInt()),

            // IOB-rem: F hoog = eerder remmen (insuline al vroeg actief)
            peakIobBrakeSuppressThreshold = max(0.30, REF_PIB - (fC - 0.5) * 0.10),

            // Horizon: F hoog = korter vooruit kijken (vroege actie domineert)
            peakPredictionHorizonH        = max(0.8, 1.2 - (fC - 0.5) * 0.6),

            // IOBstart-drempel voor WATCHING: F hoog = eerder actief
            iobStart                      = max(0.25, 0.40 - (fC - 0.5) * 0.20),

            // Early boost: F hoog = sterkere vroege commits
            // earlyBoost: uit bij F≤0.5, 1.25× bij F=0.6, 1.75× bij F=0.8
            // Bewust gestart bij F=0.5 zodat de feature pas actief wordt als
            // DFLearner expliciet meer frontload signaleert.
            earlyBoostFactor              = max(1.0, 1.0 + (fC - 0.5) * 2.5),
            earlyBoostMinConfidence       = max(0.40, 0.50 - (fC - 0.5) * 0.20),
            earlyBoostMaxCommits          = if (fC >= 0.65) 3 else 2,

            // Late commit decay: alleen actief bij F > 0.5
            // Koppelt automatisch aan earlyBoost: meer vroeg → meer late demping
            // lateDecay: uit bij F≤0.5, 0.20 bij F=0.6, 0.60 bij F=0.8
            // Milder dan earlyBoost zodat late commits niet te hard worden afgeknepen.
            lateCommitDecayFactor         = max(0.0, (fC - 0.5) * 2.0),
            lateCommitDecayThreshold      = max(0.40, 0.55 - (fC - 0.5) * 0.10),

            // Piekkalibr.: F hoog = steilere vroege stijging verwacht
            earlyRiseFracMin              = min(0.65, 0.35 + (fC - 0.5) * 0.50),
            peakMaxSlopeWeight            = max(0.0, (fC - 0.6) * 0.5),

            // Sustained Rise Response: F hoog = makkelijker triggeren
            // F=0.50: drempel=0.40 (conservatief)
            // F=0.60: drempel=0.35 (standaard)
            // F=0.70: drempel=0.30 (gevoelig)
            // F=0.80: drempel=0.25 (zeer gevoelig)
            sustainedRiseSlopeMin         = max(0.20, 0.40 - (fC - 0.5) * 0.50),

            // Reactiesnelheid: F hoog = sneller reageren (minder minuten vereist)
            // F=0.50: 12 min  F=0.60: 10 min  F=0.70: 8 min  F=0.80: 6 min
            sustainedRiseMinTarget        = max(5, (12 - ((fC - 0.5) * 20).toInt()))
        )
    }

    /**
     * Bereken sterkte, timing en volhoudendheid als STV-map voor
     * ConfigOverrideWriter.writeWithStvAndParams().
     */
    fun toStvMap(d: Double, f: Double, nachtFactor: Int): Map<String, Int> {
        val dC = d.coerceIn(D_MIN, D_MAX)
        val fC = f.coerceIn(F_MIN, F_MAX)
        return mapOf(
            "sterkte"        to (REF_S * dC).roundToInt().coerceIn(75, 125),
            "timing"         to (REF_T + (fC - 0.5) * 40).roundToInt().coerceIn(80, 125),
            "volhoudendheid" to (REF_V + (dC - 1.0) * 50).roundToInt().coerceIn(70, 125),
            "nacht_factor"   to nachtFactor
        )
    }

    /**
     * Geeft een leesbare samenvatting van de belangrijkste afgeleide waarden.
     * Voor weergave in de UI naast de D/F knoppen.
     */
    fun summary(d: Double, f: Double): String {
        val stv = toStvMap(d, f, 85)
        val po  = toParamOverrides(d, f)
        return buildString {
            append("S=${stv["sterkte"]} T=${stv["timing"]} V=${stv["volhoudendheid"]}")
            if ((po.earlyBoostFactor ?: 1.0) > 1.01)
                append("  |  EB×${String.format("%.2f", po.earlyBoostFactor)}")
            if ((po.lateCommitDecayFactor ?: 0.0) > 0.01)
                append(" LCD=${String.format("%.2f", po.lateCommitDecayFactor)}")
            append("  CC=${po.commitCooldownMinutes}m")
        }
    }
}