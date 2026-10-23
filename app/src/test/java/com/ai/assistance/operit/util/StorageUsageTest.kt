package com.ai.assistance.operit.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StorageUsageTest {
    @Test fun `categories are disjoint and nested roots are not counted twice`() {
        val root = Files.createTempDirectory("storage-usage").toFile()
        try {
            listOf(
                "files/image_pool/a.dat", "files/media_pool/a.b64",
                "files/image_cache/a", "files/downloaded_resources/a",
                "databases/chat.db", "cache/phone-control/a.jpg"
            ).forEach { path ->
                File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
            }
            val progress = mutableListOf<StorageScanProgress>()
            val usage = measureStorage(listOf(root, File(root, "files")), classify = {
                storageCategory(it.relativeTo(root).path)
            }, onProgress = { progress.add(it) })
            assertEquals(60L, usage.totalBytes)
            assertEquals(20L, usage.bytes[StorageCategory.MEDIA])
            assertEquals(10L, usage.bytes[StorageCategory.PREVIEW_CACHE])
            assertEquals(10L, usage.bytes[StorageCategory.COMPONENTS])
            assertEquals(10L, usage.bytes[StorageCategory.DATABASES])
            assertEquals(10L, usage.bytes[StorageCategory.OTHER])
            assertEquals(0, usage.skippedEntries)
            assertEquals(6L, progress.last().files)
            assertEquals(60L, progress.last().bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `similarly named directories do not get classified as media or preview cache`() {
        assertEquals(StorageCategory.OTHER, storageCategory("files/image_pool_backup/a"))
        assertEquals(StorageCategory.OTHER, storageCategory("files/image_cache_backup/a"))
        assertEquals(StorageCategory.OTHER, storageCategory("../external/files/image_pool/a"))
        assertEquals(StorageCategory.DATABASES, storageCategory("files/objectbox/data.mdb"))
        assertEquals(StorageCategory.DATABASES, storageCategory("files/objectbox_profile/data.mdb"))
        assertEquals(StorageCategory.OTHER, storageCategory("files/objectbox_profile.txt"))
    }

    @Test fun `scan observes cancellation`() {
        val root = Files.createTempDirectory("storage-cancel").toFile()
        try {
            try {
                measureStorage(listOf(root), { StorageCategory.OTHER }) {
                    throw java.util.concurrent.CancellationException()
                }
                fail("Expected cancellation")
            } catch (_: java.util.concurrent.CancellationException) {
                // Cancellation must not be converted into a successful partial scan.
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `recent complete snapshots can be reused but stale or future ones cannot`() {
        assertTrue(isStorageSnapshotFresh(1_000, 1_001))
        assertFalse(isStorageSnapshotFresh(1_000, 601_000))
        assertFalse(isStorageSnapshotFresh(1_000, 999))
        assertFalse(isStorageSnapshotFresh(0, 1_000))
    }
}
