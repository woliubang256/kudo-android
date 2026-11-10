package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SessionRecoveryTest {
    private class Cookies : SessionCookies {
        var value = "_t=old"
        override fun get(url: String) = value
        override fun set(url: String, cookie: String) { value = if (cookie.contains("Max-Age=0")) "" else cookie.substringBefore(';') }
    }

    private fun repository(server: MockWebServer, cookies: Cookies, renewals: Int = 0): CommunityRepository {
        val received = AtomicInteger()
        val api = DiscourseClient(cookies, server.url("/"), route = { base ->
            Call.Factory { request ->
                val delegate = base.newCall(request)
                object : Call by delegate {
                    override fun enqueue(responseCallback: Callback) = delegate.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(call, e)
                        override fun onResponse(call: Call, response: Response) {
                            val number = received.incrementAndGet()
                            if (number <= renewals) cookies.value = "_t=new-$number"
                            responseCallback.onResponse(call, response)
                        }
                    })
                }
            }
        })
        return CommunityRepository(api)
    }

    @Test fun staleLoggedOutResponsesAreRecheckedOnceWithTheAlreadyRenewedToken() = runBlocking {
        for (status in listOf(200, 401, 404)) MockWebServer().use { server ->
            server.start()
            val cookies = Cookies()
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"current_user":null}""")
                .addHeader("Set-Cookie", "_t=; Path=/; Max-Age=0"))
            server.enqueue(MockResponse().setBody("""{"current_user":{"id":7,"username":"current"}}"""))
            val member = repository(server, cookies, renewals = 1).currentUser()
            assertEquals(7, member?.id)
            assertEquals(2, server.requestCount)
            assertEquals("_t=old", server.takeRequest().getHeader("Cookie"))
            assertEquals("_t=new-1", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun sessionReverificationIsBoundedEvenIfAnotherTokenArrivesEachTime() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) }
            val failure = runCatching { repository(server, Cookies(), renewals = 3).currentUser() }.exceptionOrNull()
            assertEquals(FailureKind.SIGN_IN, (failure as ApiFailure).kind)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun aRevokedCurrentTokenIsNotRestoredOrRetried() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cookies = Cookies()
            server.enqueue(MockResponse().setResponseCode(401).addHeader("Set-Cookie", "_t=; Path=/; Max-Age=0").setBody("{}"))
            val failure = runCatching { repository(server, cookies).currentUser() }.exceptionOrNull()
            assertEquals(FailureKind.SIGN_IN, (failure as ApiFailure).kind)
            assertEquals("", cookies.value)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun onlyAnExplicitGuestResponseIsAcceptedAsNoUser() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repository = repository(server, Cookies())
            for (body in listOf("{}", """{"current_user":[]}""", """{"current_user":{"id":0,"username":"x"}}""",
                    """{"current_user":{"id":7,"username":{}}}""")) {
                server.enqueue(MockResponse().setBody(body))
                val failure = runCatching { repository.currentUser() }.exceptionOrNull()
                assertEquals(FailureKind.SERVER, (failure as ApiFailure).kind)
            }
            server.enqueue(MockResponse().setBody("""{"current_user":null}"""))
            assertNull(repository.currentUser())
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun serverFailuresAreNotTreatedAsExpiredLogin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val failure = runCatching { repository(server, Cookies(), renewals = 1).currentUser() }.exceptionOrNull()
            assertEquals(FailureKind.SERVER, (failure as ApiFailure).kind)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun challengesWithNewTokensKeepOnlyTheExistingBoundedChallengeRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody("<html>Verify</html>")) }
            val failure = runCatching { repository(server, Cookies(), renewals = 2).currentUser() }.exceptionOrNull()
            assertEquals(FailureKind.VERIFICATION, (failure as ApiFailure).kind)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun missingUserAtTheSessionEndpointIsAnExplicitGuest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            assertNull(repository(server, Cookies()).currentUser())
            assertEquals(1, server.requestCount)
        }
    }
}
