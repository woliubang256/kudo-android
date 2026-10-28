package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HomeTabsStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencePrefix = "home_tabs_store_test_${UUID.randomUUID()}_"
    private val requestedPreferences = mutableSetOf<String>()
    private val fixture = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            requestedPreferences += name
            return super.getSharedPreferences("$preferencePrefix$name", mode)
        }
    }
    private val prefs get() = fixture.getSharedPreferences("kudo_home_tabs", Context.MODE_PRIVATE)
    private val category = HomeTab.category(Category(7, "开发调优", "development", "", "", 0))
    private val tag = HomeTab.tag(TopicTag("公告", "444-tag", id = 444))

    @After fun cleanFixture() {
        requestedPreferences.forEach { base.deleteSharedPreferences("$preferencePrefix$it") }
    }

    @Test fun missingPreferencesUseIdentityDefaultsWithoutWritingAnything() {
        val store = HomeTabsStore(fixture)
        assertEquals(defaultHomeTabs(false), store.load(null))
        assertEquals(defaultHomeTabs(true), store.load(101))
        assertEquals(defaultHomeTabs(true), store.load(202))
        assertEquals(0, store.revision.value)
        assertTrue(prefs.all.isEmpty())
        assertEquals(setOf("kudo_home_tabs"), requestedPreferences)
    }

    @Test fun guestAndAccountsKeepIndependentOrdersAcrossSwitchesAndStoreRecreation() {
        val store = HomeTabsStore(fixture)
        val guest = listOf(HomeTab.directory(), HomeTab.filter(FeedFilter.TOP))
        val alice = listOf(HomeTab.filter(FeedFilter.BOOKMARKS), category)
        val bob = listOf(tag, HomeTab.directory(), HomeTab.filter(FeedFilter.UNREAD))
        store.save(null, guest)
        store.save(101, alice)
        store.save(202, bob)
        assertEquals(3, store.revision.value)
        assertEquals(setOf("guest", "user_101", "user_202"), prefs.all.keys)
        for (reopened in listOf(store, HomeTabsStore(fixture), HomeTabsStore(fixture))) {
            assertEquals(guest, reopened.load(null))
            assertEquals(alice, reopened.load(101))
            assertEquals(bob, reopened.load(202))
            assertEquals(guest, reopened.load(null))
            assertEquals(alice, reopened.load(101))
            assertEquals(defaultHomeTabs(true), reopened.load(303))
        }
        assertEquals(3, store.revision.value)
        assertEquals(0, HomeTabsStore(fixture).revision.value)
        assertEquals(setOf("kudo_home_tabs"), requestedPreferences)
    }

    @Test fun restoringDefaultsRemovesOnlyTheCurrentIdentityAndAlwaysIncrementsRevision() {
        val store = HomeTabsStore(fixture)
        store.save(null, listOf(category))
        store.save(101, listOf(tag))
        store.save(202, listOf(HomeTab.directory()))
        val bob = prefs.getString("user_202", null)
        store.save(null, defaultHomeTabs(false))
        assertFalse(prefs.contains("guest"))
        assertEquals(defaultHomeTabs(false), store.load(null))
        assertEquals(listOf(tag), store.load(101))
        store.save(101, defaultHomeTabs(true))
        assertFalse(prefs.contains("user_101"))
        assertEquals(bob, prefs.getString("user_202", null))
        assertEquals(listOf(HomeTab.directory()), HomeTabsStore(fixture).load(202))
        assertEquals(5, store.revision.value)
        store.save(202, defaultHomeTabs(true))
        assertTrue(prefs.all.isEmpty())
        assertEquals(6, store.revision.value)
        store.save(202, defaultHomeTabs(true))
        assertEquals(7, store.revision.value)
    }

    @Test fun savingNormalizesDuplicatesIdsAndBuiltInMetadataBeforeWriting() {
        val store = HomeTabsStore(fixture)
        val latest = HomeTab.filter(FeedFilter.LATEST)
        val tabs = listOf(tag, tag.copy(value = "renamed", label = "重命名标签"),
            category.copy(value = "007"), category, latest.copy(label = "不可信"), latest,
            HomeTab.directory().copy(label = "不可信目录"), HomeTab.directory())
        val expected = listOf(tag, category, latest, HomeTab.directory())
        store.save(101, tabs)
        assertEquals(expected, store.load(101))
        assertEquals(expected, HomeTabsStore(fixture).load(101))
        assertEquals(expected, decodeHomeTabs(prefs.getString("user_101", null), true))
        assertEquals(1, store.revision.value)
        store.save(101, tabs)
        assertEquals(2, store.revision.value)
    }

    @Test fun savingTagsWithAndWithoutIdsPreservesTheFirstIdentityAfterRecreation() {
        val store = HomeTabsStore(fixture)
        val unidentified = tag.copy(tagId = null)
        store.save(101, listOf(unidentified, tag))
        store.save(202, listOf(tag, unidentified))
        val reopened = HomeTabsStore(fixture)
        assertEquals(listOf(unidentified), reopened.load(101))
        assertEquals(listOf(tag), reopened.load(202))
        assertEquals(unidentified.key, reopened.load(101).single().key)
        assertEquals(tag.key, reopened.load(202).single().key)
        assertEquals(listOf(unidentified), decodeHomeTabs(prefs.getString("user_101", null), true))
        assertEquals(listOf(tag), decodeHomeTabs(prefs.getString("user_202", null), true))
    }

    @Test fun savingGuestFiltersNeverPersistsPersonalFiltersOrModifiesAMember() {
        val store = HomeTabsStore(fixture)
        val personal = FeedFilter.entries.filter(FeedFilter::personal).map { HomeTab.filter(it) }
        store.save(101, personal)
        val member = prefs.getString("user_101", null)
        store.save(null, personal + category + category)
        assertEquals(listOf(category), store.load(null))
        assertEquals(personal, HomeTabsStore(fixture).load(101))
        assertEquals(listOf(category), decodeHomeTabs(prefs.getString("guest", null), true))
        store.save(null, personal)
        assertFalse(prefs.contains("guest"))
        assertEquals(defaultHomeTabs(false), store.load(null))
        assertEquals(member, prefs.getString("user_101", null))
    }

    @Test fun emptySavesFailWithoutChangingStoredTabsOrRevision() {
        val store = HomeTabsStore(fixture)
        store.save(101, listOf(tag))
        val before = prefs.all.toMap()
        assertThrows(IllegalArgumentException::class.java) { store.save(101, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { store.save(null, emptyList()) }
        assertEquals(before, prefs.all)
        assertEquals(listOf(tag), store.load(101))
        assertEquals(1, store.revision.value)
    }

    @Test fun corruptedAndUnknownStoredValuesFallBackWithoutRepairingOtherAccounts() {
        assertTrue(prefs.edit().putString("guest", "broken")
            .putString("user_101", """{"version":2,"tabs":[]}""")
            .putString("user_202", encodeHomeTabs(listOf(tag, category))).commit())
        val store = HomeTabsStore(fixture)
        val before = prefs.all.toMap()
        assertEquals(defaultHomeTabs(false), store.load(null))
        assertEquals(defaultHomeTabs(true), store.load(101))
        assertEquals(listOf(tag, category), store.load(202))
        assertEquals(before, prefs.all)
        assertTrue(prefs.edit().putInt("guest", 42).commit())
        assertEquals(defaultHomeTabs(false), HomeTabsStore(fixture).load(null))
        assertEquals(listOf(tag, category), HomeTabsStore(fixture).load(202))
        assertEquals(0, store.revision.value)
    }

    @Test fun whollyInvalidTabsRecoverToDefaultsWithoutPersistingUnsafeValues() {
        val store = HomeTabsStore(fixture)
        store.save(null, listOf(category))
        store.save(null, listOf(HomeTab(HomeTabType.TAG, "../session", "无效标签", 444),
            HomeTab(HomeTabType.CATEGORY, "-7", "无效分类")))
        assertEquals(defaultHomeTabs(false), store.load(null))
        assertFalse(prefs.contains("guest"))
        assertEquals(2, store.revision.value)
    }

    @Test fun invalidAccountIdsCannotReadOrWriteAnyIdentity() {
        val store = HomeTabsStore(fixture)
        store.save(null, listOf(category))
        val before = prefs.all.toMap()
        for (accountId in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { store.load(accountId) }
            assertThrows(IllegalArgumentException::class.java) { store.save(accountId, listOf(tag)) }
        }
        assertEquals(before, prefs.all)
        assertEquals(1, store.revision.value)
    }
}
