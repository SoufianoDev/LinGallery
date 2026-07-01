package com.soufianodev.lingallery.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.Sketch
import com.soufianodev.lingallery.ui.theme.DarkPalette
import com.soufianodev.lingallery.ui.theme.LinGalleryTheme
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.system.exitProcess

fun main() = application {
    FileKit.init(appId = "com.soufianodev.lingallery")
    System.setProperty("jdk.nio.file.WatchService.maxEventsPerPoll", "16384")

    SingletonSketch.setSafe {
        Sketch.Builder(PlatformContext.INSTANCE).apply {
            logger(level = com.github.panpf.sketch.util.Logger.Level.Warn)
        }.build()
    }

    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)
    val showNativeLibError = remember { mutableStateOf(false) }
    var crashInfo by remember { mutableStateOf<CrashInfo?>(null) }

    SideEffect {
        CrashHandler.setOnCrashListener { info -> crashInfo = info }
        CrashHandler.install()
    }

    Window(
        onCloseRequest = {
            appScope.cancel()
            exitApplication()
            exitProcess(0)
        },
        title = Strings.App.name,
        state = windowState
    ) {
        val module = remember {
            AppModule(appScope, window, onNativeLibFailed = {
                showNativeLibError.value = true
            }).also { it.init() }
        }
        LinGalleryTheme(darkTheme = true) {
            when {
                crashInfo != null -> CrashErrorDialog(
                    crash = crashInfo!!,
                    onReportIssue = {
                        IssueReporter.open("App Crash", crashInfo!!.toIssueBody())
                        crashInfo = null
                    },
                    onExit = { exitApplication() }
                )
                showNativeLibError.value -> NativeLibErrorDialog(
                    onReportIssue = {
                        IssueReporter.open(
                            "Native Library Not Found",
                            buildString {
                                appendLine("The app could not load the native library lingallery_native.")
                                appendLine()
                                appendLine("App version: ${AppConst.APP_VERSION}")
                                appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                                appendLine("java.library.path: ${System.getProperty("java.library.path")}")
                            }
                        )
                    },
                    onExit = { exitApplication() }
                )
                else -> App(module)
            }
        }
        DisposableEffect(module) {
            onDispose { module.cleanup() }
        }
    }
}

@Composable
internal fun CrashErrorDialog(
    crash: CrashInfo,
    onReportIssue: () -> Unit,
    onExit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = "Application Error",
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "An unexpected error occurred.",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${crash.throwable::class.simpleName}: ${crash.throwable.message}",
                    fontSize = 12.sp,
                    color = DarkPalette.ERROR
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "App version: ${crash.appVersion}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onReportIssue,
                colors = ButtonDefaults.buttonColors(containerColor = DarkPalette.PRIMARY),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Report Issue") }
        },
        dismissButton = {
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = DarkPalette.ERROR),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Exit App") }
        }
    )
}

@Composable
internal fun NativeLibErrorDialog(
    onReportIssue: () -> Unit,
    onExit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = "Native Library Not Found",
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "The Rust native library (lingallery_native) could not be loaded. This library is required for image processing and MTP device support.",
                    fontSize = 13.sp
                )
                Text(
                    text = "Please ensure the application is built correctly with the native module.",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onReportIssue,
                colors = ButtonDefaults.buttonColors(containerColor = DarkPalette.PRIMARY),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Report Issue") }
        },
        dismissButton = {
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = DarkPalette.ERROR),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Exit") }
        }
    )
}
