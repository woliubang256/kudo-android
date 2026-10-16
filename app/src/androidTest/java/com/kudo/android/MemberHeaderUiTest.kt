package com.kudo.android

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.account.ProfileScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import coil3.*
import coil3.fetch.*
import coil3.request.Options
import coil3.decode.*
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File

class MemberHeaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previous: ImageLoader
    private lateinit var images: ImageLoader
    @Before fun images() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previous = SingletonImageLoader.get(context)
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20, 180, 60)) }
        for (x in 0..39) for (y in 0..19) bitmap.setPixel(x, y, android.graphics.Color.rgb(220, 40, 20))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        images = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://member-image.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch() = SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), "image/png", DataSource.MEMORY)
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(images)
    }
    @After fun restore() { SingletonImageLoader.setUnsafe(previous); images.shutdown() }
    @Test fun accountStatusBadgeExtendsOutsideFaceWithoutBeingClipped() {
        var loaded = false
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            Box(Modifier.width(360.dp).height(700.dp)) {
                ProfileScreen(Member(42, "fixture", "角标测试", "", status = UserStatus("", "在线"),
                    profileBackground = "https://member-image.test/header"), {}, {}, {}, {}, statusBarStyle = { loaded = it == false })
            }
        } }
        compose.waitUntil(5000) { loaded }
        val parent = compose.onNodeWithTag("member-header").fetchSemanticsNode().boundsInRoot
        val face = compose.onNodeWithTag("member-avatar").fetchSemanticsNode().boundsInRoot
        val pixels = compose.onNodeWithTag("member-header").captureToImage().toPixelMap()
        var outsideWhite = 0
        for (x in (face.right - parent.left).toInt() until (face.right - parent.left + 10).toInt().coerceAtMost(pixels.width)) {
            for (y in (face.bottom - parent.top - 40).toInt().coerceAtLeast(0) until (face.bottom - parent.top).toInt().coerceAtMost(pixels.height)) {
                val c = pixels[x, y]
                if (c.red > .97f && c.green > .97f && c.blue > .97f) outsideWhite++
            }
        }
        assertTrue("状态角标应绘制到头像外，实际白色像素=$outsideWhite", outsideWhite > 10)
    }
    @Test fun contentHeaderBalancesInsetsCollapsesFromListAndKeepsIdentityAndProfileArrow() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var profiles = 0
        var statusInset = 0
        var padding = 0f
        var avatarSize = 0f
        var identityGap = 0f
        compose.setContent { KudoTheme(DisplaySettings()) {
            val density = LocalDensity.current
            statusInset = WindowInsets.statusBars.getTop(density)
            with(density) { padding = 26.dp.toPx(); avatarSize = 64.dp.toPx(); identityGap = 60.dp.toPx() }
            Box(Modifier.width(360.dp).height(700.dp)) {
                ProfileScreen(Member(42, "fixture", "内容顶部", "", trustLevel = 3), {}, {}, {}, { profiles++ })
            }
        } }
        val header = compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag("member-avatar").fetchSemanticsNode().boundsInRoot
        val identity = compose.onNodeWithTag("member-identity").fetchSemanticsNode().boundsInRoot
        val values = compose.onAllNodesWithText("—", useUnmergedTree = true).fetchSemanticsNodes()
        val label = compose.onNodeWithText("访问天数", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("头像距状态栏底边为26dp", padding, avatar.top - header.top - statusInset, 2f)
        assertEquals("统计距底部为26dp", padding, header.bottom - label.bottom, 2f)
        assertEquals("保留64dp头像", avatarSize, avatar.height, 2f)
        assertEquals("保留身份与统计内部间距", identityGap, values.first().boundsInRoot.top - identity.bottom, 2f)
        assertTrue("顶部改为内容撑高", header.height < header.width)
        val nickname = compose.onNodeWithText("内容顶部", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val level = compose.onNodeWithText("Lv.3").fetchSemanticsNode().boundsInRoot
        assertTrue(level.left >= nickname.right)
        assertEquals(nickname.center.y, level.center.y, 3f)
        compose.onNodeWithTag("member-avatar").performClick()
        compose.onNodeWithTag("member-profile-arrow").performClick()
        compose.onNodeWithTag("member-profile").performTouchInput { swipeUp() }
        compose.onNodeWithTag("member-compact-header").assertIsDisplayed()
        compose.onNodeWithText("内容顶部").assertIsDisplayed()
        compose.onNodeWithTag("member-profile-arrow").performClick()
        compose.runOnIdle { assertEquals(3, profiles) }
        compose.onNodeWithTag("member-profile").performTouchInput { swipeDown(); swipeDown() }
        compose.waitForIdle()
        assertEquals(header.height, compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot.height, 2f)
    }
    @Test fun wholeIdentityOpensProfileButLevelRemainsIndependent() {
        var profiles = 0
        var levels = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.width(360.dp).height(700.dp)) {
                ProfileScreen(Member(42, "fixture", "身份整排", "", trustLevel = 3), {}, {}, {}, { profiles++ },
                    destination = { if (it == AccountDestination.TRUST) levels++ })
            }
        } }
        val header = compose.onNodeWithTag("member-header").fetchSemanticsNode().boundsInRoot
        val identity = compose.onNodeWithTag("member-identity").fetchSemanticsNode().boundsInRoot
        assertTrue("身份行应高于底部统计", header.bottom - identity.bottom > identity.height * 1.5f)
        compose.onNodeWithTag("member-identity").performTouchInput { click() }
        compose.onNodeWithText("Lv.3").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, profiles); assertEquals(1, levels) }
        compose.onNodeWithTag("member-profile").performTouchInput { swipeUp() }
        compose.onNodeWithTag("member-compact-header").performTouchInput { click() }
        compose.onNodeWithText("Lv.3").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, profiles); assertEquals(2, levels) }
    }
    @Test fun fontAndWindowChangesRemeasureContentAndKeepCollapsedIdentity() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var fontScale by mutableFloatStateOf(1f)
        var width by mutableStateOf(360.dp)
        var statusInset = 0
        var padding = 0f
        var avatarSize = 0f
        var shortGap = 0f
        var listMinimum = 0f
        compose.setContent { KudoTheme(DisplaySettings(fontScale = fontScale)) {
            val density = LocalDensity.current
            statusInset = WindowInsets.statusBars.getTop(density)
            with(density) {
                padding = 26.dp.toPx(); avatarSize = 64.dp.toPx()
                shortGap = 32.dp.toPx(); listMinimum = 120.dp.toPx()
            }
            Box(Modifier.fillMaxSize()) { Box(Modifier.width(width).height(480.dp)) {
                ProfileScreen(Member(42, "fixture", "较长的昵称用于验证内容测量", "", trustLevel = 3), {}, {}, {}, {})
            } }
        } }
        fun assertInsetsAndAvatar() {
            val header = compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot
            val avatar = compose.onNodeWithTag("member-avatar").fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText("访问天数", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(padding, avatar.top - header.top - statusInset, 2f)
            assertEquals(padding, header.bottom - label.bottom, 2f)
            assertEquals(avatarSize, avatar.height, 2f)
            assertTrue(compose.onNodeWithTag("member-profile").fetchSemanticsNode().boundsInRoot.height >= listMinimum)
        }
        assertInsetsAndAvatar()
        val originalHeight = compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { fontScale = 1.3f }
        assertInsetsAndAvatar()
        assertTrue("大字体应自然撑高顶部", compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot.height > originalHeight)
        compose.onNodeWithTag("member-profile").performTouchInput { swipeUp() }
        compose.onNodeWithTag("member-compact-header").assertIsDisplayed()
        compose.runOnIdle { width = 280.dp; fontScale = 1f }
        compose.onNodeWithTag("member-compact-header").assertIsDisplayed()
        compose.onNodeWithTag("member-identity").assertDoesNotExist()
        compose.onNodeWithTag("member-profile").performTouchInput { swipeDown(); swipeDown() }
        assertInsetsAndAvatar()
        val identity = compose.onNodeWithTag("member-identity").fetchSemanticsNode().boundsInRoot
        val value = compose.onAllNodesWithText("—", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        assertEquals("短窗口保留原有内部间距", shortGap, value.top - identity.bottom, 2f)
        compose.runOnIdle { fontScale = 1.3f }
        assertInsetsAndAvatar()
        compose.onNodeWithTag("member-header-viewport").performTouchInput { swipeUp() }
        compose.onNodeWithTag("member-compact-header").assertIsDisplayed()
    }
    @Test fun pinnedHeaderUsesSelectedMaterialWithoutPictureAndRestoresExpandedHeader() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var style: Boolean? = null
        var showing by mutableStateOf(false)
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT, glass = GlassMode.LIQUID))
        var expectedInk = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent { KudoTheme(settings) {
            expectedInk = MaterialTheme.colorScheme.onSurface
            Box(Modifier.width(360.dp).height(700.dp)) {
                if (showing) ProfileScreen(Member(42, "fixture", "材质顶部", "https://member-image.test/avatar"),
                    {}, {}, {}, {}, statusBarStyle = { style = it })
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (glass in GlassMode.entries) {
                compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); showing = true }
                compose.waitUntil(5000) { style == false }
                val expandedHeight = compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot.height
                val full = compose.onNodeWithTag("member-header-viewport").captureToImage().toPixelMap()
                assertTrue(full[2, full.height / 4].red > full[2, full.height / 4].green * 2)
                assertTrue(full[2, full.height - 5].green > full[2, full.height - 5].red * 2)
                compose.onNodeWithTag("member-profile").performTouchInput { swipeUp() }
                compose.onNodeWithTag("member-compact-header").assertIsDisplayed()
                val arrow = compose.onNodeWithTag("member-profile-arrow").assertIsDisplayed()
                    .assert(!hasAnyAncestor(hasTestTag("member-content-backdrop"))).fetchSemanticsNode().boundsInRoot
                val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                assertEquals("$theme/$glass 独立主页按钮保留48dp触控区", 48f * density, arrow.width, 2f)
                assertEquals(arrow.width, arrow.height, 2f)
                val compact = compose.onNodeWithTag("member-header-viewport").captureToImage().toPixelMap()
                val bottom = compact[2, compact.height - 5]
                assertTrue("$theme/$glass 吸顶不应保留绿色头图", kotlin.math.abs(bottom.green - bottom.red) < .08f)
                compose.onNodeWithTag("member-toolbar-material").assertIsDisplayed()
                    .assert(!hasAnyAncestor(hasTestTag("member-content-backdrop")))
                compose.onNodeWithTag("member-content-backdrop").assertExists()
                val textLayouts = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText("材质顶部") and hasAnyAncestor(hasTestTag("member-compact-header")), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
                assertEquals(expectedInk, textLayouts.single().layoutInput.style.color)
                compose.runOnIdle { assertEquals(theme == ThemeMode.LIGHT, style) }
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
                File(directory, "member-material-${theme.name.lowercase()}-${glass.name.lowercase()}.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                compose.onNodeWithTag("member-profile").performTouchInput { swipeDown(); swipeDown() }
                compose.onNodeWithTag("member-compact-header").assertDoesNotExist()
                compose.onNodeWithTag("member-toolbar-material").assertDoesNotExist()
                assertEquals(expandedHeight, compose.onNodeWithTag("member-header-viewport").fetchSemanticsNode().boundsInRoot.height, 2f)
                val restored = compose.onNodeWithTag("member-header-viewport").captureToImage().toPixelMap()
                assertTrue(restored[2, restored.height - 5].green > restored[2, restored.height - 5].red * 2)
                compose.runOnIdle { assertEquals(false, style); showing = false }
                compose.runOnIdle { assertNull(style) }
            }
        }
    }
    @Test fun missingImageUsesWhiteBaseAndDarkStatusIcons() {
        var style: Boolean? = null
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.width(360.dp).height(700.dp)) {
                ProfileScreen(Member(42, "fixture", "白底", ""), {}, {}, {}, {}, statusBarStyle = { style = it })
            }
        } }
        val full = compose.onNodeWithTag("member-header-viewport").captureToImage().toPixelMap()[2, 2]
        assertTrue(full.red > .98f && full.green > .98f && full.blue > .98f)
        compose.runOnIdle { assertEquals(true, style) }
    }
}
