package com.kudo.android

import com.kudo.android.data.*
import org.junit.Assert.*
import org.junit.Test

class SearchQueryTest {
    @Test fun websiteSortAliasesAreNotOverriddenOrKeptAsKeywords() {
        assertEquals(SearchSort.LATEST, searchSort("关键词 l"))
        assertEquals(SearchSort.READ, searchSort("关键词 r"))
        assertEquals("关键词 order:likes", withSearchSort("关键词 l", SearchSort.LIKES))
        assertEquals("\"l\" order:views", withSearchSort("\"l\" r", SearchSort.VIEWS))
    }

    @Test fun repeatedSingleTagFiltersRemainAnIntersection() {
        val filters = parseSearchFilters("关键词 tag:安卓 tag:开发")
        assertTrue(filters.allTags)
        assertEquals("关键词 tags:安卓+开发 order:relevance", withSearchFilters("关键词 tag:安卓 tag:开发", filters, SearchSort.RELEVANCE))
    }

    @Test fun unchangedMixedTagClausesKeepTheirOriginalBooleanExpression() {
        val query = "关键词 tags:安卓,开发 tag:教程 @old"
        val filters = parseSearchFilters(query).copy(username = "new")
        val rebuilt = withSearchFilters(query, filters, SearchSort.LIKES)
        assertTrue(rebuilt.contains("tags:安卓,开发 tag:教程"))
        assertTrue(rebuilt.contains("@new"))
        assertFalse(rebuilt.contains("@old"))
    }

    @Test fun quotedCategoryArgumentIsReplacedWithoutDeletingQuotedLiteralText() {
        val query = "\"category:literal\" linux category:\"开发 调优\""
        assertEquals("\"开发 调优\"", parseSearchFilters(query).category)
        assertEquals("\"category:literal\" linux category:4 order:relevance", withSearchFilters(query, SearchFilters(category = "4"), SearchSort.RELEVANCE))
    }

    @Test fun caseInsensitiveImageOperatorDoesNotBecomeExpertResponse() {
        val filters = parseSearchFilters("关键词 WITH:IMAGES")
        assertEquals(setOf("images"), filters.scopes)
        assertFalse(filters.expertResponse)
    }

    @Test fun allPanelFieldsUseWebsiteTokensAndRoundTrip() {
        val filters = SearchFilters("4", listOf("安卓", "开发"), true, setOf("seen", "images"), "solved", "neo",
            "after", "2026-10-01", "2", "20", "10", "300", "staff", true)
        val query = withSearchFilters("关键词", filters, SearchSort.LIKES)
        assertEquals(filters, parseSearchFilters(query))
        assertEquals(SearchSort.LIKES, searchSort(query))
        assertTrue(query.contains("tags:安卓+开发"))
        assertTrue(query.contains("with:images"))
        assertTrue(query.contains("with:category_expert_response"))
        assertNull(filters.validationError())
    }

    @Test fun editingFiltersReplacesManagedTokensButKeepsQuotedTextAndUnknownSyntax() {
        val query = withSearchFilters("\"order:latest category:4\" foo category:3 @old in:seen in:replies filetype:pdf order:views", SearchFilters(category = "9", username = "new"), SearchSort.RELEVANCE)
        assertEquals("\"order:latest category:4\" foo in:replies filetype:pdf category:9 @new order:relevance", query)
        assertEquals("9", parseSearchFilters(query).category)
    }

    @Test fun changingSortDoesNotRewriteFiltersOrQuotedOperators() {
        assertEquals("\"order:likes\" tags:a+b after:2026-01-01 order:latest_topic",
            withSearchSort("\"order:likes\" order:read tags:a+b after:2026-01-01", SearchSort.LATEST_TOPIC))
    }

    @Test fun invalidRangesDatesAndInjectedUserTokensAreRejected() {
        assertNotNull(SearchFilters(minPosts = "9", maxPosts = "3").validationError())
        assertNotNull(SearchFilters(minViews = "-1").validationError())
        assertNotNull(SearchFilters(maxViews = "99999999999").validationError())
        assertNotNull(SearchFilters(date = "2026-02-30").validationError())
        assertNotNull(SearchFilters(username = "neo order:likes").validationError())
        assertNull(SearchFilters(date = "2024-02-29", maxPosts = "0").validationError())
    }
}
