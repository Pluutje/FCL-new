package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

object FclAxisLabelFormatter {

    // S/T/V gebruiken percentages — de labels tonen de waarde direct
    // plus een context-indicator voor de afstand van de baseline (100%)

    fun sterkteLabel(pct: Int): String {
        val delta = pct - 100
        val icon = when {
            pct <= 85  -> "🛡️"
            pct <= 95  -> "🧯"
            pct <= 105 -> "⚖️"
            pct <= 115 -> "🚀"
            else       -> "🔥"
        }
        val deltaStr = if (delta == 0) "" else if (delta > 0) " (+$delta%)" else " ($delta%)"
        return "$icon ${pct}%$deltaStr"
    }

    fun timingLabel(pct: Int): String {
        val delta = pct - 100
        val icon = when {
            pct <= 85  -> "🐢"
            pct <= 95  -> "🐌"
            pct <= 105 -> "⚖️"
            pct <= 115 -> "⚡"
            else       -> "🚨"
        }
        val deltaStr = if (delta == 0) "" else if (delta > 0) " (+$delta%)" else " ($delta%)"
        return "$icon ${pct}%$deltaStr"
    }

    fun volhoudendheidLabel(pct: Int): String {
        val delta = pct - 100
        val icon = when {
            pct <= 80  -> "🛑"
            pct <= 92  -> "🧯"
            pct <= 108 -> "⚖️"
            pct <= 120 -> "🔁"
            else       -> "🔂"
        }
        val deltaStr = if (delta == 0) "" else if (delta > 0) " (+$delta%)" else " ($delta%)"
        return "$icon ${pct}%$deltaStr"
    }

    fun label(axis: StvAxis, pct: Int): String =
        when (axis) {
            StvAxis.STERKTE        -> sterkteLabel(pct)
            StvAxis.TIMING         -> timingLabel(pct)
            StvAxis.VOLHOUDENDHEID -> volhoudendheidLabel(pct)
        }

    // Backwards-compat voor code die nog old enum-index labels verwacht
    @Deprecated("Gebruik sterkteLabel(pct)")
    fun heightLabel(index: Int): String = sterkteLabel(listOf(85, 90, 100, 110, 120)[index.coerceIn(0, 4)])

    @Deprecated("Gebruik volhoudendheidLabel(pct)")
    fun persistenceLabel(index: Int): String = volhoudendheidLabel(listOf(80, 88, 100, 112, 125)[index.coerceIn(0, 4)])

    @Deprecated("Gebruik volhoudendheidLabel(pct)")
    fun hypoLabel(index: Int): String = volhoudendheidLabel(listOf(75, 87, 100, 113, 125)[index.coerceIn(0, 4)])

    @Deprecated("Niet meer van toepassing in S/T/V model")
    fun mealHandlingLabel(index: Int): String = "—"
}