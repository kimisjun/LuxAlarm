/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeAudioDocumentContractTest {
    @Test
    fun opensMultipleReusableLocalAudioDocuments() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = WakeAudioDocumentsContract().createIntent(context, Unit)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals("audio/*", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun returnsDocumentUrisInSelectionOrder() {
        val first = Uri.parse("content://documents/audio/first")
        val second = Uri.parse("content://documents/audio/second")
        val result =
            Intent().apply {
                clipData =
                    ClipData(
                            ClipDescription("audio", arrayOf("audio/*")),
                            ClipData.Item(first),
                        )
                        .apply { addItem(ClipData.Item(second)) }
            }

        assertEquals(
            listOf(first.toString(), second.toString()),
            WakeAudioDocumentsContract().parseResult(Activity.RESULT_OK, result),
        )
    }
}
