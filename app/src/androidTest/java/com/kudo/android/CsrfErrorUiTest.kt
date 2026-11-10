package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.components.ErrorState
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class CsrfErrorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun malformedCsrfOffersRetryWithReadableTextInsteadOfLogin() {
        val paths = ConcurrentLinkedQueue<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            paths.add(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val error = runBlocking {
            val cookies = object : SessionCookies {
                override fun get(url: String) = ""
                override fun set(url: String, cookie: String) = Unit
            }
            runCatching { DiscourseClient(cookies, http = http).write("POST", "/posts.json") }.exceptionOrNull()
        }
        assertEquals(FailureKind.SERVER, (error as? ApiFailure)?.kind)
        assertEquals(listOf("/session/csrf"), paths.toList())
        var retries = 0
        var logins = 0
        compose.setContent { KudoTheme(DisplaySettings()) { ErrorState(error!!, { retries++ }, { logins++ }) } }
        compose.onNodeWithText("服务器未返回有效的安全令牌，请稍后重试").assertIsDisplayed()
        compose.onNodeWithText("登录 Linux.do").assertDoesNotExist()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.cacheDir, "csrf-error-fixture.png").outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("重新加载").performClick()
        assertEquals(1, retries)
        assertEquals(0, logins)
    }
}
