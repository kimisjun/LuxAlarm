/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ProductionManifestContractTest {
    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val PACKAGE_NAME = "com.kimisjun.warmly"
        const val COMPONENT_PACKAGE = "com.dsalmun.luxalarm"

        val PRIVILEGED_PERMISSIONS =
            setOf(
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.USE_EXACT_ALARM",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.VIBRATE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WAKE_LOCK",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
                "android.permission.DISABLE_KEYGUARD",
                "android.permission.USE_FULL_SCREEN_INTENT",
            )

        val DISABLED_COMPONENTS =
            setOf(
                "AlarmReceiver",
                "UpcomingAlarmReceiver",
                "AlarmActivity",
                "AlarmService",
                "BootReceiver",
                "RescheduleReceiver",
            )
    }

    @Test
    fun releaseManifestOnlyExposesThePhaseBSleepPlanSurface() {
        val manifest = releaseMergedManifest()
        val document =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)
        val root = document.documentElement
        val application = root.getElementsByTagName("application").elementAt(0)

        val declaredPermissions =
            root.getElementsByTagName("uses-permission").elements().map { it.androidName() }.toSet()
        assertTrue(
            declaredPermissions.intersect(PRIVILEGED_PERMISSIONS).isEmpty(),
            "Release manifest still declares privileged permissions: " +
                declaredPermissions.intersect(PRIVILEGED_PERMISSIONS),
        )

        assertEquals("$COMPONENT_PACKAGE.AppContainer", application.androidName())

        val components =
            sequenceOf("activity", "receiver", "service")
                .flatMap { application.getElementsByTagName(it).elements() }
                .associateBy { it.androidName() }
        DISABLED_COMPONENTS.forEach { simpleName ->
            val className = "$COMPONENT_PACKAGE.$simpleName"
            val component = assertNotNull(components[className], "$className must remain declared")
            assertEquals(
                "false",
                component.androidAttribute("enabled"),
                "$className must be disabled",
            )
        }

        val launchers =
            sequenceOf("activity", "activity-alias")
                .flatMap { application.getElementsByTagName(it).elements() }
                .filter { it.isEnabledAndExportedLauncher() }
                .toList()
        assertEquals(
            listOf("$COMPONENT_PACKAGE.MainActivity"),
            launchers.map { it.androidName() },
            "MainActivity must be the sole enabled/exported launcher",
        )
    }

    private fun releaseMergedManifest(): File {
        val relative =
            "app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Release merged manifest not found; run :app:processReleaseMainManifest first")
    }

    private fun Element.isEnabledAndExportedLauncher(): Boolean {
        val filters = getElementsByTagName("intent-filter").elements()
        val isLauncher = filters.any { filter ->
            val actions = filter.getElementsByTagName("action").elements().map { it.androidName() }
            val categories =
                filter.getElementsByTagName("category").elements().map { it.androidName() }
            "android.intent.action.MAIN" in actions &&
                "android.intent.category.LAUNCHER" in categories
        }
        return isLauncher &&
            androidAttribute("enabled") != "false" &&
            androidAttribute("exported") == "true"
    }

    private fun Element.androidName(): String {
        val name = androidAttribute("name")
        return when {
            name.startsWith(".") -> PACKAGE_NAME + name
            '.' !in name -> "$PACKAGE_NAME.$name"
            else -> name
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun org.w3c.dom.NodeList.elements(): Sequence<Element> =
        (0 until length).asSequence().map { item(it) as Element }

    private fun org.w3c.dom.NodeList.elementAt(index: Int): Element = item(index) as Element
}
