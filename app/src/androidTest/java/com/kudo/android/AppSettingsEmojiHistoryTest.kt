package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.AppSettings
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsEmojiHistoryTest {
    @Test fun recentNamesKeepTheirOrderLimitAndRepairOnSelection() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "emoji-history-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$file-$name", mode)
        }
        val preferences = context.getSharedPreferences("kudo_preferences", Context.MODE_PRIVATE)
        try {
            val names = (0..39).map { "emoji_$it" }
            preferences.edit().putString("editor_recent_emojis",
                (listOf("", names[0], names[0]) + names + listOf(" ")).joinToString(",")).commit()
            val settings = AppSettings(context)
            assertEquals(names.take(32), settings.recentEmojis())
            settings.rememberEmoji("new")
            assertEquals(listOf("new") + names.take(31), settings.recentEmojis())
            val repaired = preferences.getString("editor_recent_emojis", "")!!
            assertEquals(settings.recentEmojis(), repaired.split(','))
            settings.rememberEmoji(" ")
            assertEquals(repaired, preferences.getString("editor_recent_emojis", ""))
            settings.rememberEmoji(names[5])
            assertEquals(listOf(names[5], "new") + names.take(31).filterNot { it == names[5] }, settings.recentEmojis())
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
