package app.aaps.plugins.aps.openAPSFCL.vnext.logging


import android.util.Log
import org.joda.time.DateTime
import org.joda.time.Hours
import java.io.File
import java.util.Locale

class FCLvNextParameterLogger(
    private val context: android.content.Context,
    private val fileName: String,
    private val parameterProvider: () -> Map<String, Any>
) {

    private var lastLogTime: DateTime = DateTime(0)
    private var lastSnapshot: Map<String, String> = emptyMap()

    companion object {

        private const val LOG_INTERVAL_HOURS = 24
        private const val SEP = ";"
    }

    fun maybeLog(now: DateTime = DateTime.now()) {

        val snapshot = parameterProvider().mapValues { format(it.value) }

        val shouldLog =
            lastLogTime.millis == 0L ||
                Hours.hoursBetween(lastLogTime, now).hours >= LOG_INTERVAL_HOURS ||
                snapshot != lastSnapshot

        if (!shouldLog) return

        write(snapshot, now)

        lastLogTime = now
        lastSnapshot = snapshot
    }

    private fun write(values: Map<String, String>, now: DateTime) {

        try {
            val dir = File(android.os.Environment.getExternalStorageDirectory(), "Documents/AAPS/ANALYSE")
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            val isNew = !file.exists()

            if (isNew) {
                val header =
                    listOf("timestamp") + values.keys
                file.appendText(header.joinToString(SEP) + "\n")
            }

            val row =
                listOf(now.toString("yyyy-MM-dd HH:mm:ss")) +
                    values.values

            file.appendText(row.joinToString(SEP) + "\n")

            trimFile(file, maxLines = 10)

            Log.d("FCLvNextParamLog", "Parameters logged")

        } catch (e: Exception) {
            Log.e("FCLvNextParamLog", "Logging failed", e)
        }
    }

    private fun trimFile(file: File, maxLines: Int = 10) {
        val lines = file.readLines()
        if (lines.size <= maxLines + 1) return   // +1 = header

        val header = lines.first()
        val body = lines.drop(1).takeLast(maxLines)

        file.writeText(header + "\n")
        file.appendText(body.joinToString("\n") + "\n")
    }


    private fun format(v: Any): String =
        when (v) {
            is Double -> String.format(Locale.US, "%.3f", v)
            is Float -> String.format(Locale.US, "%.3f", v)
            else -> v.toString()
        }
}
