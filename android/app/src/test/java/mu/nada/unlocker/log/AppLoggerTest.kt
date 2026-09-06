package mu.nada.unlocker.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLoggerTest {

    @Before
    fun setUp() {
        AppLogger.clear()
    }

    @Test
    fun testLogLevelsAndEventCollection() {
        AppLogger.d("TestTag", "Debug message")
        AppLogger.i("TestTag", "Info message")
        AppLogger.w("TestTag", "Warning message")
        AppLogger.e("TestTag", "Error message", RuntimeException("Boom"))

        val events = AppLogger.events.value
        assertEquals(4, events.size)

        assertEquals(LogLevel.DEBUG, events[0].level)
        assertEquals("Debug message", events[0].message)

        assertEquals(LogLevel.INFO, events[1].level)
        assertEquals("Info message", events[1].message)

        assertEquals(LogLevel.WARN, events[2].level)
        assertEquals("Warning message", events[2].message)

        assertEquals(LogLevel.ERROR, events[3].level)
        assertEquals("Error message", events[3].message)
        assertTrue(events[3].throwable is RuntimeException)
    }

    @Test
    fun testExportLogsWithFilter() {
        AppLogger.d("Tag1", "Debug message")
        AppLogger.i("Tag2", "Info message")
        AppLogger.e("Tag3", "Error message")

        val exportAll = AppLogger.exportLogs(LogLevel.DEBUG)
        assertTrue(exportAll.contains("Debug message"))
        assertTrue(exportAll.contains("Info message"))
        assertTrue(exportAll.contains("Error message"))

        val exportWarnAndAbove = AppLogger.exportLogs(LogLevel.WARN)
        assertTrue(!exportWarnAndAbove.contains("Debug message"))
        assertTrue(!exportWarnAndAbove.contains("Info message"))
        assertTrue(exportWarnAndAbove.contains("Error message"))
    }

    @Test
    fun testClearLogs() {
        AppLogger.i("Tag", "Msg 1")
        AppLogger.clear()
        assertEquals(0, AppLogger.events.value.size)
    }
}
