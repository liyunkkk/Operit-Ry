package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.core.workflow.advanceWorkflowAuthRestoreGeneration
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.db.ObjectBoxManager
import com.ai.assistance.operit.data.stats.TokenBaselineImportRunner
import com.ai.assistance.operit.data.stats.TokenStatSpool
import com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RawSnapshotBackupManager {

    private const val TAG = "RawSnapshotBackup"
    private const val FORMAT_VERSION = 1

    private const val ZIP_PREFIX = "operit_raw_snapshot_"

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_PAYLOAD_PREFIX = "payload/"

    private const val ENTRY_FILES = "payload/files/"
    private const val ENTRY_EXTERNAL_FILES = "payload/external_files/"
    private const val ENTRY_SHARED_PREFS = "payload/shared_prefs/"
    private const val ENTRY_DATASTORE = "payload/datastore/"
    private const val ENTRY_DATABASES = "payload/databases/"

    private val terminalTopLevelDirNames = setOf("usr", "tmp", "bin")

    private val mutex = Mutex()
    private val officialMigrationRunning = AtomicBoolean(false)
    @Volatile
    private var officialMigrationFailureMessage: String? = null

    @Volatile
    private var processRestartRequired = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 测试 seam：恢复提交阶段推进 workflow auth generation 的可替换实现。 */
    @Volatile
    internal var restoreGenerationAdvancerForTest: ((Context) -> Long)? = null

    @Serializable
    data class Manifest(
        val formatVersion: Int,
        val packageName: String,
        val createdAt: Long,
        val includes: List<String>,
        val includeTerminalData: Boolean = true
    )

    data class SnapshotOptions(
        val includeTerminalData: Boolean = false
    )

    enum class ExportProgress {
        PREPARING,
        SCANNING_FILES,
        ZIPPING_FILES,
        ZIPPING_EXTERNAL_FILES,
        ZIPPING_SHARED_PREFS,
        ZIPPING_DATASTORE,
        ZIPPING_DATABASES,
        FINALIZING
    }

    data class ExportProgressInfo(
        val stage: ExportProgress,
        val percent: Int? = null,
        val scannedFiles: Int? = null
    )

    enum class RestoreProgress {
        PREPARING,
        READING_ZIP,
        EXTRACTING,
        REPLACING_FILES,
        REPLACING_EXTERNAL_FILES,
        REPLACING_SHARED_PREFS,
        REPLACING_DATASTORE,
        REPLACING_DATABASES,
        FINALIZING
    }

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun exportToBackupDir(
        context: Context,
        options: SnapshotOptions = SnapshotOptions(),
        onProgress: ((ExportProgressInfo) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!processRestartRequired) { "Application restart required before another snapshot operation" }
            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
                exportToBackupDirLocked(
                    context,
                    options,
                    onProgress,
                    requireDatabaseCheckpoint = true,
                )
            }
        }
    }

    private suspend fun exportToBackupDirLocked(
        context: Context,
        options: SnapshotOptions,
        onProgress: ((ExportProgressInfo) -> Unit)?,
        performDatabaseCheckpoint: Boolean = true,
        requireDatabaseCheckpoint: Boolean = false
    ): File = TokenStatsResetCoordinator.withCleanupSnapshotAccess {
            AppLogger.i(TAG, "export start (includeTerminalData=${options.includeTerminalData})")
            withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.PREPARING)) }
            val exportDir = OperitBackupDirs.rawSnapshotDir()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val outFile = File(exportDir, "$ZIP_PREFIX$timestamp.zip")
            val tmpFile = File(exportDir, "${outFile.name}.tmp")

            if (tmpFile.exists()) {
                tmpFile.delete()
            }

            val dataDir = context.dataDir
            val externalFilesDir = requireNotNull(context.getExternalFilesDir(null)) {
                "External files dir is unavailable"
            }
            val sharedPrefsDir = File(dataDir, "shared_prefs")
            val datastoreDir = File(dataDir, "datastore")
            val databasesDir = File(dataDir, "databases")

            if (performDatabaseCheckpoint) {
                try {
                    checkpointRoomDatabase(context)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "wal_checkpoint failed", e)
                    if (requireDatabaseCheckpoint) throw e
                }
            }

            val includes = listOf(
                ENTRY_FILES,
                ENTRY_EXTERNAL_FILES,
                ENTRY_SHARED_PREFS,
                ENTRY_DATASTORE,
                ENTRY_DATABASES
            )
            val manifest = Manifest(
                formatVersion = FORMAT_VERSION,
                packageName = context.packageName,
                createdAt = System.currentTimeMillis(),
                includes = includes,
                includeTerminalData = options.includeTerminalData
            )

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
                zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zos.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val alwaysExcluded = OperitPaths.rawSnapshotExcludedFilesTopLevelDirNames() +
                    setOf("subagent-v2-store", "subagent-v2.json", "subagent-v2.json.bak", "subagent-v2.json.new")
                val excludedNames = if (options.includeTerminalData) {
                    alwaysExcluded
                } else {
                    alwaysExcluded + terminalTopLevelDirNames
                }
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(ExportProgressInfo(stage = ExportProgress.SCANNING_FILES, scannedFiles = 0))
                }
                val filesTotalCount = totalFilesForZip(
                    dir = context.filesDir,
                    entryPrefix = ENTRY_FILES,
                    excludedTopLevelDirNames = excludedNames,
                    onScannedCountChanged = { scanned ->
                        if (onProgress != null) {
                            mainHandler.post {
                                onProgress.invoke(
                                    ExportProgressInfo(stage = ExportProgress.SCANNING_FILES, scannedFiles = scanned)
                                )
                            }
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        ExportProgressInfo(stage = ExportProgress.SCANNING_FILES, scannedFiles = filesTotalCount)
                    )
                }
                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_FILES, 0)) }
                val filesMs = measureTimeMillis {
                    // Copy referenced generations under the store lock, then release it before ZIP I/O.
                    val collaborationSnapshot = java.nio.file.Files.createTempDirectory(
                        context.cacheDir.toPath(), "collaboration-backup-"
                    ).toFile()
                    try {
                        com.ai.assistance.operit.core.agent.collaboration.CollaborationStore(context)
                            .copyForBackup(collaborationSnapshot)
                        addDirToZip(zos, collaborationSnapshot, ENTRY_FILES)
                    } finally {
                        collaborationSnapshot.deleteRecursively()
                    }
                    addDirToZip(
                        zos = zos,
                        dir = context.filesDir,
                        entryPrefix = ENTRY_FILES,
                        excludedTopLevelDirNames = excludedNames,
                        totalFiles = filesTotalCount,
                        onPercentChanged = { percent ->
                            if (onProgress != null) {
                                mainHandler.post {
                                    onProgress.invoke(ExportProgressInfo(ExportProgress.ZIPPING_FILES, percent))
                                }
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_FILES, 100)) }
                AppLogger.i(TAG, "export add files done in ${filesMs}ms (excludedTopLevel=${excludedNames.size})")

                val externalFilesTotalCount = totalFilesForZip(
                    dir = externalFilesDir,
                    entryPrefix = ENTRY_EXTERNAL_FILES,
                    excludedTopLevelDirNames = emptySet()
                )
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_EXTERNAL_FILES, 0))
                }
                val externalFilesMs = measureTimeMillis {
                    addDirToZip(
                        zos = zos,
                        dir = externalFilesDir,
                        entryPrefix = ENTRY_EXTERNAL_FILES,
                        totalFiles = externalFilesTotalCount,
                        onPercentChanged = { percent ->
                            if (onProgress != null) {
                                mainHandler.post {
                                    onProgress.invoke(
                                        ExportProgressInfo(ExportProgress.ZIPPING_EXTERNAL_FILES, percent)
                                    )
                                }
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_EXTERNAL_FILES, 100))
                }
                AppLogger.i(TAG, "export add external_files done in ${externalFilesMs}ms")

                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_SHARED_PREFS)) }
                val sharedPrefsMs = measureTimeMillis { addDirToZip(zos, sharedPrefsDir, ENTRY_SHARED_PREFS) }
                AppLogger.i(TAG, "export add shared_prefs done in ${sharedPrefsMs}ms")

                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_DATASTORE)) }
                val datastoreMs = measureTimeMillis { addDirToZip(zos, datastoreDir, ENTRY_DATASTORE) }
                AppLogger.i(TAG, "export add datastore done in ${datastoreMs}ms")

                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_DATABASES)) }
                val databasesMs = measureTimeMillis { addDirToZip(zos, databasesDir, ENTRY_DATABASES) }
                AppLogger.i(TAG, "export add databases done in ${databasesMs}ms")
            }

            withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.FINALIZING)) }
            if (outFile.exists()) {
                outFile.delete()
            }

            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }

            AppLogger.i(TAG, "export done: ${outFile.absolutePath} (${outFile.length()} bytes)")
            outFile
    }

    fun isOfficialOperitMigrationCompleted(context: Context): Boolean =
        MigrationStateStore.read(context).state == MigrationStateStore.State.COMPLETED

    fun isOfficialOperitMigrationAvailable(context: Context): Boolean =
        context.packageName == RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE &&
            MigrationStateStore.read(context).state == MigrationStateStore.State.IDLE

    fun isOfficialOperitMigrationPending(context: Context): Boolean =
        MigrationStateStore.read(context).let { snapshot ->
            snapshot.state == MigrationStateStore.State.PENDING && snapshot.finalState == null
        }

    /**
     * Persist a pending official Operit migration URI so the migration can run in a dedicated
     * cold-start phase before [com.ai.assistance.operit.core.application.OperitApplication.initializeMainApplication]
     * initializes WorkManager, foreground services, schedulers, repositories and DataStore writers.
     *
     * The caller must already hold a persistable read URI permission for [uri]. After persisting,
     * the caller should exit the process so the next cold start observes the pending state.
     *
     * Returns true on success, false on failure. Callers MUST check the return value and surface
     * an error to the user instead of exiting the process.
     */
    fun setPendingOfficialOperitMigration(context: Context, uri: Uri): Boolean {
        check(context.packageName == RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE) {
            "Official Operit migration is only available in Operit Ry"
        }
        return MigrationStateStore.withProcessStateLock {
            check(MigrationStateStore.read(context).state == MigrationStateStore.State.IDLE) {
                "Official Operit migration is not idle"
            }
            MigrationStateStore.write(context, MigrationStateStore.State.PENDING, uri)
        }
    }

    /**
     * Stage a same-package raw snapshot for a cold-start restore. The live process only persists
     * the URI and the state to return to; it never replaces SharedPreferences/DataStore files
     * behind already-created singleton caches. The caller must hold persistable read permission
     * and exit immediately after this returns true.
     */
    fun setPendingRawSnapshotRestore(context: Context, uri: Uri): Boolean =
        MigrationStateStore.withProcessStateLock {
            check(!processRestartRequired) {
                "Application restart required before another snapshot operation"
            }
            val current = MigrationStateStore.read(context)
            val finalState = when (current.state) {
                MigrationStateStore.State.IDLE -> MigrationStateStore.State.IDLE
                MigrationStateStore.State.COMPLETED -> MigrationStateStore.State.COMPLETED
                else -> error("Raw snapshot restore cannot be staged from ${current.state}")
            }
            MigrationStateStore.write(
                context = context,
                state = MigrationStateStore.State.PENDING,
                uri = uri,
                finalState = finalState,
            )
        }

    fun cancelSafeOfficialOperitMigration(context: Context): Boolean {
        return MigrationStateStore.withProcessStateLock {
            check(!RoomDatabaseRestoreManager.hasInterruptedReplacement(context)) {
                "Database rollback must finish before cancelling restore"
            }
            val snapshot = MigrationStateStore.read(context)
            val state = snapshot.state
            check(MigrationStatePolicy.isSafelyCancellable(state)) {
                "Official Operit migration is not safely cancellable (state=$state)"
            }
            check(!officialMigrationRunning.get()) {
                "Cannot cancel an active official Operit migration"
            }
            MigrationStateStore.write(
                context,
                if (snapshot.operation == "ROOM_RESTORE") {
                    snapshot.finalState ?: MigrationStateStore.State.IDLE
                } else MigrationStateStore.State.IDLE,
            )
        }
    }

    /**
     * Read the current migration state. Any process entry point (Activity, Service, Receiver,
     * Worker) MUST call this before initializing the application. PENDING must run or resume the
     * migration. PREPARING / REPLACING / FAILED mean the data directory may be partially
     * replaced and the app MUST NOT initialize normally; it must enter the dedicated recovery
     * surface so the user can restore from the safety snapshot. COMPLETED means the migration
     * succeeded and the entry point can initialize normally.
     */
    fun officialOperitMigrationState(context: Context): MigrationStateStore.Snapshot =
        MigrationStateStore.read(context)

    fun isProcessRestartRequired(): Boolean = processRestartRequired

    internal fun requireProcessRestart() {
        processRestartRequired = true
    }

    fun isOfficialOperitMigrationRunningInProcess(): Boolean = officialMigrationRunning.get()

    fun officialOperitMigrationFailureMessage(): String? = officialMigrationFailureMessage

    /** Includes both official migration and a same-package raw restore staged for cold start. */
    fun isPendingRestoreOperationRunningInProcess(): Boolean = officialMigrationRunning.get()

    fun pendingRestoreOperationFailureMessage(): String? = officialMigrationFailureMessage

    /**
     * 恢复前先把恢复流程接入持久状态机：仅官方迁移处于 COMPLETED 时恢复完成后
     * 保持 COMPLETED，其余（IDLE/REPLACING/FAILED/虚拟 NEEDS_RECOVERY）落回 IDLE。
     */
    internal fun restoreFinalState(context: Context): MigrationStateStore.State {
        val current = MigrationStateStore.read(context).state
        return if (current == MigrationStateStore.State.COMPLETED) {
            MigrationStateStore.State.COMPLETED
        } else {
            MigrationStateStore.State.IDLE
        }
    }

    /**
     * 原始快照恢复的共用编排：持久化 REPLACING → 立即要求进程重启 →
     * 执行目录替换 →
     * 持久化“替换完成待登记” →
     * 崩溃安全登记 generation 并完成 recovery state。
     *
     * 状态机（持久化在 noBackupFilesDir，不随恢复覆盖）：
     * - [performRestore] 的 prepare 阶段必须先持久化 REPLACING 再关闭数据库并
     *   替换目录：进程在任何边界退出，冷启动都会进入恢复面而不是误以 IDLE 正常
     *   初始化（目录可能部分替换）。
     * - [performRestore] 在关闭数据库及任何目录替换前调用所给 prepare 回调。
     *   只有 REPLACING 成功持久化后才立即置 processRestartRequired = true；写入
     *   REPLACING 本身失败不要求重启。一旦回调成功，后续关闭存储、目录替换、
     *   RESTORE_REPLACED 写入或登记的任何失败都必须重启。
     * - 替换成功但登记失败（异常/进程死亡）：状态保持 RESTORE_REPLACED，冷启动
     *   通过 [completePendingRestoreRegistration] 自动补登记新 generation，无需
     *   重新替换目录；同一次替换绝不会把凭空生成的新 generation 与已应用的旧
     *   generation 混淆（generation 每次登记都是全新 UUID，幂等锚点在数据库侧）。
     * - 登记用 NonCancellable 包裹：协程取消不会在“marker 已写、状态未完成”的
     *   中间态放弃登记。
     */
    internal suspend fun runRestoreEntry(
        context: Context,
        finalState: MigrationStateStore.State,
        performRestore: suspend (prepareForReplacement: suspend () -> Unit) -> Unit,
    ) {
        performRestore {
            MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.REPLACING)
            // REPLACING 已落盘，破坏阶段从这里开始。必须先关门，再关闭任何存储。
            processRestartRequired = true
        }
        // 再持久化“替换完成待登记”（fallible），最后登记 generation（fallible）。
        MigrationStateStore.writeOrThrow(
            context,
            MigrationStateStore.State.RESTORE_REPLACED,
            finalState = finalState
        )
        withContext(NonCancellable) {
            RestoreCompletionCoordinator.registerAfterRestore(context)
        }
    }

    /**
     * 冷启动自动补登记：目录替换已完成（状态 RESTORE_REPLACED），只补登记
     * generation 并完成状态，**不重新替换任何目录**。之后调用方应退出进程，
     * 下一个冷启动在正常初始化前消费 marker 完成受控补导。
     *
     * @throws IllegalStateException 状态不是 RESTORE_REPLACED。
     */
    suspend fun completePendingRestoreRegistration(context: Context) {
        val snapshot = MigrationStateStore.read(context)
        check(snapshot.state == MigrationStateStore.State.RESTORE_REPLACED) {
            "No pending restore registration (state=${snapshot.state})"
        }
        processRestartRequired = true
        withContext(NonCancellable) {
            RestoreCompletionCoordinator.registerAfterRestore(context)
        }
    }

    /**
     * Restore from a local backup [File] (typically a snapshot listed by [listRawSnapshots]).
     * Used by the recovery surface, which operates on files inside the app's private backup
     * directory and therefore does not need a persisted URI permission.
     *
     * Shares the exact same orchestration and state machine as the cold-start URI restore:
     * REPLACING → (directories replaced) → RESTORE_REPLACED → registration → IDLE/COMPLETED.
     * On success the caller should exit the process so the next cold start consumes the
     * registered generation; a COMPLETED marker is preserved.
     */
    suspend fun restoreFromBackupFile(
        context: Context,
        file: File,
        onProgress: ((RestoreProgress) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!processRestartRequired) { "Application restart required before another snapshot operation" }
            runRestoreEntry(
                context = context,
                finalState = restoreFinalState(context),
                performRestore = { prepareForReplacement ->
                    restoreFromBackupLocked(
                        context = context,
                        openInput = { FileInputStream(file) },
                        expectedPackageName = context.packageName,
                        prepareBeforeCommit = {},
                        commitReplacement = prepareForReplacement,
                        closeStoresAfterCommit = true,
                        onProgress = onProgress
                    )
                }
            )
        }
    }

    /**
     * List raw snapshot files in the canonical backup directory, newest first. Used by the
     * recovery surface to offer the user a choice of snapshots to restore.
     */
    fun listRawSnapshots(): List<File> =
        OperitBackupDirs.rawSnapshotDir()
            .listFiles { f -> f.isFile && f.name.startsWith(ZIP_PREFIX) && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Dispatch the persisted PENDING operation during the dedicated cold-start phase. A pending
     * record with [MigrationStateStore.Snapshot.finalState] is a same-package raw restore staged
     * by Settings; the legacy record without it is the official-Operit migration.
     */
    suspend fun runPendingRestoreOperation(
        context: Context,
        onProgress: ((RestoreProgress) -> Unit)? = null,
    ): File? {
        val pending = MigrationStateStore.read(context)
        check(pending.state == MigrationStateStore.State.PENDING) {
            "Restore operation is not pending (state=${pending.state})"
        }
        return if (pending.operation == "ROOM_RESTORE") {
            check(officialMigrationRunning.compareAndSet(false, true)) {
                "A pending restore operation is already running"
            }
            officialMigrationFailureMessage = null
            try {
                mutex.withLock { RoomDatabaseRestoreManager.runPending(context) }
                processRestartRequired = true
            } catch (error: Exception) {
                // Restore fencing can outlive a successful rollback. Never initialize this process.
                processRestartRequired = true
                officialMigrationFailureMessage = error.message ?: error.javaClass.name
                throw error
            } finally {
                officialMigrationRunning.set(false)
            }
            null
        } else if (pending.finalState != null) {
            runPendingRawSnapshotRestore(context, onProgress)
            null
        } else {
            runPendingOfficialOperitMigration(context, onProgress)
        }
    }

    /**
     * Direct URI replacement is reserved for the recovery surface, which runs before normal
     * application initialization while migration state denies main-data access. Live Settings
     * must use [setPendingRawSnapshotRestore] and let the cold-start runner perform replacement.
     */
    internal suspend fun restoreFromBackupUri(
        context: Context,
        uri: Uri,
        onProgress: ((RestoreProgress) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!MigrationStateStore.isMainDataAccessAllowed(context)) {
                "Direct raw snapshot URI restore is only allowed from the cold-start recovery surface"
            }
            check(!processRestartRequired) {
                "Application restart required before another snapshot operation"
            }
            runRestoreEntry(
                context = context,
                finalState = restoreFinalState(context),
                performRestore = { prepareForReplacement ->
                    restoreFromBackupLocked(
                        context = context,
                        openInput = { context.contentResolver.openInputStream(uri) },
                        expectedPackageName = context.packageName,
                        prepareBeforeCommit = {},
                        commitReplacement = prepareForReplacement,
                        closeStoresAfterCommit = true,
                        onProgress = onProgress,
                    )
                },
            )
        }
    }

    /**
     * Apply a user-selected raw snapshot before normal application initialization. No
     * SharedPreferences/DataStore singleton or background writer exists in this process yet, so
     * directory replacement cannot race a stale cache. Validation failures restore the previous
     * IDLE/COMPLETED state; failures after REPLACING remain fail-closed for recovery.
     */
    private suspend fun runPendingRawSnapshotRestore(
        context: Context,
        onProgress: ((RestoreProgress) -> Unit)?,
    ) {
        check(officialMigrationRunning.compareAndSet(false, true)) {
            "A pending restore operation is already running in this process"
        }
        officialMigrationFailureMessage = null
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val pending = MigrationStateStore.read(context)
                    check(pending.state == MigrationStateStore.State.PENDING) {
                        "Raw snapshot restore is not pending (state=${pending.state})"
                    }
                    val finalState = checkNotNull(pending.finalState) {
                        "Pending operation is not a raw snapshot restore"
                    }
                    val uri = checkNotNull(pending.uri) {
                        "No pending raw snapshot restore URI"
                    }
                    try {
                        runRestoreEntry(
                            context = context,
                            finalState = finalState,
                            performRestore = { prepareForReplacement ->
                                restoreFromBackupLocked(
                                    context = context,
                                    openInput = { context.contentResolver.openInputStream(uri) },
                                    expectedPackageName = context.packageName,
                                    prepareBeforeCommit = {},
                                    commitReplacement = prepareForReplacement,
                                    closeStoresAfterCommit = true,
                                    onProgress = onProgress,
                                )
                            },
                        )
                    } catch (e: Exception) {
                        if (!processRestartRequired) {
                            // Validation/open/state-commit failed before any replacement. Clear
                            // PENDING so a corrupt or revoked URI cannot create a cold-start loop.
                            MigrationStateStore.writeOrThrow(context, finalState)
                        }
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            officialMigrationFailureMessage = e.message ?: e.javaClass.name
            throw e
        } finally {
            officialMigrationRunning.set(false)
        }
    }

    /**
     * Run the pending official Operit migration in a dedicated cold-start phase.
     *
     * This must be called from [com.ai.assistance.operit.ui.main.MainActivity.onCreate] BEFORE
     * [com.ai.assistance.operit.core.application.OperitApplication.initializeMainApplication]
     * so that WorkManager, foreground services, schedulers, repositories and DataStore writers are
     * not running. At this point no background writer has been started, so the safety snapshot is
     * consistent and file replacement is not racing with concurrent writes.
     *
     * State transitions (persisted to noBackupFilesDir, outside raw snapshots):
     *
     * PENDING -> IDLE (missing URI or validation failure; the user can select another archive)
     * PENDING -> PREPARING (inside prepareForReplacement, after the zip is validated)
     * PREPARING -> REPLACING (after the safety snapshot is on disk, before any directory is replaced)
     * REPLACING -> COMPLETED (after all directories are replaced and the completion marker is written)
     * PREPARING -> IDLE (pre-replacement failure)
     * REPLACING -> FAILED (failure after replacement may have started)
     *
     * Activity recreation during PREPARING observes the process ownership flag and keeps showing
     * progress. If the process crashes during PREPARING, the next cold start safely resets IDLE;
     * a crash during REPLACING enters recovery because data may be partially replaced.
     *
     * The migration state lives in noBackupFilesDir, which raw snapshots do NOT capture, so the
     * safety snapshot never contains migration state. A later restore of the safety snapshot for
     * rollback enters normal mode (IDLE) instead of re-entering the migration path.
     *
     * Returns the safety backup file on success. Throws on failure.
     */
    private suspend fun runPendingOfficialOperitMigration(
        context: Context,
        onProgress: ((RestoreProgress) -> Unit)? = null
    ): File {
        check(officialMigrationRunning.compareAndSet(false, true)) {
            "Official Operit migration is already running in this process"
        }
        officialMigrationFailureMessage = null
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
            check(context.packageName == RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE) {
                "Official Operit migration is only available in Operit Ry"
            }
            val snapshot = MigrationStateStore.read(context)
            check(snapshot.state == MigrationStateStore.State.PENDING) {
                "Official Operit migration is not pending (state=${snapshot.state})"
            }
            val uri = snapshot.uri
            if (uri == null) {
                MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.IDLE)
                throw IllegalStateException("No pending official Operit migration URI")
            }

            // State machine guard: stays PENDING until the zip is read, extracted and validated
            // inside restoreFromBackupLocked. A corrupt zip, wrong source package, or URI
            // permission failure throws before prepareForReplacement runs. The catch atomically
            // returns the state to IDLE so the next launch reaches Settings and the user can pick
            // another archive instead of retrying the same bad request forever.
            //
            // Preparation and the REPLACING commit run inside the restore barrier after validation.
            // The request fence changes only after REPLACING is durable and before replacement.
            var enteredReplacing = false
            var safetyBackupPath: String? = null
            lateinit var safetyBackup: File
            try {
                restoreFromBackupLocked(
                    context = context,
                    openInput = { context.contentResolver.openInputStream(uri) },
                    expectedPackageName = RawSnapshotPackagePolicy.OFFICIAL_OPERIT_PACKAGE,
                    prepareBeforeCommit = {
                        // Transition to PREPARING before closing databases and taking the safety
                        // snapshot. Validation has already succeeded at this point, so a crash
                        // here leaves state=PREPARING with data untouched; the next MainActivity
                        // cold start safely resets the request to IDLE.
                        MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.PREPARING, uri)
                        // In the dedicated cold-start phase no background writer is running yet,
                        // so a Room checkpoint is safe and the safety snapshot is consistent.
                        checkpointRoomDatabase(context)
                        processRestartRequired = true
                        AppDatabase.closeDatabase()
                        ObjectBoxManager.closeAll()
                        // Safety snapshot must preserve terminal data because legacy v1 manifests
                        // default to includeTerminalData=true and migration may overwrite
                        // usr/tmp/bin. Without this, a failed migration or manual rollback could
                        // not restore pre-migration terminal data from the safety snapshot.
                        // The migration state is in noBackupFilesDir, which raw snapshots do NOT
                        // capture, so the safety snapshot never contains migration state and a
                        // rollback enters normal mode (IDLE) instead of re-entering the migration.
                        safetyBackup = exportToBackupDirLocked(
                            context = context,
                            options = SnapshotOptions(includeTerminalData = true),
                            onProgress = null,
                            performDatabaseCheckpoint = false
                        )
                        safetyBackupPath = safetyBackup.absolutePath
                    },
                    commitReplacement = {
                        MigrationStateStore.writeOrThrow(
                            context,
                            MigrationStateStore.State.REPLACING,
                            uri,
                            safetyBackupPath
                        )
                        enteredReplacing = true
                    },
                    // Stores were closed before the safety snapshot; do not reopen or close twice.
                    closeStoresAfterCommit = false,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                // Only record FAILED after REPLACING was persisted, when directory replacement
                // may have started. Failures before stores close return to IDLE. Once this process
                // requires restart, PREPARING remains persisted so no entry point can reopen the
                // closed stores before exit; the next cold process safely resets it to IDLE.
                val failureState = MigrationStatePolicy.stateAfterFailure(
                    replacementStarted = enteredReplacing,
                    processRestartRequired = processRestartRequired
                )
                MigrationStateStore.writeOrThrow(
                    context,
                    failureState,
                    uri = if (failureState == MigrationStateStore.State.FAILED) uri else null,
                    safetyBackupPath = if (failureState == MigrationStateStore.State.FAILED) {
                        safetyBackupPath
                    } else {
                        null
                    }
                )
                throw e
            }
            // Success: transition to COMPLETED. The state file is in noBackupFilesDir, which is
            // not captured by raw snapshots, so a future restore of any snapshot taken after this
            // point will observe COMPLETED and enter normal mode.
            MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.COMPLETED)
                    safetyBackup
                }
            }
        } catch (e: Exception) {
            officialMigrationFailureMessage = e.message ?: e.javaClass.name
            throw e
        } finally {
            officialMigrationRunning.set(false)
        }
    }

    private suspend fun restoreFromBackupLocked(
        context: Context,
        openInput: () -> java.io.InputStream?,
        expectedPackageName: String,
        prepareBeforeCommit: suspend () -> Unit,
        commitReplacement: suspend () -> Unit,
        closeStoresAfterCommit: Boolean,
        onProgress: ((RestoreProgress) -> Unit)?
    ) {
            val cacheZip = File.createTempFile("raw_snapshot_restore_", ".zip", context.cacheDir)
            val workDir = File(context.cacheDir, "raw_snapshot_restore_work").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            try {
                AppLogger.i(TAG, "restore start")
                withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.PREPARING) }
                withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.READING_ZIP) }
                openInput()?.use { input ->
                    FileOutputStream(cacheZip).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Failed to open restore source")

                AppLogger.i(TAG, "restore cached zip: ${cacheZip.absolutePath} (${cacheZip.length()} bytes)")

                withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.EXTRACTING) }
                val manifest = extractZipToWorkDir(cacheZip, workDir, expectedPackageName)

                val payloadDir = File(workDir, "payload")
                val externalFilesPayloadDir = File(payloadDir, "external_files")

                val alwaysExcluded = OperitPaths.rawSnapshotExcludedFilesTopLevelDirNames()

                val preserveTerminal = !manifest.includeTerminalData
                val preservedTerminalNames = if (preserveTerminal) terminalTopLevelDirNames else emptySet()
                val preservedAlwaysExcludedNames = alwaysExcluded.filterNot { dirName ->
                    File(payloadDir, "files/$dirName").exists()
                }.toSet()
                val preservedNames = preservedTerminalNames + preservedAlwaysExcludedNames

                AppLogger.i(
                    TAG,
                    "restore manifest ok (formatVersion=${manifest.formatVersion}, includeTerminalData=${manifest.includeTerminalData})"
                )

                withTokenStatsRestoreIsolation(
                    context = context,
                    prepareBeforeCommit = prepareBeforeCommit,
                    commitReplacement = commitReplacement,
                ) {
                    withContext(NonCancellable) {
                        if (closeStoresAfterCommit) {
                            AppDatabase.closeDatabase()
                            ObjectBoxManager.closeAll()
                        }

                        AppLogger.i(TAG, "restore closed databases (room + objectbox)")
                        AppLogger.i(TAG, "restore replace dirs (preserveTerminalTopLevel=${preservedNames.isNotEmpty()})")

                        withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_FILES) }
                        com.ai.assistance.operit.core.agent.collaboration.CollaborationStore.prepareRestore(
                            File(payloadDir, "files"), context.filesDir
                        )
                        replaceDirContents(File(payloadDir, "files"), context.filesDir, preservedTopLevelDirNames = preservedNames)
                        if (externalFilesPayloadDir.exists()) {
                            val externalFilesDir = requireNotNull(context.getExternalFilesDir(null)) {
                                "External files dir is unavailable"
                            }
                            withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_EXTERNAL_FILES) }
                            replaceDirContents(externalFilesPayloadDir, externalFilesDir)
                        }
                        withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_SHARED_PREFS) }
                        replaceDirContents(File(payloadDir, "shared_prefs"), File(context.dataDir, "shared_prefs"))
                        withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_DATASTORE) }
                        replaceDirContents(File(payloadDir, "datastore"), File(context.dataDir, "datastore"))
                        withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_DATABASES) }
                        replaceDirContents(File(payloadDir, "databases"), File(context.dataDir, "databases"))

                        // The signing key intentionally lives outside raw snapshots. Advance a
                        // no-backup generation after replacement so credentials from the restored
                        // files cannot become valid again under the preserved same-install key.
                        restoreGenerationAdvancerForTest?.invoke(context)
                            ?: advanceWorkflowAuthRestoreGeneration(context)

                        withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.FINALIZING) }
                    }
                }

                AppLogger.i(TAG, "restore done: ${manifest.packageName}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "restore failed", e)
                throw e
            } finally {
                try {
                    cacheZip.delete()
                } catch (_: Exception) {
                }
                try {
                    workDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
    }

    /**
     * Fixed lock order for raw replacement: RawSnapshot [mutex] -> TokenStatSpool lifecycle ->
     * cleanup snapshot mutex. Export uses the same order. Cleanup releases its mutex before
     * replaying the spool, so there is no reverse nested acquisition.
     */
    internal suspend fun <T> withTokenStatsRestoreIsolation(
        context: Context,
        prepareBeforeCommit: suspend () -> Unit,
        commitReplacement: suspend () -> Unit,
        block: suspend () -> T,
    ): T = TokenStatSpool.withExclusiveRestoreAccess(
        context = context,
        prepareBeforeCommit = prepareBeforeCommit,
        commitReplacement = commitReplacement,
    ) {
        TokenStatsResetCoordinator.withCleanupSnapshotAccess(block)
    }

    private fun extractZipToWorkDir(zipFile: File, workDir: File, expectedPackageName: String): Manifest {
        val payloadRoot = File(workDir, "payload")
        payloadRoot.mkdirs()

        var manifestText: String? = null
        var extractedPayloadFiles = 0
        val supportedPayloadPrefixes = listOf(
            ENTRY_FILES,
            ENTRY_EXTERNAL_FILES,
            ENTRY_SHARED_PREFS,
            ENTRY_DATASTORE,
            ENTRY_DATABASES
        )

        val buffer = ByteArray(64 * 1024)
        val extractMs = measureTimeMillis {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name

                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }

                    if (name == ENTRY_MANIFEST) {
                        val bytes = zis.readBytesSafely(maxBytes = 512 * 1024)
                        manifestText = bytes.toString(Charsets.UTF_8)
                        zis.closeEntry()
                        continue
                    }

                    if (!name.startsWith(ENTRY_PAYLOAD_PREFIX)) {
                        zis.closeEntry()
                        continue
                    }

                    val payloadPrefix = supportedPayloadPrefixes.firstOrNull { prefix ->
                        name.startsWith(prefix)
                    }
                    if (payloadPrefix == null) {
                        zis.closeEntry()
                        throw IllegalArgumentException("Unsupported payload entry: $name")
                    }

                    val target = File(workDir, name)
                    val workCanonical = workDir.canonicalFile
                    val targetCanonical = target.canonicalFile
                    if (!targetCanonical.path.startsWith(workCanonical.path + File.separator)) {
                        zis.closeEntry()
                        throw IllegalArgumentException("Invalid zip entry path: $name")
                    }
                    val payloadRootCanonical = File(workDir, payloadPrefix).canonicalFile
                    if (!targetCanonical.path.startsWith(payloadRootCanonical.path + File.separator)) {
                        zis.closeEntry()
                        throw IllegalArgumentException("Invalid payload entry path: $name")
                    }

                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }

                    extractedPayloadFiles++

                    zis.closeEntry()
                }
            }
        }

        AppLogger.i(TAG, "restore extract done in ${extractMs}ms (payloadFiles=$extractedPayloadFiles)")

        val manifest = manifestText?.let { json.decodeFromString(Manifest.serializer(), it) }
            ?: throw IllegalArgumentException("Invalid backup zip: missing $ENTRY_MANIFEST")

        if (manifest.formatVersion != FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: ${manifest.formatVersion}")
        }

        RawSnapshotPackagePolicy.requireSourcePackage(manifest.packageName, expectedPackageName)

        val requiredIncludes = listOf(
            ENTRY_FILES,
            ENTRY_EXTERNAL_FILES,
            ENTRY_SHARED_PREFS,
            ENTRY_DATASTORE,
            ENTRY_DATABASES
        )
        val legacyIncludes = requiredIncludes - ENTRY_EXTERNAL_FILES
        RawSnapshotPackagePolicy.requireArchiveContents(
            actualIncludes = manifest.includes,
            supportedIncludes = listOf(requiredIncludes, legacyIncludes),
            payloadFileCount = extractedPayloadFiles
        )

        return manifest
    }

    private fun checkpointRoomDatabase(context: Context) {
        val sqliteDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
        sqliteDb.query("PRAGMA wal_checkpoint(FULL)").close()
    }

    private fun addDirToZip(
        zos: ZipOutputStream,
        dir: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String> = emptySet(),
        totalFiles: Int = 0,
        onPercentChanged: ((Int) -> Unit)? = null
    ) {
        if (!dir.exists() || !dir.isDirectory) return

        val baseCanonical = dir.canonicalFile
        val buffer = ByteArray(64 * 1024)
        val writtenEntryNames = HashSet<String>()

        var processedFiles = 0
        var lastPercent = -1

        dir.walkTopDown().onEnter { currentDir ->
            !shouldPruneDirForZip(currentDir, dir, entryPrefix, excludedTopLevelDirNames)
        }.forEach { f ->
            if (!f.isFile) return@forEach

            val canonical = f.canonicalFile
            if (shouldSkipForZip(canonical, baseCanonical, entryPrefix, excludedTopLevelDirNames)) {
                if (canonical.name == "lock.mdb" && canonical.parentFile?.name?.startsWith("objectbox") == true) {
                    AppLogger.w(TAG, "export skip objectbox lock file: ${canonical.absolutePath}")
                }
                return@forEach
            }

            val rel = canonical.path.substring(baseCanonical.path.length + 1)
            val entryName = entryPrefix + rel.replace(File.separatorChar, '/')

            if (!writtenEntryNames.add(entryName)) {
                AppLogger.w(TAG, "export skip duplicate entry: $entryName")
                return@forEach
            }

            zos.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(canonical)).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    zos.write(buffer, 0, read)
                }
            }
            zos.closeEntry()

            if (totalFiles > 0 && onPercentChanged != null) {
                processedFiles++
                val percent = ((processedFiles * 100) / totalFiles).coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onPercentChanged(percent)
                }
            }
        }
    }

    private fun shouldPruneDirForZip(
        currentDir: File,
        baseDir: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String>
    ): Boolean {
        if (currentDir == baseDir) return false
        val parent = currentDir.parentFile ?: return false
        if (parent != baseDir) return false

        val name = currentDir.name
        if (excludedTopLevelDirNames.contains(name)) return true

        if (entryPrefix == ENTRY_FILES) {
            if (name.startsWith("sherpa-ncnn-")) return true
        }

        return false
    }

    private fun shouldSkipForZip(
        canonical: File,
        baseCanonical: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String>
    ): Boolean {
        if (!canonical.path.startsWith(baseCanonical.path + File.separator)) return true

        if (canonical.name == "lock.mdb" && canonical.parentFile?.name?.startsWith("objectbox") == true) {
            return true
        }

        val rel = canonical.path.substring(baseCanonical.path.length + 1)
        val relNormalized = rel.replace(File.separatorChar, '/')
        val top = relNormalized.substringBefore('/', missingDelimiterValue = relNormalized)
        if (excludedTopLevelDirNames.isNotEmpty() && excludedTopLevelDirNames.contains(top)) {
            return true
        }

        if (entryPrefix == ENTRY_FILES) {
            if (top.startsWith("sherpa-ncnn-")) {
                return true
            }

            // Exclude Ubuntu rootfs package (very large). Stored as a top-level file in filesDir.
            if (!relNormalized.contains('/')) {
                val name = relNormalized
                if (name.startsWith("ubuntu-", ignoreCase = true) && name.endsWith(".tar.xz", ignoreCase = true)) {
                    return true
                }
            }

            if (!relNormalized.contains('/')) {
                if (relNormalized.startsWith("memory_hnsw_") && relNormalized.endsWith(".idx")) {
                    return true
                }
                if (relNormalized.startsWith("doc_index_") && relNormalized.endsWith(".hnsw")) {
                    return true
                }
            }
        }

        return false
    }

    private fun totalFilesForZip(
        dir: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String>,
        onScannedCountChanged: ((Int) -> Unit)? = null
    ): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        val baseCanonical = dir.canonicalFile
        var total = 0

        var lastReported = 0
        var lastReportAtMs = 0L
        dir.walkTopDown().onEnter { currentDir ->
            !shouldPruneDirForZip(currentDir, dir, entryPrefix, excludedTopLevelDirNames)
        }.forEach { f ->
            if (!f.isFile) return@forEach
            val canonical = f.canonicalFile
            if (shouldSkipForZip(canonical, baseCanonical, entryPrefix, excludedTopLevelDirNames)) return@forEach
            total++

            if (onScannedCountChanged != null) {
                val now = System.currentTimeMillis()
                if (total == 1 || total - lastReported >= 200 || now - lastReportAtMs >= 250L) {
                    lastReported = total
                    lastReportAtMs = now
                    onScannedCountChanged(total)
                }
            }
        }
        return total
    }

    private fun replaceDirContents(
        fromDir: File,
        toDir: File,
        preservedTopLevelDirNames: Set<String> = emptySet()
    ) {
        if (!toDir.exists()) {
            toDir.mkdirs()
        }

        if (!fromDir.exists() || !fromDir.isDirectory) return

        // Non-destructive restore: only overwrite files present in the backup.
        // Files not present in the backup are preserved.
        copyDir(fromDir, toDir, preservedTopLevelDirNames)
    }

    private fun copyDir(
        fromDir: File,
        toDir: File,
        preservedTopLevelDirNames: Set<String>
    ) {
        val baseCanonical = fromDir.canonicalFile
        fromDir.walkTopDown().forEach { f ->
            val canonical = f.canonicalFile
            if (!canonical.path.startsWith(baseCanonical.path + File.separator) && canonical != baseCanonical) {
                return@forEach
            }

            if (canonical == baseCanonical) return@forEach

            val rel = canonical.path.substring(baseCanonical.path.length + 1)
            if (preservedTopLevelDirNames.isNotEmpty()) {
                val relNormalized = rel.replace(File.separatorChar, '/')
                val top = relNormalized.substringBefore('/', missingDelimiterValue = relNormalized)
                if (preservedTopLevelDirNames.contains(top)) {
                    return@forEach
                }
            }
            val target = File(toDir, rel)

            if (canonical.isDirectory) {
                target.mkdirs()
            } else if (canonical.isFile) {
                target.parentFile?.mkdirs()
                canonical.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun ZipInputStream.readBytesSafely(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            if (out.size() + read > maxBytes) {
                throw IllegalArgumentException("Zip entry too large")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
