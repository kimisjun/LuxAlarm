/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId

/** The single Android token factory for canonical wake-event broadcast identities. */
internal object WakePendingIntentFactory {
    fun createPrimary(context: Context, event: WakeEventIdentity): PendingIntent =
        requireNotNull(create(context, event, WakePendingIntentData.primary(event), CREATION_FLAGS))

    fun lookupPrimary(context: Context, event: WakeEventIdentity): PendingIntent? =
        create(context, event, WakePendingIntentData.primary(event), LOOKUP_FLAGS)

    fun createDynamic(
        context: Context,
        event: WakeEventIdentity,
        slot: WakeRecoverySlotId,
        token: Long,
        recoveryTriggerEpochMillis: Long,
    ): PendingIntent =
        requireNotNull(
            create(
                context,
                event,
                WakePendingIntentData.dynamic(event, slot, token, recoveryTriggerEpochMillis),
                CREATION_FLAGS,
            )
        )

    fun lookupDynamic(
        context: Context,
        event: WakeEventIdentity,
        slot: WakeRecoverySlotId,
        token: Long,
        recoveryTriggerEpochMillis: Long,
    ): PendingIntent? =
        create(
            context,
            event,
            WakePendingIntentData.dynamic(event, slot, token, recoveryTriggerEpochMillis),
            LOOKUP_FLAGS,
        )

    fun createAnchor(
        context: Context,
        event: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
    ): PendingIntent =
        requireNotNull(
            create(context, event, WakePendingIntentData.anchor(event, kind), CREATION_FLAGS)
        )

    fun lookupAnchor(
        context: Context,
        event: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
    ): PendingIntent? =
        create(context, event, WakePendingIntentData.anchor(event, kind), LOOKUP_FLAGS)

    private fun create(
        context: Context,
        event: WakeEventIdentity,
        canonicalData: String,
        flags: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(event.kind),
            explicitIntent(context, event.kind, canonicalData),
            flags,
        )

    private fun explicitIntent(
        context: Context,
        kind: WakeEventKind,
        canonicalData: String,
    ): Intent = Intent(context, receiver(kind)).setData(Uri.parse(canonicalData))

    private fun receiver(kind: WakeEventKind): Class<out android.content.BroadcastReceiver> =
        when (kind) {
            WakeEventKind.START -> WakeStartReceiver::class.java
            WakeEventKind.GOAL -> WakeGoalReceiver::class.java
        }

    private fun requestCode(kind: WakeEventKind): Int =
        when (kind) {
            WakeEventKind.START -> WakePendingIntentData.START_REQUEST_CODE
            WakeEventKind.GOAL -> WakePendingIntentData.GOAL_REQUEST_CODE
        }

    private const val CREATION_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private const val LOOKUP_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
}
