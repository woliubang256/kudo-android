package com.kudo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kudo.android.ui.web.MiniAppClipboardRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MiniAppClipboardRequestTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancelledAndCompletedRequestsNeverWriteAgain() {
        compose.runOnUiThread {
            val context = compose.activity
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previous = manager.primaryClip
            try {
                manager.setPrimaryClip(ClipData.newPlainText("fixture", "原始测试文字"))
                val replies = mutableListOf<String?>()
                val cancelled = MiniAppClipboardRequest(true, "取消后的写入") { _, error -> replies.add(error) }
                cancelled.cancel()
                cancelled.allow(context)
                assertEquals(listOf("USER_CANCELLED"), replies)
                assertEquals("原始测试文字", manager.primaryClip!!.getItemAt(0).text.toString())

                val completed = MiniAppClipboardRequest(true, "本次测试写入") { _, error -> replies.add(error) }
                completed.allow(context)
                manager.setPrimaryClip(ClipData.newPlainText("fixture", "后续测试文字"))
                completed.allow(context)
                assertEquals("后续测试文字", manager.primaryClip!!.getItemAt(0).text.toString())
                assertEquals(listOf("USER_CANCELLED", null), replies)
            } finally {
                previous?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
            }
        }
    }

    @Test fun invalidatedRequestsAndOversizedUnicodeNeverWrite() {
        compose.runOnUiThread {
            val context = compose.activity
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previous = manager.primaryClip
            try {
                manager.setPrimaryClip(ClipData.newPlainText("fixture", "保持测试文字"))
                var error: String? = null
                val stale = MiniAppClipboardRequest(true, "旧页面", valid = { false }) { _, code -> error = code }
                stale.allow(context)
                assertEquals("APP_UNLOADED", error)
                val oversized = MiniAppClipboardRequest(true, "界".repeat(1366)) { _, code -> error = code }
                oversized.allow(context)
                assertEquals("INVALID_ARGUMENTS", error)
                assertEquals("保持测试文字", manager.primaryClip!!.getItemAt(0).text.toString())
            } finally { previous?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip() }
        }
    }
}
