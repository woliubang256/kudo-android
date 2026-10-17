package com.kudo.android

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.topic.RichContent
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ArticleContentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun copyingCodeUsesOnlyAnIconAndShowsTheSharedToast() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.padding(16.dp)) { RichContent("<pre><code>val answer = 42</code></pre>", {}, {}) }
        } }
        compose.onNodeWithContentDescription("复制代码").assertIsDisplayed()
        compose.onAllNodesWithText("复制").assertCountEquals(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val event = instrumentation.uiAutomation.executeAndWaitForEvent(
            { compose.onNodeWithContentDescription("复制代码").performClick() },
            { notification -> notification.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                notification.text.any { it.toString() == "已复制" } }, 5000)
        assertTrue(event.text.any { it.toString() == "已复制" })
        event.recycle()
        compose.onAllNodesWithText("已复制").assertCountEquals(0)
        compose.onNodeWithContentDescription("复制代码").assertIsDisplayed()
        var screenshot: Bitmap? = null
        try {
            compose.waitUntil(2000) {
                screenshot?.recycle()
                screenshot = instrumentation.uiAutomation.takeScreenshot()
                val bitmap = screenshot!!
                (0 until bitmap.height / 3 step 4).any { row ->
                    (bitmap.width / 4 until bitmap.width * 3 / 4 step 4).count { column ->
                        bitmap.getPixel(column, row) == LogoYellow.toArgb()
                    } > bitmap.width / 40
                }
            }
            File(instrumentation.targetContext.getExternalFilesDir(null), "code-copy-icon-toast.png")
                .outputStream().use { screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            screenshot?.recycle()
        }
    }

    @Test fun copyingCodePreservesSpacesNewlinesAndEntitiesAndOnlyCopiesThatBlock() {
        val code = "  val x = \"<&>\"\n\tprintln(x)\n"
        compose.setContent { KudoTheme(DisplaySettings()) { Column(Modifier.padding(16.dp)) {
            RichContent("<p>正文不复制</p><pre><code>  val x = &quot;&lt;&amp;&gt;&quot;\n\tprintln(x)\n</code></pre><pre>other()</pre>", {}, {})
        } } }
        compose.onAllNodesWithContentDescription("复制代码")[0].performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onAllNodesWithContentDescription("复制代码")[1].performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("other()", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
}
