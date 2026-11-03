package com.kudo.android

import com.kudo.android.ui.web.MiniAppLaunchPhase
import com.kudo.android.ui.web.MiniAppLaunchState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniAppLaunchStateTest {
    private val id = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
    private val url = "https://linux.do/apps/$id?embed_mode=true#play"
    private fun message(phase: String = "loading") = JSONObject().put("type", "launch").put("appId", id)
        .put("url", url).put("phase", phase).put("stage", "正在解压应用…")

    @Test fun progressKeepsRealStageFractionsAndUnknownProgressStaysIndeterminate() {
        val message = message().put("progress", .375)
        val state = MiniAppLaunchState.parse(message, url)!!
        assertEquals("正在解压应用…", state.stage)
        assertEquals(.375f, state.progress!!, .0001f)
        assertTrue(state.coversPage)
        for (invalid in listOf(-1, 2, "0.5", JSONObject.NULL)) {
            message.put("progress", invalid)
            assertNull(MiniAppLaunchState.parse(message, url)!!.progress)
        }
    }

    @Test fun permissionAndReadyReleaseTheCoverAndErrorsKeepTheirRetryDetails() {
        for (phase in listOf("permission", "ready")) {
            val state = MiniAppLaunchState.parse(message(phase).put("progress", .8), url)!!
            assertFalse(state.coversPage)
            assertNull(state.progress)
        }
        val error = MiniAppLaunchState.parse(message("error").put("stage", "启动失败")
            .put("detail", "网络连接失败").put("retryable", true), url)!!
        assertEquals(MiniAppLaunchPhase.ERROR, error.phase)
        assertEquals("网络连接失败", error.detail)
        assertTrue(error.retryable)
        assertTrue(error.coversPage)
    }

    @Test fun oldDocumentsOtherApplicationsAndUnsupportedMessagesAreRejected() {
        assertNull(MiniAppLaunchState.parse(message().put("url", "$url&old=true"), url))
        assertNull(MiniAppLaunchState.parse(message().put("appId", "01a1966e-935a-79e0-8c76-9b0f7552c578"), url))
        assertNull(MiniAppLaunchState.parse(message("unknown"), url))
        assertNull(MiniAppLaunchState.parse(message().put("type", "presentation"), url))
        assertNull(MiniAppLaunchState.parse(message(), "https://outside.test/apps/$id"))
        assertNull(MiniAppLaunchState.parse(message(), "https://linux.do/apps"))
    }
}
