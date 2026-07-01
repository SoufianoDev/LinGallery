package com.soufianodev.lingallery.devices.ui

import androidx.compose.ui.graphics.vector.ImageVector

enum class DialogActionKind { RETRY, CANCEL, DISMISS }

data class DialogAction(
    val label: String,
    val kind: DialogActionKind,
)

data class DeviceIssueDisplayData(
    val title: String,
    val message: String,
    val steps: List<String>,
    val notes: List<String>,
    val primaryAction: DialogAction,
    val secondaryAction: DialogAction?,
    val icon: ImageVector? = null,
)
