package com.soufianodev.lingallery.devices.ui

import com.soufianodev.lingallery.app.Strings
import com.soufianodev.lingallery.devices.core.*
import com.soufianodev.lingallery.ui.icons.MobileBlock

class DeviceIssuePresenter(
    private val strings: Strings.DeviceIssue,
    private val deviceRepository: DeviceRepository,
) {
    fun present(action: DeviceUserAction): DeviceUiEffect {
        val name = deviceRepository.getDeviceDisplayName(action.deviceId)
        return when (action.issue) {
            DeviceIssue.PermissionRequired,
            DeviceIssue.DeviceLocked -> DeviceUiEffect.Dialog(
                DeviceIssueDisplayData(
                    title = strings.permissionTitle(name),
                    message = strings.permissionMessage,
                    steps = strings.permissionSteps(),
                    notes = strings.permissionNotes(),
                    primaryAction = DialogAction(strings.retry, DialogActionKind.RETRY),
                    secondaryAction = DialogAction(strings.cancel, DialogActionKind.CANCEL),
                    icon = MobileBlock,
                )
            )
            DeviceIssue.Busy -> DeviceUiEffect.Snackbar(
                message = strings.deviceBusy(name),
                isError = true,
            )
            DeviceIssue.UnsupportedProtocol -> DeviceUiEffect.Dialog(
                DeviceIssueDisplayData(
                    title = strings.unsupportedTitle(name),
                    message = strings.unsupportedMessage(name),
                    steps = emptyList(),
                    notes = emptyList(),
                    primaryAction = DialogAction(strings.learnMore, DialogActionKind.DISMISS),
                    secondaryAction = DialogAction(strings.close, DialogActionKind.DISMISS),
                )
            )
            else -> DeviceUiEffect.Dialog(
                DeviceIssueDisplayData(
                    title = strings.unknownTitle,
                    message = strings.unknownMessage(
                        action.issue::class.simpleName ?: "Unknown"
                    ),
                    steps = emptyList(),
                    notes = emptyList(),
                    primaryAction = DialogAction(strings.dismiss, DialogActionKind.DISMISS),
                    secondaryAction = null,
                )
            )
        }
    }

}
