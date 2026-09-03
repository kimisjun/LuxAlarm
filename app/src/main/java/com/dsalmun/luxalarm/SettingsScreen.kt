/*
 * This file is part of Lux Alarm, authored by Daniel Salmun, and was modified
 * for GentleWake in 2026.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberLightSensorValue(): Float {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lightLevel by remember { mutableFloatStateOf(0f) }

    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_LIGHT) {
                        lightLevel = event.values[0]
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    lightSensor?.let {
                        sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    sensorManager.unregisterListener(listener)
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            sensorManager.unregisterListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return lightLevel
}

/** Both dependencies are defaulted parameters so a test can supply its own. */
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    settingsManager: SettingsManager = remember { AppContainer.settingsManager },
    currentLightLevel: Float = rememberLightSensorValue(),
) {
    val context = LocalContext.current
    val requiredLuxLevel by settingsManager.requiredLuxLevel.collectAsState()
    val wakeProfile by settingsManager.wakeProfile.collectAsState()
    var sliderValue by remember(requiredLuxLevel) { mutableFloatStateOf(requiredLuxLevel) }
    val scope = rememberCoroutineScope()
    val audioStore =
        remember(context) {
            WakeAudioStore(File(context.filesDir, "gentle-wake-audio")) { documentUri ->
                context.contentResolver.openInputStream(documentUri.toUri())
            }
        }
    val audioPicker =
        rememberLauncherForActivityResult(WakeAudioDocumentContract()) { documentUri ->
            if (documentUri != null) {
                val profileAtSelection = settingsManager.getWakeProfile()
                scope.launch {
                    val imported =
                        withContext(Dispatchers.IO) {
                            runCatching { audioStore.importDocument(documentUri) }
                        }
                    imported.onSuccess { source ->
                        settingsManager.updateWakeProfile(
                            profileAtSelection.copy(importedAudioPath = source.path)
                        )
                    }
                }
            }
        }
    val playableProfile =
        if (audioStore.playbackSource(wakeProfile.importedAudioPath) == WakeAudioSource.Default) {
            wakeProfile.copy(importedAudioPath = null)
        } else {
            wakeProfile
        }

    SettingsScreenContent(
        requiredLuxLevel = sliderValue,
        currentLightLevel = currentLightLevel,
        wakeProfile = playableProfile,
        onBackClick = onBackClick,
        onLuxLevelChange = { sliderValue = it },
        onLuxLevelChangeFinished = { settingsManager.setRequiredLuxLevel(sliderValue) },
        onWakeProfileChange = settingsManager::updateWakeProfile,
        onImportAudioClick = { audioPicker.launch(Unit) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    requiredLuxLevel: Float,
    currentLightLevel: Float,
    wakeProfile: WakeProfile = WakeProfile(),
    onBackClick: () -> Unit,
    onLuxLevelChange: (Float) -> Unit,
    onLuxLevelChangeFinished: () -> Unit,
    onWakeProfileChange: (WakeProfile) -> Unit = {},
    onImportAudioClick: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            WakeProfileSetting(
                profile = wakeProfile,
                onProfileChange = onWakeProfileChange,
                onImportAudioClick = onImportAudioClick,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LuxLevelSetting(
                currentValue = requiredLuxLevel,
                currentLightLevel = currentLightLevel,
                onValueChange = onLuxLevelChange,
                onValueChangeFinished = onLuxLevelChangeFinished,
            )
        }
    }
}

@Composable
private fun WakeProfileSetting(
    profile: WakeProfile,
    onProfileChange: (WakeProfile) -> Unit,
    onImportAudioClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("부드러운 기상", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                "${profile.rampMinutes}분 · ${(profile.startVolume * 100).toInt()}% → " +
                    "${(profile.maxVolume * 100).toInt()}%",
                fontSize = 16.sp,
            )
            Text(
                "미리보기 전용 · 예약 실행은 아직 연결되지 않았어요",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = profile.dismissal == WakeDismissal.CONFIRM,
                    onClick = {
                        onProfileChange(profile.copy(dismissal = WakeDismissal.CONFIRM))
                    },
                    label = { Text("확인") },
                )
                FilterChip(
                    selected = profile.dismissal == WakeDismissal.LUX,
                    onClick = { onProfileChange(profile.copy(dismissal = WakeDismissal.LUX)) },
                    label = { Text("Lux 미션 (선택)") },
                )
            }
            Text(
                if (profile.importedAudioPath == null) "기본 알람 소리" else "가져온 음악",
                fontSize = 14.sp,
            )
            OutlinedButton(onClick = onImportAudioClick) { Text("휴대폰 음악 가져오기") }
            Text(
                stringResource(R.string.gpl_modification_notice),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
internal fun LuxLevelSetting(
    currentValue: Float,
    currentLightLevel: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val meetsThreshold = currentLightLevel >= currentValue

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Required Light Level",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The minimum light level (in lux) required to turn off the alarm.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Current light level display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (meetsThreshold) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Current Light Level",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${currentLightLevel.toInt()} lux",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (meetsThreshold) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${SettingsManager.MIN_LUX_LEVEL.toInt()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Slider(
                    value = currentValue,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = SettingsManager.MIN_LUX_LEVEL..SettingsManager.MAX_LUX_LEVEL,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    text = "${SettingsManager.MAX_LUX_LEVEL.toInt()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "${currentValue.toInt()} lux",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text =
                    if (meetsThreshold) "✓ Current light level meets threshold"
                    else "Current light is below threshold",
                fontSize = 12.sp,
                color =
                    if (meetsThreshold) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
