package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer

/**
 * Berekent hoeveel insuline er WERKELIJK is afgegeven in een tijdvenster —
 * via de AAPS-behandelhistorie (PersistenceLayer: de echte, uitgevoerde
 * temp-basaal- en bolus/SMB-records), niet via FCL's eigen
 * commandedU/basalRate/deliveredTotal.
 *
 * Achtergrond (19/06/2026, n.a.v. Ecko's screenshots van AAPS Treatments
 * History): als FCL zelf besluit niets te doen, loopt DetermineBasalFCL door
 * naar de standaard oref0/SMB-fallback, die zélf een temp-basaal/SMB zet.
 * FCL's eigen kolommen (basal_u_h, delivered_total, commanded_dose,
 * final_dose, bolus) laten dus alleen FCL's EIGEN bijdrage zien — niet wat
 * er werkelijk in het lichaam terechtkomt. Deze class telt de daadwerkelijke
 * AAPS-behandelrecords op, ongeacht welke laag de bron was.
 *
 * Bewust GEEN gebruik van IobCobCalculator.calculateIobFromTempBasals…/
 * calculateIobFromBolus(): die berekenen vervalcurven (IOB) over de hele
 * DIA-periode, niet "hoeveel is er in dít venster afgeleverd". netbasalinsulin
 * wordt bovendien alleen gevuld in het extended-bolus-faking-pad (zie
 * IobCobCalculatorPlugin.calculateIobToTimeFromTempBasalsIncludingConvertedExtended),
 * dus niet bruikbaar voor reguliere temp-basaal-pompen.
 *
 * Basaal en bolus/SMB worden bewust apart geretourneerd (niet alleen een
 * totaal) — dat kost niets extra (al apart berekend) en maakt het later
 * mogelijk te onderscheiden of een patroon (bv. negatieve IOB) door
 * basaal-onderdrukking komt of door agressieve SMB's (19/06/2026).
 */
class FclRealDoseTracker(
    private val persistenceLayer: PersistenceLayer
) {

    data class RealDelivery(val basalU: Double, val bolusU: Double) {
        val totalU: Double get() = basalU + bolusU
    }

    /**
     * Hoever terugkijken voorbij [fromMs] om temp-basalen te vinden die al
     * vóór het venster gestart zijn maar er nog in doorlopen. AAPS staat
     * temp-basalen tot enkele uren toe; 24u is een ruime, veilige marge.
     */
    private val LOOKBACK_MARGIN_MS = 24L * 60 * 60 * 1000

    /**
     * Werkelijk afgegeven insuline (basaal + bolus/SMB apart) in het
     * halfopen venster [fromMs, toMs), ongeacht of FCL of de oref0-fallback
     * de bron was.
     */
    suspend fun deliveredUnits(fromMs: Long, toMs: Long): RealDelivery {
        if (toMs <= fromMs) return RealDelivery(0.0, 0.0)
        return RealDelivery(
            basalU = deliveredBasalUnits(fromMs, toMs),
            bolusU = deliveredBolusUnits(fromMs, toMs)
        )
    }

    private suspend fun deliveredBasalUnits(fromMs: Long, toMs: Long): Double {
        val temps = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(
            fromMs - LOOKBACK_MARGIN_MS, toMs, true
        )
        var sum = 0.0
        for (t in temps) {
            if (!t.isValid) continue
            // FAKE_EXTENDED is alleen een in-memory representatie voor
            // pompen die temps via extended boluses simuleren — geen echt
            // gepersisteerd temp-basaal-record, dus hier niet relevant
            // (de bijbehorende extended bolus telt al mee als bolus-record
            // als die methode van AAPS gebruikt wordt — niet apart nodig).
            if (t.type == TB.Type.FAKE_EXTENDED) continue

            val segStart = maxOf(t.timestamp, fromMs)
            val segEnd = minOf(t.end, toMs)
            if (segEnd <= segStart) continue

            val overlapHours = (segEnd - segStart) / 3_600_000.0
            sum += t.rate * overlapHours
        }
        return sum
    }

    private suspend fun deliveredBolusUnits(fromMs: Long, toMs: Long): Double {
        // getBolusesFromTime kent alleen een ondergrens — bovengrens hier
        // zelf filteren.
        val boluses = persistenceLayer.getBolusesFromTime(fromMs, true)
        var sum = 0.0
        for (b in boluses) {
            if (!b.isValid) continue
            if (b.timestamp < fromMs || b.timestamp >= toMs) continue
            // PRIMING bewust NIET uitgesloten: AAPS' eigen IOB-calculator
            // (IobCobCalculatorPlugin.calculateIobFromBolusToTime) filtert
            // dit ook niet weg bij de hoofd-iob/activity-som — consistent
            // blijven met wat de loop zelf als "afgeleverd" beschouwt.
            // Mocht dit in de praktijk een vertekening geven (priming-
            // events komen zelden voor maar zijn doorgaans groter dan een
            // normale dosis), dan is dit de plek om dat alsnog te filteren.
            sum += b.amount
        }
        return sum
    }
}
