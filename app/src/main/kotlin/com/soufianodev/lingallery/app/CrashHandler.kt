package com.soufianodev.lingallery.app

import java.util.concurrent.atomic.AtomicReference

data class CrashInfo(
    val throwable: Throwable,
    val thread: Thread,
    val timestamp: Long,
    val appVersion: String
) {
    fun toIssueBody(): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        val trace = sw.toString().lines().take(50).joinToString("\n")
        return buildString {
            appendLine("## Steps to reproduce")
            appendLine("(Describe what you were doing when the crash occurred)")
            appendLine()
            appendLine("## Crash details")
            appendLine("- **App version**: $appVersion")
            appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("- **Java**: ${System.getProperty("java.version")}")
            appendLine("- **Thread**: ${thread.name}")
            appendLine()
            appendLine("### Stack trace")
            appendLine("```")
            append(trace)
            appendLine()
            appendLine("```")
        }
    }
}

object CrashHandler : Thread.UncaughtExceptionHandler {
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val lastCrash = AtomicReference<CrashInfo?>(null)
    private var onCrash: ((CrashInfo) -> Unit)? = null

    fun setOnCrashListener(listener: (CrashInfo) -> Unit) {
        onCrash = listener
    }

    fun consume(): CrashInfo? = lastCrash.getAndSet(null)

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val info = CrashInfo(throwable, thread, System.currentTimeMillis(), AppConst.APP_VERSION)
        lastCrash.set(info)

        java.awt.EventQueue.invokeLater { onCrash?.invoke(info) }

        Thread {
            Thread.sleep(500)
            if (lastCrash.getAndSet(null) != null) {
                showFallbackDialog(info)
            }
        }.apply { isDaemon = true }.start()

        previousHandler?.uncaughtException(thread, throwable)
    }

    private fun showFallbackDialog(crash: CrashInfo) {
        java.awt.EventQueue.invokeLater {
            val message = buildString {
                appendLine("An unexpected error occurred.")
                appendLine()
                appendLine("Thread: ${crash.thread.name}")
                appendLine("Exception: ${crash.throwable::class.simpleName}")
                appendLine("Message: ${crash.throwable.message}")
                appendLine()
                appendLine("Would you like to report this issue on GitHub?")
            }
            val opts = arrayOf("Report Issue", "Exit")
            val choice = javax.swing.JOptionPane.showOptionDialog(
                null, message, "LinGallery - Application Error",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.ERROR_MESSAGE,
                null, opts, opts[0]
            )
            if (choice == 0) {
                IssueReporter.open("App Crash", crash.toIssueBody())
            }
            kotlin.system.exitProcess(1)
        }
    }
}
