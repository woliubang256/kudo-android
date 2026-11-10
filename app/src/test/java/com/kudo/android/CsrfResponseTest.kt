package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CsrfResponseTest {
    @Test fun malformedCsrfIsAServerErrorAndNeverSubmitsAMutation() = runBlocking {
        for (body in listOf("{}", """{"csrf":null}""", """{"csrf":""}""", """{"csrf":"  "}""",
            """{"csrf":"null"}""", """{"csrf":123}""", """{"csrf":true}""", """{"csrf":{}}""", """{"csrf":[]}""")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody(body))
                // A faulty parser must fail promptly rather than wait for an unqueued POST response.
                server.enqueue(MockResponse().setBody("{}"))
                val client = DiscourseClient(NoCookies, server.url("/"))
                val failure = runCatching { client.write("POST", "/posts.json", mapOf("raw" to "fixture")) }.exceptionOrNull()
                assertEquals(body, FailureKind.SERVER, (failure as? ApiFailure)?.kind)
                assertEquals(body, 1, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
            }
        }
    }

    @Test fun csrfTransportAndAuthenticationFailuresKeepTheirOriginalMeaning() = runBlocking {
        for ((status, body, kind) in listOf(
            Triple(401, "{}", FailureKind.SIGN_IN),
            Triple(403, """{"error_type":"not_logged_in"}""", FailureKind.SIGN_IN),
            Triple(403, "{}", FailureKind.FORBIDDEN),
            Triple(503, "{}", FailureKind.SERVER),
        )) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            val client = DiscourseClient(NoCookies, server.url("/"))
            val failure = runCatching { client.write("POST", "/posts.json") }.exceptionOrNull()
            assertEquals(kind, (failure as? ApiFailure)?.kind)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun malformedCsrfIsNotCachedAndAnExplicitRetryCanSubmitOnce() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"csrf":"valid-csrf"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val client = DiscourseClient(NoCookies, server.url("/"))
            assertEquals(FailureKind.SERVER, (runCatching { client.write("POST", "/posts.json") }.exceptionOrNull() as? ApiFailure)?.kind)
            client.write("POST", "/posts.json")
            assertEquals(3, server.requestCount)
            assertEquals("/session/csrf", server.takeRequest().path)
            assertEquals("/session/csrf", server.takeRequest().path)
            assertEquals("valid-csrf", server.takeRequest().getHeader("X-CSRF-Token"))
        }
    }
}
