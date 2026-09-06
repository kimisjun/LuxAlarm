/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

private const val MINUTES_PER_DAY = 24 * 60
private const val TIME_STEP_MINUTES = 15
private const val DEFAULT_WAKE_MINUTES = 7 * 60
private const val DEFAULT_CUSTOM_BEDTIME_MINUTES = 22 * 60 + 45

private val WarmlyNight = Color(0xFF171429)
private val WarmlyViolet = Color(0xFF49334F)
private val WarmlyApricot = Color(0xFFD98560)
private val WarmlyCream = Color(0xFFFFF2E8)

private enum class WarmlyOnboardingStep {
    WELCOME,
    WAKE_TIME,
    BEDTIME,
    CUSTOM_BEDTIME,
}

@Composable
fun WarmlyOnboardingScreen(
    modifier: Modifier = Modifier,
    onPlanComplete: (SleepPlan) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(WarmlyOnboardingStep.WELCOME) }
    var wakeMinutes by rememberSaveable { mutableIntStateOf(DEFAULT_WAKE_MINUTES) }
    var customBedtimeMinutes by rememberSaveable {
        mutableIntStateOf(DEFAULT_CUSTOM_BEDTIME_MINUTES)
    }
    var selectedBedtimeMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedBedtimeDayOffset by rememberSaveable { mutableIntStateOf(-1) }
    var showOpenSourceNotice by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(WarmlyNight, WarmlyViolet, WarmlyApricot))
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        when (step) {
            WarmlyOnboardingStep.WELCOME ->
                WarmlyWelcome(
                    onContinue = { step = WarmlyOnboardingStep.WAKE_TIME },
                    onOpenSourceNotice = { showOpenSourceNotice = true },
                )
            WarmlyOnboardingStep.WAKE_TIME ->
                WarmlyWakeTime(
                    wakeMinutes = wakeMinutes,
                    onDecrease = { wakeMinutes = wrapMinutes(wakeMinutes - TIME_STEP_MINUTES) },
                    onIncrease = { wakeMinutes = wrapMinutes(wakeMinutes + TIME_STEP_MINUTES) },
                    onContinue = {
                        selectedBedtimeMinutes = null
                        step = WarmlyOnboardingStep.BEDTIME
                    },
                )
            WarmlyOnboardingStep.BEDTIME ->
                WarmlyBedtimeRecommendations(
                    wakeTime = wakeMinutes.toLocalTime(),
                    selectedBedtimeMinutes = selectedBedtimeMinutes,
                    onSelect = { minutes, dayOffset ->
                        selectedBedtimeMinutes = minutes
                        selectedBedtimeDayOffset = dayOffset
                    },
                    onCustom = { step = WarmlyOnboardingStep.CUSTOM_BEDTIME },
                    onComplete = {
                        onPlanComplete(
                            SleepPlan(
                                wakeMinutes = wakeMinutes,
                                bedtimeMinutes = checkNotNull(selectedBedtimeMinutes),
                                bedtimeDayOffset = selectedBedtimeDayOffset,
                            )
                        )
                    },
                )
            WarmlyOnboardingStep.CUSTOM_BEDTIME ->
                WarmlyCustomBedtime(
                    bedtimeMinutes = customBedtimeMinutes,
                    onDecrease = {
                        customBedtimeMinutes = wrapMinutes(customBedtimeMinutes - TIME_STEP_MINUTES)
                    },
                    onIncrease = {
                        customBedtimeMinutes = wrapMinutes(customBedtimeMinutes + TIME_STEP_MINUTES)
                    },
                    onUse = {
                        selectedBedtimeMinutes = customBedtimeMinutes
                        selectedBedtimeDayOffset = if (customBedtimeMinutes < wakeMinutes) 0 else -1
                        step = WarmlyOnboardingStep.BEDTIME
                    },
                )
        }
    }

    if (showOpenSourceNotice) {
        AlertDialog(
            onDismissRequest = { showOpenSourceNotice = false },
            title = { Text(stringResource(R.string.warmly_open_source_title)) },
            text = { Text(stringResource(R.string.gpl_modification_notice)) },
            confirmButton = {
                TextButton(onClick = { showOpenSourceNotice = false }) {
                    Text(stringResource(R.string.warmly_close))
                }
            },
        )
    }
}

@Composable
private fun WarmlyWelcome(
    onContinue: () -> Unit,
    onOpenSourceNotice: () -> Unit,
) {
    WarmlyStepWithBottomAction(
        action = {
            WarmlyPrimaryButton(
                text = stringResource(R.string.warmly_create_first_plan),
                onClick = onContinue,
            )
        }
    ) {
        WarmlyStepLabel(R.string.warmly_brand)
        WarmlyHeading(R.string.warmly_welcome_title)
        WarmlyBody(R.string.warmly_welcome_body)
        TextButton(onClick = onOpenSourceNotice) {
            Text(stringResource(R.string.warmly_open_source_action), color = WarmlyCream)
        }
    }
}

@Composable
private fun WarmlyWakeTime(
    wakeMinutes: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onContinue: () -> Unit,
) {
    WarmlyStepWithBottomAction(
        action = {
            WarmlyPrimaryButton(text = stringResource(R.string.warmly_next), onClick = onContinue)
        }
    ) {
        WarmlyStepLabel(R.string.warmly_onboarding_step_wake)
        WarmlyHeading(R.string.warmly_wake_question)
        Spacer(Modifier.height(28.dp))
        WarmlyTimeAdjuster(
            timeMinutes = wakeMinutes,
            decreaseDescription = stringResource(R.string.warmly_decrease_wake_time),
            increaseDescription = stringResource(R.string.warmly_increase_wake_time),
            onDecrease = onDecrease,
            onIncrease = onIncrease,
        )
    }
}

@Composable
private fun WarmlyBedtimeRecommendations(
    wakeTime: LocalTime,
    selectedBedtimeMinutes: Int?,
    onSelect: (Int, Int) -> Unit,
    onCustom: () -> Unit,
    onComplete: () -> Unit,
) {
    val recommendations = recommendBedtimes(wakeTime)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WarmlyStepLabel(R.string.warmly_onboarding_step_bedtime)
        WarmlyHeading(R.string.warmly_bedtime_question)
        WarmlyBody(R.string.warmly_bedtime_note)
        selectedBedtimeMinutes?.let {
            Text(
                text =
                    stringResource(
                        R.string.warmly_selected_bedtime,
                        formatWarmlyTime(LocalContext.current, it),
                    ),
                color = WarmlyCream,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        recommendations.forEach { recommendation ->
            val bedtimeMinutes = recommendation.bedtime.hour * 60 + recommendation.bedtime.minute
            OutlinedButton(
                onClick = { onSelect(bedtimeMinutes, recommendation.bedtimeDayOffset) },
                modifier =
                    Modifier.fillMaxWidth().testTag("bedtime-recommendation").semantics {
                        selected = selectedBedtimeMinutes == bedtimeMinutes
                    },
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatWarmlyTime(LocalContext.current, bedtimeMinutes),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(recommendation.kind.labelResource()),
                        color = WarmlyCream,
                    )
                }
            }
        }
        OutlinedButton(onClick = onCustom, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.warmly_custom_bedtime), color = Color.White)
        }
        if (selectedBedtimeMinutes != null) {
            WarmlyPrimaryButton(
                text = stringResource(R.string.warmly_save_sleep_plan),
                onClick = onComplete,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WarmlyCustomBedtime(
    bedtimeMinutes: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onUse: () -> Unit,
) {
    WarmlyStepWithBottomAction(
        action = {
            WarmlyPrimaryButton(
                text = stringResource(R.string.warmly_use_this_time),
                onClick = onUse,
            )
        }
    ) {
        WarmlyStepLabel(R.string.warmly_onboarding_step_custom_bedtime)
        WarmlyHeading(R.string.warmly_custom_bedtime_question)
        Spacer(Modifier.height(28.dp))
        WarmlyTimeAdjuster(
            timeMinutes = bedtimeMinutes,
            decreaseDescription = stringResource(R.string.warmly_decrease_bedtime),
            increaseDescription = stringResource(R.string.warmly_increase_bedtime),
            onDecrease = onDecrease,
            onIncrease = onIncrease,
        )
    }
}

@Composable
private fun WarmlyStepWithBottomAction(
    action: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { action() }
    }
}

@Composable
private fun WarmlyTimeAdjuster(
    timeMinutes: Int,
    decreaseDescription: String,
    increaseDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = formatWarmlyTime(LocalContext.current, timeMinutes),
            color = Color.White,
            fontSize = 56.sp,
            lineHeight = 60.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDecrease,
                modifier =
                    Modifier.weight(1f).semantics {
                        contentDescription = decreaseDescription
                    },
            ) {
                Text(stringResource(R.string.warmly_minus_15), color = Color.White)
            }
            OutlinedButton(
                onClick = onIncrease,
                modifier =
                    Modifier.weight(1f).semantics {
                        contentDescription = increaseDescription
                    },
            ) {
                Text(stringResource(R.string.warmly_plus_15), color = Color.White)
            }
        }
    }
}

@Composable
private fun WarmlyStepLabel(resource: Int) {
    Text(
        text = stringResource(resource),
        color = WarmlyCream,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun WarmlyHeading(resource: Int) {
    Text(
        text = stringResource(resource),
        color = Color.White,
        fontSize = 38.sp,
        lineHeight = 43.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun WarmlyBody(resource: Int) {
    Text(
        text = stringResource(resource),
        color = WarmlyCream.copy(alpha = 0.86f),
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun BedtimeRecommendationKind.labelResource(): Int =
    when (this) {
        BedtimeRecommendationKind.NINE_HOURS -> R.string.warmly_bedtime_nine_hours
        BedtimeRecommendationKind.EIGHT_HOURS -> R.string.warmly_bedtime_eight_hours
        BedtimeRecommendationKind.SEVEN_AND_HALF_HOURS ->
            R.string.warmly_bedtime_seven_and_half_hours
    }

@Composable
private fun WarmlyPrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            ButtonDefaults.buttonColors(containerColor = WarmlyCream, contentColor = WarmlyNight),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 8.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun wrapMinutes(minutes: Int): Int =
    ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

private fun Int.toLocalTime(): LocalTime = LocalTime.of(this / 60, this % 60)
