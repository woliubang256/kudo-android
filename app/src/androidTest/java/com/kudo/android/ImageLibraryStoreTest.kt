package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ImageLibraryStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "image_library_test_${UUID.randomUUID()}"
    private val fixture = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(this@ImageLibraryStoreTest.name, mode)
    }
    private val image = SavedImage(id = "image", originalUrl = "https://cdn.example.com/a.gif", source = ImageSource(123, 4))

    @After fun cleanup() { base.deleteSharedPreferences(name) }

    @Test fun guestAndTwoAccountsRemainIsolatedAfterReopening() = runBlocking {
        val store = ImageLibraryStore(fixture)
        store.update(null) { it.save(image) }
        store.update(101) { it.addFolder("表情", "emoji").save(image.copy(id = "alice", folderId = "emoji")) }
        store.update(202) { it.save(image.copy(id = "bob", originalUrl = "https://cdn.example.com/b.png")) }
        val reopened = ImageLibraryStore(fixture)
        assertEquals("image", reopened.load(null).images.single().id)
        assertEquals("alice", reopened.load(101).images.single().id)
        assertEquals("bob", reopened.load(202).images.single().id)
        assertEquals(ImageLibrary(), reopened.load(303))
        assertEquals(setOf("guest", "user_101", "user_202"), base.getSharedPreferences(name, 0).all.keys)
    }

    @Test fun concurrentUpdatesNeverLoseImages() = runBlocking {
        val store = ImageLibraryStore(fixture)
        coroutineScope { (1..40).map { number -> async {
            store.update(101) { it.save(image.copy(id = "$number", originalUrl = "https://cdn.example.com/$number.png")) }
        } }.awaitAll() }
        assertEquals(40, ImageLibraryStore(fixture).load(101).images.size)
        assertEquals(40L, store.revision.value)
    }

    @Test fun folderOrderIsLocalToGuestAndEachAccountAndSurvivesReopening() = runBlocking {
        val store = ImageLibraryStore(fixture)
        for (account in listOf(null, 101, 202)) store.update(account) { it.addFolder("表情", "emoji").addFolder("风景", "landscape").save(image) }
        store.update(null) { it.reorderFolders(listOf("emoji", DEFAULT_IMAGE_FOLDER, "landscape")) }
        store.update(101) { it.reorderFolders(listOf("landscape", "emoji", DEFAULT_IMAGE_FOLDER)) }
        val reopened = ImageLibraryStore(fixture)
        assertEquals(listOf("emoji", DEFAULT_IMAGE_FOLDER, "landscape"), reopened.load(null).folders.map(ImageFolder::id))
        assertEquals(listOf("landscape", "emoji", DEFAULT_IMAGE_FOLDER), reopened.load(101).folders.map(ImageFolder::id))
        assertEquals(listOf(DEFAULT_IMAGE_FOLDER, "emoji", "landscape"), reopened.load(202).folders.map(ImageFolder::id))
        for (account in listOf(null, 101, 202)) assertEquals(listOf(image), reopened.load(account).images)
    }

    @Test fun duplicatesAndCanceledTransformDoNotWrite() = runBlocking {
        val store = ImageLibraryStore(fixture)
        store.update(101) { it.save(image) }
        store.update(101) { it.save(image.copy(id = "duplicate")) }
        try { store.update(101) { throw IllegalArgumentException("fixture") }; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(image), store.load(101).images)
        assertEquals(1L, store.revision.value)
    }

    @Test fun corruptDataIsNotOverwrittenByReadOrMutation() = runBlocking {
        val prefs = base.getSharedPreferences(name, 0)
        for (raw in listOf("broken", "{\"version\":2}")) {
            assertTrue(prefs.edit().putString("user_101", raw).commit())
            val store = ImageLibraryStore(fixture)
            try { store.load(101); fail() } catch (_: Exception) { }
            try { store.update(101) { it.save(image) }; fail() } catch (_: Exception) { }
            assertEquals(raw, prefs.getString("user_101", null))
            assertEquals(0L, store.revision.value)
            assertEquals(ImageLibrary(), store.load(null))
        }
    }

    @Test fun imageMovesPersistForOnlyTheirOwnerAndUseTheLatestMetadata() = runBlocking {
        val store = ImageLibraryStore(fixture)
        for (account in listOf(null, 101, 202)) store.update(account) { it.addFolder("表情", "emoji").save(image) }
        val latest = image.copy(alt = "更新描述", source = ImageSource(456, 9), savedAt = 456)
        store.update(101) { library -> library.copy(images = listOf(latest)) }
        store.update(101) { it.moveImage(image.id, "emoji") }
        val reopened = ImageLibraryStore(fixture)
        assertEquals(listOf(latest.copy(folderId = "emoji")), reopened.load(101).images)
        assertEquals(listOf(image), reopened.load(null).images)
        assertEquals(listOf(image), reopened.load(202).images)
    }

    @Test fun creatingAFolderAndMovingIsAtomicAndStaleRequestsCannotResurrectImages() = runBlocking {
        val store = ImageLibraryStore(fixture)
        store.update(101) { it.save(image) }
        store.update(101) { it.addFolder("常用", "new").moveImage(image.id, "new") }
        assertEquals(listOf(image.copy(folderId = "new")), ImageLibraryStore(fixture).load(101).images)
        store.update(101) { it.removeImage(image.id) }
        val before = store.load(101)
        val revision = store.revision.value
        val raw = base.getSharedPreferences(name, 0).getString("user_101", null)
        try {
            store.update(101) { it.addFolder("未保存", "not-saved").moveImage(image.id, "not-saved") }
            fail("A deleted image must not be restored")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, ImageLibraryStore(fixture).load(101))
        assertEquals(raw, base.getSharedPreferences(name, 0).getString("user_101", null))
        assertEquals(revision, store.revision.value)
    }

    @Test fun removingFoldersAndImagesOnlyChangesTheirOwner() = runBlocking {
        val store = ImageLibraryStore(fixture)
        store.update(101) { it.addFolder("临时", "temp").save(image.copy(folderId = "temp")) }
        store.update(202) { it.save(image) }
        store.update(101) { it.deleteFolder("temp") }
        assertEquals(DEFAULT_IMAGE_FOLDER, store.load(101).images.single().folderId)
        store.update(101) { it.removeImage(image.id) }
        assertTrue(store.load(101).images.isEmpty())
        assertEquals(listOf(image), store.load(202).images)
    }
}
