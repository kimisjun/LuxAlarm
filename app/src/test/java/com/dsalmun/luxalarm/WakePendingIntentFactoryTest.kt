/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakePendingIntentFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyLegalCreationUsesTheCompleteCanonicalIdentityTuple() {
        val start = WakeEventIdentity("matrix-start", WakeEventKind.START, 10_000L)
        val goal = WakeEventIdentity("matrix-goal", WakeEventKind.GOAL, 20_000L)
        val cases =
            listOf(
                CreationCase(
                    WakePendingIntentFactory.createPrimary(context, start),
                    WakePendingIntentData.primary(start),
                    WakeStartReceiver::class.java,
                    WakePendingIntentData.START_REQUEST_CODE,
                ),
                CreationCase(
                    WakePendingIntentFactory.createPrimary(context, goal),
                    WakePendingIntentData.primary(goal),
                    WakeGoalReceiver::class.java,
                    WakePendingIntentData.GOAL_REQUEST_CODE,
                ),
            ) +
                listOf(start, goal).flatMap { event ->
                    WakeRecoverySlotId.entries.map { slot ->
                        CreationCase(
                            WakePendingIntentFactory.createDynamic(
                                context,
                                event,
                                slot,
                                token = 1L,
                                recoveryTriggerEpochMillis = 11_000L,
                            ),
                            WakePendingIntentData.dynamic(event, slot, 1L, 11_000L),
                            receiverFor(event.kind),
                            requestCodeFor(event.kind),
                        )
                    }
                } +
                WakeRecoveryAnchorKind.entries
                    .filter { it != WakeRecoveryAnchorKind.GOAL_PRIMARY }
                    .map { kind ->
                        CreationCase(
                            WakePendingIntentFactory.createAnchor(context, goal, kind),
                            WakePendingIntentData.anchor(goal, kind),
                            WakeGoalReceiver::class.java,
                            WakePendingIntentData.GOAL_REQUEST_CODE,
                        )
                    }

        assertEquals(cases.size, cases.map { it.pendingIntent }.toSet().size)
        assertEquals(cases.size, cases.map { it.canonicalData }.toSet().size)
        cases.forEach { case ->
            val shadow = shadowOf(case.pendingIntent)
            val intent = shadow.savedIntent
            assertEquals(ComponentName(context, case.receiver), intent.component)
            assertEquals(case.canonicalData, intent.dataString)
            assertEquals(case.requestCode, shadow.requestCode)
            assertEquals(
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                shadow.flags,
            )
            assertNull(intent.action)
            assertNull(intent.categories)
            assertNull(intent.`package`)
        }
    }

    @Test
    fun everyLegalLookupIsAbsentBeforeCreationAndFindsOnlyItsCanonicalToken() {
        listOf(WakeEventKind.START, WakeEventKind.GOAL).forEachIndexed { index, kind ->
            val event = WakeEventIdentity("lookup-matrix-$kind", kind, 90_000L + index)
            assertNull(WakePendingIntentFactory.lookupPrimary(context, event))
            val primary = WakePendingIntentFactory.createPrimary(context, event)
            assertSame(primary, WakePendingIntentFactory.lookupPrimary(context, event))

            WakeRecoverySlotId.entries.forEach { slot ->
                assertNull(
                    WakePendingIntentFactory.lookupDynamic(context, event, slot, 9L, 91_000L)
                )
                val dynamic =
                    WakePendingIntentFactory.createDynamic(context, event, slot, 9L, 91_000L)
                assertSame(
                    dynamic,
                    WakePendingIntentFactory.lookupDynamic(context, event, slot, 9L, 91_000L),
                )
                assertNull(
                    WakePendingIntentFactory.lookupDynamic(context, event, slot, 10L, 91_000L)
                )
            }
        }
        val goal = WakeEventIdentity("lookup-anchor-matrix", WakeEventKind.GOAL, 100_000L)
        WakeRecoveryAnchorKind.entries
            .filter { it != WakeRecoveryAnchorKind.GOAL_PRIMARY }
            .forEach { kind ->
                assertNull(WakePendingIntentFactory.lookupAnchor(context, goal, kind))
                val anchor = WakePendingIntentFactory.createAnchor(context, goal, kind)
                assertSame(anchor, WakePendingIntentFactory.lookupAnchor(context, goal, kind))
            }
    }

    @Test
    fun arbitraryExtrasNeitherChangeTokenIdentityNorCanonicalParserInput() {
        val event = WakeEventIdentity("extras-ignored", WakeEventKind.START, 60_000L)
        val canonical = WakePendingIntentData.primary(event)
        val created = WakePendingIntentFactory.createPrimary(context, event)
        val withUntrustedExtras =
            Intent(context, WakeStartReceiver::class.java)
                .setData(Uri.parse(canonical))
                .putExtra("snapshot_id", "forged")
                .putExtra("trigger", Long.MAX_VALUE)
        val updated =
            PendingIntent.getBroadcast(
                context,
                WakePendingIntentData.START_REQUEST_CODE,
                withUntrustedExtras,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        assertSame(created, updated)
        val savedIntent = shadowOf(assertNotNull(updated)).savedIntent
        assertEquals("forged", savedIntent.getStringExtra("snapshot_id"))
        assertEquals(Long.MAX_VALUE, savedIntent.getLongExtra("trigger", -1L))
        assertEquals(canonical, savedIntent.dataString)
        assertEquals(
            event,
            assertNotNull(WakePendingIntentData.parse(assertNotNull(savedIntent.dataString))).event,
        )
    }

    @Test
    fun compiledCreationAndLookupFlagsEqualTheExactApprovedIntegerSets() {
        val type = WakePendingIntentFactory::class.java
        val creationField = type.getDeclaredField("CREATION_FLAGS").apply { isAccessible = true }
        val lookupField = type.getDeclaredField("LOOKUP_FLAGS").apply { isAccessible = true }

        assertTrue(Modifier.isPrivate(creationField.modifiers))
        assertTrue(Modifier.isPrivate(lookupField.modifiers))
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            creationField.getInt(null),
        )
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            lookupField.getInt(null),
        )
    }

    private data class CreationCase(
        val pendingIntent: PendingIntent,
        val canonicalData: String,
        val receiver: Class<*>,
        val requestCode: Int,
    )

    private fun receiverFor(kind: WakeEventKind): Class<*> =
        when (kind) {
            WakeEventKind.START -> WakeStartReceiver::class.java
            WakeEventKind.GOAL -> WakeGoalReceiver::class.java
        }

    private fun requestCodeFor(kind: WakeEventKind): Int =
        when (kind) {
            WakeEventKind.START -> WakePendingIntentData.START_REQUEST_CODE
            WakeEventKind.GOAL -> WakePendingIntentData.GOAL_REQUEST_CODE
        }
}
