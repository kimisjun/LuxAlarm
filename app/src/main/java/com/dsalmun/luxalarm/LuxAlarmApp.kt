/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

/** Warmly Solo routes one durable plan to either onboarding or its compact home summary. */
@Composable
fun LuxAlarmApp(
    sleepPlanStore: SleepPlanStore = AppContainer.sleepPlanStore,
    wakePlaylistStore: WakePlaylistStore = AppContainer.wakePlaylistStore,
) {
    var loaded by remember(sleepPlanStore) { mutableStateOf(false) }
    var sleepPlan by remember(sleepPlanStore) { mutableStateOf<SleepPlan?>(null) }
    var selectedPlaylist by remember(wakePlaylistStore) { mutableStateOf<WakePlaylist?>(null) }
    val scope = rememberCoroutineScope()
    var showMusic by remember { mutableStateOf(false) }

    LaunchedEffect(sleepPlanStore) {
        sleepPlan = sleepPlanStore.load()
        selectedPlaylist = wakePlaylistStore.selectedPlaylistForWake()
        loaded = true
    }
    LifecycleResumeEffect(wakePlaylistStore, showMusic) {
        if (!showMusic) {
            scope.launch { selectedPlaylist = wakePlaylistStore.selectedPlaylistForWake() }
        }
        onPauseOrDispose {}
    }

    when {
        !loaded ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        sleepPlan == null ->
            WarmlyOnboardingScreen { completed ->
                scope.launch {
                    sleepPlanStore.save(completed)
                    sleepPlan = completed
                }
            }
        showMusic ->
            WakePlaylistRoute(
                playlistStore = wakePlaylistStore,
                onBack = {
                    scope.launch {
                        selectedPlaylist = wakePlaylistStore.selectedPlaylistForWake()
                        showMusic = false
                    }
                },
                onSelectionChanged = { selectedPlaylist = it },
            )
        else ->
            WarmlyHomeScreen(
                plan = checkNotNull(sleepPlan),
                selectedPlaylist = selectedPlaylist,
                onOpenMusic = { showMusic = true },
            )
    }
}

@Composable
private fun WarmlyHomeScreen(
    plan: SleepPlan,
    selectedPlaylist: WakePlaylist?,
    onOpenMusic: () -> Unit,
) {
    val context = LocalContext.current
    val wake = formatWarmlyTime(context, plan.wakeMinutes)
    val bedtime = formatWarmlyTime(context, plan.bedtimeMinutes)
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.warmly_brand), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.warmly_home_wake_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(wake, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.warmly_home_bedtime, bedtime),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.warmly_home_saved_locally),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            selectedPlaylist?.let {
                stringResource(R.string.warmly_home_selected_playlist, it.name)
            } ?: stringResource(R.string.warmly_home_default_sound)
        )
        Button(onClick = onOpenMusic) { Text(stringResource(R.string.warmly_home_music)) }
    }
}
