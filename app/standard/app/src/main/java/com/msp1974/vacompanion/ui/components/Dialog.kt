package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
fun VADialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    dialogTitle: String,
    dialogText: String,
    confirmText: String = "Yes",
    dismissText: String = "No",
) {
    AlertDialog(
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
            decorFitsSystemWindows = true,
            dismissOnClickOutside = false,
            dismissOnBackPress = false
        ),
        shape = RoundedCornerShape(20.dp),
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            Button(onClick = { onConfirmation() }) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismissRequest() }) {
                Text(text = dismissText)
            }
        },
        title = {
            Text(text = dialogTitle, fontSize = 18.sp)
        },
        text = {
            Text(text = dialogText)
        }
    )
}

@Preview
@Composable
fun VAAlertDialogPreview() {
    VADialog(
        onDismissRequest = {},
        onConfirmation = {},
        dialogTitle = "Dialog Title",
        dialogText = "Some dialog text message",
    )
}