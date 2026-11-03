package com.kudo.android

import com.kudo.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageLibraryTest {
    private val image = SavedImage(id = "one", originalUrl = "https://cdn.example.com/原图.png", source = ImageSource(42, 7, "来源帖子"), savedAt = 123)

    @Test fun foldersAndImagesRoundTripWithUnicodeAndSource() {
        val library = ImageLibrary().addFolder("表情", "emoji").save(image.copy(folderId = "emoji", alt = "猫 [开心]", ratio = 1.5f))
        assertEquals(library, decodeImageLibrary(encodeImageLibrary(library)))
        assertEquals("https://linux.do/t/topic/42/7", library.images.single().source?.url)
        assertEquals(ImageLibrary(), decodeImageLibrary(null))
    }

    @Test fun customFolderOrderIncludingDefaultSurvivesReopening() {
        val original = ImageLibrary().addFolder("表情", "emoji").addFolder("风景", "landscape").save(image)
        val reordered = original.copy(folders = listOf(original.folders[2], original.folders[0], original.folders[1]))
        val restored = decodeImageLibrary(encodeImageLibrary(reordered))
        assertEquals(listOf("landscape", DEFAULT_IMAGE_FOLDER, "emoji"), restored.folders.map { it.id })
        assertEquals(original.images, restored.images)
    }

    @Test fun sortingMergesOnlyExistingIdsAndKeepsNewFoldersAndImages() {
        val library = ImageLibrary().addFolder("表情", "emoji").addFolder("新目录", "new").save(image)
        val sorted = library.reorderFolders(listOf("emoji", "removed", "emoji", DEFAULT_IMAGE_FOLDER))
        assertEquals(listOf("emoji", DEFAULT_IMAGE_FOLDER, "new"), sorted.folders.map { it.id })
        assertEquals(library.images, sorted.images)
        assertEquals(listOf("改名", "默认收藏", "新目录"), sorted.renameFolder("emoji", "改名").folders.map { it.name })
        assertSame(sorted, sorted.reorderFolders(emptyList()))
        assertEquals(listOf(DEFAULT_IMAGE_FOLDER, "new"), sorted.deleteFolder("emoji").folders.map { it.id })
    }

    @Test fun legacyDataMissingDefaultRestoresItWithoutLosingCustomOrder() {
        val original = ImageLibrary().addFolder("表情", "emoji").addFolder("风景", "landscape")
        val raw = JSONObject(encodeImageLibrary(original)).put("folders", org.json.JSONArray().apply {
            put(JSONObject().put("id", "landscape").put("name", "风景"))
            put(JSONObject().put("id", "emoji").put("name", "表情"))
        })
        assertEquals(listOf(DEFAULT_IMAGE_FOLDER, "landscape", "emoji"), decodeImageLibrary(raw.toString()).folders.map { it.id })
    }

    @Test fun duplicateIsIdempotentWithinFolderButCanBeSavedToAnother() {
        val library = ImageLibrary().addFolder("常用", "other").save(image)
        assertEquals(library, library.save(image.copy(id = "two")))
        assertEquals(2, library.save(image.copy(id = "three", folderId = "other")).images.size)
    }

    @Test fun folderValidationRejectsEmptyDuplicateAndExcessiveNames() {
        val library = ImageLibrary().addFolder("  Meme ", "meme")
        assertEquals("Meme", library.folders.last().name)
        listOf("", "   ", "meme", "a".repeat(41)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { library.addFolder(name) }
        }
        assertThrows(IllegalArgumentException::class.java) { library.save(image.copy(folderId = "missing")) }
    }

    @Test fun renamingPreservesOrderAndDeletingMovesImagesWithoutDuplicates() {
        val library = ImageLibrary().addFolder("旧名", "other").save(image).save(image.copy(id = "two", folderId = "other"))
        assertEquals(listOf("默认收藏", "新名"), library.renameFolder("other", "新名").folders.map { it.name })
        val deleted = library.deleteFolder("other")
        assertEquals(1, deleted.images.size)
        assertEquals(DEFAULT_IMAGE_FOLDER, deleted.images.single().folderId)
        assertThrows(IllegalArgumentException::class.java) { library.deleteFolder(DEFAULT_IMAGE_FOLDER) }
        assertEquals(emptyList<SavedImage>(), library.removeImage("one").removeImage("two").images)
    }

    @Test fun invalidUrlsCannotEnterLibrary() {
        listOf("file:///tmp/a", "javascript:alert(1)", "data:image/png,a", "https://user:pass@cdn.example.com/a", "https://example.com/a\nb").forEach {
            assertFalse(validLibraryImageUrl(it))
            assertThrows(IllegalArgumentException::class.java) { ImageLibrary().save(image.copy(originalUrl = it)) }
        }
        val saved = ImageLibrary().save(image.copy(thumbnailUrl = "file:///a", source = ImageSource(-1)))
        assertEquals(image.originalUrl, saved.images.single().thumbnailUrl)
        assertNull(saved.images.single().source)
    }

    @Test fun corruptAndFutureVersionsAreNotSilentlyReplaced() {
        assertThrows(Exception::class.java) { decodeImageLibrary("broken") }
        assertThrows(IllegalArgumentException::class.java) { decodeImageLibrary(JSONObject(encodeImageLibrary(ImageLibrary())).put("version", 2).toString()) }
    }

    @Test fun individualInvalidRowsAreSkippedAndMissingFoldersFallBack() {
        val json = JSONObject(encodeImageLibrary(ImageLibrary().save(image)))
        val images = json.getJSONArray("images")
        images.put(JSONObject(images.getJSONObject(0).toString()).put("id", "duplicate"))
        images.put(JSONObject().put("id", "bad").put("original", "file:///private"))
        images.getJSONObject(0).put("folder", "missing").put("ratio", -1).put("source", JSONObject().put("topic", 0))
        val decoded = decodeImageLibrary(json.toString())
        assertEquals(1, decoded.images.size)
        assertEquals(DEFAULT_IMAGE_FOLDER, decoded.images.single().folderId)
        assertNull(decoded.images.single().ratio)
        assertNull(decoded.images.single().source)
    }
}
