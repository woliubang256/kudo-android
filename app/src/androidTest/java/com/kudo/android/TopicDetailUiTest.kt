package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.kudo.android.data.*
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.TopicScreen
import com.kudo.android.ui.topic.TopicState
import com.kudo.android.ui.topic.TopicSolutionsSummary
import com.kudo.android.ui.topic.LocalTopicActions
import com.kudo.android.ui.topic.TopicActionsController
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class TopicDetailUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(2, "author", "测试楼主", "", flair = AvatarFlair("测试资质", "battery-quarter"),
        status = UserStatus("smile", "测试状态"), avatarDetailsLoaded = true, canFollow = true)
    private val first = Post(10, 1, author,
        """<p>正文表情 <img class="emoji" src="https://emoji.test/distorted_face.png" alt=":distorted_face:"> 继续阅读</p>""" +
            (1..12).joinToString("") { "<p>用于检查固定作者标题栏的正文第${it}段。</p>" },
        "2026-10-09T00:00:00Z", 5, false, null, null, true, reactionsAvailable = true)
    private val reply = first.copy(id = 11, number = 2, author = author.copy(id = 3, username = "reply", name = "回复作者"), cooked = "<p>回复正文</p>")
    private val detail = TopicDetail(5, "详情作者、关注与回应", 1, listOf(first, reply), listOf(10, 11), false,
        validReactions = listOf("heart", "+1", "laughing", "open_mouth", "clap", "confetti_ball", "hugs", "distorted_face", "tieba_087", "bili_057"))
    private val initial = TopicState(detail, false, viewerId = 99, authorProfile = author)
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private val loaded = CopyOnWriteArrayList<String>()

    @Before fun images() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://emoji.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            val bytes = instrumentation.context.assets.open("avatars/static.png").use { it.readBytes() }
                            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), "image/png", DataSource.MEMORY)
                        }
                    }
                }
            })
        }.eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { loaded += request.data.toString() }
        }).build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restoreImages() { SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown() }

    @Composable private fun Screen(
        state: TopicState, settings: DisplaySettings = DisplaySettings(theme = ThemeMode.LIGHT),
        like: (Post) -> Unit = {}, follow: () -> Unit = {}, react: (Post, String) -> Unit = { _, _ -> },
        user: (String) -> Unit = {}, retry: () -> Unit = {}, share: () -> Unit = {}, link: (String) -> Unit = {},
    ) {
        val scope = rememberCoroutineScope()
        val cache = remember { AvatarMetadataCache(scope, { emptyList() }, {
            detail.validReactions.associateWith { "https://emoji.test/$it.png" }
        }) }
        DisposableEffect(cache) { onDispose { cache.close() } }
        CompositionLocalProvider(LocalAvatarMetadata provides cache) {
            KudoTheme(settings) {
                TopicScreen(state, settings.glass, {}, retry, {}, {}, like, {}, {}, user, {}, link, share, follow, react)
            }
        }
    }

    @Test fun authorAppearsOnceInTheFixedToolbarAndFollowReflectsState() {
        var state by mutableStateOf(initial)
        var opened = ""
        var follows = 0
        compose.setContent {
            Screen(state, user = { opened = it }, follow = {
                follows++
                state = state.copy(authorProfile = state.authorProfile!!.copy(isFollowed = !state.authorProfile!!.isFollowed))
            })
        }
        compose.onAllNodesWithText(author.displayName).assertCountEquals(1)
        compose.onNodeWithTag("topic-author").performTouchInput { click() }
        compose.runOnIdle { assertEquals(author.username, opened) }
        compose.onNodeWithText("关注").performTouchInput { click() }
        compose.onNodeWithText("已关注").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("关注").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, follows) }
        val before = compose.onNodeWithTag("topic-author").fetchSemanticsNode().boundsInRoot
        capture("topic-toolbar-light.png")
        compose.onNodeWithTag("topic-posts").performTouchInput { swipeUp(durationMillis = 600) }
        val after = compose.onNodeWithTag("topic-author").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 0.5f)
        compose.onNodeWithText(author.displayName).assertIsDisplayed()
    }

    @Test fun authorKeepsFlairOnAvatarAndOtherDecorationsAfterTheNickname() {
        compose.setContent { Screen(initial) }
        assertPublicAuthorDecorations(author)
        capture("topic-avatar-and-name-light.png")
    }

    @Test fun replyKeepsFlairOnAvatarAndOtherDecorationsAfterTheNickname() {
        compose.setContent { Screen(initial) }
        compose.onNodeWithTag("topic-posts").performScrollToNode(hasText(reply.author.displayName))
        assertPublicAuthorDecorations(reply.author)
        capture("topic-reply-avatar-and-name-light.png")
    }

    @Test fun replyNicknameIsNotEllipsizedByTrailingAuthorSpacer() {
        val member = reply.author.copy(name = "回复作者昵称需要完整显示", status = null)
        val post = reply.copy(author = member)
        val state = initial.copy(detail = detail.copy(posts = listOf(first, post)))
        compose.setContent { Screen(state) }
        compose.onNodeWithTag("topic-posts").performScrollToNode(hasText(member.displayName))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(member.displayName, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("回复作者的中等长度昵称不应被尾部空白 Spacer 压成省略", layouts.single().isLineEllipsized(0))
    }

    @Test fun statusOnlyStaysOnAvatarWithoutADuplicateAfterTheNickname() {
        val member = author.copy(flair = null)
        val post = first.copy(author = member)
        compose.setContent { Screen(initial.copy(detail = detail.copy(posts = listOf(post), author = member), authorProfile = member)) }
        compose.onNodeWithContentDescription("${member.displayName}的状态：测试状态", useUnmergedTree = true)
            .assertIsDisplayed().assert(hasAnyAncestor(hasContentDescription("${member.displayName}的头像")))
        compose.onAllNodesWithContentDescription("${member.displayName}的状态：测试状态", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("member-name-decorations-${member.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun flairOnlyStaysOnAvatarWithoutAnEmptyNameDecorationRow() {
        val member = author.copy(status = null)
        val post = first.copy(author = member)
        compose.setContent { Screen(initial.copy(detail = detail.copy(posts = listOf(post), author = member), authorProfile = member)) }
        compose.onNodeWithContentDescription("${member.displayName}的资质：测试资质", useUnmergedTree = true)
            .assertIsDisplayed().assert(hasAnyAncestor(hasContentDescription("${member.displayName}的头像")))
        compose.onAllNodesWithContentDescription("${member.displayName}的资质：测试资质", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("member-name-decorations-${member.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun mediumNicknameIsNotEllipsizedByTrailingAuthorSpacer() {
        val member = author.copy(name = "夏日夜空里的", status = null)
        val post = first.copy(author = member)
        compose.setContent {
            Screen(initial.copy(detail = detail.copy(posts = listOf(post), author = member), authorProfile = member))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(member.displayName, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("中等长度昵称不应被尾部空白 Spacer 压成省略", layouts.single().isLineEllipsized(0))
    }

    @Test fun longNicknameKeepsItsAvatarAndInlineIconsInDarkLargeText() {
        val member = author.copy(name = "这是一个用于检查头像挂件和昵称图标的很长昵称")
        val post = first.copy(author = member)
        compose.setContent {
            Screen(initial.copy(detail = detail.copy(posts = listOf(post), author = member), authorProfile = member),
                DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))
        }
        assertPublicAuthorDecorations(member)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(member.displayName, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("长昵称应保持单行", 1, layouts.single().lineCount)
        compose.onNodeWithText("关注").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多").assertIsDisplayed()
        capture("topic-avatar-and-name-dark-large.png")
    }

    private fun assertPublicAuthorDecorations(member: Member) {
        val avatar = compose.onNodeWithContentDescription("${member.displayName}的头像", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val flair = compose.onNodeWithContentDescription("${member.displayName}的资质：测试资质", useUnmergedTree = true)
            .assertIsDisplayed().assert(hasAnyAncestor(hasContentDescription("${member.displayName}的头像")))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("挂件应位于头像右下角", flair.center.x >= avatar.center.x && flair.center.x <= avatar.right &&
            flair.center.y >= avatar.center.y && flair.center.y <= avatar.bottom)
        val name = compose.onNodeWithText(member.displayName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithContentDescription("${member.displayName}的状态：测试状态", useUnmergedTree = true)
            .assertIsDisplayed().assert(hasAnyAncestor(hasTestTag("member-name-decorations-${member.id}")))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("其他图标应紧跟昵称", status.left >= name.right && status.left - name.right < 40f)
        compose.onAllNodesWithContentDescription("${member.displayName}的资质：测试资质", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("${member.displayName}的状态：测试状态", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun topicAuthorDecorationsAreNotDuplicatedByAvatarBadges() {
        compose.setContent { Screen(initial) }
        compose.onAllNodesWithContentDescription("${author.displayName}\u7684\u8d44\u8d28\uff1a\u6d4b\u8bd5\u8d44\u8d28", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("${author.displayName}\u7684\u72b6\u6001\uff1a\u6d4b\u8bd5\u72b6\u6001", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun solutionSummaryShowsEverySolutionAndMarksLoadedFloors() {
        val solutionAuthor = author.copy(id = 3, username = "helper", name = "热心用户")
        val solutionOne = reply.copy(id = 11, number = 2, author = solutionAuthor, cooked = "<p>第一种解决方案</p>")
        val solutionTwo = reply.copy(id = 12, number = 3, author = author.copy(id = 4, username = "another", name = "另一位用户"),
            cooked = "<p>第二种解决方案</p>")
        val solved = detail.copy(posts = listOf(first, solutionOne), stream = listOf(first.id, solutionOne.id, solutionTwo.id),
            solutions = listOf(TopicSolution(2, "helper", "热心用户", "第一种解决方案"),
                TopicSolution(3, "another", "另一位用户", "第二种解决方案")))
        compose.setContent { Screen(initial.copy(detail = solved)) }

        compose.onNodeWithTag("topic-posts").performScrollToNode(hasTestTag("topic-solutions"))
        compose.onNodeWithTag("topic-solutions").assertIsDisplayed()
        compose.onNodeWithTag("topic-solution-2").assertIsDisplayed()
        compose.onNodeWithTag("topic-solution-3").assertIsDisplayed()
        compose.onNodeWithTag("topic-posts").performScrollToNode(hasTestTag("post-2"))
        compose.onNodeWithTag("solution-marker-2").assertIsDisplayed()
        compose.onNodeWithTag("solution-marker-3").assertDoesNotExist()
    }

    @Test fun solutionSummaryNavigatesToAnUnloadedFloor() {
        val solved = detail.copy(solutions = listOf(TopicSolution(2, "helper", "热心用户", "摘要")))
        val jumps = mutableListOf<Int>()
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
                TopicSolutionsSummary(solved, solved.posts, true) { jumps += it }
            }
        }
        compose.onNodeWithTag("topic-solution-2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(2), jumps) }
    }

    @Test fun topicWithoutSolutionsDoesNotRenderSolutionSummary() {
        compose.setContent { Screen(initial.copy(detail = detail.copy(solutions = emptyList()))) }
        compose.onNodeWithTag("topic-solutions").assertDoesNotExist()
    }

    @Test fun shortTapCancelsHeartOrEmojiAndLongPressOnlyOpensTheResponsePicker() {
        var post by mutableStateOf(first.copy(liked = true, currentReaction = UserReaction("heart", true)))
        var shortTaps = 0
        var selected = ""
        compose.setContent {
            Screen(initial.copy(detail = detail.copy(posts = listOf(post, reply))), like = {
                assertEquals(first.id, it.id)
                shortTaps++
                post = post.copy(liked = false, currentReaction = null)
            }, react = { current, name -> assertEquals(first.id, current.id); selected = name })
        }
        compose.onNodeWithContentDescription("赞主帖").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, shortTaps); post = post.copy(currentReaction = UserReaction("distorted_face", true)) }
        compose.onNodeWithContentDescription("取消主帖回应").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, shortTaps) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithContentDescription("赞主帖").performTouchInput { longClick(durationMillis = 650) }
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        compose.onNodeWithTag("reaction-choice-distorted_face").assertIsDisplayed()
        compose.onNodeWithText("写回复").assertDoesNotExist()
        capture("topic-responses-light.png")
        compose.onNodeWithTag("reaction-choice-distorted_face").performTouchInput { click() }
        compose.runOnIdle { assertEquals("distorted_face", selected); assertEquals(2, shortTaps) }
    }

    @Test fun lockedReactionsStayDisabledAndDeepLinkActionsStillTargetTheOriginalPost() {
        var state by mutableStateOf(initial.copy(detail = detail.copy(posts = listOf(first.copy(currentReaction = UserReaction("distorted_face", false)), reply))))
        var target = 0
        compose.setContent { Screen(state, like = { target = it.id }) }
        compose.onNodeWithContentDescription("取消主帖回应").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, target); state = initial.copy(viewerId = author.id) }
        compose.onNodeWithTag("topic-follow").assertDoesNotExist()
        compose.runOnIdle { state = initial.copy(detail = detail.copy(posts = listOf(reply), pinnedOriginal = first)) }
        compose.onNodeWithContentDescription("赞主帖").performTouchInput { click() }
        compose.runOnIdle { assertEquals(first.id, target) }
        compose.onNodeWithText(author.displayName).assertIsDisplayed()
    }

    @Test fun moreMenuTargetsOriginalPostEvenFromAFloorDeepLink() {
        var target: Post? = null
        var topicId = 0
        compose.setContent {
            CompositionLocalProvider(LocalTopicActions provides TopicActionsController({}, { id, post, _ -> topicId = id; target = post })) {
                Screen(initial.copy(detail = detail.copy(posts = listOf(reply), pinnedOriginal = first)))
            }
        }
        compose.onNodeWithContentDescription("更多").performTouchInput { click() }
        compose.runOnIdle { assertEquals(5, topicId); assertEquals(first.id, target?.id) }
    }

    @Test fun emojiLoadsAsAnInlineImageAndLargeTextKeepsAVisiblePlaceholderRectangle() {
        compose.setContent { Screen(initial, DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) }
        compose.waitUntil(5_000) { loaded.contains("https://emoji.test/distorted_face.png") }
        compose.onNodeWithContentDescription(":distorted_face:", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(":distorted_face:", substring = true).assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("正文表情", substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val emoji = layout.placeholderRects.single()!!
        assertTrue(emoji.width > 0 && emoji.height > 0)
        assertTrue(emoji.top >= 0 && emoji.bottom <= layout.size.height)
        capture("topic-emoji-dark-large.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
