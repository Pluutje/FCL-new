package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

data class UiAdviceRow(
    val axis: String,
    val fromLabel: String,
    val toLabel: String
)

object FclAdviceFormatter {

    fun formatTransitions(
        transitions: List<FclAxisTransition>
    ): List<UiAdviceRow> {

        return transitions.map {

            val from =
                FclAxisLabelFormatter.label(it.axis, it.fromIndex)

            val to =
                FclAxisLabelFormatter.label(it.axis, it.toIndex)

            UiAdviceRow(
                axis = it.axis.name,
                fromLabel = from,
                toLabel = to
            )
        }
    }
}