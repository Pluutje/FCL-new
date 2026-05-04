package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

object FclAdviceText {

    fun explainPattern(pattern: FclPattern): String =
        when (pattern) {

            FclPattern.FLAT_GOOD ->
                "Maaltijdrespons is stabiel. Glucose blijft grotendeels binnen doelgebied."

            FclPattern.EARLY_SPIKE ->
                "Glucose stijgt snel kort na de maaltijd."

            FclPattern.LATE_PEAK ->
                "De piek komt laat en wordt te hoog."

            FclPattern.EARLY_HYPO ->
                "Glucose daalt te snel na de maaltijd."

            FclPattern.LATE_HYPO ->
                "Glucose zakt pas later na de maaltijd te laag."

            FclPattern.LONG_HIGH_TAIL ->
                "Glucose blijft lang verhoogd na de maaltijd."

            FclPattern.OSCILLATING_RESPONSE ->
                "De glucoserespons oscilleert: overshoot gevolgd door hypo."

            FclPattern.MIXED_UNCLEAR ->
                "Er is geen duidelijk patroon zichtbaar."
        }

    fun adviceSummary(rec: FclAdvisorRecommendation): String {

        if (rec.transitions.isEmpty()) {
            return "Geen aanpassing aanbevolen. Huidige instellingen lijken goed te werken."
        }

        return rec.transitions.joinToString("\n") {

            val direction =
                if (it.step > 0) "verhogen (+${it.step}%)"
                else "verlagen (${it.step}%)"

            "${axisName(it.axis)}: $direction"
        }
    }

    private fun axisName(axis: StvAxis): String =
        when (axis) {
            StvAxis.STERKTE        -> "💪 Sterkte (S)"
            StvAxis.TIMING         -> "⏱️ Timing (T)"
            StvAxis.VOLHOUDENDHEID -> "🔁 Volhoudendheid (V)"
        }
}