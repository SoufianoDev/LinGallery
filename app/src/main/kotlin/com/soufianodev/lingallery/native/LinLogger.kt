package com.soufianodev.lingallery.native

import java.io.BufferedWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LinLogger {

    enum class Level(val value: Int) {
        TRACE(0),
        DEBUG(1),
        INFO(2),
        WARN(3),
        ERROR(4);

        companion object {
            fun fromValue(value: Int): Level =
                entries.firstOrNull { it.value == value } ?: DEBUG
        }
    }

    private var minLevel: Level = Level.DEBUG
    private var nativeMinLevel: Level = Level.DEBUG
    private var enabled = true

    @Volatile
    private var bufferedMode = false
    private var tempLogFile: Path? = null
    private var logWriter: BufferedWriter? = null

    @Volatile
    private var fileLoggingEnabled = false
    private var logFileWriter: BufferedWriter? = null
    private var logFilePath: Path? = null

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("UTC"))

    fun init(minLevel: Level = Level.DEBUG, nativeMinLevel: Level = Level.DEBUG) {
        this.minLevel = minLevel
        this.nativeMinLevel = nativeMinLevel
        this.enabled = true
        i(TAG, "LinLogger initialized: minLevel=$minLevel, nativeMinLevel=$nativeMinLevel")
    }

    fun setLevel(level: Level) { minLevel = level }
    fun setNativeLevel(level: Level) { nativeMinLevel = level }
    fun setEnabled(enabled: Boolean) { this.enabled = enabled }

    fun setFileLogging(enabled: Boolean) {
        synchronized(this) {
            if (enabled == fileLoggingEnabled) return
            if (enabled) {
                try {
                    val projectRoot = findProjectRoot() ?: Paths.get(System.getProperty("user.dir"))
                    val logDir = projectRoot.resolve("logs")
                    Files.createDirectories(logDir)
                    val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.now())
                    val logFile = logDir.resolve("lingallery-$timestamp.log")
                    logFilePath = logFile
                    logFileWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)
                    fileLoggingEnabled = true
                    println("[LinLogger] File logging enabled: ${logFile.toAbsolutePath()}")
                } catch (e: Exception) {
                    println("[LinLogger] Failed to enable file logging: ${e.message}")
                    fileLoggingEnabled = false
                    logFileWriter = null
                    logFilePath = null
                }
            } else {
                fileLoggingEnabled = false
                try { logFileWriter?.close() } catch (_: Exception) {}
                logFileWriter = null
                val f = logFilePath
                logFilePath = null
                if (f != null) {
                    println("[LinLogger] File saved: ${f.toAbsolutePath()}")
                }
            }
        }
        nativeSetFileLogging(enabled)
    }

    private external fun nativeSetFileLogging(enabled: Boolean)

    private fun findProjectRoot(): Path? {
        var dir = Paths.get(System.getProperty("user.dir"))
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        return null
    }

    private fun shouldLog(level: Level): Boolean {
        if (!enabled) return false
        return level.ordinal >= minLevel.ordinal
    }

    private fun formatTimestamp(): String = formatter.format(Instant.now())

    private fun buildMessage(level: Level, tag: String, message: String, throwable: Throwable? = null): String {
        val ts = formatTimestamp()
        val levelStr = level.name.padStart(5)
        val sb = StringBuilder()
        sb.append("$ts [$levelStr] [$tag] $message")
        if (throwable != null) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.flush()
            sb.append("\n").append(sw.toString().lines().joinToString("\n") { "  $it" })
        }
        return sb.toString()
    }

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(level)) return
        val msg = buildMessage(level, tag, message, throwable)
        synchronized(this) {
            if (bufferedMode) {
                try { logWriter?.run { write(msg); newLine(); flush() } } catch (_: Exception) {}
                return
            }
            if (fileLoggingEnabled) {
                try { logFileWriter?.run { write(msg); newLine(); flush() } } catch (_: Exception) {}
            }
        }
        println(msg)
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Level.ERROR, tag, msg, tr)

    @JvmStatic
    @Synchronized
    fun nativeLog(levelOrdinal: Int, tag: String, message: String) {
        val level = Level.fromValue(levelOrdinal)
        if (!enabled) return
        if (!bufferedMode && !fileLoggingEnabled && level.ordinal < nativeMinLevel.ordinal) return
        val msg = "${formatTimestamp()} [${level.name.padStart(5)}] [$tag] $message"
        if (bufferedMode) {
            if (tag == "mtp_bridge") return
            try { logWriter?.run { write(msg); newLine(); flush() } } catch (_: Exception) {}
            return
        }
        if (fileLoggingEnabled) {
            try { logFileWriter?.run { write(msg); newLine(); flush() } } catch (_: Exception) {}
        }
        println(msg)
    }

    fun startBufferedMode(): Boolean {
        synchronized(this) {
            if (bufferedMode) return true
            try {
                tempLogFile = Files.createTempFile("lingallery-log-", ".tmp")
                logWriter = Files.newBufferedWriter(tempLogFile!!, StandardCharsets.UTF_8)
                bufferedMode = true
                println("[LinLogger] Buffered mode started: ${tempLogFile}")
                return true
            } catch (e: Exception) {
                println("[LinLogger] Failed to start buffered mode: ${e.message}")
                tempLogFile = null
                logWriter = null
                return false
            }
        }
    }

    fun endBufferedMode() {
        val file = synchronized(this) {
            bufferedMode = false
            val f = tempLogFile
            tempLogFile = null
            try { logWriter?.close() } catch (_: Exception) {}
            logWriter = null
            f
        } ?: return

        try {
            Files.readAllLines(file).forEach { println(it) }
        } catch (_: Exception) {}
        try { Files.deleteIfExists(file) } catch (_: Exception) {}
    }

    fun formatDuration(ms: Long): String {
        val days = ms / 86400000
        val hours = (ms % 86400000) / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s ${millis}ms"
            seconds > 0 -> "${seconds}s ${millis}ms"
            else -> "${millis}ms"
        }
    }

    private const val TAG = "LinLogger"
}
