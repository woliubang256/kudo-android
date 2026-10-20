package com.kudo.android

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asDrawable
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.kudo.android.data.*
import com.kudo.android.media.communityAnimations
import com.kudo.android.ui.account.ProfileScreen
import com.kudo.android.ui.account.UserBackground
import com.kudo.android.ui.components.*
import com.kudo.android.ui.feed.FeedScreen
import com.kudo.android.ui.feed.FeedState
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.TopicScreen
import com.kudo.android.ui.topic.TopicState
import com.kudo.android.ui.topic.UserSignature
import okio.Buffer
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AvatarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var original: ImageLoader
    private lateinit var loader: ImageLoader
    private val requested = CopyOnWriteArrayList<String>()
    private val rendered = CopyOnWriteArrayList<Pair<String, Drawable>>()
    private var highResolutionGate: CompletableDeferred<Unit>? = null
    private val staticUrl = "https://avatar.test/static.png"
    private val flair = AvatarFlair("测试资质", "https://avatar.test/badge.gif")
    private val status = UserStatus("bili_118", "测试状态")
    private val member = Member(218, "current", "当前用户", staticUrl, flair = flair, status = status, avatarDetailsLoaded = true)

    @Before fun imageFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        original = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    val url = data.toString()
                    if (!url.startsWith("https://avatar.test/") && !url.startsWith("https://linux.do/images/emoji/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            requested += url
                            if (url.contains("/512/")) highResolutionGate?.await()
                            if (url.contains("broken")) throw IOException("fixture image unavailable")
                            val name = when {
                                url.contains("header") || url.contains("animated.png") -> "three-frame.png"
                                url.endsWith(".gif") -> "three-frame.gif"
                                url.endsWith(".webp") -> "three-frame.webp"
                                else -> "static.png"
                            }
                            val bytes = instrumentation.context.assets.open("avatars/$name").use { it.readBytes() }
                            return SourceFetchResult(source = ImageSource(Buffer().write(bytes), options.fileSystem), dataSource = DataSource.MEMORY, mimeType = "application/octet-stream")
                        }
                    }
                }
            })
            communityAnimations()
        }.eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                rendered += request.data.toString() to result.image.asDrawable(context.resources)
            }
        }).build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restoreImages() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { rendered.forEach { (it.second as? Animatable)?.stop() } }
        SingletonImageLoader.setUnsafe(original)
        loader.shutdown()
    }

    @Composable private fun Content(settings: DisplaySettings = DisplaySettings(), content: @Composable () -> Unit) {
        val scope = rememberCoroutineScope()
        val cache = remember { AvatarMetadataCache(scope, { emptyList() }, { mapOf("bili_118" to "https://avatar.test/custom.gif") }) }
        DisposableEffect(cache) { onDispose { cache.close() } }
        KudoTheme(settings) { CompositionLocalProvider(LocalAvatarMetadata provides cache, content = content) }
    }

    @Test fun aLoadedListAvatarIsReusedByPostAndProfileSizes() {
        var page by mutableIntStateOf(0)
        val sameMember = member.copy(avatar = "https://avatar.test/{size}/static.png", flair = null, status = null)
        compose.setContent {
            Content {
                key(page) {
                    Avatar(sameMember, listOf(35.dp, 40.dp, 60.dp)[page], Modifier.testTag("reused-avatar"))
                }
            }
        }
        compose.waitUntil(5000) { rendered.isNotEmpty() }
        compose.runOnIdle { page = 1 }
        compose.waitForIdle()
        compose.runOnIdle { page = 2 }
        compose.waitForIdle()
        val requests = requested.filter { it.endsWith("/static.png") }
        assertEquals("All ordinary avatar sizes must reuse the loaded list image", 1, requests.size)
        assertEquals("https://avatar.test/256/static.png", requests.single())
        val pixels = compose.onNodeWithTag("reused-avatar").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("The profile avatar must still show the loaded red fixture", center.red > .7f && center.green < .5f)
    }

    @Test fun highResolutionAvatarKeepsTheLoadedImageWhileWaiting() {
        var large by mutableStateOf(false)
        highResolutionGate = CompletableDeferred()
        val sameMember = member.copy(avatar = "https://avatar.test/{size}/static.png", flair = null, status = null)
        compose.setContent { Content {
            key(large) { Avatar(sameMember, if (large) 180.dp else 35.dp, Modifier.testTag("high-resolution-avatar")) }
        } }
        compose.waitUntil(5000) { rendered.any { it.first.contains("/256/") } }
        compose.runOnIdle { large = true }
        compose.waitUntil(5000) { requested.any { it.contains("/512/") } }
        val pixels = compose.onNodeWithTag("high-resolution-avatar").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("The cached image must remain visible while the larger request is suspended",
            center.red > .7f && center.green < .5f)
        highResolutionGate?.complete(Unit)
        compose.waitUntil(5000) { rendered.any { it.first.contains("/512/") } }
    }

    @Test fun changingTheAvatarAssetDoesNotReuseAnOldIdentityKey() {
        var updated by mutableStateOf(false)
        compose.setContent { Content {
            Avatar(member.copy(avatar = "https://avatar.test/${if (updated) "updated/" else ""}{size}/static.png",
                flair = null, status = null), 35.dp)
        } }
        compose.waitUntil(5000) { rendered.isNotEmpty() }
        compose.runOnIdle { updated = true }
        compose.waitUntil(5000) { rendered.any { it.first.contains("/updated/") } }
        assertEquals(2, requested.count { it.endsWith("/static.png") })
    }

    @Test fun ownerHasRoundedSquareOthersHaveCirclesAndBadgeIsNotClipped() {
        val neo = member.copy(id = 1, username = "neo", name = "Neo", flair = AvatarFlair("admins", "battery-quarter", "00aeff", "fff"))
        compose.setContent {
            Content {
                Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("头像与角标", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                        Box(Modifier.testTag("neo-container").padding(12.dp)) { Avatar(neo, 84.dp, Modifier.testTag("neo")) }
                        Box(Modifier.padding(12.dp)) { Avatar(member.copy(flair = null, status = null), 84.dp, Modifier.testTag("round")) }
                    }
                    Text("账户入口 · 自定义状态")
                    Avatar(member, 60.dp, presentation = AvatarPresentation.ACCOUNT)
                    Text("公开展示 · 优先资质")
                    Avatar(member, 60.dp)
                }
            }
        }
        compose.waitUntil(5000) { rendered.any { it.first == staticUrl } && rendered.any { it.first.endsWith("custom.gif") } }
        fun red(color: Color) = color.red > 0.7f && color.green < 0.5f && color.blue < 0.5f
        val square = compose.onNodeWithTag("neo", useUnmergedTree = true).captureToImage().toPixelMap()
        val round = compose.onNodeWithTag("round", useUnmergedTree = true).captureToImage().toPixelMap()
        assertTrue("Neo 在 10% 位置应保留方形头像内容", red(square[square.width / 10, square.height / 10]))
        assertFalse("普通用户同一位置应被圆形裁切", red(round[round.width / 10, round.height / 10]))
        val parent = compose.onNodeWithTag("neo-container").fetchSemanticsNode().boundsInRoot
        val face = compose.onNodeWithTag("neo", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pixels = compose.onNodeWithTag("neo-container").captureToImage().toPixelMap()
        var outsideBlue = 0
        for (x in (face.right - parent.left).toInt() until pixels.width) for (y in 0 until pixels.height) {
            val color = pixels[x, y]
            if (color.red < 0.2f && color.blue > 0.9f && color.green > 0.4f) outsideBlue++
        }
        assertTrue("资质应延伸到头像外且未被裁切", outsideBlue > 10)
        compose.onNodeWithContentDescription("当前用户的状态：测试状态", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("当前用户的资质：测试资质", useUnmergedTree = true).assertExists()
        capture("avatar-light-gallery.png")
    }

    @Test fun accountEntryAndPostsUseDifferentBadgePrioritiesForTheSameUser() {
        var page by mutableIntStateOf(0)
        val post = Post(1, 1, member, "<p>正文</p>", "", 0, false, null, null, true)
        compose.setContent {
            Content {
                when (page) {
                    0 -> FeedScreen(FeedState(loading = false, accountId = member.id), member, {}, {}, {}, {}, {}, {}, {}, {})
                    1 -> ProfileScreen(member, {}, {}, {}, {})
                    else -> TopicScreen(TopicState(detail = TopicDetail(1, "本人帖子", 1, listOf(post), listOf(1), false), loading = false), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        for (index in 0..1) {
            compose.runOnIdle { page = index }
            compose.onNodeWithContentDescription("当前用户的状态：测试状态", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("当前用户的资质：测试资质", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { page = 2 }
        compose.onNodeWithContentDescription("当前用户的资质：测试资质", useUnmergedTree = true).assertExists()
            .assert(hasAnyAncestor(hasContentDescription("当前用户的头像")))
        compose.onNodeWithContentDescription("当前用户的状态：测试状态", useUnmergedTree = true).assertExists()
            .assert(hasAnyAncestor(hasTestTag("member-name-decorations-${member.id}")))
        compose.onAllNodesWithContentDescription("当前用户的资质：测试资质", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("当前用户的状态：测试状态", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun brokenOriginalFallsBackToStaticAvatarAndExpiredStatusDisappears() {
        val expiring = member.copy(flair = null, status = status.copy(endsAt = Instant.now().plusSeconds(5)), animatedAvatar = "https://avatar.test/broken.gif")
        compose.setContent { Content(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("状态到期与图片回退", style = MaterialTheme.typography.headlineSmall)
                Avatar(expiring, 84.dp, Modifier.testTag("fallback"))
                Avatar(member.copy(status = null), 60.dp, presentation = AvatarPresentation.ACCOUNT)
            }
        } }
        compose.waitUntil(5000) { requested.any { it.endsWith("broken.gif") } && rendered.any { it.first == staticUrl } }
        val pixels = compose.onNodeWithTag("fallback", useUnmergedTree = true).captureToImage().toPixelMap()
        val pixel = pixels[pixels.width / 4, pixels.height / 2]
        assertTrue("动图失败后应显示静态头像", pixel.red > 0.7f && pixel.green < 0.5f)
        compose.waitUntil(8000) { compose.onAllNodesWithContentDescription("当前用户的状态：测试状态", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("当前用户的资质：测试资质", useUnmergedTree = true).assertDoesNotExist()
        capture("avatar-dark-large-fallback.png")
    }

    @Test fun backgroundAndRemovalStopAnimationsAndResumeRestartsThem() {
        var show by mutableStateOf(true)
        compose.setContent { Content { if (show) Avatar(member.copy(animatedAvatar = "https://avatar.test/animated.png"), 84.dp) } }
        fun running() = rendered.mapNotNull { it.second as? Animatable }.any { it.isRunning }
        compose.waitUntil(5000) { running() }
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            for (attempt in 0 until 75) { if (!running()) break; Thread.sleep(20) }
            assertFalse("后台不能继续播放头像或资质动画", running())
        } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitUntil(5000) { running() }
        compose.runOnIdle { show = false }
        compose.waitUntil(5000) { !running() }
    }

    @Test fun collapsingTheHomeHeaderStopsItsAnimationUntilItIsVisibleAgain() {
        val user = member.copy(animatedAvatar = "https://avatar.test/header.png", flair = null, status = null)
        val author = member.copy(id = 19, username = "other", name = "其他用户", flair = null, status = null)
        val topics = (1..30).map { Topic(it, "滚动测试 $it", "列表正文", author, 1, emptyList(), 0, 0, 0, "", "") }
        compose.setContent { Content { FeedScreen(FeedState(topics = topics, loading = false, accountId = user.id), user, {}, {}, {}, {}, {}, {}, {}, {}) } }
        fun running() = rendered.filter { it.first.endsWith("header.png") }.mapNotNull { it.second as? Animatable }.any { it.isRunning }
        compose.waitUntil(5000) { running() }
        val list = compose.onNodeWithTag("feed-list-latest")
        list.performTouchInput { swipeUp(durationMillis = 800) }
        compose.onNodeWithContentDescription("当前用户的头像", useUnmergedTree = true).assertIsNotDisplayed()
        compose.waitUntil(5000) { !running() }
        list.performTouchInput { swipeDown(durationMillis = 800) }
        compose.onNodeWithContentDescription("当前用户的头像", useUnmergedTree = true).assertIsDisplayed()
        compose.waitUntil(5000) { running() }
    }

    @Test fun disabledSignaturesMakeNoRequestsAndAllThreeAnimationFormatsRenderFramesAndPause() {
        var settings by mutableStateOf(DisplaySettings(showSignatures = false))
        var signature by mutableStateOf("https://avatar.test/signature.gif")
        val post = Post(50, 1, member, "正文", "", 0, false, null, null, false)
        compose.setContent { Content(settings) { Column(Modifier.padding(24.dp)) {
            Text("个性签名预览", style = MaterialTheme.typography.titleMedium)
            UserSignature(post.copy(signature = signature), {})
        } } }
        compose.onNodeWithTag("user-signature-50").assertDoesNotExist()
        assertFalse(requested.any { it.contains("signature") })
        compose.runOnIdle { settings = settings.copy(showSignatures = true) }
        for (file in listOf("signature.gif", "signature-animated.png", "signature.webp")) {
            val url = "https://avatar.test/$file"
            compose.runOnIdle { signature = url }
            compose.waitUntil(5000) { rendered.any { it.first == url } }
            val colors = mutableSetOf<Int>()
            try { compose.waitUntil(5000) {
                val pixels = compose.onNodeWithContentDescription("用户签名图片").captureToImage().toPixelMap()
                val pixel = pixels[pixels.width / 2, pixels.height / 2]
                colors += when {
                    pixel.red > pixel.green + 0.15f && pixel.red > pixel.blue + 0.15f -> 1
                    pixel.green > pixel.red + 0.15f && pixel.green > pixel.blue + 0.15f -> 2
                    pixel.blue > pixel.red + 0.15f && pixel.blue > pixel.green + 0.15f -> 3
                    else -> 0
                }
                colors.containsAll(listOf(1, 2, 3))
            } } catch (e: Throwable) {
                println("Signature frames: $file colors=$colors bounds=${compose.onNodeWithContentDescription("用户签名图片").fetchSemanticsNode().boundsInRoot} drawables=${rendered.filter { it.first == url }.map { it.second.javaClass.name to (it.second as? Animatable)?.isRunning }}")
                capture("signature-frame-failure.png")
                throw e
            }
        }
        compose.runOnIdle { signature = "<p>欢迎来到我的主页</p><img src='https://avatar.test/signature.gif'>" }
        compose.onNodeWithText("欢迎来到我的主页").assertIsDisplayed()
        compose.onNodeWithContentDescription("用户签名图片").assertIsDisplayed()
        val signaturePreview = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "user-signature-animated.png")
            .outputStream().use { signaturePreview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun running() = rendered.filter { it.first.contains("signature") }.mapNotNull { it.second as? Animatable }.any { it.isRunning }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        for (attempt in 0 until 75) { if (!running()) break; Thread.sleep(20) }
        assertFalse(running())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { running() }
        compose.runOnIdle { settings = settings.copy(showSignatures = false) }
        compose.onNodeWithTag("user-signature-50").assertDoesNotExist()
        compose.waitUntil(5000) { !running() }
    }

    @Test fun memberHeaderUsesProfileTitleAndBrokenBackgroundFallsBack() {
        var headerStyle: Boolean? = null
        var current by mutableStateOf(member.copy(cardBackground = "https://avatar.test/card.png", profileBackground = "https://avatar.test/profile.png"))
        var sample by mutableStateOf(false)
        var background by mutableStateOf("https://avatar.test/broken.png")
        var loaded = true
        compose.setContent { Content {
            if (!sample) ProfileScreen(current, {}, {}, {}, {}, statusBarStyle = { headerStyle = it })
            else UserBackground(background, Color.Yellow, Modifier.size(240.dp).testTag("background-sample")) {
                SideEffect { loaded = it }
                Text(if (it) "图片背景" else "默认背景")
            }
        } }
        compose.waitUntil(5000) { rendered.any { it.first.endsWith("profile.png") } }
        compose.runOnIdle { assertEquals(false, headerStyle) }
        assertFalse(requested.any { it.endsWith("card.png") })
        capture("profile-card-background.png")
        compose.runOnIdle { sample = true }
        compose.waitUntil(5000) { requested.any { it.endsWith("broken.png") } }
        compose.onNodeWithText("默认背景").assertIsDisplayed()
        compose.runOnIdle { assertFalse(loaded); background = current.profileBackground }
        compose.waitUntil(5000) { rendered.any { it.first.endsWith("profile.png") } }
        compose.onNodeWithText("图片背景").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
