package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.TopicScreen
import com.kudo.android.ui.topic.TopicViewModel
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ReactionBoostIndependenceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mainPostCanStillBeBoostedAfterLikingAndUnliking() = checkIndependentActions(1)

    @Test fun replyCanStillBeBoostedAfterLikingAndUnliking() = checkIndependentActions(2)

    private fun postJson(number: Int, liked: Boolean, withBoosts: Boolean = false): String {
        val boostFields = if (withBoosts) """"can_boost":true,"boosts":[{"id":${100 + number},
            "cooked":"<p>已有支持</p>","user":{"id":3,"username":"booster"}}],""" else ""
        val reaction = if (liked) """{"id":"heart","type":"emoji","can_undo":true}""" else "null"
        return """{"id":$number,"post_number":$number,"user_id":1,"username":"author","name":"作者",
            "cooked":"<p>第 $number 楼正文</p>",$boostFields
            "actions_summary":[{"id":2,"count":${if (liked) 27 else 26},"can_act":true,"can_undo":true}],
            "reactions":[{"id":"heart","type":"emoji","count":${if (liked) 27 else 26}}],
            "current_user_reaction":$reaction}"""
    }

    private fun checkIndependentActions(number: Int) {
        val reactions = AtomicInteger()
        val boosts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/t/5.json" -> """{"id":5,"title":"点赞与 Boost 独立回归","valid_reactions":["heart"],
                    "post_stream":{"stream":[1,2],"posts":[${postJson(1, false, true)},${postJson(2, false, true)}]}}"""
                "/u/author.json" -> """{"user":{"id":1,"username":"author","name":"作者"}}"""
                "/session/csrf" -> """{"csrf":"fixture"}"""
                "/emojis.json" -> "{}"
                "/discourse-reactions/posts/$number/custom-reactions/heart/toggle.json" -> {
                    assertEquals("PUT", request.method)
                    postJson(number, reactions.incrementAndGet() == 1)
                }
                "/discourse-boosts/posts/$number/boosts" -> {
                    assertEquals("POST", request.method)
                    boosts.incrementAndGet()
                    """{"id":300,"cooked":"<p>新增支持</p>","user":{"id":9,"username":"viewer"}}"""
                }
                else -> error("Unexpected offline endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val cookies = object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }
        val repository = CommunityRepository(DiscourseClient(cookies, "https://reaction-boost.test/".toHttpUrl(), http = http))
        lateinit var vm: TopicViewModel
        compose.runOnIdle { vm = TopicViewModel(repository, 5, viewerId = 9) }
        try {
            compose.setContent { KudoTheme(DisplaySettings()) {
                val state by vm.state.collectAsState()
                TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, vm::like, {}, {}, {}, {}, {}, {},
                    readingEnabled = false, loadBoostRules = vm::loadBoostRules, boost = vm::boost)
            } }
            compose.waitUntil(5_000) { !vm.state.value.loading }
            compose.runOnIdle {
                assertNull(vm.state.value.error)
                assertEquals(1, vm.state.value.detail!!.posts.single { it.number == number }.boosts.size)
            }
            val like = if (number == 1) compose.onNodeWithContentDescription("赞主帖")
                else compose.onNode(hasContentDescription("回应此楼层") and hasAnyAncestor(hasTestTag("post-$number")))
            val boost = if (number == 1) compose.onNodeWithContentDescription("Boost 主帖")
                else compose.onNode(hasContentDescription("Boost 此帖子") and hasAnyAncestor(hasTestTag("post-$number")))
            fun showAction(action: SemanticsNodeInteraction): SemanticsNodeInteraction {
                if (number > 1) action.performScrollTo()
                return action.assertIsDisplayed()
            }
            showBoost(100 + number)
            showAction(like).performClick()
            compose.waitUntil(5_000) {
                vm.state.value.busy.isEmpty() && vm.state.value.detail?.posts?.single { it.number == number }?.liked == true
            }
            showBoost(100 + number)
            showAction(boost).assertIsEnabled().performClick()
            compose.onNodeWithTag("boost-input").performTextInput("新增支持")
            compose.waitUntil(5_000) { vm.state.value.boostEmojis != null }
            compose.onNodeWithText("发送").assertIsEnabled().performClick()
            compose.waitUntil(5_000) {
                vm.state.value.busy.isEmpty() && vm.state.value.detail?.posts?.single { it.number == number }?.boosts?.size == 2
            }
            compose.onNodeWithTag("boost-input").assertDoesNotExist()
            showBoost(300)
            compose.runOnIdle {
                assertEquals("heart", vm.state.value.detail!!.posts.single { it.number == number }.activeReaction)
                assertEquals(1, boosts.get())
            }
            showAction(like).assertIsEnabled().performClick()
            compose.waitUntil(5_000) {
                reactions.get() == 2 && vm.state.value.busy.isEmpty() &&
                    vm.state.value.detail?.posts?.single { it.number == number }?.activeReaction == null
            }
            showBoost(100 + number)
            showBoost(300)
            showAction(boost).assertIsEnabled()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "reaction-boost-$number.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            boost.performClick()
            compose.onNodeWithTag("boost-input").assertIsDisplayed()
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            http.dispatcher.executorService.shutdown()
        }
    }

    private fun showBoost(id: Int) {
        compose.onNodeWithTag("topic-posts").performScrollToNode(hasTestTag("boost-$id"))
        compose.onNodeWithTag("boost-$id").assertIsDisplayed()
    }
}
