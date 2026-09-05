/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.data.AuthenticatedWakeEventArrivalFactory
import com.dsalmun.luxalarm.wake.AuthenticatedWakeRecoveryAnchorArrivalFactory
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeReceiverArchitectureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = repositoryRoot()

    @Test
    fun mergedManifestRegistersPrivateReceiversWithoutImplicitFilters() {
        listOf(WakeStartReceiver::class.java, WakeGoalReceiver::class.java).forEach { receiver ->
            val info = context.packageManager.getReceiverInfo(ComponentName(context, receiver), 0)
            assertEquals(receiver.name, info.name)
            assertFalse(info.exported, receiver.name)
            val implicit =
                context.packageManager.queryBroadcastReceivers(
                    Intent("com.dsalmun.luxalarm.UNTRUSTED_WAKE").setPackage(context.packageName),
                    0,
                )
            assertTrue(implicit.none { it.activityInfo.name == receiver.name }, receiver.name)
        }
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        listOf(".WakeStartReceiver", ".WakeGoalReceiver").forEach { name ->
            assertTrue(
                Regex(
                        "<receiver\\s+android:name=\\\"${Regex.escape(name)}\\\"\\s+android:exported=\\\"false\\\"\\s*/>"
                    )
                    .containsMatchIn(manifest),
                "$name must be a filter-free declaration",
            )
        }
    }

    @Test
    fun receiverSourceHasNoExtrasRawDaoSchedulingOrFgsBoundaryBypass() {
        val sources =
            listOf("WakeStartReceiver.kt", "WakeGoalReceiver.kt").associateWith {
                File(root, "app/src/main/java/com/dsalmun/luxalarm/$it").readText()
            }
        val forbidden =
            listOf(
                "extras",
                "getExtra",
                "Bundle",
                "wakeEventDispatchDao",
                "wakeRecoveryAnchorDao",
                "AlarmManager",
                "WakeScheduler",
                "startForegroundService",
                "AlarmService",
            )
        sources.forEach { (name, source) ->
            forbidden.forEach { token ->
                assertFalse(source.contains(token), "$name contains $token")
            }
        }
        val goalRoute =
            sources
                .getValue("WakeStartReceiver.kt")
                .substringAfter("fun routeGoal")
                .substringBefore("private fun processAnchor")
        assertFalse(goalRoute.contains("WakeEventArrival.Primary"))
        assertTrue(goalRoute.contains("processAnchor"))
    }

    @Test
    fun canonicalParserIsTheOnlyProductionCallerOfBothVerifiedFactories() {
        val production =
            File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }
        val dynamicCallers =
            production
                .filter {
                    it.readText()
                        .contains(
                            "AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData"
                        )
                }
                .toList()
        val anchorCallers =
            File(root, "app/src/main/java")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter {
                    it.readText()
                        .contains(
                            "AuthenticatedWakeRecoveryAnchorArrivalFactory.fromVerifiedPendingIntentData"
                        )
                }
                .toList()
        assertEquals(listOf("WakePendingIntentData.kt"), dynamicCallers.map(File::getName))
        assertEquals(listOf("WakePendingIntentData.kt"), anchorCallers.map(File::getName))
        assertTrue(
            AuthenticatedWakeEventArrivalFactory::class.java.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            }
        )
        assertTrue(
            AuthenticatedWakeRecoveryAnchorArrivalFactory::class.java.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            }
        )
    }

    @Test
    fun startPrimarySchedulerCanReachAndroidTokensOnlyThroughCanonicalFactory() {
        val scheduler =
            File(root, "app/src/main/java/com/dsalmun/luxalarm/StartPrimaryWakeScheduler.kt")
                .readText()
        val factory =
            File(root, "app/src/main/java/com/dsalmun/luxalarm/WakePendingIntentFactory.kt")
                .readText()

        assertTrue(scheduler.contains("WakePendingIntentFactory.createPrimary(context, event)"))
        listOf(
                "PendingIntent.getBroadcast",
                "WakePendingIntentData.",
                "Uri.parse",
                ".setData(",
                ".putExtra(",
                "extras",
            )
            .forEach { forbidden ->
                assertFalse(scheduler.contains(forbidden), "scheduler contains $forbidden")
            }
        assertEquals(1, Regex("PendingIntent.getBroadcast").findAll(factory).count())
        assertTrue(factory.contains("WakePendingIntentData.primary(event)"))
        assertTrue(factory.contains("WakePendingIntentData.dynamic(event"))
        assertTrue(factory.contains("WakePendingIntentData.anchor(event"))
        assertFalse(factory.contains(".putExtra("))
    }

    @Test
    fun primaryCoordinatorRoutesAndroidTokensAndDurabilityThroughCanonicalBoundariesOnly() {
        val coordinator =
            File(root, "app/src/main/java/com/dsalmun/luxalarm/PrimaryWakeScheduleCoordinator.kt")
                .readText()
        assertTrue(coordinator.contains("WakePendingIntentFactory.createPrimary(context, event)"))
        assertTrue(coordinator.contains("WakePendingIntentFactory.createDynamic("))
        assertTrue(coordinator.contains("alarmClockPort.schedule("))
        assertTrue(coordinator.contains("store.recordApiReturn(snapshot, event)"))
        assertTrue(coordinator.contains("store.recordDynamicApiReturn(snapshot, request)"))
        val scheduleBody = coordinator.substringAfter("private fun scheduleAndRecord")
        val createIndex =
            scheduleBody.indexOf("WakePendingIntentFactory.createPrimary(context, event)")
        val preflightIndex = scheduleBody.indexOf("store.preflightApiCall(snapshot, event)")
        val finalClockIndex = scheduleBody.indexOf("val finalNow = epochClock()")
        val scheduleIndex = scheduleBody.indexOf("alarmClockPort.schedule(")
        assertTrue(preflightIndex in 0 until createIndex)
        assertTrue(createIndex < finalClockIndex)
        assertTrue(finalClockIndex < scheduleIndex)
        val finalClockToOsCall = scheduleBody.substring(finalClockIndex, scheduleIndex)
        assertFalse(finalClockToOsCall.contains("createPrimary"))
        assertFalse(finalClockToOsCall.contains("preflightApiCall"))
        assertTrue(
            coordinator.indexOf("alarmClockPort.schedule(") <
                coordinator.indexOf("store.recordApiReturn(snapshot, event)")
        )
        val dynamicBody = coordinator.substringAfter("private fun scheduleDynamicAndRecord")
        val dynamicPreflightIndex =
            dynamicBody.indexOf("store.preflightDynamicApiCall(snapshot, request)")
        val dynamicCreateIndex = dynamicBody.indexOf("WakePendingIntentFactory.createDynamic(")
        val dynamicClockIndex = dynamicBody.indexOf("val finalNow = epochClock()")
        val dynamicScheduleIndex = dynamicBody.indexOf("alarmClockPort.schedule(")
        val dynamicRecordIndex =
            dynamicBody.indexOf("store.recordDynamicApiReturn(snapshot, request)")
        assertTrue(dynamicPreflightIndex in 0 until dynamicCreateIndex)
        assertTrue(dynamicCreateIndex < dynamicClockIndex)
        assertTrue(dynamicClockIndex < dynamicScheduleIndex)
        assertTrue(dynamicScheduleIndex < dynamicRecordIndex)
        listOf(
                "PendingIntent.getBroadcast",
                "WakePendingIntentData.",
                "Uri.parse",
                ".setData(",
                ".putExtra(",
                "FLAG_NO_CREATE",
                "AlarmDatabase",
                "wakePrimaryScheduleDao",
            )
            .forEach { forbidden ->
                assertFalse(coordinator.contains(forbidden), "coordinator contains $forbidden")
            }
    }

    @Test
    fun trustedImplementationsExposeNoConstructorOrCreateBypassIncludingSyntheticBytecode() {
        val start = WakeEventIdentity("surface-start", WakeEventKind.START, 1_000L)
        val goal = WakeEventIdentity("surface-goal", WakeEventKind.GOAL, 2_000L)
        val parsedTypes =
            listOf(
                    WakePendingIntentData.primary(start),
                    WakePendingIntentData.dynamic(start, WakeRecoverySlotId.A, 1L, 1_500L),
                    WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M),
                )
                .map { requireNotNull(WakePendingIntentData.parse(it)).javaClass }
        val trustedImplementations =
            parsedTypes +
                AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
                        start,
                        WakeRecoverySlotId.B,
                        2L,
                        1_600L,
                    )
                    .javaClass +
                AuthenticatedWakeRecoveryAnchorArrivalFactory.fromVerifiedPendingIntentData(
                        goal,
                        WakeRecoveryAnchorKind.GOAL_PLUS_5M,
                        302_000L,
                        WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_5M),
                    )
                    .javaClass
        assertEquals(5, trustedImplementations.size)
        trustedImplementations.forEach { type ->
            type.declaredConstructors.forEach { constructor ->
                assertFalse(
                    Modifier.isPublic(constructor.modifiers) ||
                        Modifier.isProtected(constructor.modifiers),
                    "${type.name} exposes $constructor (synthetic=${constructor.isSynthetic})",
                )
                assertFalse(
                    constructor.parameterTypes.any {
                        it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                    },
                    "${type.name} exposes a DefaultConstructorMarker bridge: $constructor",
                )
            }
            type.declaredMethods
                .filter { it.name == "create" }
                .forEach { method ->
                    assertFalse(
                        Modifier.isPublic(method.modifiers) ||
                            Modifier.isProtected(method.modifiers),
                        "${type.name} exposes create bypass $method",
                    )
                }
        }
    }

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: error("user.dir unavailable"))) {
                it.parentFile
            }
            .first { File(it, "settings.gradle.kts").isFile }
}
