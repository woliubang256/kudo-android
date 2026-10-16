package com.kudo.android.ui.feed

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.Backdrop

@Composable
internal fun SearchFiltersPanel(
    state: SearchState, draft: SearchFilters, change: (SearchFilters) -> Unit,
    suggestTags: (String) -> Unit, retryMetadata: () -> Unit,
    apply: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalToolbarBackdrop.current,
) {
    var tagQuery by remember { mutableStateOf("") }
    var categoryQuery by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)).testTag("search-filters-panel")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("高级筛选", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TopBarIconButton(Icons.Rounded.Close, "收起高级筛选", dismiss, backdrop = backdrop)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.metadataLoading) Text("正在读取筛选选项…", style = MaterialTheme.typography.bodySmall)
            if (state.metadataError != null) Text("筛选选项读取失败，点击重试", Modifier.clickable(onClick = retryMetadata).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.error)
            FilterLabel("分类")
            SearchFilterInput(categoryQuery, { categoryQuery = it }, "查找分类", "search-category-query")
            FilterChoice("选择分类", draft.category,
                listOf("" to "任意分类") + state.categories.filter { categoryQuery.isBlank() || it.name.contains(categoryQuery, true) || it.slug.contains(categoryQuery, true) }.map { it.id.toString() to it.name },
                { change(draft.copy(category = it)) }, "search-category")
            if (state.capabilities.tagging) {
                FilterLabel("拥有该标签")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.tags.forEach { tag -> SearchChip("$tag ×", true) { change(draft.copy(tags = draft.tags - tag)) } }
                }
                SearchFilterInput(tagQuery, { tagQuery = it; suggestTags(it) }, "搜索服务器标签", "search-tag-query")
                if (state.tagsLoading) Text("正在查找标签…", style = MaterialTheme.typography.bodySmall)
                if (state.tagsError != null) Text("标签读取失败，点击重试", Modifier.clickable { suggestTags(tagQuery) }.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.tagSuggestions.filterNot { it.name in draft.tags }.forEach { tag ->
                        SearchChip(tag.name, false) { change(draft.copy(tags = draft.tags + tag.name)) }
                    }
                }
                FilterSwitch("匹配所有选中的标签", draft.allTags, "search-match-all-tags") { change(draft.copy(allTags = it)) }
            }
            FilterLabel("仅返回话题 / 帖子…")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchScopes.filter { state.accountId != null || !it.personal }.forEach { scope ->
                    SearchChip(scope.label, scope.value in draft.scopes) {
                        change(draft.copy(scopes = if (scope.value in draft.scopes) draft.scopes - scope.value else draft.scopes + scope.value))
                    }
                }
            }
            FilterLabel("话题")
            FilterChoice("话题状态", draft.status, listOf("" to "任意") + SearchStatuses.filter { state.capabilities.solved || it.first !in setOf("solved", "unsolved") },
                { change(draft.copy(status = it)) }, "search-status")
            FilterLabel("发帖人")
            SearchFilterInput(draft.username, { change(draft.copy(username = it.trim().removePrefix("@"))) }, "用户名", "search-author")
            FilterLabel("发布时间")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp)) { FilterChoice("发布时间", draft.timeWhen, listOf("before" to "早于", "after" to "晚于"), { change(draft.copy(timeWhen = it)) }, "search-date-direction") }
                SearchFilterInput(draft.date, { change(draft.copy(date = it.trim())) }, "YYYY-MM-DD", "search-date", Modifier.weight(1f))
            }
            FilterLabel("帖子数量")
            FilterRange(draft.minPosts, draft.maxPosts, { change(draft.copy(minPosts = it)) }, { change(draft.copy(maxPosts = it)) }, "posts")
            FilterLabel("浏览量")
            FilterRange(draft.minViews, draft.maxViews, { change(draft.copy(minViews = it)) }, { change(draft.copy(maxViews = it)) }, "views")
            if (state.capabilities.assignTo) {
                FilterLabel("已指定给")
                SearchFilterInput(draft.assigned, { change(draft.copy(assigned = it.trim().removePrefix("@"))) }, "用户名或群组名", "search-assigned")
            }
            if (state.capabilities.expertResponse) {
                FilterLabel("类别专家")
                FilterSwitch("有专家回应", draft.expertResponse, "search-expert-response") { change(draft.copy(expertResponse = it)) }
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(4.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("重置", Modifier.clickable(role = Role.Button) { change(SearchFilters()); validation = null }.padding(12.dp).testTag("search-filter-reset"), style = MaterialTheme.typography.bodyMedium)
            CommunityButton("应用筛选", {
                validation = draft.validationError()
                if (validation == null) apply()
            }, Modifier.weight(1f).testTag("search-filter-apply"), compact = true)
        }
    }
}

@Composable
private fun FilterLabel(label: String) { Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
internal fun SearchFilterInput(value: String, change: (String) -> Unit, hint: String, tag: String, modifier: Modifier = Modifier, numeric: Boolean = false) {
    CommunityTextField(value, change, modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f), RoundedCornerShape(6.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag), placeholder = hint, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
}

@Composable
private fun FilterRange(minimum: String, maximum: String, changeMin: (String) -> Unit, changeMax: (String) -> Unit, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchFilterInput(minimum, changeMin, "最小", "search-min-$tag", Modifier.weight(1f), true)
        SearchFilterInput(maximum, changeMax, "最大", "search-max-$tag", Modifier.weight(1f), true)
    }
}

@Composable
private fun FilterCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CommunityCheckbox(checked, change)
        Text(label, Modifier.weight(1f).clickable { change(!checked) }.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FilterSwitch(label: String, checked: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).clickable { change(!checked) }.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        CommunitySwitch(checked, change, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun FilterChoice(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = !expanded }.testTag(tag)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(options.firstOrNull { it.first == value }?.second ?: value.ifBlank { label }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Rounded.ExpandMore, label, Modifier.size(18.dp))
        }
        if (expanded) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            options.forEach { (key, name) ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { change(key); expanded = false }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CommunityRadio(key == value)
                    Text(name, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun SearchChip(label: String, selected: Boolean, action: () -> Unit) {
    Text(label, Modifier.clipChip(selected).clickable(role = Role.Button, onClick = action).padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall, color = if (selected) com.kudo.android.ui.theme.Ink else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Modifier.clipChip(selected: Boolean): Modifier = this.background(
    if (selected) LogoYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f), RoundedCornerShape(6.dp))
