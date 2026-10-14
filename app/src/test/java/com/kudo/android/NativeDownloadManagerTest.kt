package com.kudo.android

import com.kudo.android.media.filenameFromContentDisposition
import com.kudo.android.media.filenameFromUrl
import com.kudo.android.media.isDownloadUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDownloadManagerTest {
    @Test fun recognizesFileLinksAndDownloadParameters() {
        assertTrue(isDownloadUrl("https://linux.do/uploads/key-labyrinth-20261013.zip"))
        assertTrue(isDownloadUrl("https://files.example.test/get?id=7&download=1"))
        assertFalse(isDownloadUrl("https://linux.do/t/topic/3020259"))
        assertFalse(isDownloadUrl("javascript:download('x.zip')"))
        assertFalse(isDownloadUrl("https://user:pass@example.test/file.zip"))
    }

    @Test fun sanitizesUrlAndHeaderFilenames() {
        assertEquals("key-labyrinth-20261013.zip", filenameFromUrl("https://linux.do/files/key-labyrinth-20261013.zip"))
        assertEquals("report final.zip", filenameFromContentDisposition("attachment; filename*=UTF-8''report%20final.zip"))
        assertEquals("name_.zip", filenameFromContentDisposition("attachment; filename=\"bad:/name?.zip\""))
        assertNull(filenameFromContentDisposition("attachment"))
    }
}
