package com.kudo.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.ObserveHomeUpdates
import com.kudo.android.ui.feed.FeedViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HomeRequestLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingTabsAndBackgroundingStopHomeRequestsAndReturningResubscribes() {
        val active = AtomicInteger()
        val starts = AtomicInteger()
        val reads = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath == "/site.json") """{"categories":[]}""" else {
                reads.incrementAndGet()
                """{"topic_list":{"topics":[{"id":1,"title":"离线话题"}]}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val cookies = object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }
        lateinit var vm: FeedViewModel
        lateinit var owner: Owner
        var visible by mutableStateOf(true)
        compose.runOnUiThread {
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            vm = FeedViewModel(CommunityRepository(DiscourseClient(cookies, "https://offline.test/".toHttpUrl(), http = http)),
                updates = FeedUpdates { flow {
                    starts.incrementAndGet(); active.incrementAndGet()
                    try { emit(Unit); awaitCancellation() } finally { active.decrementAndGet() }
                } }, updateIntervalMillis = 30)
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { ObserveHomeUpdates(vm, null, visible) }
            }
            compose.waitUntil(5000) { active.get() == 1 && reads.get() >= 2 }
            compose.runOnIdle { visible = false }
            compose.waitUntil(3000) { active.get() == 0 }
            val hiddenReads = reads.get()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.runOnIdle { assertEquals(0, active.get()); assertEquals(hiddenReads, reads.get()); visible = true }
            compose.waitUntil(3000) { active.get() == 1 && starts.get() == 2 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil(3000) { active.get() == 0 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(3000) { active.get() == 1 && starts.get() == 3 }
        } finally {
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED; vm.viewModelScope.cancel() }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
