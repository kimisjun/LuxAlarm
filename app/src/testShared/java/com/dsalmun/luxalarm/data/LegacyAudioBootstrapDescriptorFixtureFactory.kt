/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.lang.reflect.InvocationTargetException

/** Test-source-only escape hatch; production descriptors require canonical Room discovery. */
internal object LegacyAudioBootstrapDescriptorFixtureFactory {
    fun create(
        evidence: LegacyAudioBootstrapEvidence,
        sourcePath: String,
        revalidateSource: () -> LegacyAudioSourceSnapshot,
    ): LegacyAudioBootstrapDescriptor {
        val validated = validateEvidence(evidence)
        val constructor =
            LegacyAudioBootstrapDescriptor::class
                .java
                .getDeclaredConstructor(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    BootstrapPhase::class.java,
                    kotlin.Function0::class.java,
                )
        constructor.isAccessible = true
        return try {
            constructor.newInstance(
                validated.installEpoch,
                requireNotNull(validated.sourceFingerprint),
                requireNotNull(validated.attemptToken),
                requireNotNull(validated.targetStorageKey),
                sourcePath,
                BootstrapPhase.valueOf(requireNotNull(validated.bootstrapPhase)),
                revalidateSource,
            )
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }
}
