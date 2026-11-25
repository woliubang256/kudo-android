package com.kudo.android

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.components.TopicTransitionHost
import com.kudo.android.ui.components.TopicTransitionItem
import com.kudo.android.ui.components.TopicTransitions
import com.kudo.android.ui.components.topicDetailComposable
import com.kudo.android.ui.components.topicListComposable
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class TopicSnapshotBypassUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun affectedDeviceOpensAndReturnsWithoutCardSnapshot() {
        assumeTrue(Build.MANUFACTURER.equals("Xiaomi", true) && Build.DEVICE == "dada" &&
            Build.VERSION.SDK_INT == 36 && Build.VERSION.INCREMENTAL == "OS3.0.301.0.WOCCNXM")
        lateinit var navigation: NavHostController
        lateinit var controller: TopicTransitions
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                val nav = rememberNavController()
                val transitions = remember(nav) { TopicTransitions(nav) }
                SideEffect { navigation = nav; controller = transitions }
                TopicTransitionHost(transitions) {
                    NavHost(nav, "list", Modifier.fillMaxSize()) {
                        topicListComposable("list") {
                            TopicTransitionItem(5, 18.dp, { transitions.openTopic(5) }) { open ->
                                Box(Modifier.fillMaxSize().clickable(onClick = open).testTag("open-topic")) {
                                    Text("打开测试帖子")
                                }
                            }
                        }
                        topicDetailComposable("topic/{id}?post={post}", listOf(
                            navArgument("id") { type = NavType.IntType },
                            navArgument("post") { type = NavType.IntType; defaultValue = 0 },
                        )) { Text("测试正文", Modifier.testTag("topic-body")) }
                    }
                }
            }
        }
        repeat(5) {
            compose.onNodeWithTag("open-topic").performClick()
            compose.onNodeWithTag("topic-body").assertIsDisplayed()
            compose.runOnIdle {
                val entry = navigation.currentBackStackEntry!!
                assertEquals(5, entry.arguments!!.getInt("id"))
                assertNull("Affected devices must not capture the source card", controller.snapshot(entry))
                navigation.popBackStack()
            }
            compose.onNodeWithTag("open-topic").assertIsDisplayed()
        }
    }
}
