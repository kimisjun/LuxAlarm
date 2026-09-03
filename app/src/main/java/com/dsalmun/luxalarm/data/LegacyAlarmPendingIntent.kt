/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Filter identity of the single global legacy alarm broadcast; extras are deliberately excluded.
 */
internal data class LegacyAlarmPendingIntentSpec(
    val kind: String,
    val requestCode: Int,
    val componentPackage: String,
    val componentClass: String,
    val action: String?,
    val data: String?,
    val categories: Set<String>,
    val packageName: String?,
) {
    fun intent(context: Context): Intent =
        Intent().setClassName(componentPackage, componentClass).apply {
            action = this@LegacyAlarmPendingIntentSpec.action
            data = this@LegacyAlarmPendingIntentSpec.data?.let(Uri::parse)
            this@LegacyAlarmPendingIntentSpec.categories.forEach(::addCategory)
            `package` = packageName
        }

    fun pendingIntent(
        context: Context,
        flags: Int,
        requestCode: Int = this.requestCode,
        configure: Intent.() -> Unit = {},
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent(context).apply(configure),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun identity(): String =
        "$kind;requestCode=$requestCode;component=$componentPackage/$componentClass;" +
            "action=$action;data=$data;categories=${categories.sorted()};package=$packageName"
}

internal val LEGACY_ALARM_PENDING_INTENT_SPEC =
    LegacyAlarmPendingIntentSpec(
        kind = "broadcast",
        requestCode = 0,
        componentPackage = "com.dsalmun.luxalarm",
        componentClass = "com.dsalmun.luxalarm.AlarmReceiver",
        action = null,
        data = null,
        categories = emptySet(),
        packageName = null,
    )
