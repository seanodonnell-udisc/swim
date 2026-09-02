package swim.desktop

import swim.core.config.configDir
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app log: one rotating file beside the config, and the same line on stderr. A logging
 * framework would buy configuration this app does not have.
 */
object Log {
    private val file = File(configDir(), "swim-desktop.log")
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** Rotates the log and records the uncaught exceptions the UI thread would otherwise swallow. */
    fun start() {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.renameTo(File(file.parentFile, file.name + ".1"))
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            line("uncaught on ${thread.name}: ${error.stackTraceToString()}")
        }
        line("swim desktop started, log at ${file.absolutePath}")
    }

    /** One line, timestamped, to the file and to stderr. */
    fun line(message: String) {
        val text = "${LocalDateTime.now().format(stamp)} $message"
        System.err.println(text)
        runCatching { file.appendText(text + "\n") }
    }
}

private const val MAX_BYTES = 1_000_000L
