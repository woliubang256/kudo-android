package com.kudo.android

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.kudo.android.ui.IncomingLinuxDoLink
import com.kudo.android.ui.openContentLink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeepLinkUiTest {
    @get:Rule val compose = createComposeRule()
    private var incoming by mutableStateOf<String?>(null)
    private var sequence by mutableStateOf(0L)
    private var selected = 4
    private var consumed = 0
    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            IncomingLinuxDoLink(nav, incoming, sequence, { selected = 0 }) { incoming = null; consumed++ }
            NavHost(nav, "root") {
                composable("root") { Text("首页") }
                composable("unavailable") { Text("内容不存在或暂无权限") }
                composable("user/{username}") { Text("用户：${it.arguments!!.getString("username")}") }
                composable("category/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    Text("分类：${it.arguments!!.getInt("id")}")
                }
                composable("tag/{slug}?id={id}&name={name}", arguments = listOf(navArgument("slug") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" })) {
                    Text("标签：${it.arguments!!.getString("name").orEmpty().ifBlank { it.arguments!!.getString("slug").orEmpty() }}")
                }
                composable("web?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) {
                    Text("网页：${it.arguments!!.getString("url")}")
                }
                composable("topic/{id}?post={post}", arguments = listOf(navArgument("id") { type = NavType.IntType },
                    navArgument("post") { type = NavType.IntType; defaultValue = 0 })) {
                    Text("帖子：${it.arguments!!.getInt("id")} 楼层：${it.arguments!!.getInt("post")}")
                }
            }
        }
    }

    private fun open(url: String) { compose.runOnIdle { sequence++; incoming = url } }

    @Test fun namedTagRoutesPreserveReadableNamesWhileExternalLinksStillMatchWithoutName() {
        show()
        compose.runOnIdle { nav.navigate("tag/444-tag?id=444&name=${Uri.encode("公告 & 分享")}") }
        compose.onNodeWithText("标签：公告 & 分享").assertIsDisplayed()
        open("https://linux.do/tag/193-tag/193")
        compose.onNodeWithText("标签：193-tag").assertIsDisplayed()
    }

    @Test fun direct404AndContent404UseNativeUnavailablePageAndCanReturn() {
        show()
        open("https://linux.do/404")
        compose.onNodeWithText("内容不存在或暂无权限").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("首页").assertIsDisplayed()
        compose.runOnIdle { nav.openContentLink("/404", { selected = 0 }) }
        compose.onNodeWithText("内容不存在或暂无权限").assertIsDisplayed()
    }

    @Test fun initialAndRepeatedLinkAfterBackAreConsumedSeparately() {
        val url = "https://linux.do/t/topic/3001000/8"
        incoming = url
        show()
        compose.onNodeWithText("帖子：3001000 楼层：8").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, consumed); assertNull(incoming); nav.popBackStack() }
        compose.onNodeWithText("首页").assertIsDisplayed()
        open(url)
        compose.onNodeWithText("帖子：3001000 楼层：8").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, consumed); nav.popBackStack() }
        compose.onNodeWithText("首页").assertIsDisplayed()
    }

    @Test fun profileSubpathAndOtherSiteLinkReturnToHomeTab() {
        show()
        open("https://linux.do/u/stingysheep/activity/replies/")
        compose.onNodeWithText("用户：stingysheep").assertIsDisplayed()
        open("https://linux.do/guidelines")
        compose.onNodeWithText("首页").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, selected) }
        open("https://linux.do/t/topic/3001000")
        compose.onNodeWithText("帖子：3001000 楼层：0").assertIsDisplayed()
        open("https://linux.do/c/4")
        compose.onNodeWithText("分类：4").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("帖子：3001000 楼层：0").assertIsDisplayed()
    }

    @Test fun articleLinksOpenNativeTargetsAndUnknownPagesUseTheInAppWebRoute() {
        show()
        fun click(value: String) { compose.runOnIdle { nav.openContentLink(value, { selected = 0 }) } }
        click("/t/topic/3001000/8")
        compose.onNodeWithText("帖子：3001000 楼层：8").assertIsDisplayed()
        compose.runOnIdle {
            nav.openContentLink("/t/topic/3002000/9", { selected = 0 })
        }
        compose.onNodeWithText("帖子：3002000 楼层：9").assertIsDisplayed()
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        compose.onNodeWithText("帖子：3001000 楼层：8").assertIsDisplayed()
        click("/u/stingysheep/activity/")
        compose.onNodeWithText("用户：stingysheep").assertIsDisplayed()
        click("/c/parent/develop/4")
        compose.onNodeWithText("分类：4").assertIsDisplayed()
        click("/tag/193-tag/193")
        compose.onNodeWithText("标签：193-tag").assertIsDisplayed()
        click("/guidelines")
        compose.onNodeWithText("网页：https://linux.do/guidelines").assertIsDisplayed()
        click("https://example.org/help?from=topic&next=%2Ft%2F123#section")
        compose.onNodeWithText("网页：https://example.org/help?from=topic&next=%2Ft%2F123#section").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("网页：https://linux.do/guidelines").assertIsDisplayed()
        click("javascript:alert(1)")
        compose.onNodeWithText("网页：https://linux.do/guidelines").assertIsDisplayed()
        click("https://connect.linux.do/t/123")
        compose.onNodeWithText("网页：https://connect.linux.do/t/123").assertIsDisplayed()
    }
}
