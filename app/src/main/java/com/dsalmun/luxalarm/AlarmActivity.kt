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

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import java.text.SimpleDateFormat
import java.util.*

class AlarmActivity : ComponentActivity(), SensorEventListener {

    private var alarmId: Int = -1
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var currentLightLevel by mutableFloatStateOf(0f)
    private var requiredLightLevel by mutableFloatStateOf(SettingsManager.DEFAULT_LUX_LEVEL)
    private var dismissal = WakeDismissal.DEFAULT
    private var gentleWake = false
    private var rampMinutes = WakeRamp.DEFAULT_RAMP_MINUTES
    private var rampStartedAtElapsedRealtime = 0L
    private var gentleWakeProgress by mutableFloatStateOf(0f)
    private val screenRampHandler = Handler(Looper.getMainLooper())
    private var screenRampRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    /* block back press while alarm is ringing */
                }
            },
        )

        alarmId = intent.getIntExtra("alarm_id", -1)
        gentleWake = intent.getBooleanExtra("gentle_wake", false)
        dismissal =
            intent.getStringExtra("dismissal")?.let { stored ->
                WakeDismissal.entries.firstOrNull { it.name == stored }
            } ?: WakeDismissal.DEFAULT
        rampMinutes = intent.getIntExtra("ramp_minutes", WakeRamp.DEFAULT_RAMP_MINUTES)
        rampStartedAtElapsedRealtime =
            if (intent.hasExtra("ramp_started_elapsed_realtime")) {
                intent.getLongExtra("ramp_started_elapsed_realtime", SystemClock.elapsedRealtime())
            } else {
                SystemClock.elapsedRealtime()
            }
        setupScreenWake()
        if (!gentleWake || dismissal == WakeDismissal.LUX) {
            requiredLightLevel = AppContainer.settingsManager.getRequiredLuxLevel()
            setupLightSensor()
        }
        if (gentleWake) {
            startGentleWakeScreenRamp()
        }

        setContent {
            LuxAlarmTheme {
                if (!gentleWake) {
                    AlarmRingingScreen(
                        currentLightLevel = currentLightLevel,
                        requiredLightLevel = requiredLightLevel,
                        onStopAlarm = { stopAlarm() },
                    )
                } else {
                    GentleWakeRingingScreen(
                        progress = gentleWakeProgress,
                        currentLightLevel = currentLightLevel,
                        requiredLightLevel = requiredLightLevel,
                        requiresLux = dismissal == WakeDismissal.LUX,
                        onAwake = { stopAlarm() },
                    )
                }
            }
        }
        setupFullscreen()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        isVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isVolumeKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    private fun setupLightSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onResume() {
        super.onResume()
        if (!gentleWake || dismissal == WakeDismissal.LUX) {
            lightSensor?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!gentleWake || dismissal == WakeDismissal.LUX) sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            currentLightLevel = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // No action needed for light sensor accuracy changes
    }

    private fun setupScreenWake() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startGentleWakeScreenRamp() {
        val durationMillis = rampMinutes.coerceAtLeast(0).toLong() * 60_000L
        lateinit var updateScreen: Runnable
        updateScreen = Runnable {
            val elapsedMillis =
                (SystemClock.elapsedRealtime() - rampStartedAtElapsedRealtime).coerceAtLeast(0L)
            gentleWakeProgress =
                if (durationMillis == 0L) 1f
                else (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
            window.attributes =
                window.attributes.apply {
                    screenBrightness = WakeRamp.frameAt(gentleWakeProgress).screenBrightness
                }
            if (gentleWakeProgress < 1f) {
                screenRampHandler.postDelayed(updateScreen, SCREEN_UPDATE_INTERVAL_MILLIS)
            } else if (screenRampRunnable === updateScreen) {
                screenRampRunnable = null
            }
        }
        screenRampRunnable = updateScreen
        screenRampHandler.post(updateScreen)
    }

    override fun onDestroy() {
        screenRampRunnable?.let(screenRampHandler::removeCallbacks)
        screenRampRunnable = null
        super.onDestroy()
    }

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    private fun stopAlarm() {
        val stopIntent =
            Intent(this, AlarmService::class.java).apply {
                action = AlarmService.ACTION_STOP_ALARM
                putExtra("alarm_id", alarmId)
            }
        startService(stopIntent)
        finish()
    }

    private companion object {
        const val SCREEN_UPDATE_INTERVAL_MILLIS = 100L
    }
}

@Composable
fun GentleWakeRingingScreen(
    progress: Float,
    currentLightLevel: Float = 0f,
    requiredLightLevel: Float = SettingsManager.DEFAULT_LUX_LEVEL,
    requiresLux: Boolean = false,
    onAwake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame = WakeRamp.frameAt(progress)
    val sunrise = Color(frame.sunriseRgb[0], frame.sunriseRgb[1], frame.sunriseRgb[2])
    val currentTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val currentDate = remember {
        SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date())
    }
    val canDismiss = !requiresLux || currentLightLevel >= requiredLightLevel

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("gentle-wake-ringing")
                .background(Brush.verticalGradient(listOf(Color(0xFF140A05), sunrise))),
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
                text = currentDate,
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = currentTime,
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
            )
            if (requiresLux) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "밝기 미션 · ${currentLightLevel.toInt()} / ${requiredLightLevel.toInt()} lux",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (canDismiss) "충분히 밝아요" else "더 밝은 곳으로 이동해 주세요",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(56.dp))
            Button(
                onClick = onAwake,
                enabled = canDismiss,
                modifier = Modifier.widthIn(min = 280.dp).heightIn(min = 72.dp),
                shape = RoundedCornerShape(36.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF5A2508),
                    ),
            ) {
                Text(
                    text = if (canDismiss) "일어났어요" else "밝은 곳으로 이동 중",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun AlarmRingingScreen(
    currentLightLevel: Float,
    requiredLightLevel: Float,
    onStopAlarm: () -> Unit,
) {
    val currentTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val currentDate = remember {
        SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date())
    }
    val greeting = remember { getTimeBasedGreeting() }

    val gradientColors =
        listOf(
            Color(0xFF6366F1), // Soft indigo
            Color(0xFF8B5CF6), // Soft purple
            Color(0xFFA855F7), // Light purple
        )

    val isButtonEnabled = currentLightLevel >= requiredLightLevel

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors = gradientColors,
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY,
                        )
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TimeDisplay(greeting, currentDate, currentTime)
            Spacer(modifier = Modifier.height(48.dp))
            LightSensorIndicator(currentLightLevel, requiredLightLevel, isButtonEnabled)
            AlarmControlButton(isButtonEnabled, onStopAlarm)
        }
    }
}

@Composable
private fun TimeDisplay(greeting: String, currentDate: String, currentTime: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = greeting,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = currentDate,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = currentTime,
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LightSensorIndicator(
    currentLightLevel: Float,
    requiredLightLevel: Float,
    isButtonEnabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Light Level",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = "${currentLightLevel.toInt()} lx",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (isButtonEnabled) Color(0xFF10B981) else Color.White,
            )
            Text(
                text =
                    if (isButtonEnabled) "Bright enough!"
                    else "Need ${requiredLightLevel.toInt()} lx minimum",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            if (!isButtonEnabled) {
                Text(
                    text = "Go to a brighter area to turn off alarm",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AlarmControlButton(isButtonEnabled: Boolean, onStopAlarm: () -> Unit) {
    ElevatedButton(
        onClick = onStopAlarm,
        enabled = isButtonEnabled,
        modifier = Modifier.fillMaxWidth(0.6f).height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors =
            ButtonDefaults.elevatedButtonColors(
                containerColor =
                    if (isButtonEnabled) Color.White.copy(alpha = 0.95f)
                    else Color.Gray.copy(alpha = 0.5f),
                contentColor =
                    if (isButtonEnabled) Color(0xFF6366F1) else Color.White.copy(alpha = 0.6f),
                disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        elevation =
            ButtonDefaults.elevatedButtonElevation(
                defaultElevation = if (isButtonEnabled) 8.dp else 2.dp,
                pressedElevation = if (isButtonEnabled) 12.dp else 2.dp,
                disabledElevation = 0.dp,
            ),
    ) {
        Text(
            text = if (isButtonEnabled) "Turn Off Alarm" else "Need More Light",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun getTimeBasedGreeting(): String {
    val calendar = Calendar.getInstance()
    val hour = calendar[Calendar.HOUR_OF_DAY]

    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..17 -> "Good Afternoon"
        in 18..21 -> "Good Evening"
        else -> "Time to Wake Up" // Late night/early morning (22-4)
    }
}
