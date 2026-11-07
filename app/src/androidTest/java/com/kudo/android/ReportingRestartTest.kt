package com.kudo.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.reporting.ReportingRestartFixtureActivity
import io.sentry.Sentry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReportingRestartTest {
    @Test fun realHelperRestartsAnIsolatedProcessAndKeepsData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val real = context.getSharedPreferences("error_reporting", Context.MODE_PRIVATE).all.toMap()
        val file = File(context.cacheDir, "reporting-restart-result.txt")
        file.delete()
        val preferences = context.getSharedPreferences("reporting_restart_fixture", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().putString("consent", "denied").commit())
        try {
            context.startActivity(Intent(context, ReportingRestartFixtureActivity::class.java).putExtra("first", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val deadline = System.currentTimeMillis() + 15000
            while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Restart helper must relaunch the offline fixture", file.isFile)
            val lines = file.readLines(Charsets.UTF_8)
            assertEquals(5, lines.size)
            assertNotEquals(lines[0], lines[1])
            assertEquals("true", lines[2])
            assertEquals("1", lines[3])
            assertEquals("keep-local-data", lines[4])
            assertEquals(real, context.getSharedPreferences("error_reporting", Context.MODE_PRIVATE).all)
            assertFalse(Sentry.isEnabled())
        } finally { file.delete(); context.deleteSharedPreferences("reporting_restart_fixture") }
    }
}
