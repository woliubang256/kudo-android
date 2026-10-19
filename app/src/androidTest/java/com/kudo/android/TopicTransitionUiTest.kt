package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.data.Topic
import com.kudo.android.ui.components.TopicTransitionHost
import com.kudo.android.ui.components.TopicTransitionContentReady
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
    private lateinit var controller: TopicTransitions
    private var detailReady by mutableStateOf(false)
    private var sourceVisible by mutableStateOf(true)

    private fun showList(patterned: Boolean = false) {
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = Color.Red, background = Color.Red)) {
                    val nav = rememberNavController()
                    val transitions = remember(nav) { TopicTransitions(nav) }
                    SideEffect { navigation = nav; controller = transitions }
                    TopicTransitionHost(transitions) {
                        NavHost(nav, "list", Modifier.fillMaxSize()) {
                            topicListComposable("list") {
                                LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
                                    item { Spacer(Modifier.height(150.dp)) }
                                    if (sourceVisible) item {
                                        Box(Modifier.padding(horizontal = if (patterned) 70.dp else 8.dp)) {
                                            TopicTransitionItem(5, 18.dp, { transitions.openTopic(5) }) { open ->
                                                Box(Modifier.fillMaxWidth().height(140.dp).background(Color.Red)
                                                    .clickable(onClick = open).testTag("transition-source")) {
                                                    if (patterned) Box(Modifier.padding(start = 20.dp, top = 20.dp)) {
                                                        Box(Modifier.size(48.dp, 24.dp).background(Color.Green))
                                                    }
                                                }
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
                                TopicTransitionContentReady(!patterned || detailReady)
                                Box(Modifier.fillMaxSize().background(if (patterned) {
                                    if (detailReady) Color.Yellow else Color.White
                                } else Color.Red).testTag("transition-content"))
                            }
                            composable("profile", popExitTransition = { fadeOut(tween(160)) }) {
                                Box(Modifier.fillMaxSize().background(Color.Cyan))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun coldDetailKeepsTheCardContentScaledUntilItIsReady() {
        showList(patterned = true)
        val original = greenMarker()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("transition-source").performClick()
        compose.mainClock.advanceTimeBy(160)
        val expanding = greenMarker()
        assertTrue("Card content must scale with its container", expanding.width > original.width * 1.05f)
        assertTrue("Card content must move upwards with its container", expanding.top < original.top)
        compose.mainClock.advanceTimeBy(700)
        assertTrue("A cold request must not replace the expanded card with a loading page", greenMarker().width > original.width * 1.4f)
        compose.runOnIdle { detailReady = true }
        compose.mainClock.advanceTimeBy(500)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("Loaded detail must take over", center.red > 0.95f && center.green > 0.95f)
    }

    @Test fun returningRestoresTheScaledCardContentBeforeLanding() {
        detailReady = true
        showList(patterned = true)
        val original = greenMarker()
        compose.onNodeWithTag("transition-source").performClick()
        compose.waitForIdle()
        val detail = navigation.currentBackStackEntry!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle {
            val snapshot = controller.snapshot(detail)!!
            assertNotNull("Returning key=${snapshot.key}; anchors=${controller.anchors.mapValues { it.value.bounds() }}",
                controller.anchors[snapshot.key]?.bounds())
        }
        val shrinking = greenMarker()
        assertTrue("The card image must return before the final frame", shrinking.width > original.width)
        compose.mainClock.advanceTimeBy(700)
        assertEquals(original, greenMarker())
    }

    @Test fun coldDetailCanReturnAndReleasesItsSnapshot() {
        showList(patterned = true)
        val original = greenMarker()
        compose.onNodeWithTag("transition-source").performClick()
        compose.waitForIdle()
        val detail = navigation.currentBackStackEntry!!
        compose.runOnIdle { assertNotNull(controller.snapshot(detail)) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertEquals(original, greenMarker())
        compose.runOnIdle {
            assertEquals("list", navigation.currentBackStackEntry?.destination?.route)
            assertEquals(null, controller.snapshot(detail))
        }
    }

    @Test fun directDetailDoesNotWaitForCardReadiness() {
        showList(patterned = true)
        compose.runOnIdle { controller.openTopic(5) }
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("Without a card snapshot the ordinary loading page must remain visible", center.red > .95f && center.blue > .95f)
        compose.runOnIdle {
            assertEquals(null, navigation.currentBackStackEntry?.savedStateHandle?.get<String>("topic-transition-key"))
            navigation.popBackStack()
        }
        compose.waitForIdle()
        assertEquals("list", navigation.currentBackStackEntry?.destination?.route)
    }

    @Test fun coldCardContentStaysBelowTheStatusBar() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        showList(patterned = true)
        compose.onNodeWithTag("transition-source").performClick()
        compose.waitForIdle()
        val inset = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.statusBars()).top
        assertTrue("The enlarged card's content must not enter the status bar", greenMarker().top >= inset)
    }

    @Test fun missingReturningCardFadesInsteadOfShrinkingToAnObsoletePosition() {
        detailReady = true
        showList(patterned = true)
        compose.onNodeWithTag("transition-source").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { sourceVisible = false; navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(128)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val top = pixels[pixels.width / 2, 5]
        assertTrue("Without a live source the detail must fade in place", top.red > .5f && top.green > .5f)
        compose.mainClock.advanceTimeBy(700)
        assertEquals("list", navigation.currentBackStackEntry?.destination?.route)
    }

    @Test fun repeatedClicksDuringCaptureOpenOnlyOneDetail() {
        showList()
        val click = compose.onNodeWithTag("transition-source").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.waitForIdle()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        assertEquals("list", navigation.currentBackStackEntry?.destination?.route)
    }

    @Test fun returningFromAChildPageDoesNotReplayTheListCardExpansion() {
        detailReady = true
        showList(patterned = true)
        compose.onNodeWithTag("transition-source").performClick()
        compose.waitForIdle()
        compose.runOnIdle { navigation.navigate("profile") }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(180)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val bottom = pixels[pixels.width / 2, pixels.height - 100]
        assertTrue("A child-page return must keep the detail full-size", bottom.red > .5f && bottom.green > .5f)
        val hasCardImage = (0 until pixels.height).any { row ->
            (0 until pixels.width).any { column ->
                val color = pixels[column, row]
                color.green > color.red + .2f && color.blue < .5f
            }
        }
        assertTrue("Returning from a child route must not expand the old list card again", !hasCardImage)
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("list", navigation.currentBackStackEntry?.destination?.route)
    }

    private fun greenMarker(): Rect {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (row in 0 until pixels.height) for (column in 0 until pixels.width) {
            val color = pixels[column, row]
            if (color.green > 0.95f && color.red < 0.05f && color.blue < 0.05f) {
                left = minOf(left, column); top = minOf(top, row)
                right = maxOf(right, column); bottom = maxOf(bottom, row)
            }
        }
        assertTrue("The original card's high-contrast content must stay visible", right >= left && bottom >= top)
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
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
