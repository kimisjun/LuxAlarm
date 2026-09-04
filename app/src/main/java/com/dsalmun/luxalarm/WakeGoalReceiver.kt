/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData

/** Dormant GOAL receiver: validates canonical data and requests durable Room work only. */
class WakeGoalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parsed = intent.dataString?.let(WakePendingIntentData::parse) ?: return
        if (parsed.event.kind != WakeEventKind.GOAL) return
        val runtime = WakeReceiverRuntime.capture()
        executeAsync(runtime.executor) { runtime.coordinator(context).routeGoal(parsed) }
    }
}
