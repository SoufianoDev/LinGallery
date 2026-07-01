package com.soufianodev.lingallery.devices.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soufianodev.lingallery.ui.theme.DarkPalette

@Composable
fun DeviceIssueDialog(
    displayData: DeviceIssueDisplayData,
    onPrimary: () -> Unit,
    onSecondary: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = {},
        icon = displayData.icon?.let { icon ->
            { Icon(icon, contentDescription = null, modifier = Modifier.size(60.dp)) }
        },
        title = {
            Text(
                text = displayData.title,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = displayData.message,
                    fontSize = 13.sp,
                )
                displayData.steps.forEachIndexed { i, step ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${i + 1}. $step",
                        fontSize = 13.sp,
                    )
                }
                displayData.notes.forEach { note ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = note,
                        fontSize = 11.sp,
                        color = Color.Gray,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onPrimary,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkPalette.PRIMARY,
                ),
            ) {
                Text(displayData.primaryAction.label)
            }
        },
        dismissButton = displayData.secondaryAction?.let { action ->
            {
                Button(
                    onClick = onSecondary!!,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkPalette.ERROR,
                        contentColor = DarkPalette.ON_ERROR,
                    ),
                ) {
                    Text(action.label)
                }
            }
        },
    )
}
