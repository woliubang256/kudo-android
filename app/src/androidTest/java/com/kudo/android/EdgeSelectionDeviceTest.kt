package com.kudo.android

import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.network.*
import okhttp3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class EdgeSelectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun savingWinnerDoesNotMutateRoutingConfiguration() {
        val settings = DohSettings(instrumentation.context)
        val original = settings.configuration.value
        val before = settings.cachedWinner()
        val winner = JSONObject().put("ip", "104.16.0.1").put("ech", "test").put("latency_ms", 80).put("checked_at", 1792700000)
        try {
            settings.update(original.copy(localSelection = true))
            val configuration = settings.configuration.value
            settings.saveSelection(JSONObject().put("phase", "ready").put("winner", winner).toString())
            assertEquals(configuration, settings.configuration.value)
            assertEquals(winner.toString(), DohSettings(instrumentation.context).cachedWinner())
            settings.saveSelection("""{"phase":"fallback"}""")
            assertNull(settings.cachedWinner()); assertEquals(configuration, settings.configuration.value)
        } finally {
            settings.update(original)
            instrumentation.context.getSharedPreferences("kudo_network", 0).edit().apply {
                if (before == null) remove("edge_winner") else putString("edge_winner", before)
            }.commit()
        }
    }

    /** Explicit opt-in: public reads only; independent of both installed apps' cookies and settings. */
    @Test fun liveSelectionPinsSubdomainsAndRecoversAnObsoleteEchConfig() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("edgeLive") == "true")
        val report = JSONObject()
        val accepted = mutableSetOf<String>()
        val transport = DohTransport(BuiltInDohServer, localSelection = true) { host, ech -> if (ech) accepted += host }
        val http = transport.configure(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(40, TimeUnit.SECONDS)).build()
        try {
            val addresses = transport.dns.lookup("linux.do")
            val status = JSONObject(transport.selectionState())
            report.put("phase", status.optString("phase"))
            assertEquals("ready", status.getString("phase"))
            assertEquals(1, addresses.size)
            assertEquals(addresses, transport.dns.lookup("connect.linux.do"))
            report.put("sameNodeAndFamily", true)
            for (host in listOf("linux.do", "connect.linux.do")) {
                http.newCall(Request.Builder().url("https://$host/cdn-cgi/trace").build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.string().lineSequence().any { line -> line == "h=$host" })
                }
            }
            report.put("bothEchAccepted", accepted.containsAll(listOf("linux.do", "connect.linux.do")))
            assertTrue(report.getBoolean("bothEchAccepted"))
            http.newCall(Request.Builder().url("https://linux.do/latest.json").build()).execute().use {
                report.put("listHttpStatus", it.code)
                if (it.isSuccessful) report.put("topicCount", JSONObject(it.body!!.string()).getJSONObject("topic_list").getJSONArray("topics").length())
            }
            val saved = status.getJSONObject("winner")
            val bytes = Base64.getDecoder().decode(saved.getString("ech"))
            bytes[6] = (bytes[6].toInt() xor 0x7f).toByte() // ECH config_id, retaining a valid public config structure.
            saved.put("ech", Base64.getEncoder().encodeToString(bytes))
            val restored = DohTransport(BuiltInDohServer, localSelection = true, cachedWinner = saved.toString())
            try {
                assertEquals(addresses, restored.dns.lookup("linux.do"))
                val fresh = JSONObject(restored.selectionState())
                assertEquals("ready", fresh.getString("phase"))
                assertNotEquals(saved.getString("ech"), fresh.getJSONObject("winner").getString("ech"))
                report.put("obsoleteKeyRecovered", true)
            } finally { restored.close() }
        } finally {
            transport.close(); http.connectionPool.evictAll()
            File(instrumentation.targetContext.filesDir, "edge-live-result.json").writeText(report.toString(2), Charsets.UTF_8)
        }
    }
}
