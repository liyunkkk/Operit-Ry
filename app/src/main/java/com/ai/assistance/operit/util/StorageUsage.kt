package com.ai.assistance.operit.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

data class StorageScanProgress(val files: Long, val bytes: Long, val category: StorageCategory?)

internal fun isStorageSnapshotFresh(measuredAt: Long, now: Long): Boolean =
    measuredAt > 0 && now >= measuredAt && now - measuredAt < 10 * 60 * 1000L

enum class StorageCategory { MEDIA, COMPONENTS, DATABASES, PREVIEW_CACHE, OTHER }

data class StorageUsage(
    val bytes: Map<StorageCategory, Long>,
    val skippedEntries: Int
) {
    val totalBytes: Long get() = bytes.values.sum()
}

/** Measures regular files only. Terminal symlinks must not escape the app or form cycles. */
internal fun measureStorage(
    roots: List<File>,
    classify: (File) -> StorageCategory,
    onProgress: (StorageScanProgress) -> Unit = {},
    checkCancelled: () -> Unit = {}
): StorageUsage {
    val totals = StorageCategory.entries.associateWith { 0L }.toMutableMap()
    var skipped = 0
    var files = 0L
    var bytes = 0L
    var lastProgress = 0L
    fun report(category: StorageCategory?, force: Boolean = false) {
        checkCancelled()
        val now = System.nanoTime()
        if (force || now - lastProgress >= 200_000_000L) {
            onProgress(StorageScanProgress(files, bytes, category))
            lastProgress = now
        }
    }
    // Canonicalize roots once, not every file in a potentially very large terminal installation.
    val paths = roots.mapNotNull {
        try {
            if (it.exists()) it.canonicalFile.toPath() else null
        } catch (_: java.io.IOException) {
            skipped++
            null
        }
    }.distinct().let { all -> all.filter { path -> all.none { it != path && path.startsWith(it) } } }
    for (root in paths) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                report(classify(dir.toFile()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (attrs.isRegularFile) {
                    val category = classify(file.toFile())
                    totals[category] = totals.getValue(category) + attrs.size()
                    files++
                    bytes += attrs.size()
                    report(category)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                skipped++
                report(null)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) skipped++
                report(null)
                return FileVisitResult.CONTINUE
            }
        })
    }
    report(null, force = true)
    return StorageUsage(totals, skipped)
}

internal fun storageCategory(relativeToDataDir: String): StorageCategory {
    val path = relativeToDataDir.replace('\\', '/')
    return when {
        path.startsWith("files/image_pool/") || path.startsWith("files/media_pool/") ->
            StorageCategory.MEDIA
        listOf("downloaded_resources", ".sherpa_ncnn_models", "local_voice_models", "usr")
            .any { path.startsWith("files/$it/") } -> StorageCategory.COMPONENTS
        path.startsWith("databases/") || path.startsWith("files/objectbox/") ||
            (path.startsWith("files/objectbox_") && path.substringAfter("files/").contains('/')) ->
            StorageCategory.DATABASES
        path.startsWith("files/image_cache/") -> StorageCategory.PREVIEW_CACHE
        else -> StorageCategory.OTHER
    }
}
