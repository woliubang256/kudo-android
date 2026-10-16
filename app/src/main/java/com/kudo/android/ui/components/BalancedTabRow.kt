package com.kudo.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Equal, centered slots when every label fits; preserve horizontal navigation otherwise. */
@Composable
internal fun BalancedTabRow(
    labels: List<String>, textStyle: TextStyle, modifier: Modifier = Modifier,
    tab: @Composable (Int, Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widest = labels.maxOfOrNull {
            measurer.measure(AnnotatedString(it), textStyle, maxLines = 1).size.width
        } ?: 0
        val minimum = with(density) { (widest.toDp() + 24.dp).coerceAtLeast(48.dp) }
        val equal = constraints.hasBoundedWidth && minimum * labels.size <= maxWidth
        Row(Modifier.fillMaxWidth().then(if (equal) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            labels.indices.forEach { index -> tab(index, if (equal) Modifier.weight(1f) else Modifier) }
        }
    }
}
