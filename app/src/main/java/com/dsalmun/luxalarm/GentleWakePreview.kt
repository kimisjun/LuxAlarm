/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Full-screen, scrubbable preview. [progress] is injected so tests and callers own time. */
@Composable
fun GentleWakePreview(
    progress: Float,
    onProgressChange: (Float) -> Unit = {},
    onAwake: () -> Unit,
    modifier: Modifier = Modifier,
    playbackStatus: String? = null,
) {
    val frame = WakeRamp.frameAt(progress)
    val sunrise = Color(frame.sunriseRgb[0], frame.sunriseRgb[1], frame.sunriseRgb[2])
    val base = Color(0xFF140A05)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("gentle-wake-preview")
                .background(Brush.verticalGradient(listOf(base, sunrise))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "부드럽게 깨어날 시간이에요",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text =
                    "진행 ${(frame.clampedProgress * 100).roundToInt()}% · " +
                        "화면 ${(frame.screenBrightness * 100).roundToInt()}% · " +
                        "음악 ${(frame.audioVolume * 100).roundToInt()}%",
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "미리보기 진행도",
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 14.sp,
            )
            if (playbackStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = playbackStatus,
                    color = Color.White.copy(alpha = 0.84f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Slider(
                value = frame.clampedProgress,
                onValueChange = onProgressChange,
                modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp),
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onAwake,
                modifier = Modifier.widthIn(min = 280.dp).heightIn(min = 72.dp),
                shape = RoundedCornerShape(36.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF5A2508),
                    ),
            ) {
                Text(text = "일어났어요", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
