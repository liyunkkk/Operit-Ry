package com.ai.assistance.operit.util

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class MediaPersistenceTest {
    private lateinit var root: File
    private lateinit var logger: org.mockito.MockedStatic<AppLogger>
    private var logging = true
    private var imageLimit = 20
    private var mediaLimit = 12

    @Before fun setup() {
        logger = Mockito.mockStatic(AppLogger::class.java)
        logging = AppLogger.enableFileLogging
        AppLogger.enableFileLogging = false
        root = Files.createTempDirectory("persistent-chat-media").toFile()
        imageLimit = ImagePoolManager.maxPoolSize
        mediaLimit = MediaPoolManager.maxPoolSize
        ImagePoolManager.maxPoolSize = 2
        MediaPoolManager.maxPoolSize = 2
    }

    @After fun cleanup() {
        ImagePoolManager.maxPoolSize = imageLimit
        MediaPoolManager.maxPoolSize = mediaLimit
        root.deleteRecursively()
        AppLogger.enableFileLogging = logging
        logger.close()
    }

    @Test fun `startup and memory eviction retain exact image payloads`() {
        val dir = File(root, "image_pool").apply { mkdirs() }
        repeat(5) { index ->
            File(dir, "image-$index.dat").writeText("payload-$index")
            File(dir, "image-$index.meta").writeText("""{"mimeType":"image/png","width":1,"height":1}""")
        }
        ImagePoolManager.initialize(root)
        assertEquals(2, ImagePoolManager.size())
        repeat(5) { assertEquals("payload-$it", ImagePoolManager.getImage("image-$it")?.base64) }
        assertEquals(10, dir.listFiles()!!.size)
        // Switch away and back to discard the process-local map, like a fresh startup.
        ImagePoolManager.initialize(File(root, "other"), preloadNow = false)
        ImagePoolManager.initialize(root, preloadNow = false)
        assertEquals("payload-0", ImagePoolManager.resolveImage("image-0")?.base64)
    }

    @Test fun `audio and video survive memory eviction and reinitialization`() {
        val dir = File(root, "media_pool").apply { mkdirs() }
        repeat(5) { index ->
            File(dir, "media-$index.b64").writeText("payload-$index")
            File(dir, "media-$index.meta").writeText("video/mp4")
        }
        MediaPoolManager.initialize(root)
        repeat(5) { assertEquals("payload-$it", MediaPoolManager.getMedia("media-$it")?.base64) }
        assertEquals(10, dir.listFiles()!!.size)
        MediaPoolManager.initialize(File(root, "other"), preloadNow = false)
        MediaPoolManager.initialize(root, preloadNow = false)
        assertEquals("payload-0", MediaPoolManager.getMedia("media-0")?.base64)
    }

    @Test fun `raw backups include durable chat media`() {
        assertFalse("image_pool" in OperitPaths.rawSnapshotExcludedFilesTopLevelDirNames())
        assertFalse("media_pool" in OperitPaths.rawSnapshotExcludedFilesTopLevelDirNames())
    }
}
