package com.ai.assistance.operit.ui.features.settings.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ImportStrategy
import com.ai.assistance.operit.data.model.MemorySpace
import com.ai.assistance.operit.data.backup.OperitBackupDirs
import com.ai.assistance.operit.data.backup.RawSnapshotBackupManager
import com.ai.assistance.operit.data.backup.RoomDatabaseBackupManager
import com.ai.assistance.operit.data.backup.RoomDatabaseBackupPreferences
import com.ai.assistance.operit.data.backup.RoomDatabaseBackupScheduler
import com.ai.assistance.operit.data.backup.RoomDatabaseRestoreManager
import com.ai.assistance.operit.data.stats.TokenStatSpool
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.repository.MemoryRepository
import com.ai.assistance.operit.data.converter.ExportFormat
import com.ai.assistance.operit.data.converter.ChatFormat
import com.ai.assistance.operit.ui.features.settings.components.rememberSettingsCardColor
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.BackupFilesStatisticsCard
import com.ai.assistance.operit.ui.features.settings.components.CharacterCardManagementCard
import com.ai.assistance.operit.ui.features.settings.components.ChatHistoryOperation
import com.ai.assistance.operit.ui.features.settings.components.DataManagementCard
import com.ai.assistance.operit.ui.features.settings.components.DeleteConfirmationDialog
import com.ai.assistance.operit.ui.features.settings.components.ExportFormatDialog
import com.ai.assistance.operit.ui.features.settings.components.FaqCard
import com.ai.assistance.operit.ui.features.settings.components.ImportFormatDialog
import com.ai.assistance.operit.ui.features.settings.components.MemoryImportStrategyDialog
import com.ai.assistance.operit.ui.features.settings.components.MemoryManagementCard
import com.ai.assistance.operit.ui.features.settings.components.MemoryOperation
import com.ai.assistance.operit.ui.features.settings.components.ModelConfigExportWarningDialog
import com.ai.assistance.operit.ui.features.settings.components.ModelConfigManagementCard
import com.ai.assistance.operit.ui.features.settings.components.ModelConfigOperation
import com.ai.assistance.operit.ui.features.settings.components.ManagementButton
import com.ai.assistance.operit.ui.features.settings.components.OperationProgressView
import com.ai.assistance.operit.ui.features.settings.components.OperationResultCard
import com.ai.assistance.operit.ui.features.settings.components.OverviewCard
import com.ai.assistance.operit.ui.features.settings.components.ProfileSelectionDialog
import com.ai.assistance.operit.ui.features.settings.components.RoomDbBackupListItem
import com.ai.assistance.operit.ui.features.settings.components.SectionHeader
import com.ai.assistance.operit.ui.features.settings.components.CharacterCardOperation
import com.ai.assistance.operit.ui.main.MainActivity
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RoomDatabaseBackupOperation {
    IDLE,
    BACKING_UP,
    SUCCESS,
    FAILED
}

enum class RoomDatabaseRestoreOperation {
    IDLE,
    RESTORING,
    SUCCESS,
    FAILED
}

enum class RawSnapshotOperation {
    IDLE,
    BACKING_UP,
    BACKUP_SUCCESS,
    FAILED
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L ->
        String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 隔离证据区信息快照（P1-3：含未完成删除事务的计数与字节）。 */
private data class QuarantineInfoSnapshot(
    val evidenceCount: Int,
    val evidenceBytes: Long,
    val stuckTrashCount: Int,
    val stuckTrashBytes: Long,
    val summaryRecordCount: Int,
    val summaryBytes: Long,
)

/**
 * P2：导出失败/取消时目录清理的调度缝。生产路径在 [ioDispatcher]（默认 [Dispatchers.IO]）
 * 执行真实递归删除，绝不运行在 Main；测试注入 dispatcher 与删除动作以覆盖失败/取消分支并
 * 断言删除不在 Main。取消分支额外使用 NonCancellable 完成本轮独占目录的有界清理，之后由
 * 调用方重抛取消。
 */
internal object QuarantineExportCleanup {
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var deleteRecursivelyForTest: ((File) -> Boolean?)? = null

    internal suspend fun deleteRecursively(destination: File, nonCancellable: Boolean = false): Boolean =
        if (nonCancellable) {
            // The outer context prevents prompt cancellation while returning from a different
            // dispatcher, so the caller can reliably log the cleanup result before rethrowing.
            withContext(NonCancellable) {
                withContext(NonCancellable + ioDispatcher) {
                    deleteRecursivelyForTest?.invoke(destination) ?: destination.deleteRecursively()
                }
            }
        } else {
            withContext(ioDispatcher) {
                deleteRecursivelyForTest?.invoke(destination) ?: destination.deleteRecursively()
            }
        }
}

/**
 * P2：导出失败/取消时的目录清理。只删除**本轮成功独占创建**的目录（本应用刚创建、
 * 不含用户文件），绝不触碰他人目录或上一轮残留；返回清理是否成功，调用方据此向用户
 * 反馈清理失败（而非静默假装已清理）。删除在 [QuarantineExportCleanup] 指定的 IO 线程
 * 执行；调用方协程已取消时仍完成有界清理（NonCancellable），随后调用方重抛取消。
 */
private suspend fun cleanupQuarantineExportDirectory(
    destination: File,
    createdByThisRun: Boolean,
    nonCancellable: Boolean = false,
): Boolean {
    if (!createdByThisRun) return true
    return QuarantineExportCleanup.deleteRecursively(destination, nonCancellable)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatBackupSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val chatHistoryManager = remember { ChatHistoryManager.getInstance(context) }
    val userPreferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val modelConfigManager = remember { ModelConfigManager(context) }
    val activeProfileId by userPreferencesManager.activeMemorySpaceIdFlow.collectAsState(initial = "default")
    var memoryRepo by remember { mutableStateOf<MemoryRepository?>(null) }

    var totalChatCount by remember { mutableStateOf(0) }
    var totalCharacterCardCount by remember { mutableStateOf(0) }
    var totalMemoryCount by remember { mutableStateOf(0) }
    var totalMemoryLinkCount by remember { mutableStateOf(0) }
    var totalModelConfigCount by remember { mutableStateOf(0) }
    var operationState by remember { mutableStateOf(ChatHistoryOperation.IDLE) }
    var operationMessage by remember { mutableStateOf("") }
    var characterCardOperationState by remember { mutableStateOf(CharacterCardOperation.IDLE) }
    var characterCardOperationMessage by remember { mutableStateOf("") }
    var memoryOperationState by remember { mutableStateOf(MemoryOperation.IDLE) }
    var memoryOperationMessage by remember { mutableStateOf("") }
    var modelConfigOperationState by remember { mutableStateOf(ModelConfigOperation.IDLE) }
    var modelConfigOperationMessage by remember { mutableStateOf("") }
    var roomDbBackupOperationState by remember { mutableStateOf(RoomDatabaseBackupOperation.IDLE) }
    var roomDbBackupOperationMessage by remember { mutableStateOf("") }
    var roomDbRestoreOperationState by remember { mutableStateOf(RoomDatabaseRestoreOperation.IDLE) }
    var roomDbRestoreOperationMessage by remember { mutableStateOf("") }
    var rawSnapshotOperationState by remember { mutableStateOf(RawSnapshotOperation.IDLE) }
    var rawSnapshotOperationMessage by remember { mutableStateOf("") }
    var pendingRawSnapshotRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRawSnapshotRestoreConfirmDialog by remember { mutableStateOf(false) }
    var quarantineEvidenceCount by remember { mutableIntStateOf(0) }
    var quarantineEvidenceBytes by remember { mutableLongStateOf(0L) }
    var quarantineSummaryCount by remember { mutableIntStateOf(0) }
    var quarantineSummaryBytes by remember { mutableLongStateOf(0L) }
    // P1-3：未完成删除事务（非空 ack trash）计数与字节，单独显示并计入总量
    var quarantineStuckTrashCount by remember { mutableIntStateOf(0) }
    var quarantineStuckTrashBytes by remember { mutableLongStateOf(0L) }
    var quarantineOperationMessage by remember { mutableStateOf("") }
    var quarantineOperationFailed by remember { mutableStateOf(false) }
    var quarantineBusy by remember { mutableStateOf(false) }
    var showQuarantineDeleteConfirmDialog by remember { mutableStateOf(false) }
    // 文件扫描/统计全部在 IO 线程执行（P2-2）：Main 只更新 state，避免大证据卡死 UI。
    // P1-6：证据枚举失败（如 ack trash 目录不可枚举）→ fail-closed：保留上次显示值并提示
    // 信息不可用，绝不回传“0 证据”误导用户（也不会因此关闭确认删除入口的计数）。
    val refreshQuarantineInfo: suspend () -> Unit = remember {
        {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    // P1-3：quarantineEvidence 已含非空 ack trash 目录（目录 length 恒为 0，
                    // 字节需用 stuckAckTrashBytes 补足）
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    val stuck = TokenStatSpool.stuckAckTrashEvidence(context)
                    val stuckBytes = TokenStatSpool.stuckAckTrashBytes(context)
                    val summary = TokenStatSpool.quarantineSummaryInfo(context)
                    QuarantineInfoSnapshot(
                        evidenceCount = evidence.size,
                        evidenceBytes = evidence.sumOf { it.length() } + stuckBytes,
                        stuckTrashCount = stuck.size,
                        stuckTrashBytes = stuckBytes,
                        summaryRecordCount = summary?.recordCount ?: 0,
                        summaryBytes = summary?.summaryBytes ?: 0L,
                    )
                }
                quarantineEvidenceCount = snapshot.evidenceCount
                quarantineEvidenceBytes = snapshot.evidenceBytes
                quarantineStuckTrashCount = snapshot.stuckTrashCount
                quarantineStuckTrashBytes = snapshot.stuckTrashBytes
                quarantineSummaryCount = snapshot.summaryRecordCount
                quarantineSummaryBytes = snapshot.summaryBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(
                    "ChatBackupSettings",
                    "quarantine evidence info refresh failed; keeping previous values",
                    e,
                )
                quarantineOperationFailed = true
                quarantineOperationMessage =
                    context.getString(
                        R.string.stats_quarantine_info_error,
                        e.localizedMessage ?: e.toString()
                    )
            }
        }
    }
    LaunchedEffect(Unit) { refreshQuarantineInfo() }
    var officialOperitMigrationAvailable by remember {
        mutableStateOf(RawSnapshotBackupManager.isOfficialOperitMigrationAvailable(context))
    }
    var pendingOfficialOperitMigrationUri by remember { mutableStateOf<Uri?>(null) }
    var showOfficialOperitMigrationConfirmDialog by remember { mutableStateOf(false) }
    var officialOperitMigrationErrorResId by remember { mutableStateOf<Int?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showMemoryImportStrategyDialog by remember { mutableStateOf(false) }
    var pendingMemoryImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRoomDbRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRoomDbRestoreFile by remember { mutableStateOf<File?>(null) }
    var showRoomDbRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showRoomDbRestoreRestartDialog by remember { mutableStateOf(false) }

    // 模型配置导出安全警告
    var showModelConfigExportWarning by remember { mutableStateOf(false) }
    var exportedModelConfigPath by remember { mutableStateOf("") }

    // Operit 目录备份文件统计
    var chatBackupFileCount by remember { mutableStateOf(0) }
    var characterCardBackupFileCount by remember { mutableStateOf(0) }
    var memoryBackupFileCount by remember { mutableStateOf(0) }
    var modelConfigBackupFileCount by remember { mutableStateOf(0) }
    var roomDbBackupFileCount by remember { mutableStateOf(0) }
    var recentRoomDbBackups by remember { mutableStateOf<List<File>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    val roomDbBackupPreferences = remember { RoomDatabaseBackupPreferences.getInstance(context) }
    val isRoomDbDailyBackupEnabled by roomDbBackupPreferences.enableDailyBackupFlow.collectAsState(initial = true)
    val roomDbLastSuccessTime by roomDbBackupPreferences.lastSuccessTimeFlow.collectAsState(initial = 0L)
    val roomDbLastError by roomDbBackupPreferences.lastErrorFlow.collectAsState(initial = "")
    val roomDbMaxBackupCount by roomDbBackupPreferences.maxBackupCountFlow.collectAsState(
        initial = RoomDatabaseBackupPreferences.DEFAULT_MAX_BACKUP_COUNT
    )

    val profileIds by userPreferencesManager.memorySpaceListFlow.collectAsState(initial = listOf("default"))
    var allProfiles by remember { mutableStateOf<List<MemorySpace>>(emptyList()) }
    var selectedExportProfileId by remember { mutableStateOf(activeProfileId) }
    var selectedImportProfileId by remember { mutableStateOf(activeProfileId) }
    var showExportProfileDialog by remember { mutableStateOf(false) }
    var showImportProfileDialog by remember { mutableStateOf(false) }

    // 导出格式选择
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var selectedExportFormat by remember { mutableStateOf(ExportFormat.JSON) }

    // 导入格式选择
    var showImportFormatDialog by remember { mutableStateOf(false) }
    var selectedImportFormat by remember { mutableStateOf(ChatFormat.OPERIT) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(activeProfileId) {
        memoryRepo = MemoryRepository(context, activeProfileId)
        selectedExportProfileId = activeProfileId
        selectedImportProfileId = activeProfileId
    }

    LaunchedEffect(profileIds) {
        val profiles = profileIds.mapNotNull { profileId ->
            try {
                userPreferencesManager.getMemorySpaceFlow(profileId).first()
            } catch (_: Exception) {
                null
            }
        }
        allProfiles = profiles
    }

    LaunchedEffect(Unit) {
        chatHistoryManager.allChatHistoriesInternalFlow.collect { chatHistories ->
            totalChatCount = chatHistories.size
        }
    }

    LaunchedEffect(Unit) {
        characterCardManager.characterCardListFlow.collect { cardIds ->
            totalCharacterCardCount = cardIds.size
        }
    }

    LaunchedEffect(memoryRepo) {
        memoryRepo?.let { repo ->
            val memories = repo.searchMemories("*")
            totalMemoryCount = memories.count { !it.isDocumentNode }
            val graph = repo.getMemoryGraph()
            totalMemoryLinkCount = graph.edges.size
        }
    }

    LaunchedEffect(Unit) {
        modelConfigManager.configListFlow.collect { configList ->
            totalModelConfigCount = configList.size
        }
    }

    // 扫描 Operit 目录中的备份文件
    LaunchedEffect(Unit) {
        scope.launch {
            isScanning = true
            try {
                val legacyDir = OperitBackupDirs.operitRootDir()
                val legacyFiles = legacyDir.listFiles()?.toList() ?: emptyList()

                fun mergedFiles(newDir: File): List<File> {
                    val newFiles = newDir.listFiles()?.toList() ?: emptyList()
                    return (newFiles + legacyFiles)
                        .filter { it.isFile }
                        .distinctBy { it.name }
                }

                val chatFiles = mergedFiles(OperitBackupDirs.chatDir())
                val characterCardFiles = mergedFiles(OperitBackupDirs.characterCardsDir())
                val memoryFiles = mergedFiles(OperitBackupDirs.memoryDir())
                val modelConfigFiles = mergedFiles(OperitBackupDirs.modelConfigDir())
                val roomDbFiles = mergedFiles(OperitBackupDirs.roomDbDir())

                chatBackupFileCount = chatFiles.count { file ->
                    file.name.startsWith("chat_backup_") && file.extension == "json" ||
                        file.name.startsWith("chat_export_") && file.extension in listOf("json", "md", "html", "txt")
                }

                characterCardBackupFileCount = characterCardFiles.count { file ->
                    file.name.startsWith("character_cards_backup_") && file.extension == "json"
                }

                memoryBackupFileCount = memoryFiles.count { file ->
                    file.name.startsWith("memory_backup_") && file.extension == "json"
                }

                modelConfigBackupFileCount = modelConfigFiles.count { file ->
                    file.name.startsWith("model_config_backup_") && file.extension == "json"
                }

                roomDbBackupFileCount = roomDbFiles.count { file ->
                    RoomDatabaseRestoreManager.isRoomDatabaseBackupFile(file.name)
                }

                recentRoomDbBackups = RoomDatabaseRestoreManager.listRecentBackups(context, limit = 3)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanning = false
            }
        }
    }

    val characterCardFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch {
                    characterCardOperationState = CharacterCardOperation.IMPORTING
                    characterCardOperationMessage = ""
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val jsonContent = inputStream?.bufferedReader()?.use { it.readText() }
                        if (jsonContent != null) {
                            val importResult = characterCardManager.importAllCharacterCardsFromBackupContent(jsonContent)
                            if (importResult.total > 0) {
                                characterCardOperationState = CharacterCardOperation.IMPORTED
                                val skippedText = if (importResult.skipped > 0) {
                                    context.getString(
                                        R.string.backup_character_cards_import_result_skipped,
                                        importResult.skipped
                                    )
                                } else {
                                    ""
                                }
                                characterCardOperationMessage = context.getString(
                                    R.string.backup_character_cards_import_result_success,
                                    importResult.new,
                                    importResult.updated,
                                    skippedText
                                )
                            } else {
                                characterCardOperationState = CharacterCardOperation.FAILED
                                characterCardOperationMessage =
                                    context.getString(R.string.backup_character_cards_import_result_failed)
                            }
                        } else {
                            characterCardOperationState = CharacterCardOperation.FAILED
                            characterCardOperationMessage =
                                context.getString(R.string.backup_import_failed_unreadable_file)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        characterCardOperationState = CharacterCardOperation.FAILED
                        characterCardOperationMessage = context.getString(
                            R.string.backup_import_failed_with_reason,
                            e.localizedMessage ?: e.toString()
                        )
                    }
                }
            }
        }
    }

    val chatFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    // 保存URI，显示格式选择对话框
                    pendingImportUri = uri
                    showImportFormatDialog = true
                }
            }
        }

    val roomDbRestoreFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    pendingRoomDbRestoreUri = uri
                    pendingRoomDbRestoreFile = null
                    showRoomDbRestoreConfirmDialog = true
                }
            }
        }

    val rawSnapshotRestoreFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    pendingRawSnapshotRestoreUri = uri
                    showRawSnapshotRestoreConfirmDialog = true
                }
            }
        }

    val officialOperitMigrationFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    pendingOfficialOperitMigrationUri = uri
                    showOfficialOperitMigrationConfirmDialog = true
                }
            }
        }

    val memoryFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    pendingMemoryImportUri = uri
                    showImportProfileDialog = true
                }
            }
        }

    val modelConfigFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    scope.launch {
                        modelConfigOperationState = ModelConfigOperation.IMPORTING
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val jsonContent = inputStream?.bufferedReader()?.use { it.readText() }
                            if (jsonContent != null) {
                                val (newCount, updatedCount, skippedCount) =
                                    modelConfigManager.importConfigs(jsonContent)
                                modelConfigOperationState = ModelConfigOperation.IMPORTED
                                val skippedText = if (skippedCount > 0) {
                                    context.getString(
                                        R.string.backup_model_config_import_result_skipped,
                                        skippedCount
                                    )
                                } else {
                                    ""
                                }
                                modelConfigOperationMessage = context.getString(
                                    R.string.backup_model_config_import_result_success,
                                    newCount,
                                    updatedCount,
                                    skippedText
                                )
                            } else {
                                modelConfigOperationState = ModelConfigOperation.FAILED
                                modelConfigOperationMessage =
                                    context.getString(R.string.backup_import_failed_unreadable_file)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            modelConfigOperationState = ModelConfigOperation.FAILED
                            modelConfigOperationMessage = context.getString(
                                R.string.backup_import_failed_with_reason,
                                e.localizedMessage ?: e.toString()
                            )
                        }
                    }
                }
            }
        }

    val activeProfileName =
        allProfiles.find { it.id == activeProfileId }?.name
            ?: stringResource(R.string.default_profile_name)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            OverviewCard(
                totalChatCount = totalChatCount,
                totalCharacterCardCount = totalCharacterCardCount,
                totalMemoryCount = totalMemoryCount,
                totalLinkCount = totalMemoryLinkCount,
                activeProfileName = activeProfileName
            )
        }
        item {
            BackupFilesStatisticsCard(
                chatBackupCount = chatBackupFileCount,
                characterCardBackupCount = characterCardBackupFileCount,
                memoryBackupCount = memoryBackupFileCount,
                modelConfigBackupCount = modelConfigBackupFileCount,
                roomDbBackupCount = roomDbBackupFileCount,
                isScanning = isScanning,
                onRefresh = {
                    scope.launch {
                        isScanning = true
                        try {
                            val legacyDir = OperitBackupDirs.operitRootDir()
                            val legacyFiles = legacyDir.listFiles()?.toList() ?: emptyList()

                            fun mergedFiles(newDir: File): List<File> {
                                val newFiles = newDir.listFiles()?.toList() ?: emptyList()
                                return (newFiles + legacyFiles)
                                    .filter { it.isFile }
                                    .distinctBy { it.name }
                            }

                            val chatFiles = mergedFiles(OperitBackupDirs.chatDir())
                            val characterCardFiles = mergedFiles(OperitBackupDirs.characterCardsDir())
                            val memoryFiles = mergedFiles(OperitBackupDirs.memoryDir())
                            val modelConfigFiles = mergedFiles(OperitBackupDirs.modelConfigDir())
                            val roomDbFiles = mergedFiles(OperitBackupDirs.roomDbDir())

                            chatBackupFileCount = chatFiles.count { file ->
                                file.name.startsWith("chat_backup_") && file.extension == "json" ||
                                    file.name.startsWith("chat_export_") && file.extension in listOf("json", "md", "html", "txt")
                            }

                            characterCardBackupFileCount = characterCardFiles.count { file ->
                                file.name.startsWith("character_cards_backup_") && file.extension == "json"
                            }

                            memoryBackupFileCount = memoryFiles.count { file ->
                                file.name.startsWith("memory_backup_") && file.extension == "json"
                            }

                            modelConfigBackupFileCount = modelConfigFiles.count { file ->
                                file.name.startsWith("model_config_backup_") && file.extension == "json"
                            }

                            roomDbBackupFileCount = roomDbFiles.count { file ->
                                RoomDatabaseRestoreManager.isRoomDatabaseBackupFile(file.name)
                            }

                            recentRoomDbBackups = RoomDatabaseRestoreManager.listRecentBackups(context, limit = 3)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isScanning = false
                        }
                    }
                }
            )
        }
        item {
            DataManagementCard(
                totalChatCount = totalChatCount,
                operationState = operationState,
                operationMessage = operationMessage,
                onExport = {
                    // 显示格式选择对话框
                    showExportFormatDialog = true
                },
                onImport = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"  // 接受所有类型
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "application/json",
                            "text/markdown",
                            "text/plain",
                            "text/csv"
                        ))
                    }
                    chatFilePickerLauncher.launch(intent)
                },
                onDelete = { showDeleteConfirmDialog = true }
            )
        }
        item {
            CharacterCardManagementCard(
                totalCharacterCardCount = totalCharacterCardCount,
                operationState = characterCardOperationState,
                operationMessage = characterCardOperationMessage,
                onExport = {
                    scope.launch {
                        characterCardOperationState = CharacterCardOperation.EXPORTING
                        characterCardOperationMessage = ""
                        try {
                            val filePath = characterCardManager.exportAllCharacterCardsToBackupFile()
                            if (filePath != null) {
                                characterCardOperationState = CharacterCardOperation.EXPORTED
                                characterCardOperationMessage = context.getString(
                                    R.string.backup_character_cards_export_result_success,
                                    totalCharacterCardCount,
                                    filePath
                                )
                            } else {
                                characterCardOperationState = CharacterCardOperation.FAILED
                                characterCardOperationMessage =
                                    context.getString(R.string.backup_export_failed_create_file)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            characterCardOperationState = CharacterCardOperation.FAILED
                            characterCardOperationMessage = context.getString(
                                R.string.backup_export_failed_with_reason,
                                e.localizedMessage ?: e.toString()
                            )
                        }
                    }
                },
                onImport = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    }
                    characterCardFilePickerLauncher.launch(intent)
                }
            )
        }
        item {
            MemoryManagementCard(
                totalMemoryCount = totalMemoryCount,
                totalLinkCount = totalMemoryLinkCount,
                operationState = memoryOperationState,
                operationMessage = memoryOperationMessage,
                onExport = { showExportProfileDialog = true },
                onImport = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    }
                    memoryFilePickerLauncher.launch(intent)
                }
            )
        }
        item {
            ModelConfigManagementCard(
                totalConfigCount = totalModelConfigCount,
                operationState = modelConfigOperationState,
                operationMessage = modelConfigOperationMessage,
                onExport = {
                    scope.launch {
                        modelConfigOperationState = ModelConfigOperation.EXPORTING
                        try {
                            val jsonContent = modelConfigManager.exportAllConfigs()
                            val exportDir = OperitBackupDirs.modelConfigDir()
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                            val timestamp = dateFormat.format(Date())
                            val exportFile = File(exportDir, "model_config_backup_$timestamp.json")
                            exportFile.writeText(jsonContent)

                            // 导出成功，显示安全警告对话框
                            exportedModelConfigPath = exportFile.absolutePath
                            showModelConfigExportWarning = true
                            modelConfigOperationState = ModelConfigOperation.EXPORTED
                        } catch (e: Exception) {
                            e.printStackTrace()
                            modelConfigOperationState = ModelConfigOperation.FAILED
                            modelConfigOperationMessage = context.getString(
                                R.string.backup_export_failed_with_reason,
                                e.localizedMessage ?: e.toString()
                            )
                        }
                    }
                },
                onImport = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    }
                    modelConfigFilePickerLauncher.launch(intent)
                }
            )
        }
        item {
            FaqCard()
        }

        item {
            SettingsCard(backgroundColor = rememberSettingsCardColor(), contentPadding = PaddingValues(0.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionHeader(
                        title = stringResource(R.string.backup_room_db_title),
                        subtitle = stringResource(R.string.backup_room_db_subtitle, roomDbMaxBackupCount),
                        icon = Icons.Default.Storage
                    )

                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.backup_room_db_low_level_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.backup_room_db_enable_daily),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.backup_room_db_enable_daily_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isRoomDbDailyBackupEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    roomDbBackupPreferences.setDailyBackupEnabled(enabled)
                                    if (enabled) {
                                        RoomDatabaseBackupScheduler.ensureScheduled(context)
                                    } else {
                                        RoomDatabaseBackupScheduler.cancelScheduled(context)
                                    }
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.backup_room_db_max_backup_count),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.backup_room_db_max_backup_count_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    val next = (roomDbMaxBackupCount - 1).coerceAtLeast(1)
                                    scope.launch {
                                        roomDbBackupPreferences.setMaxBackupCount(next)
                                        RoomDatabaseBackupManager.pruneExcessBackups(context)
                                        isScanning = true
                                        try {
                                            val legacyDir = OperitBackupDirs.operitRootDir()
                                            val legacyFiles = legacyDir.listFiles()?.toList() ?: emptyList()
                                            val newFiles = OperitBackupDirs.roomDbDir().listFiles()?.toList() ?: emptyList()
                                            val roomDbFiles = (newFiles + legacyFiles)
                                                .filter { it.isFile }
                                                .distinctBy { it.name }
                                            roomDbBackupFileCount = roomDbFiles.count { file ->
                                                RoomDatabaseRestoreManager.isRoomDatabaseBackupFile(file.name)
                                            }
                                            recentRoomDbBackups = RoomDatabaseRestoreManager.listRecentBackups(context, limit = 3)
                                        } finally {
                                            isScanning = false
                                        }
                                    }
                                },
                                enabled = roomDbMaxBackupCount > 1
                            ) { Text("-") }

                            Text(text = roomDbMaxBackupCount.toString())

                            TextButton(
                                onClick = {
                                    val next = (roomDbMaxBackupCount + 1).coerceAtMost(100)
                                    scope.launch {
                                        roomDbBackupPreferences.setMaxBackupCount(next)
                                    }
                                },
                                enabled = roomDbMaxBackupCount < 100
                            ) { Text("+") }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ManagementButton(
                            text = stringResource(R.string.backup_room_db_backup_now),
                            icon = Icons.Default.CloudDownload,
                            onClick = {
                                scope.launch {
                                    roomDbBackupOperationState = RoomDatabaseBackupOperation.BACKING_UP
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            RoomDatabaseBackupManager.backupIfNeeded(context, force = true)
                                        }
                                        roomDbBackupOperationState = RoomDatabaseBackupOperation.SUCCESS
                                        roomDbBackupOperationMessage =
                                            result.backupFile?.absolutePath
                                                ?: context.getString(R.string.backup_operation_failed)

                                        isScanning = true
                                        try {
                                            val legacyDir = OperitBackupDirs.operitRootDir()
                                            val legacyFiles = legacyDir.listFiles()?.toList() ?: emptyList()
                                            val newFiles = OperitBackupDirs.roomDbDir().listFiles()?.toList() ?: emptyList()
                                            val roomDbFiles = (newFiles + legacyFiles)
                                                .filter { it.isFile }
                                                .distinctBy { it.name }

                                            roomDbBackupFileCount = roomDbFiles.count { file ->
                                                RoomDatabaseRestoreManager.isRoomDatabaseBackupFile(file.name)
                                            }

                                            recentRoomDbBackups = RoomDatabaseRestoreManager.listRecentBackups(context, limit = 3)
                                        } finally {
                                            isScanning = false
                                        }
                                    } catch (e: Exception) {
                                        roomDbBackupOperationState = RoomDatabaseBackupOperation.FAILED
                                        roomDbBackupOperationMessage = e.localizedMessage ?: e.toString()
                                        try {
                                            roomDbBackupPreferences.markFailure(roomDbBackupOperationMessage)
                                        } catch (_: Exception) {

                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        ManagementButton(
                            text = stringResource(R.string.backup_room_db_restore_from_file),
                            icon = Icons.Default.FileOpen,
                            onClick = {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip"))
                                }
                                roomDbRestoreFilePickerLauncher.launch(intent)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            isWarning = true
                        )
                    }

                    AnimatedVisibility(visible = recentRoomDbBackups.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.backup_room_db_recent_auto_backups),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            recentRoomDbBackups.forEach { file ->
                                RoomDbBackupListItem(
                                    file = file,
                                    onRestoreClick = {
                                        pendingRoomDbRestoreFile = file
                                        pendingRoomDbRestoreUri = null
                                        showRoomDbRestoreConfirmDialog = true
                                    }
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = roomDbBackupOperationState != RoomDatabaseBackupOperation.IDLE) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (roomDbBackupOperationState) {
                                RoomDatabaseBackupOperation.BACKING_UP ->
                                    OperationProgressView(message = stringResource(R.string.backup_room_db_backing_up))
                                RoomDatabaseBackupOperation.SUCCESS ->
                                    OperationResultCard(
                                        title = stringResource(R.string.backup_export_success),
                                        message = roomDbBackupOperationMessage,
                                        icon = Icons.Default.CloudDownload
                                    )
                                RoomDatabaseBackupOperation.FAILED ->
                                    OperationResultCard(
                                        title = stringResource(R.string.backup_operation_failed),
                                        message = roomDbBackupOperationMessage,
                                        icon = Icons.Default.Info,
                                        isError = true
                                    )
                                else -> {}
                            }
                        }
                    }

                    AnimatedVisibility(visible = roomDbRestoreOperationState != RoomDatabaseRestoreOperation.IDLE) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (roomDbRestoreOperationState) {
                                RoomDatabaseRestoreOperation.RESTORING ->
                                    OperationProgressView(message = stringResource(R.string.backup_room_db_restoring))
                                RoomDatabaseRestoreOperation.SUCCESS ->
                                    OperationResultCard(
                                        title = stringResource(R.string.backup_room_db_restore_success),
                                        message = roomDbRestoreOperationMessage,
                                        icon = Icons.Default.Restore
                                    )
                                RoomDatabaseRestoreOperation.FAILED ->
                                    OperationResultCard(
                                        title = stringResource(R.string.backup_room_db_restore_failed),
                                        message = roomDbRestoreOperationMessage,
                                        icon = Icons.Default.Info,
                                        isError = true
                                    )
                                else -> {}
                            }
                        }
                    }

                    val timeText = remember(roomDbLastSuccessTime) {
                        if (roomDbLastSuccessTime <= 0L) {
                            "-"
                        } else {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(roomDbLastSuccessTime))
                        }
                    }
                    Text(
                        text = stringResource(R.string.backup_room_db_last_success, timeText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (roomDbLastError.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.backup_room_db_last_error, roomDbLastError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(backgroundColor = rememberSettingsCardColor(), contentPadding = PaddingValues(0.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionHeader(
                        title = stringResource(R.string.backup_raw_snapshot_title),
                        subtitle = stringResource(R.string.backup_raw_snapshot_subtitle),
                        icon = Icons.Default.Storage
                    )

                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.backup_raw_snapshot_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ManagementButton(
                            text = stringResource(R.string.backup_raw_snapshot_backup_now),
                            icon = Icons.Default.CloudDownload,
                            onClick = {
                                scope.launch {
                                    rawSnapshotOperationState = RawSnapshotOperation.BACKING_UP
                                    rawSnapshotOperationMessage = context.getString(R.string.backup_raw_snapshot_progress_preparing)
                                    try {
                                        val outFile = RawSnapshotBackupManager.exportToBackupDir(
                                            context = context,
                                            onProgress = { progress ->
                                                val suffix = progress.percent?.let { " ${it}%" } ?: ""
                                                rawSnapshotOperationMessage = when (progress.stage) {
                                                    RawSnapshotBackupManager.ExportProgress.PREPARING ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_preparing)

                                                    RawSnapshotBackupManager.ExportProgress.SCANNING_FILES ->
                                                        progress.scannedFiles?.let { scanned ->
                                                            context.getString(
                                                                R.string.backup_raw_snapshot_progress_scanning_files_with_count,
                                                                scanned
                                                            )
                                                        } ?: context.getString(R.string.backup_raw_snapshot_progress_scanning_files)

                                                    RawSnapshotBackupManager.ExportProgress.ZIPPING_FILES ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_zipping_files) + suffix

                                                    RawSnapshotBackupManager.ExportProgress.ZIPPING_EXTERNAL_FILES ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_zipping_external_files) + suffix

                                                    RawSnapshotBackupManager.ExportProgress.ZIPPING_SHARED_PREFS ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_zipping_shared_prefs)

                                                    RawSnapshotBackupManager.ExportProgress.ZIPPING_DATASTORE ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_zipping_datastore)

                                                    RawSnapshotBackupManager.ExportProgress.ZIPPING_DATABASES ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_zipping_databases)

                                                    RawSnapshotBackupManager.ExportProgress.FINALIZING ->
                                                        context.getString(R.string.backup_raw_snapshot_progress_finalizing)
                                                }
                                            }
                                        )
                                        rawSnapshotOperationState = RawSnapshotOperation.BACKUP_SUCCESS
                                        rawSnapshotOperationMessage = outFile.absolutePath
                                    } catch (e: Exception) {
                                        rawSnapshotOperationState = RawSnapshotOperation.FAILED
                                        rawSnapshotOperationMessage = e.localizedMessage ?: e.toString()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            enabled = rawSnapshotOperationState != RawSnapshotOperation.BACKING_UP
                        )

                        ManagementButton(
                            text = stringResource(R.string.backup_raw_snapshot_restore_from_file),
                            icon = Icons.Default.FileOpen,
                            onClick = {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip"))
                                }
                                rawSnapshotRestoreFilePickerLauncher.launch(intent)
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            isWarning = true,
                            enabled = rawSnapshotOperationState != RawSnapshotOperation.BACKING_UP
                        )
                    }

                    if (officialOperitMigrationAvailable) {
                        HorizontalDivider()
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.backup_official_operit_migration_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ManagementButton(
                            text = stringResource(R.string.backup_official_operit_migration_action),
                            icon = Icons.Default.CloudUpload,
                            onClick = {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip"))
                                }
                                officialOperitMigrationFilePickerLauncher.launch(intent)
                            },
                            isWarning = true,
                            enabled = rawSnapshotOperationState != RawSnapshotOperation.BACKING_UP
                        )
                    }

                    AnimatedVisibility(visible = rawSnapshotOperationState != RawSnapshotOperation.IDLE) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (rawSnapshotOperationState) {
                                RawSnapshotOperation.BACKING_UP ->
                                    OperationProgressView(
                                        message = rawSnapshotOperationMessage.ifBlank {
                                            stringResource(R.string.backup_raw_snapshot_backing_up)
                                        }
                                    )
                                RawSnapshotOperation.BACKUP_SUCCESS ->
                                    OperationResultCard(
                                        title = stringResource(R.string.backup_export_success),
                                        message = rawSnapshotOperationMessage,
                                        icon = Icons.Default.CloudDownload
                                    )
                                RawSnapshotOperation.FAILED ->
                                    OperationResultCard(
                                        title = stringResource(R.string.backup_operation_failed),
                                        message = rawSnapshotOperationMessage,
                                        icon = Icons.Default.Info,
                                        isError = true
                                    )
                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard(backgroundColor = rememberSettingsCardColor(), contentPadding = PaddingValues(0.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionHeader(
                        title = stringResource(R.string.stats_quarantine_title),
                        subtitle = stringResource(R.string.stats_quarantine_subtitle),
                        icon = Icons.Default.Info
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(
                            R.string.stats_quarantine_desc,
                            formatBytes(TokenStatSpool.MAX_QUARANTINE_BYTES)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val quarantineInfoText = buildString {
                        if (quarantineEvidenceCount > 0) {
                            append(
                                stringResource(
                                    R.string.stats_quarantine_info,
                                    quarantineEvidenceCount,
                                    formatBytes(quarantineEvidenceBytes)
                                )
                            )
                        }
                        // P1-3：未完成删除事务（stuck ack trash）单独显示，计数已计入总量；
                        // 确认删除全部证据时会一并清除
                        if (quarantineStuckTrashCount > 0) {
                            if (isNotEmpty()) append("\n")
                            append(
                                stringResource(
                                    R.string.stats_quarantine_stuck_trash_info,
                                    quarantineStuckTrashCount,
                                    formatBytes(quarantineStuckTrashBytes)
                                )
                            )
                        }
                        if (quarantineSummaryCount > 0) {
                            if (isNotEmpty()) append("\n")
                            append(
                                stringResource(
                                    R.string.stats_quarantine_summary_info,
                                    quarantineSummaryCount,
                                    formatBytes(quarantineSummaryBytes)
                                )
                            )
                        }
                        if (isEmpty()) {
                            append(stringResource(R.string.stats_quarantine_info_empty))
                        }
                    }
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = quarantineInfoText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (quarantineOperationMessage.isNotBlank()) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = quarantineOperationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (quarantineOperationFailed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ManagementButton(
                            text = stringResource(R.string.stats_quarantine_export),
                            icon = Icons.Default.CloudDownload,
                            onClick = {
                                // P2：主线程立即置 busy（防双击，状态写先于协程启动）；
                                // 目录名 = 可读时间戳前缀 + UUID（并发导出各自唯一，毫秒级
                                // 碰撞不可能撞目录）；目录用 Files.createDirectory 独占创建
                                // （已存在即失败，绝不复用旧导出目录）；失败/取消只清理本轮
                                // 成功创建的独占目录。
                                if (quarantineBusy) return@ManagementButton
                                quarantineBusy = true
                                quarantineOperationFailed = false
                                val baseDir = OperitBackupDirs.rawSnapshotDir()
                                val destination =
                                    File(
                                        baseDir,
                                        "token_stats_quarantine_" +
                                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                                .format(Date()) +
                                            "_" + UUID.randomUUID().toString().replace("-", "")
                                    )
                                var directoryCreatedByThisRun = false
                                scope.launch {
                                    try {
                                        try {
                                            Files.createDirectory(destination.toPath())
                                        } catch (e: FileAlreadyExistsException) {
                                            throw IOException(
                                                "quarantine export directory already exists: " +
                                                    destination.absolutePath,
                                                e,
                                            )
                                        }
                                        directoryCreatedByThisRun = true
                                        // 文件复制/fsync 在 spool 内部切到 IO 线程（P2-2）
                                        TokenStatSpool.exportQuarantineEvidence(context, destination)
                                        quarantineOperationMessage =
                                            context.getString(
                                                R.string.stats_quarantine_export_success,
                                                destination.absolutePath
                                            )
                                    } catch (e: CancellationException) {
                                        // P2：已取消也要完成有界清理（NonCancellable+IO），
                                        // 清理失败/异常记录日志，随后重抛取消
                                        val cleaned = try {
                                            cleanupQuarantineExportDirectory(
                                                destination,
                                                directoryCreatedByThisRun,
                                                nonCancellable = true,
                                            )
                                        } catch (cleanupError: Exception) {
                                            AppLogger.e(
                                                "ChatBackupSettings",
                                                "quarantine export cancelled; cleanup failed: " +
                                                    destination.absolutePath,
                                                cleanupError,
                                            )
                                            false
                                        }
                                        if (!cleaned) {
                                            AppLogger.e(
                                                "ChatBackupSettings",
                                                "quarantine export cancelled; cleanup failed: " +
                                                    destination.absolutePath,
                                            )
                                        }
                                        throw e
                                    } catch (e: Exception) {
                                        quarantineOperationFailed = true
                                        val cleaned = try {
                                            cleanupQuarantineExportDirectory(
                                                destination,
                                                directoryCreatedByThisRun,
                                            )
                                        } catch (cleanupError: Exception) {
                                            AppLogger.e(
                                                "ChatBackupSettings",
                                                "quarantine export cleanup failed: " +
                                                    destination.absolutePath,
                                                cleanupError,
                                            )
                                            false
                                        }
                                        val reason = e.localizedMessage ?: e.toString()
                                        quarantineOperationMessage =
                                            if (cleaned) {
                                                context.getString(
                                                    R.string.stats_quarantine_export_failed,
                                                    reason,
                                                )
                                            } else {
                                                context.getString(
                                                    R.string.stats_quarantine_export_failed_cleanup,
                                                    reason,
                                                    destination.absolutePath,
                                                )
                                            }
                                    } finally {
                                        quarantineBusy = false
                                        refreshQuarantineInfo()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            enabled = !quarantineBusy
                        )
                        ManagementButton(
                            text = stringResource(R.string.stats_quarantine_delete),
                            icon = Icons.Default.Delete,
                            onClick = { showQuarantineDeleteConfirmDialog = true },
                            modifier = Modifier.weight(1f, fill = false),
                            isDestructive = true,
                            enabled =
                                !quarantineBusy &&
                                    (quarantineEvidenceCount > 0 || quarantineSummaryCount > 0)
                        )
                    }
                }
            }
        }
    }

    if (showQuarantineDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showQuarantineDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.stats_quarantine_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.stats_quarantine_delete_confirm_message,
                        quarantineEvidenceCount,
                        formatBytes(quarantineEvidenceBytes),
                        quarantineSummaryCount,
                        formatBytes(quarantineSummaryBytes),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showQuarantineDeleteConfirmDialog = false
                        scope.launch {
                            quarantineBusy = true
                            quarantineOperationFailed = false
                            try {
                                // 扫描/删除在 spool 内部切到 IO 线程（P2-2）
                                val names =
                                    withContext(Dispatchers.IO) {
                                        TokenStatSpool.quarantineEvidence(context).map { it.name }.toSet()
                                    }
                                TokenStatSpool.acknowledgeAndDeleteQuarantine(
                                    context = context,
                                    names = names,
                                    deleteSummary = quarantineSummaryCount > 0,
                                )
                                quarantineOperationMessage =
                                    context.getString(
                                        R.string.stats_quarantine_delete_success,
                                        names.size,
                                        quarantineSummaryCount,
                                    )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                quarantineOperationFailed = true
                                quarantineOperationMessage =
                                    context.getString(
                                        R.string.stats_quarantine_delete_failed,
                                        e.localizedMessage ?: e.toString()
                                    )
                            } finally {
                                quarantineBusy = false
                                refreshQuarantineInfo()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.stats_quarantine_delete_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuarantineDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.stats_quarantine_delete_cancel_action))
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        DeleteConfirmationDialog(
            onDismiss = { showDeleteConfirmDialog = false },
            onConfirm = {
                showDeleteConfirmDialog = false
                scope.launch {
                    operationState = ChatHistoryOperation.DELETING
                    try {
                        val result = deleteAllChatHistories(context)
                        operationState = ChatHistoryOperation.DELETED
                        val skippedText = if (result.skippedLockedCount > 0) {
                            context.getString(
                                R.string.backup_delete_skipped_locked,
                                result.skippedLockedCount
                            )
                        } else {
                            ""
                        }
                        operationMessage =
                            context.getString(
                                R.string.backup_delete_result_success,
                                result.deletedCount
                            ) + skippedText
                    } catch (e: Exception) {
                        operationState = ChatHistoryOperation.FAILED
                        operationMessage = context.getString(
                            R.string.backup_clear_failed_with_reason,
                            e.localizedMessage ?: e.toString()
                        )
                    }
                }
            }
        )
    }

    if (showMemoryImportStrategyDialog) {
        MemoryImportStrategyDialog(
            onDismiss = {
                showMemoryImportStrategyDialog = false
                pendingMemoryImportUri = null
            },
            onConfirm = { strategy ->
                showMemoryImportStrategyDialog = false
                val uri = pendingMemoryImportUri
                pendingMemoryImportUri = null

                if (uri != null) {
                    scope.launch {
                        memoryOperationState = MemoryOperation.IMPORTING
                        try {
                            val importRepo = MemoryRepository(context, selectedImportProfileId)
                            val result = importMemoriesFromUri(context, importRepo, uri, strategy)
                            memoryOperationState = MemoryOperation.IMPORTED
                            val profileName = allProfiles.find { it.id == selectedImportProfileId }?.name
                                ?: selectedImportProfileId
                            memoryOperationMessage = context.getString(
                                R.string.backup_memory_import_result_success,
                                profileName,
                                result.newMemories,
                                result.updatedMemories,
                                result.skippedMemories,
                                result.newLinks
                            )

                            if (selectedImportProfileId == activeProfileId) {
                                val repo = memoryRepo
                                if (repo != null) {
                                    val memories = repo.searchMemories("")
                                    totalMemoryCount = memories.count { !it.isDocumentNode }
                                    val graph = repo.getMemoryGraph()
                                    totalMemoryLinkCount = graph.edges.size
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            memoryOperationState = MemoryOperation.FAILED
                            memoryOperationMessage = context.getString(
                                R.string.backup_import_failed_with_reason,
                                e.localizedMessage ?: e.toString()
                            )
                        }
                    }
                }
            }
        )
    }

    if (showExportProfileDialog) {
        ProfileSelectionDialog(
            title = stringResource(R.string.select_export_profile),
            profiles = allProfiles,
            selectedProfileId = selectedExportProfileId,
            onProfileSelected = { selectedExportProfileId = it },
            onDismiss = { showExportProfileDialog = false },
            onConfirm = {
                showExportProfileDialog = false
                scope.launch {
                    memoryOperationState = MemoryOperation.EXPORTING
                    try {
                        val exportRepo = MemoryRepository(context, selectedExportProfileId)
                        val filePath = exportMemories(context, exportRepo)
                        if (filePath != null) {
                            memoryOperationState = MemoryOperation.EXPORTED
                            val profileName = allProfiles.find { it.id == selectedExportProfileId }?.name
                                ?: selectedExportProfileId
                            val memories = exportRepo.searchMemories("")
                            val memoryCount = memories.count { !it.isDocumentNode }
                            val graph = exportRepo.getMemoryGraph()
                            val linkCount = graph.edges.size
                            memoryOperationMessage = context.getString(
                                R.string.backup_memory_export_result_success,
                                profileName,
                                memoryCount,
                                linkCount,
                                filePath
                            )
                        } else {
                            memoryOperationState = MemoryOperation.FAILED
                            memoryOperationMessage =
                                context.getString(R.string.backup_export_failed_create_file)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        memoryOperationState = MemoryOperation.FAILED
                        memoryOperationMessage = context.getString(
                            R.string.backup_export_failed_with_reason,
                            e.localizedMessage ?: e.toString()
                        )
                    }
                }
            }
        )
    }

    if (showImportProfileDialog) {
        ProfileSelectionDialog(
            title = stringResource(R.string.select_import_profile),
            profiles = allProfiles,
            selectedProfileId = selectedImportProfileId,
            onProfileSelected = { selectedImportProfileId = it },
            onDismiss = {
                showImportProfileDialog = false
                pendingMemoryImportUri = null
            },
            onConfirm = {
                showImportProfileDialog = false
                showMemoryImportStrategyDialog = true
            }
        )
    }

    if (showExportFormatDialog) {
        ExportFormatDialog(
            selectedFormat = selectedExportFormat,
            onFormatSelected = { selectedExportFormat = it },
            onDismiss = { showExportFormatDialog = false },
            onConfirm = {
                showExportFormatDialog = false
                scope.launch {
                    operationState = ChatHistoryOperation.EXPORTING
                    try {
                        val filePath = chatHistoryManager.exportChatHistoriesToDownloads(selectedExportFormat)
                        if (filePath != null) {
                            operationState = ChatHistoryOperation.EXPORTED
                            val chatCount =
                                chatHistoryManager.allChatHistoriesInternalFlow.first().size
                            val formatName = when (selectedExportFormat) {
                                ExportFormat.JSON -> context.getString(R.string.backup_format_json)
                                ExportFormat.MARKDOWN -> context.getString(R.string.backup_format_markdown)
                                ExportFormat.HTML -> context.getString(R.string.backup_format_html)
                                ExportFormat.TXT -> context.getString(R.string.backup_format_txt)
                                ExportFormat.CSV -> "CSV"
                            }
                            operationMessage = context.getString(
                                R.string.backup_chat_export_result_success,
                                chatCount,
                                formatName,
                                filePath
                            )
                        } else {
                            operationState = ChatHistoryOperation.FAILED
                            operationMessage =
                                context.getString(R.string.backup_export_failed_create_file)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        operationState = ChatHistoryOperation.FAILED
                        operationMessage = context.getString(
                            R.string.backup_export_failed_with_reason,
                            e.localizedMessage ?: e.toString()
                        )
                    }
                }
            }
        )
    }

    if (showImportFormatDialog) {
        ImportFormatDialog(
            selectedFormat = selectedImportFormat,
            onFormatSelected = { selectedImportFormat = it },
            onDismiss = {
                showImportFormatDialog = false
                pendingImportUri = null
            },
            onConfirm = {
                showImportFormatDialog = false
                pendingImportUri?.let { uri ->
                    scope.launch {
                        operationState = ChatHistoryOperation.IMPORTING
                        try {
                            val importResult = chatHistoryManager.importChatHistoriesFromUri(uri, selectedImportFormat)
                            operationMessage =
                                if (
                                    importResult.total > 0 ||
                                        importResult.foldersCreated > 0
                                ) {
                                operationState = ChatHistoryOperation.IMPORTED
                                val formatName = when (selectedImportFormat) {
                                    ChatFormat.OPERIT -> context.getString(R.string.backup_format_operit)
                                    ChatFormat.CHATGPT -> context.getString(R.string.backup_format_chatgpt)
                                    ChatFormat.CHATBOX -> context.getString(R.string.backup_format_chatbox)
                                    ChatFormat.MARKDOWN -> context.getString(R.string.backup_format_markdown)
                                    ChatFormat.GENERIC_JSON -> context.getString(R.string.backup_format_generic_json)
                                    ChatFormat.CLAUDE -> context.getString(R.string.backup_format_claude)
                                    else -> context.getString(R.string.backup_format_unknown)
                                }
                                val skippedText = if (importResult.skipped > 0) {
                                    context.getString(
                                        R.string.backup_import_result_skipped,
                                        importResult.skipped
                                    )
                                } else {
                                    ""
                                }
                                val foldersCreatedText =
                                    if (importResult.foldersCreated > 0) {
                                        context.getString(
                                            R.string.backup_import_result_folders_created,
                                            importResult.foldersCreated,
                                        )
                                    } else {
                                        ""
                                    }
                                val emptyFoldersWarning =
                                    if (importResult.mayLeavePreviousEmptyFolders) {
                                        context.getString(
                                            R.string.backup_import_result_empty_folders_warning
                                        )
                                    } else {
                                        ""
                                    }
                                context.getString(
                                    R.string.backup_import_result_success,
                                    formatName,
                                    importResult.new,
                                    importResult.updated,
                                    skippedText + foldersCreatedText + emptyFoldersWarning
                                )
                            } else {
                                operationState = ChatHistoryOperation.FAILED
                                context.getString(R.string.backup_import_result_failed)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            operationState = ChatHistoryOperation.FAILED
                            operationMessage = context.getString(
                                R.string.backup_import_failed_with_reason,
                                e.localizedMessage ?: e.toString()
                            )
                        } finally {
                            pendingImportUri = null
                        }
                    }
                }
            }
        )
    }

    // 模型配置导出安全警告对话框
    if (showModelConfigExportWarning) {
        ModelConfigExportWarningDialog(
            exportPath = exportedModelConfigPath,
            onDismiss = {
                showModelConfigExportWarning = false
                modelConfigOperationMessage = context.getString(
                    R.string.backup_export_result_success,
                    exportedModelConfigPath
                )
            }
        )
    }

    if (showRoomDbRestoreConfirmDialog) {
        val targetName = pendingRoomDbRestoreFile?.name
            ?: pendingRoomDbRestoreUri?.lastPathSegment
            ?: "-"

        AlertDialog(
            onDismissRequest = {
                showRoomDbRestoreConfirmDialog = false
                pendingRoomDbRestoreUri = null
                pendingRoomDbRestoreFile = null
            },
            title = { Text(stringResource(R.string.backup_room_db_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_room_db_restore_confirm_message, targetName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRoomDbRestoreConfirmDialog = false
                        val uri = pendingRoomDbRestoreUri
                        val file = pendingRoomDbRestoreFile
                        pendingRoomDbRestoreUri = null
                        pendingRoomDbRestoreFile = null

                        scope.launch {
                            roomDbRestoreOperationState = RoomDatabaseRestoreOperation.RESTORING
                            roomDbRestoreOperationMessage = ""
                            try {
                                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                                    if (file != null) {
                                        RoomDatabaseRestoreManager.stageRestoreFromFile(context, file)
                                    } else if (uri != null) {
                                        try {
                                            context.contentResolver.takePersistableUriPermission(
                                                uri,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            )
                                        } catch (_: Exception) {
                                        }
                                        RoomDatabaseRestoreManager.stageRestoreFromUri(context, uri)
                                    } else {
                                        throw IllegalStateException("No restore target")
                                    }
                                    exitProcess(0)
                                }
                            } catch (e: Exception) {
                                if (RawSnapshotBackupManager.isProcessRestartRequired()) exitProcess(0)
                                roomDbRestoreOperationState = RoomDatabaseRestoreOperation.FAILED
                                roomDbRestoreOperationMessage = e.localizedMessage ?: e.toString()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.backup_room_db_restore_confirm_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRoomDbRestoreConfirmDialog = false
                        pendingRoomDbRestoreUri = null
                        pendingRoomDbRestoreFile = null
                    }
                ) {
                    Text(stringResource(R.string.backup_room_db_restore_cancel_action))
                }
            }
        )
    }

    if (showRawSnapshotRestoreConfirmDialog) {
        val targetName = pendingRawSnapshotRestoreUri?.lastPathSegment ?: "-"
        val uriPermissionFailureMessage =
            stringResource(R.string.backup_raw_snapshot_restore_uri_permission_failed)
        val scheduleFailureMessage =
            stringResource(R.string.backup_raw_snapshot_restore_schedule_failed)

        AlertDialog(
            onDismissRequest = {
                showRawSnapshotRestoreConfirmDialog = false
                pendingRawSnapshotRestoreUri = null
            },
            title = { Text(stringResource(R.string.backup_raw_snapshot_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_raw_snapshot_restore_confirm_message, targetName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRawSnapshotRestoreConfirmDialog = false
                        val uri = pendingRawSnapshotRestoreUri
                        pendingRawSnapshotRestoreUri = null
                        if (uri != null) {
                            scope.launch {
                                try {
                                    try {
                                        context.contentResolver.takePersistableUriPermission(
                                            uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        )
                                    } catch (e: Exception) {
                                        AppLogger.e("ChatBackupSettings", "raw snapshot URI permission failed", e)
                                        rawSnapshotOperationState = RawSnapshotOperation.FAILED
                                        rawSnapshotOperationMessage = uriPermissionFailureMessage
                                        return@launch
                                    }
                                    val persisted = RawSnapshotBackupManager
                                        .setPendingRawSnapshotRestore(context, uri)
                                    if (!persisted) {
                                        rawSnapshotOperationState = RawSnapshotOperation.FAILED
                                        rawSnapshotOperationMessage = scheduleFailureMessage
                                        return@launch
                                    }
                                    // No live preference/database file has been replaced. Exit
                                    // immediately; the next cold start applies the archive before
                                    // Application initialization creates any cached writer.
                                    restartApplication(context)
                                } catch (e: Exception) {
                                    AppLogger.e("ChatBackupSettings", "raw snapshot staging failed", e)
                                    rawSnapshotOperationState = RawSnapshotOperation.FAILED
                                    rawSnapshotOperationMessage = scheduleFailureMessage
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.backup_raw_snapshot_restore_confirm_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRawSnapshotRestoreConfirmDialog = false
                        pendingRawSnapshotRestoreUri = null
                    }
                ) {
                    Text(stringResource(R.string.backup_raw_snapshot_restore_cancel_action))
                }
            }
        )
    }

    if (showOfficialOperitMigrationConfirmDialog) {
        val targetName = pendingOfficialOperitMigrationUri?.lastPathSegment ?: "-"

        AlertDialog(
            onDismissRequest = {
                showOfficialOperitMigrationConfirmDialog = false
                pendingOfficialOperitMigrationUri = null
            },
            title = { Text(stringResource(R.string.backup_official_operit_migration_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_official_operit_migration_confirm_message,
                        targetName
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOfficialOperitMigrationConfirmDialog = false
                        val uri = pendingOfficialOperitMigrationUri
                        pendingOfficialOperitMigrationUri = null
                        if (uri != null) {
                            // Persist the URI permission so it survives the process restart and
                            // can be read during the dedicated cold-start migration phase. If
                            // this fails (e.g. the granting app revoked permission), do NOT
                            // proceed: the cold-start migration would be unable to read the zip.
                            try {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: Exception) {
                                AppLogger.e("ChatBackupSettings", "takePersistableUriPermission failed", e)
                                officialOperitMigrationErrorResId =
                                    R.string.backup_official_operit_migration_error_uri_permission
                                return@TextButton
                            }
                            // Persist the pending migration request. If the state write fails,
                            // do NOT exit the process: the cold start would not observe a
                            // pending state and the migration would never run.
                            val persisted = RawSnapshotBackupManager.setPendingOfficialOperitMigration(context, uri)
                            if (!persisted) {
                                officialOperitMigrationErrorResId =
                                    R.string.backup_official_operit_migration_error_state_write
                                return@TextButton
                            }
                            officialOperitMigrationAvailable = false
                            restartApplication(context)
                        }
                    }
                ) {
                    Text(stringResource(R.string.backup_official_operit_migration_confirm_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOfficialOperitMigrationConfirmDialog = false
                        pendingOfficialOperitMigrationUri = null
                    }
                ) {
                    Text(stringResource(R.string.backup_raw_snapshot_restore_cancel_action))
                }
            }
        )
    }

    officialOperitMigrationErrorResId?.let { errorResId ->
        AlertDialog(
            onDismissRequest = { officialOperitMigrationErrorResId = null },
            title = { Text(stringResource(R.string.backup_official_operit_migration_error_title)) },
            text = { Text(stringResource(errorResId)) },
            confirmButton = {
                TextButton(onClick = { officialOperitMigrationErrorResId = null }) {
                    Text(stringResource(R.string.backup_raw_snapshot_restore_cancel_action))
                }
            }
        )
    }

    if (showRoomDbRestoreRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRoomDbRestoreRestartDialog = false },
            title = { Text(stringResource(R.string.backup_room_db_restart_title)) },
            text = { Text(stringResource(R.string.backup_room_db_restart_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRoomDbRestoreRestartDialog = false
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        context.startActivity(intent)
                        exitProcess(0)
                    }
                ) {
                    Text(stringResource(R.string.backup_room_db_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRoomDbRestoreRestartDialog = false }) {
                    Text(stringResource(R.string.backup_room_db_restart_later))
                }
            }
        )
    }

}

private fun restartApplication(context: Context) {
    try {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    } finally {
        exitProcess(0)
    }
}

private data class DeleteAllChatsResult(
    val deletedCount: Int,
    val skippedLockedCount: Int
)

private suspend fun deleteAllChatHistories(context: Context): DeleteAllChatsResult =
    withContext(Dispatchers.IO) {
        try {
            val chatHistoryManager = ChatHistoryManager.getInstance(context)
            val chatHistories = chatHistoryManager.allChatHistoriesInternalFlow.first()
            var deletedCount = 0
            var skippedLockedCount = 0

            for (chatHistory in chatHistories) {
                val deleted = chatHistoryManager.deleteChatHistory(chatHistory.id)
                if (deleted) {
                    deletedCount++
                } else {
                    skippedLockedCount++
                }
            }

            return@withContext DeleteAllChatsResult(
                deletedCount = deletedCount,
                skippedLockedCount = skippedLockedCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

private suspend fun exportMemories(_context: Context, memoryRepository: MemoryRepository): String? =
    withContext(Dispatchers.IO) {
        try {
            val jsonString = memoryRepository.exportMemoriesToJson()

            val exportDir = OperitBackupDirs.memoryDir()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val exportFile = File(exportDir, "memory_backup_$timestamp.json")

            exportFile.writeText(jsonString)

            return@withContext exportFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

private suspend fun importMemoriesFromUri(
    context: Context,
    memoryRepository: MemoryRepository,
    uri: Uri,
    strategy: ImportStrategy
) = withContext(Dispatchers.IO) {
    val inputStream = context.contentResolver.openInputStream(uri)
        ?: throw Exception(context.getString(R.string.backup_open_file_failed))
    val jsonString = inputStream.bufferedReader().use { it.readText() }
    inputStream.close()

    if (jsonString.isBlank()) {
        throw Exception(context.getString(R.string.backup_import_file_empty))
    }

    memoryRepository.importMemoriesFromJson(jsonString, strategy)
}

