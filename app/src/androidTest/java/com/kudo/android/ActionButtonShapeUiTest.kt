package com.kudo.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kudo.android.data.DisplaySettings
import com.kudo.android.data.ThemeMode
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.LocalSheetActions
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.theme.LogoYellow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ActionButtonShapeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val background = Color(0xFF6A3B8D)

    @Test fun communityButtonsStayCapsulesAcrossThemesTextSizesAndEnabledStates() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var enabled by mutableStateOf(true)
        var actions = 0
        compose.setContent { KudoTheme(settings) {
            Column(Modifier.fillMaxSize().background(background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CommunityButton("确认", { actions++ }, Modifier.width(240.dp).testTag("normal-button"), enabled = enabled)
                CommunityButton("发布", { actions++ }, Modifier.testTag("compact-button"), enabled = enabled, compact = true)
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.3f)) for (active in listOf(true, false)) {
            compose.runOnIdle { settings = settings.copy(theme = theme, fontScale = scale); enabled = active }
            val fill = LogoYellow.copy(alpha = if (active) 1f else .18f).compositeOver(background)
            for ((tag, height) in listOf("normal-button" to 48.dp, "compact-button" to 36.dp)) {
                val button = compose.onNodeWithTag(tag)
                button.assertHeightIsAtLeast(height)
                if (active) button.assertIsEnabled() else button.assertIsNotEnabled()
                assertCapsule(button, fill)
                val before = actions
                if (active) button.performSemanticsAction(SemanticsActions.OnClick) { it() }
                else button.performTouchInput { click() }
                compose.runOnIdle { assertEquals(before + if (active) 1 else 0, actions) }
            }
        }
    }

    @Test fun sheetAndDialogPrimaryActionsKeepCapsulesAndExistingHeights() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var sheet by mutableStateOf(false)
        var actions = 0
        compose.setContent { KudoTheme(settings) {
            Column(Modifier.fillMaxSize().background(background).padding(24.dp)) {
                CompositionLocalProvider(LocalSheetActions provides sheet) {
                    LiquidModalAction("确认", { actions++ }, Modifier.width(240.dp).testTag("modal-action"), primary = true)
                }
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.3f)) for (bottom in listOf(false, true)) {
            compose.runOnIdle { settings = settings.copy(theme = theme, fontScale = scale); sheet = bottom }
            val button = compose.onNodeWithTag("modal-action")
            button.assertHeightIsAtLeast(if (bottom) 56.dp else 48.dp)
            assertCapsule(button, LogoYellow)
            val before = actions
            button.performClick()
            compose.runOnIdle { assertEquals(before + 1, actions) }
        }
    }

    @Test fun iconButtonIndicationIsCircularWithoutChangingItsTouchSizeOrEnabledState() {
        var enabled by mutableStateOf(true)
        var actions = 0
        val indication = Color(0xFF38BD9F)
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.fillMaxSize().background(background).padding(24.dp)) {
                CompositionLocalProvider(LocalIndication provides SolidIndication(indication)) {
                    CommunityIconButton(Icons.Rounded.Close, "关闭", { actions++ }, Modifier.testTag("icon-button"), enabled = enabled)
                }
            }
        } }
        val button = compose.onNodeWithTag("icon-button")
        button.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        button.performTouchInput { down(center) }
        assertCapsule(button, indication)
        button.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, actions); enabled = false }
        button.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, actions) }
    }

    private fun assertCapsule(button: SemanticsNodeInteraction, fill: Color) {
        val pixels = button.captureToImage().toPixelMap()
        val inset = (pixels.height / 50).coerceAtLeast(2)
        for (horizontal in listOf(inset, pixels.width - inset - 1)) {
            for (vertical in listOf(pixels.height / 4, pixels.height * 3 / 4))
                assertColor("capsule corner at $horizontal,$vertical", background, pixels[horizontal, vertical])
            assertColor("capsule center edge at $horizontal", fill, pixels[horizontal, pixels.height / 2])
        }
        assertColor("capsule top edge", fill, pixels[pixels.width / 2, inset])
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        assertEquals("$message red", expected.red, actual.red, .01f)
        assertEquals("$message green", expected.green, actual.green, .01f)
        assertEquals("$message blue", expected.blue, actual.blue, .01f)
    }

    private data class SolidIndication(val color: Color) : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() { drawRect(color); drawContent() }
        }
    }
}
