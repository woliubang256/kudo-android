package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.data.Topic
import com.kudo.android.ui.components.TopicTransitionHost
import com.kudo.android.ui.components.TopicTransitionItem
import com.kudo.android.ui.components.TopicTransitions
import com.kudo.android.ui.components.topicDetailComposable
import com.kudo.android.ui.components.topicListComposable
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.feed.FeedScreen
import com.kudo.android.ui.feed.FeedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class TopicTransitionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var navigation: NavHostController

    private fun showList() {
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = Color.Red, background = Color.Red)) {
                    val nav = rememberNavController()
                    val transitions = remember(nav) { TopicTransitions(nav) }
                    SideEffect { navigation = nav }
                    TopicTransitionHost(transitions) {
                        NavHost(nav, "list", Modifier.fillMaxSize()) {
                            topicListComposable("list") {
                                LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
                                    item { Spacer(Modifier.height(150.dp)) }
                                    item {
                                        Box(Modifier.padding(horizontal = 8.dp)) {
                                            TopicTransitionItem(5, 18.dp, { transitions.openTopic(5) }) { open ->
                                                Box(Modifier.fillMaxWidth().height(140.dp).background(Color.Red)
                                                    .clickable(onClick = open).testTag("transition-source"))
                                            }
                                        }
                                    }
                                    item {
                                        TopicTransitionItem(6, 18.dp, { transitions.openTopic(6) }) {
                                            Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Blue)
                                                .testTag("transition-neighbor"))
                                        }
                                    }
                                }
                            }
                            topicDetailComposable("topic/{id}?post={post}", listOf(
                                navArgument("id") { type = NavType.IntType },
                                navArgument("post") { type = NavType.IntType; defaultValue = 0 },
                            )) {
                                Box(Modifier.fillMaxSize().background(Color.Red).testTag("transition-content"))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun openingExpandsFromTheCardAndKeepsTheNeighborStill() {
        showList()
        val source = compose.onNodeWithTag("transition-source").fetchSemanticsNode().boundsInRoot
        val neighbor = compose.onNodeWithTag("transition-neighbor").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("transition-source").performClick()
        compose.mainClock.advanceTimeBy(128)
        compose.runOnIdle {
            assertNotNull("The detail must receive the clicked source token",
                navigation.currentBackStackEntry?.savedStateHandle?.get<String>("topic-transition-key"))
        }
        assertIntermediateFrame(source, "opening")
        assertEquals(neighbor, compose.onNodeWithTag("transition-neighbor").fetchSemanticsNode().boundsInRoot)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("An unrelated list item must not fade with the selected card", pixels[0, neighbor.center.y.toInt()].blue > 0.95f)
        compose.mainClock.advanceTimeBy(1000)
    }

    @Test fun returningContractsToTheOriginalCard() {
        showList()
        val source = compose.onNodeWithTag("transition-source").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("transition-source").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(128)
        assertIntermediateFrame(source, "closing")
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(source, compose.onNodeWithTag("transition-source").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun feedRestoresHeaderPaddingBeforePlacingTheReturningCard() {
        val placements = mutableListOf<Rect>()
        var recordReturn = false
        var owner: SemanticsOwner? = null
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
                val nav = rememberNavController()
                SideEffect { navigation = nav }
                NavHost(nav, "list") {
                    composable("list") {
                        FeedScreen(FeedState(topics = listOf(Topic(5, "返回位置测试", "", null, 1,
                            emptyList(), 0, 0, 0, "", "")), loading = false),
                            null, {}, {}, {}, { nav.navigate("detail") }, {}, {}, {}, {},
                            modifier = Modifier.onGloballyPositioned {
                                if (recordReturn) owner?.rootSemanticsNode?.findTag("topic-card-5")?.let { node ->
                                    placements += node.boundsInRoot
                                }
                            })
                    }
                    composable("detail") { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        owner = compose.onRoot().fetchSemanticsNode().root?.semanticsOwner
        val original = compose.onNodeWithTag("topic-card-5").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("topic-card-5").performClick()
        compose.waitForIdle()
        compose.runOnIdle { recordReturn = true; navigation.popBackStack() }
        compose.waitForIdle()
        assertTrue("The returning feed must be placed", placements.isNotEmpty())
        assertEquals("The first placement must already include the restored header", original.top, placements.first().top)
    }

    private fun SemanticsNode.findTag(tag: String): SemanticsNode? {
        if (config.getOrNull(SemanticsProperties.TestTag) == tag) return this
        return children.firstNotNullOfOrNull { it.findTag(tag) }
    }

    private fun assertIntermediateFrame(source: Rect, name: String) {
        val image = compose.onRoot().captureToImage()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "topic-transition-$name.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val pixels = image.toPixelMap()
        val center = pixels.width / 2
        val colored = (0 until pixels.height).filter { row ->
            val color = pixels[center, row]
            color.red > 0.7f && color.green < 0.08f && color.blue < 0.08f
        }
        assertTrue("The animated card must remain visible: $name", colored.isNotEmpty())
        val top = colored.first()
        val bottom = colored.last()
        val height = bottom - top + 1
        val report = "$name card=$source animated=[$top,$bottom] viewport=${pixels.height}"
        android.util.Log.i("TopicTransitionTest", report)
        assertTrue("The container must move above the original card: $report", top < source.top - 5)
        assertTrue("The container must grow below the original card: $report", bottom > source.bottom + 5)
        assertTrue("An intermediate frame must not already fill the screen: $report", height < pixels.height * 0.95f)
    }
}
