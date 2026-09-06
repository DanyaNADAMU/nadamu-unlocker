package mu.nada.unlocker.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {

    private const val MAX_LOG_CAPACITY = 1000

    private val logDeque = ConcurrentLinkedDeque<LogEvent>()
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val event = LogEvent(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        logDeque.addLast(event)
        while (logDeque.size > MAX_LOG_CAPACITY) {
            logDeque.pollFirst()
        }

        _events.value = logDeque.toList()

        // Also output to Android Logcat
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, message, throwable)
            LogLevel.INFO -> android.util.Log.i(tag, message, throwable)
            LogLevel.WARN -> android.util.Log.w(tag, message, throwable)
            LogLevel.ERROR -> android.util.Log.e(tag, message, throwable)
        }
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.INFO, tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    fun clear() {
        logDeque.clear()
        _events.value = emptyList()
    }

    fun exportLogs(minLevel: LogLevel = LogLevel.DEBUG): String {
        return _events.value
            .filter { it.level.ordinal >= minLevel.ordinal }
            .joinToString("\n") { it.toFormattedString() }
    }
}
