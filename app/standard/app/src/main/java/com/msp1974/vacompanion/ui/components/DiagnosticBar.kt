package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.ui.theme.CustomColours
import com.msp1974.vacompanion.utils.Event

/**
 * Diagnostics overlay. Voice/audio diagnostics were removed with the wake word engine, so this
 * shows camera motion/face detection state.
 */
@Composable
fun DiagnosticBar(
    diagnosticInfo: DiagnosticInfo,
    @Suppress("UNUSED_PARAMETER") onEvent: (Event) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .zIndex(2f)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) {
                // Prevent propagation of click
            }
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (diagnosticInfo.hasCamera && diagnosticInfo.motionDetectionMode != "none") {
            MotionIndicator(diagnosticInfo)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = "Motion detection off",
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = if (diagnosticInfo.hasCamera) "Motion detection off" else "No camera",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun MotionIndicator(diagnosticInfo: DiagnosticInfo) {
    val motionDetected = diagnosticInfo.motionDetected
    val isFaceMode = diagnosticInfo.motionDetectionMode == "face"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = if (isFaceMode) Icons.Default.Face else Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = if (isFaceMode) "Face Detected" else "Motion Detected",
            tint = if (motionDetected) CustomColours.GREEN else Color.Gray,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = if (isFaceMode) "Face" else "Motion",
            color = if (motionDetected) CustomColours.GREEN else Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Preview
@Composable
fun DiagnosticBarPreview() {
    DiagnosticBar(
        DiagnosticInfo(
            show = true,
            motionDetected = true,
            hasCamera = true,
            lastMotionTimestamp = System.currentTimeMillis(),
            motionInterval = 10000,
            motionDetectionMode = "face",
        )
    )
}
