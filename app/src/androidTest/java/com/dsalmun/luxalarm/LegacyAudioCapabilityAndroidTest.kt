/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.test.platform.app.InstrumentationRegistry
import com.dsalmun.luxalarm.data.BootstrapPhase
import com.dsalmun.luxalarm.data.LegacyAudioBootstrapDescriptor
import com.dsalmun.luxalarm.data.LegacyAudioBootstrapDescriptorFixtureFactory
import com.dsalmun.luxalarm.data.LegacyAudioBootstrapEvidence
import com.dsalmun.luxalarm.data.LegacyAudioSourceSnapshot
import com.dsalmun.luxalarm.data.PlatformLegacyAudioCapabilityFactory
import com.dsalmun.luxalarm.data.legacyDiscoveryAttemptToken
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.Test

class LegacyAudioCapabilityAndroidTest {
    @Test
    fun appFilesDirCapabilityCreatesOnceAndRejectsPathEscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root =
            context.filesDir.resolve("capability-android-test").apply {
                deleteRecursively()
                mkdirs()
            }
        try {
            val source = root.resolve("source.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val descriptor = descriptor(source.path)
            PlatformLegacyAudioCapabilityFactory.acquire(root, descriptor).use { capability ->
                capability.createNew("probe").use { channel ->
                    channel.write(ByteBuffer.wrap(byteArrayOf(7)))
                    channel.force(true)
                }
                assertNotNull(capability.identity("probe"))
                assertFailsWith<FileAlreadyExistsException> { capability.createNew("probe") }
                assertFailsWith<IllegalArgumentException> { capability.createNew("../escape") }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun descriptor(sourcePath: String): LegacyAudioBootstrapDescriptor {
        val fingerprint = "legacy-canonical-v1:${"a".repeat(64)}:${"b".repeat(64)}"
        val token = legacyDiscoveryAttemptToken("android-test", fingerprint)
        val evidence =
            LegacyAudioBootstrapEvidence(
                "LEGACY",
                "android-test",
                fingerprint,
                token,
                "bootstrap/$token/legacy-audio",
                BootstrapPhase.DISCOVERED.name,
            )
        return LegacyAudioBootstrapDescriptorFixtureFactory.create(
            evidence,
            sourcePath,
        ) {
            LegacyAudioSourceSnapshot(sourcePath, fingerprint)
        }
    }
}
