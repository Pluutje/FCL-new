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
    const val D_START = 1.05   // startpunt na reset: ~92% sterkte (was 1.00 = 95%)

    const val F_MIN = 0.20
    const val F_MAX = 0.80
    const val F_START = 0.65   // startpunt na reset: ~118% timing (was 0.60 = 110%)

    // ── Referentiewaarden (bij D=1.0, F=0.5) ─────────────────────────────
    private const val REF_S   = 95
    private const val REF_T   = 106
    private const val REF_V   = 95
    private const val REF_CC  = 13
    // ── Insuline-type aanpassing ──────────────────────────────────────────
    // REF_PIB: peakIobBrake drempel. Verlaagd van 0.42 naar 0.22 voor
    // snelwerkende insuline (Lyumjev U200) waarbij IOB@piek structureel laag is.
    // Bij standaard insuline (Novorapid, Fiasp) kan dit naar 0.35-0.42.
    private const val REF_PIB = 0.22

    // ── Kalibreerbare referentiewaarden ───────────────────────────────────
    // Deze drie bepalen het basisgedrag bij D=1.0, F=0.5 en kunnen via de
    // Kalibratie-sectie in de Automaat-tab worden aangepast door de gebruiker.
    // Standaardwaarden zijn de gevalideerde baselines.
    const val REF_WMD_DEFAULT = 1.10   // Stijgingsdrempel frontload (mmol boven target)
    // VLOER 26/06/2026 (Ecko, architectuurreview): FrontloadLearner corrigeert nu
    // alleen naar EERDER (lager WMD), maar zonder vloer zou hij via meerdere
    // episoden te ver zakken. Onder 1.00 triggert watching bij BG=6.4 mmol
    // (target 5.4) — bij trage maaltijden levert dat alleen kleine nutteloze
    // commits op terwijl BG gewoon doorstijgt. REF_WMD_MIN verhoogd 0.80→1.00.
    const val REF_WFF_DEFAULT = 0.72   // Frontload grootte (fractie van max SMB)
    const val REF_EB_DEFAULT  = 1.0    // Vroege boost (1.0 = uit, 2.0 = maximaal)
    const val REF_PEAK_BIAS_DEFAULT = 0.20  // Vroege piek-bias-correctie (mmol)
    // 26/06/2026: teruggezet van 0.0 naar 0.20. De learner had dit naar 0.0
    // getrokken maar bij snelle grote maaltijden (slope ≥ 4 mmol/h) onderschat
    // de voorspelling de piek systematisch met ~1-2 mmol. Een structurele bias
    // van 0.20 mmol corrigeert dit zonder andere parameters te destabiliseren.

    // refLcd: BEWUSTE UITZONDERING op het "alles via D/F"-principe hierboven.
    // lateCommitDecayFactor was tot 20/06/2026 puur afgeleid van F — maar een
    // (bijna-)hypo veroorzaakt door specifiek de LAATSTE, te-late commit moet
    // niet per se F (en daarmee de hele frontload-balans) bijstellen; dat is
    // een ander signaal dan "te veel/te weinig vroeg gedoseerd". refLcd is een
    // losse, additionele extra bovenop de F-afgeleide basiswaarde — zelfde
    // soort persistente "ondergrond"-parameter als refEb, alleen specifiek
    // gericht op het krimpen/laten vervallen van late commits, onafhankelijk
    // bijgestuurd door DFLearner.evaluateLateCommitDecay().
    const val REF_LCD_DEFAULT = 0.0    // 0.0 = geen extra demping bovenop de F-afgeleide basis

    // Bereiken voor de kalibratie-knoppen
    const val REF_WMD_MIN = 1.00;  const val REF_WMD_MAX = 2.00  // min verhoogd 0.80→1.00
    const val REF_WFF_MIN = 0.40;  const val REF_WFF_MAX = 0.90
    const val REF_EB_MIN  = 1.0;   const val REF_EB_MAX  = 2.0
    const val REF_PEAK_BIAS_MIN = 0.0;  const val REF_PEAK_BIAS_MAX = 1.5
    const val REF_LCD_MIN = 0.0;   const val REF_LCD_MAX = 0.45

    /**
     * Bereken alle 17 parameters als ConfigOverrideWriter.ParamOverrides
     * op basis van D, F, vExtra en de drie kalibreerbare referentiewaarden.
     * Kan direct worden geschreven naar AAPS via ConfigOverrideWriter.
     *
     * vExtra (−0.5 .. +0.5): onafhankelijke volhoudendheidsoffset per maaltijdtype.
     *   vExtra > 0: meer persistentie na de piek — lager lateCommitDecay,
     *               lagere sustainedRiseMinTarget, lagere peakIobBrakeLockout.
     *   vExtra < 0: minder persistentie — hogere lateCommitDecay, eerder remmen.
     *   vExtra = 0: gedrag identiek aan de situatie vóór introductie van vExtra.
     */
    fun toParamOverrides(
        d: Double,
        f: Double,
        refWmd: Double = REF_WMD_DEFAULT,
        refWff: Double = REF_WFF_DEFAULT,
        refEb:  Double = REF_EB_DEFAULT,
        refPeakBias: Double = REF_PEAK_BIAS_DEFAULT,
        refLcd: Double = REF_LCD_DEFAULT,
        vExtra: Double = 0.0,
        aggLevel: Int = 5
    ): ConfigOverrideWriter.ParamOverrides {
        val dC    = d.coerceIn(D_MIN, D_MAX)
        val fC    = f.coerceIn(F_MIN, F_MAX)
        val vC    = vExtra.coerceIn(-0.5, 0.5)
        // Agressiviteitsmultiplier: verschuift D/F effectief zonder ze op te slaan.
        // aggLevel 5 = geen effect. Niveau 9: dC +12%, fC +6%, refWmd -0.25.
        val scale = aggScaleFromLevel(aggLevel)
        val dEff  = (dC * (1.0 + scale * 0.12)).coerceIn(D_MIN * 0.85, D_MAX * 1.15)
        val fEff  = (fC + scale * (F_MAX - 0.5) * 0.06).coerceIn(F_MIN * 0.90, F_MAX * 1.05)
        val wmdEff = (refWmd - scale * 0.25).coerceIn(0.70, 1.60)

        return ConfigOverrideWriter.ParamOverrides(
            // ── D-afhankelijk ───────────────────────────────────────────
            // sterkte en volhoudendheid gaan via STV-override in stv-blok,
            // maar worden hier ook berekend voor de JSON (als param_overrides
            // worden ze gebruikt als persistente override die applySTVModel
            // niet kan overschrijven via de DoubleKey-fix in FCLvNextConfig).
            // Zie ook DFMapping.toStvMap() voor de stv-blok waarden.

            // Piek-drempel: D hoog = agressiever = eerder remmen gewenst
            peakPredictionThreshold       = max(10.5, 12.5 - (dEff - 1.0) * 2.0),

            // Actiedrempel: D hoog = systeem activeert bij kleinere stijging
            // F hoog = eerder frontload gewenst = ook lagere activatiedrempel
            // Gecombineerd: D verlaagt (meer insuline nodig), F verlaagt (eerder actief)
            watchingMinDeltaToTarget      = max(0.5, wmdEff - (dEff - 1.0) * 0.5 - (fEff - 0.5) * 0.8),

            // ── F-afhankelijk ───────────────────────────────────────────

            // Frontload dosis bij stijgingsdetectie
            watchingFrontloadFrac         = min(0.90, refWff + (fEff - 0.5) * 0.40),

            // Vroege piek-bias-correctie: directe doorgave, geen D/F-afhankelijkheid.
            // Wordt onafhankelijk geleerd door FrontloadLearner op basis van
            // predFout0_20 (zie REF_PEAK_BIAS in DFMapping).
            earlyPeakBiasMmol             = refPeakBias.coerceIn(REF_PEAK_BIAS_MIN, REF_PEAK_BIAS_MAX),

            // Commit pauze: F hoog = frequenter committen
            commitCooldownMinutes         = max(5, (REF_CC - (fEff - 0.5) * 10).roundToInt()),

            // IOB-rem: F hoog = eerder remmen (insuline al vroeg actief)
            peakIobBrakeSuppressThreshold = max(0.30, REF_PIB - (fEff - 0.5) * 0.10),

            // Horizon: F hoog = korter vooruit kijken (vroege actie domineert)
            peakPredictionHorizonH        = max(0.8, 1.2 - (fEff - 0.5) * 0.6),

            // IOBstart-drempel voor WATCHING: F hoog = eerder actief
            // iobStart: drempel waar IOB-rem begint.
            // Verlaagd van 0.40 naar 0.15 basis voor snelwerkende insuline.
            // Bij Lyumjev U200 is IOB@piek 0.15-0.25 → rem moet eerder starten.
            iobStart                      = max(0.10, 0.25 - (fEff - 0.5) * 0.20),

            // Early boost: F hoog = sterkere vroege commits
            // refEb bepaalt het maximum bij F=0.8. Bij F=0.5 is boost altijd refEb.
            // Bij F>0.5 schaalt de boost op tot max(refEb, refEb + (fEff-0.5)*2.5).
            // refEb=1.0 (default/uit): boost pas actief bij hogere F-waarden.
            earlyBoostFactor              = max(1.0, refEb + (fEff - 0.5) * 2.5),
            earlyBoostMinConfidence       = max(0.40, 0.50 - (fEff - 0.5) * 0.20),
            earlyBoostMaxCommits          = if (fEff >= 0.65) 4 else 3,

            // Late commit decay: alleen actief bij F > 0.5
            // Koppelt automatisch aan earlyBoost: meer vroeg → meer late demping
            // lateDecay: uit bij F≤0.5, 0.20 bij F=0.6, 0.60 bij F=0.8
            // Milder dan earlyBoost zodat late commits niet te hard worden afgeknepen.
            // vC > 0 verlaagt de decay (minder remmen na de piek = meer volhoudendheid).
            // vC < 0 verhoogt de decay (eerder afkappen = minder volhoudendheid).
            // lateCommitDecayFactor: coeff verlaagd 2.0 -> 1.4 voor Lyumjev U200.
            // Bij F=0.75: lcd=0.35 (was 0.50). Gecombineerd met budgetDecay
            // blijft effectiveDecay onder het max van 0.70, wat commit 2
            // iets groter maakt (lateDecayMul 0.33 i.p.v. 0.30).
            // refLcd: losse, onafhankelijk geleerde extra bovenop de
            // F-afgeleide basis (zie kdoc bij REF_LCD_DEFAULT hierboven) —
            // specifiek voor "laatste commit was te laat/te groot", los van
            // de algehele frontload-balans die F stuurt.
            // MINIMUM 26/06/2026 (Ecko, architectuurreview): earlyBoostFactor
            // en lateCommitDecayFactor worden beide geleerd maar in dezelfde
            // richting beïnvloed door F. Na overdoseringen daalde refLcd terug
            // naar 0.0 terwijl F ook al daalde — het systeem corrigeerde
            // dubbel waardoor lateCommitDecayFactor te ver zakte (0.36).
            // Een vloer van 0.45 bij hogere F-waarden (≥ 0.65, waarbij earlyBoost
            // ook actief is) voorkomt dat de decay zover zakt dat de commits na
            // de grote frontload nog substantieel zijn. Dit werkt samen met de
            // IOB-afhankelijke decay-vloer in FCLvNext.kt.
            lateCommitDecayFactor         = run {
                val rawLcd = max(0.0, (fEff - 0.5) * 1.4 - vC * 0.6) + refLcd
                val minLcd = if (fEff >= 0.65) 0.45 else 0.0
                rawLcd.coerceIn(minLcd, REF_LCD_MAX + 0.65)
            },
            lateCommitDecayThreshold      = max(0.40, 0.55 - (fEff - 0.5) * 0.10),

            // Piekkalibr.: F hoog = steilere vroege stijging verwacht
            earlyRiseFracMin              = min(0.65, 0.35 + (fEff - 0.5) * 0.50),
            peakMaxSlopeWeight            = max(0.0, (fEff - 0.6) * 0.5),

            // Sustained Rise Response: F hoog = makkelijker triggeren
            // F=0.50: drempel=0.40 (conservatief)
            // F=0.60: drempel=0.35 (standaard)
            // F=0.70: drempel=0.30 (gevoelig)
            // F=0.80: drempel=0.25 (zeer gevoelig)
            sustainedRiseSlopeMin         = max(0.20, 0.40 - (fEff - 0.5) * 0.50),

            // Reactiesnelheid: F hoog = sneller reageren (minder minuten vereist)
            // F=0.50: 12 min  F=0.60: 10 min  F=0.70: 8 min  F=0.80: 6 min
            // vC > 0 = nog sneller reageren na de piek als BG niet zakt
            // vC=+0.5: -2 min extra; vC=-0.5: +2 min extra
            sustainedRiseMinTarget        = max(5, (12 - ((fEff - 0.5) * 20).toInt() - (vC * 4).toInt()))
        )
    }

    /**
     * Bereken sterkte, timing en volhoudendheid als STV-map voor
     * ConfigOverrideWriter.writeWithStvAndParams().
     *
     * vExtra (−0.5 .. +0.5): onafhankelijke V-offset, los van D.
     *   S = round(95 × D)           — alleen gestuurd door D
     *   T = round(106 + (F−0.5)×40) — alleen gestuurd door F
     *   V = round(95 + (D−1.0)×50 + vExtra×30) — D-basis + vrije V-offset
     *
     * Met vExtra=0 is het gedrag identiek aan vóór de introductie van vExtra.
     * Bij vExtra=+0.33: V +10% zonder dat S meebeweegt.
     */
    // Agressiviteitsschaal uit niveau 1-9 (5=standaard=0.0)
    fun aggScaleFromLevel(level: Int): Double = (level - 5) / 4.0
    fun nfScaleFromLevel(level: Double): Double = (level - 5.0) / 4.0

    // Labels en afgeleide gain% voor de NF-schaal (1-9) — gedeelde bron
    // voor zowel de Nacht-tab-schuif (NachtControlTab) als de NF-kaart
    // (AdvisorAssenKaarten.NfKaart), zodat ze nooit uit de pas lopen.
    private val NF_LABELS = mapOf(
        1.0 to "Zeer voorzichtig",
        2.0 to "Voorzichtig",
        3.0 to "Iets voorzichtig",
        4.0 to "Licht conservatief",
        5.0 to "Standaard (balanced)",
        6.0 to "Licht responsiever",
        7.0 to "Responsief",
        8.0 to "Proactief",
        9.0 to "Maximaal proactief"
    )
    fun nfLabel(level: Double): String =
        NF_LABELS.entries.minByOrNull { kotlin.math.abs(it.key - level) }?.value ?: "Standaard"

    /** Afgeleide gain% voor toelichting (85% bij niveau 5, ±3.75% per niveau-stap). */
    fun nfGainPct(level: Double): Int =
        (85.0 + (level - 5.0) * (15.0 / 4.0)).toInt().coerceIn(60, 110)


    // aggLevel verschuift de geleerde waarden met een multiplier.
    // De geleerde D/F/vExtra blijven ongewijzigd in SharedPreferences.
    // Niveau 5 = geen effect (mul=1.0). Niveau 9 = +12% S, +6% T, +10% V.
    fun toStvMap(d: Double, f: Double, nfLevel: Double = 5.0, vExtra: Double = 0.0,
                 aggLevel: Int = 5): Map<String, Int> {
        val dC    = d.coerceIn(D_MIN, D_MAX)
        val fC    = f.coerceIn(F_MIN, F_MAX)
        val vC    = vExtra.coerceIn(-0.5, 0.5)
        val scale = aggScaleFromLevel(aggLevel)
        val sMul  = 1.0 + scale * 0.12
        val tMul  = 1.0 + scale * 0.06
        val vMul  = 1.0 + scale * 0.10
        return mapOf(
            "sterkte"        to ((REF_S * dC) * sMul).roundToInt().coerceIn(75, 130),
            "timing"         to ((REF_T + (fC - 0.5) * 40) * tMul).roundToInt().coerceIn(80, 130),
            "volhoudendheid" to ((REF_V + (dC - 1.0) * 50 + vC * 30) * vMul).roundToInt().coerceIn(70, 130),
        )
    }  // nf_level apart bewaard via DFLearner, niet in de Int-map

    /**
     * Geeft een leesbare samenvatting van de belangrijkste afgeleide waarden.
     * Voor weergave in de UI naast de D/F knoppen.
     */
    fun summary(d: Double, f: Double, vExtra: Double = 0.0): String {
        val stv = toStvMap(d, f, 85.0, vExtra)
        val po  = toParamOverrides(d, f, vExtra = vExtra)
        return buildString {
            append("S=${stv["sterkte"]} T=${stv["timing"]} V=${stv["volhoudendheid"]}")
            if ((po.earlyBoostFactor ?: 1.0) > 1.01)
                append("  |  EB×${String.format("%.2f", po.earlyBoostFactor)}")
            if ((po.lateCommitDecayFactor ?: 0.0) > 0.01)
                append(" LCD=${String.format("%.2f", po.lateCommitDecayFactor)}")
            if ((po.earlyPeakBiasMmol ?: 0.0) > 0.01)
                append(" PBias=+${String.format("%.2f", po.earlyPeakBiasMmol)}")
            append("  CC=${po.commitCooldownMinutes}m")
        }
    }
}