package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.account.PublicProfilePage
import com.kudo.android.ui.account.PublicProfileState
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.TopicTopBar
import com.kudo.android.ui.topic.TopicState
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class AvatarProfileTransitionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var original: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var navigation: NavHostController
    private lateinit var transitions: AvatarTransitions
    private val requests = AtomicInteger()
    private val member = Member(77, "avatar-user", "头像连续展示", "https://avatar-transition.test/{size}/green.png",
        avatarDetailsLoaded = true)
    private var sourceVisible by mutableStateOf(true)

    @Before fun images() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        original = SingletonImageLoader.get(context)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://avatar-transition.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            requests.incrementAndGet()
                            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), "image/png", DataSource.MEMORY)
                        }
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restore() { SingletonImageLoader.setUnsafe(original); loader.shutdown() }

    private fun showNavigation(duplicate: Boolean = false, topicAuthor: Boolean = false) {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            val nav = rememberNavController()
            val avatars = remember(nav) { AvatarTransitions(nav) }
            SideEffect { navigation = nav; transitions = avatars }
            val topics = remember(nav) { TopicTransitions(nav) }
            TopicTransitionHost(topics, avatars) {
                NavHost(nav, "list", Modifier.fillMaxSize(), enterTransition = { fadeIn(tween(280)) },
                    exitTransition = { fadeOut(tween(180)) }, popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(220)) }) {
                    topicListComposable("list") {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            if (topicAuthor) TopicTopBar(TopicState(detail = TopicDetail(1, "头像入口", 1, emptyList(), emptyList(), false, author = member)),
                                true, {}, { avatars.openUser(it) }, {}, {})
                            else if (sourceVisible) Avatar(member, 35.dp, Modifier.offset(160.dp, 450.dp).testTag("avatar-source")) {
                                avatars.openUser(member.username)
                            }
                            if (duplicate) Avatar(member, 35.dp, Modifier.offset(10.dp, 570.dp).testTag("avatar-neighbor")) {
                                avatars.openUser(member.username)
                            }
                        }
                    }
                    topicListComposable("user/{username}") { entry ->
                        Box(Modifier.fillMaxSize().background(Color(0xFF202060))) {
                            val initial = avatars.seed(entry)?.member ?: member
                            Avatar(initial, 60.dp, Modifier.offset(20.dp, 80.dp).testTag("avatar-target"), sharedProfileAvatar = true)
                        }
                    }
                    composable("child") { Text("子页面") }
                }
            }
        } }
        compose.waitUntil(5000) { requests.get() > 0 }
        compose.waitForIdle()
    }

    @Test fun openingAndReturningMoveTheLoadedAvatarThroughIntermediatePositions() {
        showNavigation()
        val source = compose.onNodeWithTag("avatar-source").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("avatar-source").performClick()
        compose.mainClock.advanceTimeBy(144)
        val opening = greenBounds()
        assertTrue("Opening must move the real image, not just fade two static avatars: $opening", opening.top < source.top - 10)
        assertTrue("The opening image must not jump directly to the target: $opening",
            opening.top > with(compose.density) { 90.dp.toPx() })
        compose.mainClock.advanceTimeBy(600)
        val target = greenBounds()
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(144)
        val closing = greenBounds()
        assertTrue("Returning must move back towards the original list avatar: $closing", closing.top > target.top + 10)
        assertTrue(closing.top < source.top - 10)
        compose.mainClock.advanceTimeBy(600)
        val returned = greenBounds()
        assertEquals(source.top, returned.top, 3f)
        assertEquals("List and profile must share the same loaded image", 1, requests.get())
    }

    @Test fun topicAuthorRowSharesItsAvatarWhenTheParentHandlesTheClick() {
        showNavigation(topicAuthor = true)
        val source = greenBounds()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("topic-author").performClick()
        compose.mainClock.advanceTimeBy(144)
        compose.runOnIdle { assertNotNull("The topic author row must carry its displayed avatar", transitions.seed(navigation.currentBackStackEntry!!)) }
        val opening = greenBounds()
        assertTrue("The actual avatar must move down towards the profile: $opening", opening.top > source.top + 4)
        compose.mainClock.advanceTimeBy(600)
        val target = greenBounds()
        assertTrue(opening.top < target.top - 4)
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(144)
        val closing = greenBounds()
        assertTrue(closing.top < target.top - 4 && closing.top > source.top + 4)
        compose.mainClock.advanceTimeBy(600)
        assertEquals(source.top, greenBounds().top, 3f)
    }

    @Test fun repeatedAuthorOnlySharesTheClickedInstanceAndIgnoresASecondTap() {
        showNavigation(duplicate = true)
        val neighbor = compose.onNodeWithTag("avatar-neighbor").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("avatar-source").performClick()
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("avatar-neighbor").assertPositionInRootIsEqualTo(
            with(compose.density) { neighbor.left.toDp() }, with(compose.density) { neighbor.top.toDp() })
        compose.onNodeWithTag("avatar-neighbor").performClick()
        compose.runOnIdle { transitions.openUser("") }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertNotNull(transitions.seed(navigation.currentBackStackEntry!!))
            navigation.popBackStack()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("avatar-source").assertIsDisplayed()
    }

    @Test fun childReturnDoesNotReplayTheListAvatarAndMissingSourceFallsBack() {
        showNavigation()
        compose.onNodeWithTag("avatar-source").performClick()
        compose.waitForIdle()
        val target = greenBounds()
        compose.runOnIdle { navigation.navigate("child") }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(144)
        assertEquals(target.top, greenBounds().top, 3f)
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { sourceVisible = false; navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("avatar-source").assertDoesNotExist()
        compose.onNodeWithTag("avatar-target").assertDoesNotExist()
    }

    @Test fun aColdProfileShowsTheKnownAvatarWithoutInventingPermissions() {
        var state by mutableStateOf(PublicProfileState())
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            PublicProfilePage(state, null, {}, {}, {}, {}, {}, {}, initialMember = member)
        } }
        compose.onNodeWithTag("public-profile-avatar", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("profile-follow").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, error = ApiFailure(FailureKind.NOT_FOUND, "用户不存在")) }
        compose.onNodeWithTag("public-profile-avatar", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aDirectUserLinkDoesNotReuseThePreviousAvatarSeed() {
        showNavigation()
        compose.onNodeWithTag("avatar-source").performClick()
        compose.waitForIdle()
        compose.runOnIdle { transitions.openUser("another-user") }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(transitions.seed(navigation.currentBackStackEntry!!)) }
    }

    @Test fun serverProfileReplacesOnlyUnknownDataWithoutMovingTheSeedAvatar() {
        var state by mutableStateOf(PublicProfileState())
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            PublicProfilePage(state, 12, {}, {}, {}, {}, {}, {}, initialMember = member)
        } }
        compose.waitUntil(5000) { requests.get() >= 2 }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("public-profile-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val before = requests.get()
        compose.runOnIdle {
            state = state.copy(profile = PublicProfile(member.copy(name = "服务器名称", canFollow = true), 5, 2, "", "", "", "", "", false), loading = false)
        }
        compose.onNodeWithTag("profile-follow", useUnmergedTree = true).assertExists()
        val updated = compose.onNodeWithTag("public-profile-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(first, updated)
        assertEquals("A profile response must not reload the same avatar asset", before, requests.get())
    }

    private fun greenBounds(): Rect {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = 0
        var bottom = 0
        for (row in 0 until pixels.height) for (column in 0 until pixels.width) {
            val color = pixels[column, row]
            if (color.green > .5f && color.green > color.red + .1f && color.green > color.blue + .1f) {
                left = minOf(left, column); top = minOf(top, row)
                right = maxOf(right, column + 1); bottom = maxOf(bottom, row + 1)
            }
        }
        assertTrue("The already loaded avatar pixels must remain visible", right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}
