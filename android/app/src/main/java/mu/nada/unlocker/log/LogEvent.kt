package mu.nada.unlocker.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEvent(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null
) {
    fun formatDisplayTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timestamp))
    }

    fun toFormattedString(): String {
        val timeStr = formatDisplayTime()
        val base = "[$timeStr] [${level.name}] [$tag] $message"
        return if (throwable != null) {
            val stack = throwable.stackTraceToString()
            "$base\n$stack"
        } else {
            base
        }
    }
}
