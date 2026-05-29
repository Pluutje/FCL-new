package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import android.content.SharedPreferences

/**
 * BgUnits — centrale unit-helper voor de FCLvNext UI-laag.
 *
 * Alle BG-waarden worden intern in mmol/L bijgehouden.
 * Bij weergave wordt via [isMgdl] bepaald welke eenheid de gebruiker
 * heeft ingesteld en worden waarden en labels overeenkomstig geconverteerd.
 *
 * De unit-keuze wordt door OpenAPSFCLPlugin bij elke algoritme-run
 * weggeschreven naar SharedPreferences (KEY_IS_MGDL).
 * Composables lezen die waarde via [isMgdl(context)].
 */
object BgUnits {

    private const val PREFS_NAME = "fcl_vnext_units"
    private const val KEY_IS_MGDL = "is_mgdl"
    private const val MMOL_TO_MGDL = 18.0182

    // ── Opslag ────────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Schrijf de unit-keuze vanuit OpenAPSFCLPlugin (elke algoritme-run). */
    fun setIsMgdl(ctx: Context, mgdl: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_IS_MGDL, mgdl).apply()

    /** Lees de unit-keuze vanuit een Composable of formatter. */
    fun isMgdl(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_IS_MGDL, false)

    // ── Conversie ─────────────────────────────────────────────────────────────

    /** Converteer mmol/L naar de ingestelde eenheid. */
    fun fromMmol(mmol: Double, mgdl: Boolean): Double =
        if (mgdl) mmol * MMOL_TO_MGDL else mmol

    // ── Formattering ──────────────────────────────────────────────────────────

    /** Eenheidslabel: "mmol/L" of "mg/dL". */
    fun unitLabel(mgdl: Boolean): String = if (mgdl) "mg/dL" else "mmol/L"

    /** Kort label voor compacte weergave: "mmol" of "mg/dL". */
    fun unitShort(mgdl: Boolean): String = if (mgdl) "mg/dL" else "mmol"

    /**
     * Formatteer een absoluut BG-getal (bijv. 8.4 mmol → "8.4 mmol/L" of "151 mg/dL").
     * Gebruik voor: glucose, pieken, drempelwaarden.
     */
    fun formatBg(mmol: Double, mgdl: Boolean, decimals: Int = 1): String {
        val v = fromMmol(mmol, mgdl)
        val fmt = if (mgdl) "%.0f" else "%.${decimals}f"
        return "${fmt.format(v)} ${unitLabel(mgdl)}"
    }

    /**
     * Formatteer een BG-getal zonder eenheidslabel (voor in een tabel of samengestelde string).
     */
    fun formatBgValue(mmol: Double, mgdl: Boolean, decimals: Int = 1): String {
        val v = fromMmol(mmol, mgdl)
        val fmt = if (mgdl) "%.0f" else "%.${decimals}f"
        return fmt.format(v)
    }

    /**
     * Formatteer een BG-delta (±) met teken.
     * Gebruik voor: piekfout, IOB-correctie, stijging t.o.v. target.
     */
    fun formatDelta(mmol: Double, mgdl: Boolean, decimals: Int = 2): String {
        val v = fromMmol(mmol, mgdl)
        val fmt = if (mgdl) "%.0f" else "%.${decimals}f"
        val sign = if (v >= 0) "+" else ""
        return "$sign${fmt.format(v)} ${unitShort(mgdl)}"
    }

    /**
     * Formatteer een slope (mmol/5min of mg/dL/5min).
     */
    fun formatSlope(mmolPer5min: Double, mgdl: Boolean): String {
        val v = fromMmol(mmolPer5min, mgdl)
        return if (mgdl) "${"%.0f".format(v)} mg/dL/5min"
        else "${"%.2f".format(v)} mmol/5min"
    }
}
