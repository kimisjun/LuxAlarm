/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GentleWakePreviewTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun halfProgressUsesTheRampAndOffersALargeKoreanConfirmation() {
        var confirmations = 0
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(progress = 0.5f, onAwake = { confirmations++ })
            }
        }

        composeRule.onNodeWithText("부드럽게 깨어날 시간이에요").assertIsDisplayed()
        composeRule.onNodeWithText("진행 50% · 화면 51% · 음악 20%").assertIsDisplayed()
        composeRule.onNodeWithTag("gentle-wake-preview").assertIsDisplayed()
        composeRule
            .onNodeWithText("일어났어요")
            .assertWidthIsAtLeast(240.dp)
            .assertHeightIsAtLeast(64.dp)
            .performClick()

        assertEquals(1, confirmations)
    }

    @Test
    fun progressControlScrubsThroughDeterministicRampFrames() {
        val progress = mutableFloatStateOf(0f)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(
                    progress = progress.floatValue,
                    onProgressChange = { progress.floatValue = it },
                    onAwake = {},
                )
            }
        }

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        composeRule.onNodeWithText("진행 75% · 화면 85% · 음악 30%").assertIsDisplayed()
    }

    @Test
    fun routePlaysTheImportedMusicAndReleasesItWhenThePreviewCloses() {
        val importedUri = Uri.parse("file:///private/selected-audio")
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory()
        val visible = mutableStateOf(true)
        val progress = mutableFloatStateOf(0f)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                if (visible.value) {
                    GentleWakePreviewRoute(
                        progress = progress.floatValue,
                        onProgressChange = { progress.floatValue = it },
                        onAwake = { visible.value = false },
                        importedAudioUri = importedUri,
                        defaultAlarmUri = defaultUri,
                        playerFactory = factory,
                    )
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(listOf(importedUri), factory.requestedUris)
            assertEquals(WakeRamp.frameAt(0f).audioVolume, factory.player.initialVolume)
            progress.floatValue = 0.75f
        }
        composeRule.runOnIdle {
            assertEquals(WakeRamp.frameAt(0.75f).audioVolume, factory.player.lastVolume)
        }

        composeRule.onNodeWithText("일어났어요").performClick()

        composeRule.runOnIdle {
            assertTrue(factory.player.stopped)
            assertTrue(factory.player.released)
        }
    }

    @Test
    fun routeStopsAndReleasesPlaybackWhenItLeavesComposition() {
        val factory = PreviewRecordingFactory()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                if (visible.value) {
                    GentleWakePreviewRoute(
                        progress = 0f,
                        onAwake = {},
                        importedAudioUri = null,
                        defaultAlarmUri = Uri.parse("content://settings/system/alarm_alert"),
                        playerFactory = factory,
                    )
                }
            }
        }

        composeRule.runOnIdle { visible.value = false }

        composeRule.runOnIdle {
            assertTrue(factory.player.stopped)
            assertTrue(factory.player.released)
        }
    }

    @Test
    fun routeDisplaysTheKoreanFallbackStateWhenImportedCreationFails() {
        val importedUri = Uri.parse("file:///private/selected-audio")
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory(failingUri = importedUri)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    importedAudioUri = importedUri,
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        composeRule.onNodeWithText("가져온 음악 재생 실패 · 기본 알람 소리 재생 중").assertIsDisplayed()
        assertEquals(listOf(importedUri, defaultUri), factory.requestedUris)
    }

    @Test
    fun routeDisplaysTheKoreanFailureStateWhenNoPlayerCanBeCreated() {
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory(failAll = true)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    importedAudioUri = null,
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        composeRule.onNodeWithText("미리보기 소리를 재생할 수 없어요").assertIsDisplayed()
    }

    private class PreviewRecordingFactory(
        private val failingUri: Uri? = null,
        private val failAll: Boolean = false,
    ) : GentleWakePreviewPlayerFactory {
        val requestedUris = mutableListOf<Uri>()
        val player = PreviewRecordingPlayer()

        override fun create(uri: Uri, initialVolume: Float): GentleWakePreviewPlayer? {
            requestedUris += uri
            if (failAll || uri == failingUri) return null
            player.initialVolume = initialVolume
            return player
        }
    }

    private class PreviewRecordingPlayer : GentleWakePreviewPlayer {
        var initialVolume = Float.NaN
        var lastVolume = Float.NaN
        var stopped = false
        var released = false

        override fun setVolume(volume: Float) {
            lastVolume = volume
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }
    }
}
