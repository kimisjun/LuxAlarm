/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsalmun.luxalarm.data.AlarmItem
import com.dsalmun.luxalarm.data.nextTrigger
import java.util.Calendar
import kotlinx.coroutines.flow.collectLatest

/**
 * The alarm list, wired to its [AlarmViewModel]. [ringtoneNameFor] is a seam: resolving a title
 * goes through [RingtoneManager] and the content resolver, so tests substitute a fixed name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    onSettingsClick: () -> Unit = {},
    alarmViewModel: AlarmViewModel = viewModel(factory = AlarmViewModelFactory()),
    ringtoneNameFor: @Composable (String?) -> String = { uri ->
        val context = LocalContext.current
        remember(uri) { getRingtoneDisplayName(context, uri) }
    },
) {
    val context = LocalContext.current
    val alarmStates by alarmViewModel.alarmUiStates.collectAsState()
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<AlarmItem?>(null) }
    var expandedAlarmId by remember { mutableStateOf<Int?>(null) }
    var alarmIdForRingtonePicker by remember { mutableStateOf<Int?>(null) }
    var alarmToDelete by remember { mutableStateOf<AlarmItem?>(null) }

    val ringtonePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val alarmId = alarmIdForRingtonePicker
            alarmIdForRingtonePicker = null
            if (alarmId == null || it.resultCode != Activity.RESULT_OK)
                return@rememberLauncherForActivityResult

            val selectedUri: Uri? =
                it.data?.let { data ->
                    IntentCompat.getParcelableExtra(
                        data,
                        RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                        Uri::class.java,
                    )
                }
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtoneUriToStore =
                selectedUri?.toString()?.takeUnless { selectedUri == defaultUri }
            alarmViewModel.setAlarmRingtone(alarmId, ringtoneUriToStore)
        }

    LaunchedEffect(key1 = Unit) {
        alarmViewModel.events.collectLatest { event ->
            when (event) {
                is AlarmViewModel.Event.ShowPermissionError -> {
                    Toast.makeText(
                            context,
                            "Cannot schedule exact alarms. Please grant permission in settings.",
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
                is AlarmViewModel.Event.ShowAlarmSetMessage -> {
                    showSetAlarmToast(context, event.hour, event.minute, event.repeatDays)
                }
            }
        }
    }

    val timePickerState =
        remember(alarmToEdit) {
            val calendar = Calendar.getInstance()
            val initialHour = alarmToEdit?.hour ?: calendar[Calendar.HOUR_OF_DAY]
            val initialMinute = alarmToEdit?.minute ?: calendar[Calendar.MINUTE]
            TimePickerState(
                initialHour = initialHour,
                initialMinute = initialMinute,
                is24Hour = true,
            )
        }

    AlarmScreenContent(
        alarmStates = alarmStates,
        expandedAlarmId = expandedAlarmId,
        ringtoneNameFor = ringtoneNameFor,
        onSettingsClick = onSettingsClick,
        onAddClick = {
            alarmToEdit = null
            showTimePickerDialog = true
        },
        onAlarmClick = { alarm ->
            expandedAlarmId = if (expandedAlarmId == alarm.id) null else alarm.id
        },
        onTimeClick = { alarm ->
            alarmToEdit = alarm
            showTimePickerDialog = true
        },
        onToggle = { alarm, isActive -> alarmViewModel.toggleAlarm(alarm.id, isActive) },
        onSkip = { alarm -> alarmViewModel.skipNext(alarm) },
        onCancelSkip = { alarm -> alarmViewModel.cancelSkip(alarm.id) },
        onRepeatDaysChange = { alarm, newDays ->
            alarmViewModel.setRepeatDays(alarm.id, newDays)
        },
        onVolumeChange = { alarm, newVolume -> alarmViewModel.setAlarmVolume(alarm.id, newVolume) },
        onVibrationToggle = { alarm, enabled ->
            alarmViewModel.setAlarmVibration(alarm.id, enabled)
        },
        onDeleteClick = { alarm -> alarmToDelete = alarm },
        onRingtoneClick = { alarm ->
            if (alarmIdForRingtonePicker == null) {
                alarmIdForRingtonePicker = alarm.id
                ringtonePickerLauncher.launch(ringtonePickerIntent(alarm))
            }
        },
    )

    if (showTimePickerDialog) {
        TimePickerDialog(
            onConfirm = {
                if (alarmToEdit != null) {
                    alarmViewModel.updateAlarmTime(
                        alarmToEdit!!.id,
                        timePickerState.hour,
                        timePickerState.minute,
                    )
                } else {
                    alarmViewModel.addAlarm(timePickerState.hour, timePickerState.minute)
                }
                showTimePickerDialog = false
                alarmToEdit = null
            },
            onDismiss = {
                showTimePickerDialog = false
                alarmToEdit = null
            },
            timePickerState = timePickerState,
        )
    }

    alarmToDelete?.let { alarm ->
        DeleteAlarmDialog(
            alarm = alarm,
            onConfirm = {
                alarmViewModel.deleteAlarm(alarm.id)
                alarmToDelete = null
            },
            onDismiss = { alarmToDelete = null },
        )
    }
}

private fun ringtonePickerIntent(alarm: AlarmItem): Intent {
    val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    val existingUri = alarm.ringtoneUri?.toUri() ?: defaultUri
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri)
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select ringtone")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreenContent(
    alarmStates: List<AlarmViewModel.AlarmUiState>,
    expandedAlarmId: Int?,
    ringtoneNameFor: @Composable (String?) -> String,
    onSettingsClick: () -> Unit,
    onAddClick: () -> Unit,
    onAlarmClick: (AlarmItem) -> Unit,
    onTimeClick: (AlarmItem) -> Unit,
    onToggle: (AlarmItem, Boolean) -> Unit,
    onSkip: (AlarmItem) -> Unit,
    onCancelSkip: (AlarmItem) -> Unit,
    onRepeatDaysChange: (AlarmItem, Set<Int>) -> Unit,
    onVolumeChange: (AlarmItem, Float) -> Unit,
    onVibrationToggle: (AlarmItem, Boolean) -> Unit,
    onDeleteClick: (AlarmItem) -> Unit,
    onRingtoneClick: (AlarmItem) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(
                    painter = painterResource(R.drawable.add_24px),
                    contentDescription = "Add Alarm",
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Lux Alarm") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = painterResource(R.drawable.settings_24px),
                            contentDescription = "Settings",
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
        if (alarmStates.isEmpty()) {
            Box(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No alarms set. Tap '+' to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(alarmStates, key = { it.alarm.id }) { state ->
                    val alarm = state.alarm
                    AlarmRow(
                        alarm = alarm,
                        isUpcoming = state.isUpcoming,
                        isSkippingNext = state.isSkippingNext,
                        onSkip = { onSkip(alarm) },
                        onCancelSkip = { onCancelSkip(alarm) },
                        ringtoneDisplayName = ringtoneNameFor(alarm.ringtoneUri),
                        expanded = expandedAlarmId == alarm.id,
                        onToggle = { isActive -> onToggle(alarm, isActive) },
                        onClick = { onAlarmClick(alarm) },
                        onTimeClick = { onTimeClick(alarm) },
                        onRepeatDaysChange = { newDays -> onRepeatDaysChange(alarm, newDays) },
                        onDelete = { onDeleteClick(alarm) },
                        onVolumeChange = { newVolume -> onVolumeChange(alarm, newVolume) },
                        onVibrationToggle = { enabled -> onVibrationToggle(alarm, enabled) },
                        onRingtoneClick = { onRingtoneClick(alarm) },
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteAlarmDialog(alarm: AlarmItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete alarm") },
        text = {
            Text(
                String.format(
                    LocalLocale.current.platformLocale,
                    "Delete the %02d:%02d alarm?",
                    alarm.hour,
                    alarm.minute,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AlarmRow(
    alarm: AlarmItem,
    isUpcoming: Boolean,
    isSkippingNext: Boolean,
    onSkip: () -> Unit,
    onCancelSkip: () -> Unit,
    ringtoneDisplayName: String,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onTimeClick: () -> Unit,
    onDelete: () -> Unit,
    onRepeatDaysChange: (Set<Int>) -> Unit,
    onRingtoneClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVibrationToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        String.format(
                            LocalLocale.current.platformLocale,
                            "%02d:%02d",
                            alarm.hour,
                            alarm.minute,
                        ),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onTimeClick),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Icon(
                            painter =
                                painterResource(
                                    if (expanded) R.drawable.keyboard_arrow_up_24px
                                    else R.drawable.keyboard_arrow_down_24px
                                ),
                            contentDescription = if (expanded) "Collapse" else "Expand",
                        )
                        Switch(
                            checked = alarm.isActive,
                            onCheckedChange = onToggle,
                            modifier = Modifier.semantics { contentDescription = "Alarm enabled" },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatRepeatDays(alarm.repeatDays, alarm.hour, alarm.minute),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isSkippingNext) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Skipping next alarm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onCancelSkip, contentPadding = PaddingValues(0.dp)) {
                        Text("Undo")
                    }
                }
            } else if (isUpcoming) {
                TextButton(onClick = onSkip, contentPadding = PaddingValues(0.dp)) {
                    Text("Dismiss")
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                DaySelector(
                    selectedDays = alarm.repeatDays,
                    onDayClick = { day ->
                        val newDays = alarm.repeatDays.toMutableSet()
                        if (newDays.contains(day)) {
                            newDays.remove(day)
                        } else {
                            newDays.add(day)
                        }
                        onRepeatDaysChange(newDays)
                    },
                )
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.notifications_active_24px),
                        contentDescription = "Ringtone",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = ringtoneDisplayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRingtoneClick),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.volume_up_24px),
                        contentDescription = "Volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    var sliderVolume by
                        remember(alarm.volume) { mutableFloatStateOf(alarm.volume ?: 1f) }
                    Slider(
                        value = sliderVolume,
                        onValueChange = { sliderVolume = it },
                        onValueChangeFinished = { onVolumeChange(sliderVolume) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.mobile_vibrate_24px),
                        contentDescription = "Vibration",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Checkbox(
                        checked = alarm.vibrationEnabled,
                        onCheckedChange = { onVibrationToggle(it) },
                        modifier = Modifier.semantics { contentDescription = "Vibration enabled" },
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.delete_24px),
                        contentDescription = "Delete alarm",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun DaySelector(selectedDays: Set<Int>, onDayClick: (Int) -> Unit) {
    val days =
        listOf(
            "S" to Calendar.SUNDAY,
            "M" to Calendar.MONDAY,
            "T" to Calendar.TUESDAY,
            "W" to Calendar.WEDNESDAY,
            "T" to Calendar.THURSDAY,
            "F" to Calendar.FRIDAY,
            "S" to Calendar.SATURDAY,
        )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        days.forEach { (label, day) ->
            val isSelected = selectedDays.contains(day)
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onDayClick(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color =
                        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

fun formatRepeatDays(days: Set<Int>, hour: Int, minute: Int): String {
    if (days.isEmpty()) {
        val now = Calendar.getInstance()
        val alarmTime =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return if (alarmTime.after(now)) "Today" else "Tomorrow"
    }
    if (days.size == 7) return "Every day"

    val sortedDays = days.toSortedSet()
    val dayNames = sortedDays.map {
        when (it) {
            Calendar.SUNDAY -> "Sun"
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            else -> ""
        }
    }
    return dayNames.joinToString(", ")
}

private fun getRingtoneDisplayName(context: Context, ringtoneUri: String?): String {
    val uri =
        if (ringtoneUri.isNullOrBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else ringtoneUri.toUri()
    return RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Unknown"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    timePickerState: TimePickerState,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Alarm Time") },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onConfirm) { Text("Set") }
            }
        },
    )
}

private fun showSetAlarmToast(context: Context, hour: Int, minute: Int, repeatDays: Set<Int>) {
    val now = Calendar.getInstance()
    val scheduledTimeMillis = nextTrigger(hour, minute, repeatDays, now.timeInMillis)

    // nextTrigger always returns an instant after `now`, so at least one part below applies.
    val diffMillis = scheduledTimeMillis - now.timeInMillis
    val totalMinutes = kotlin.math.ceil(diffMillis / (1000.0 * 60)).toInt()
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes % (60 * 24)) / 60
    val minutes = totalMinutes % 60

    val timeParts = mutableListOf<String>()
    if (days > 0) {
        timeParts.add("$days ${if (days == 1) "day" else "days"}")
    }
    if (hours > 0) {
        timeParts.add("$hours ${if (hours == 1) "hour" else "hours"}")
    }
    if (minutes > 0) {
        timeParts.add("$minutes ${if (minutes == 1) "minute" else "minutes"}")
    }

    val toastMessage = "Alarm set for ${timeParts.joinToString(", ")} from now."
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}
