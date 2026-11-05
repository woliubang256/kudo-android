package com.kudo.android.ui.apps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidButton
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.theme.LogoYellow

@Composable
internal fun AppSquareToolbar(query: String, expanded: Boolean, grid: Boolean, back: () -> Unit,
    expand: () -> Unit, search: (String) -> Unit, toggleLayout: () -> Unit, filter: () -> Unit) {
    val progress by animateFloatAsState(if (expanded) 1f else 0f, label = "app-search-expansion")
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded) { if (expanded) { focus.requestFocus(); keyboard?.show() } }
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp)
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back, Modifier.testTag("apps-back"))
        BoxWithConstraints(Modifier.weight(1f).height(48.dp)) {
            val available = maxWidth
            if (progress < 1f) Box(Modifier.fillMaxSize().then(if (progress > 0f) Modifier.clipToBounds() else Modifier)) {
                Row(Modifier.fillMaxSize().graphicsLayer {
                    translationX = -available.toPx() * progress
                    alpha = 1f - progress
                }.then(if (expanded) Modifier.clearAndSetSemantics {} else Modifier),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用广场", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TopBarIconButton(Icons.Rounded.FilterList, "筛选应用", filter, Modifier.testTag("apps-filter"), enabled = !expanded)
                    TopBarIconButton(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                        if (grid) "切换列表布局" else "切换宫格布局", toggleLayout, Modifier.testTag("apps-layout"), enabled = !expanded)
                    TopBarIconButton(Icons.Rounded.Search, "搜索应用", expand, Modifier.testTag("apps-search"), enabled = !expanded)
                }
            }
            if (expanded || progress > 0f) {
                val backdrop = checkNotNull(LocalToolbarBackdrop.current)
                Box(Modifier.align(Alignment.CenterEnd).width(48.dp + (maxWidth - 48.dp) * progress)
                    .height(48.dp).testTag("apps-search-surface"), contentAlignment = Alignment.Center) {
                    LiquidButton(null, backdrop, LocalDisplaySettings.current.glass,
                        Modifier.fillMaxWidth().height(40.dp), isInteractive = false, minimumHeight = 40.dp,
                        contentPadding = PaddingValues(0.dp), surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {}
                    Row(Modifier.fillMaxSize().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, Modifier.padding(start = 12.dp, end = 8.dp).size(22.dp))
                        BasicTextField(query, search, Modifier.weight(1f).focusRequester(focus)
                            .testTag("apps-search-field").semantics { contentDescription = "搜索应用" },
                            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(LogoYellow), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            decorationBox = { field -> Box {
                                if (query.isEmpty()) Text("搜索", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge)
                                field()
                            } })
                        if (query.isNotEmpty()) IconButton({ search("") }, Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Close, "清空搜索", Modifier.size(20.dp))
                        } else Spacer(Modifier.width(12.dp))
                    }
                }
            }
        }
    }
}
