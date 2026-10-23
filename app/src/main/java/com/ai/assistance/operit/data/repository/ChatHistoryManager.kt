package com.ai.assistance.operit.data.repository

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException

import com.ai.assistance.operit.data.model.toChatHistory
import com.ai.assistance.operit.data.model.fromChatHistory
import com.ai.assistance.operit.data.model.toChatMessage
import com.ai.assistance.operit.data.model.fromChatMessage
import com.ai.assistance.operit.data.model.applyTo

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.ai.assistance.operit.util.AppLogger
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.agent.SubagentCoordinator
import com.ai.assistance.operit.data.backup.OperitBackupDirs
import com.ai.assistance.operit.data.dao.ChatDao
import com.ai.assistance.operit.data.dao.ChatFolderDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import com.ai.assistance.operit.data.model.ChatTodoPriority
import com.ai.assistance.operit.data.model.ChatTodoStatus
import com.ai.assistance.operit.data.model.CharacterCardChatStats
import com.ai.assistance.operit.data.model.CharacterGroupChatStats
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import com.ai.assistance.operit.data.model.SYSTEM_UNGROUPED_FOLDER_ID
import com.ai.assistance.operit.data.model.OperitArchivedChat
import com.ai.assistance.operit.data.model.OperitArchivedFolder
import com.ai.assistance.operit.data.model.OperitArchivedMessage
import com.ai.assistance.operit.data.model.OperitArchivedMessageVariant
import com.ai.assistance.operit.data.model.OperitArchivedSubagentRun
import com.ai.assistance.operit.data.model.OperitArchivedTodo
import com.ai.assistance.operit.data.model.OperitChatArchive
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.data.model.WorkspaceRenameResult
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.data.converter.*
import com.ai.assistance.operit.data.exporter.*
import com.google.gson.GsonBuilder
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.io.BufferedWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// 仅保留这个DataStore用于存储当前聊天ID
private val Context.currentChatIdDataStore by preferencesDataStore(name = "current_chat_id")

internal fun normalizeChatFolderId(folderId: String?): String? =
    folderId.takeUnless { it == SYSTEM_UNGROUPED_FOLDER_ID }

internal suspend fun createAndPersistNewChatHistory(
    chatDao: ChatDao,
    chatFolderDao: ChatFolderDao,
    folderId: String?,
    inheritGroupFromChatId: String?,
    buildHistory: (folderId: String?) -> ChatHistory,
): ChatHistory {
    val requestedFolderId = normalizeChatFolderId(folderId)
    if (requestedFolderId != null) {
        require(chatFolderDao.getFolder(requestedFolderId) != null) {
            "Unknown folderId: $requestedFolderId"
        }
    }
    val finalFolderId =
        when {
            folderId != null -> requestedFolderId
            inheritGroupFromChatId != null ->
                chatDao
                    .getChatById(inheritGroupFromChatId)
                    ?.folderId
                    .let(::normalizeChatFolderId)
            else -> null
        }

    val newHistory = buildHistory(finalFolderId).copy(folderId = finalFolderId)
    chatDao.insertChat(ChatEntity.fromChatHistory(newHistory))
    return newHistory
}

/**
 * 阅读伴侣审计聊天永久隐藏前缀。hiddenReason 以该前缀开头的聊天只能查看/删除，
 * 任何路径都禁止取消隐藏（见 [ChatHistoryManager.setChatHidden] 与 merge 保留规则）。
 */
internal const val READING_COMPANION_PERMANENT_HIDDEN_REASON_PREFIX =
    "READING_COMPANION_AUDIT_"

internal fun ChatHistory.isPermanentHiddenAuditChat(): Boolean =
    isHidden &&
        hiddenReason?.startsWith(READING_COMPANION_PERMANENT_HIDDEN_REASON_PREFIX) == true

internal val OPERIT_ARCHIVE_SUBAGENT_RUN_SNAPSHOT_COLUMNS =
    listOf(
        "id",
        "parentChatId",
        "childChatId",
        "parentToolCallId",
        "agentProfileId",
        "title",
        "status",
        "createdAt",
        "startedAt",
        "completedAt",
        "error",
        "agentConfigSnapshot",
        "modelConfigIdSnapshot",
        "modelIndexSnapshot",
        "toolInvocationCount",
        "externalOwnerType",
        "externalOwnerId",
        "modelRoundCount",
        "archivedAt",
    )

internal val OPERIT_ARCHIVE_CHAT_TODO_SNAPSHOT_COLUMNS =
    listOf("chatId", "position", "content", "status", "priority")

internal const val OPERIT_ARCHIVE_CONTENT_CHUNK_BYTE_COUNT = 65_536
internal const val OPERIT_ARCHIVE_CONTENT_INITIAL_PROJECTION =
    "COALESCE(" +
        "SUBSTR(CAST(content AS BLOB), 1, $OPERIT_ARCHIVE_CONTENT_CHUNK_BYTE_COUNT), X'') " +
        "AS contentChunkBytes, LENGTH(CAST(content AS BLOB)) AS contentByteCount"
internal const val OPERIT_ARCHIVE_CONTENT_CHUNK_EXPRESSION =
    "SUBSTR(CAST(content AS BLOB), ?, ?)"

internal fun materializeOperitArchiveUtf8Content(
    initialChunk: ByteArray,
    contentByteCount: Long,
    entityDescription: String,
    readChunk: (startByte: Long, byteCount: Int) -> ByteArray?,
): String {
    if (contentByteCount <= initialChunk.size.toLong()) {
        return initialChunk.toString(StandardCharsets.UTF_8)
    }

    val content = ByteArrayOutputStream(initialChunk.size * 2)
    content.write(initialChunk)
    var startByte = initialChunk.size.toLong() + 1L
    while (startByte <= contentByteCount) {
        val chunk =
            checkNotNull(readChunk(startByte, OPERIT_ARCHIVE_CONTENT_CHUNK_BYTE_COUNT)) {
                "Archived chat row disappeared while reading content: $entityDescription"
            }
        check(chunk.isNotEmpty()) {
            "Archived chat content ended before its recorded length: $entityDescription"
        }
        content.write(chunk)
        startByte += chunk.size
    }
    check(content.size().toLong() == contentByteCount) {
        "Archived chat content length changed while reading: $entityDescription"
    }
    return content.toByteArray().toString(StandardCharsets.UTF_8)
}

internal fun remapArchivedParentToolCallIds(
    chat: OperitArchivedChat,
    callIdRemap: Map<String, String>,
    taskIdRemap: Map<String, String> = emptyMap(),
): OperitArchivedChat {
    if (callIdRemap.isEmpty() && taskIdRemap.isEmpty()) return chat

    fun remapContent(content: String): String =
        remapPersistedTaskIds(
            callIdRemap.entries.fold(content) { current, (oldCallId, newCallId) ->
                Regex(
                    """(\bcall_id\s*=\s*")${Regex.escape(oldCallId)}(")""",
                    RegexOption.IGNORE_CASE,
                ).replace(current) { match ->
                    match.groupValues[1] + newCallId + match.groupValues[2]
                }
            },
            taskIdRemap,
        )

    return chat.copy(
        messages =
            chat.messages.map { archivedMessage ->
                archivedMessage.copy(
                    baseMessage =
                        archivedMessage.baseMessage.copy(
                            content = remapContent(archivedMessage.baseMessage.content)
                        ),
                    variants =
                        archivedMessage.variants.map { variant ->
                            variant.copy(content = remapContent(variant.content))
                        },
                )
            }
    )
}

class ChatHistoryManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ChatHistoryManager"
        private const val LOCATOR_PREVIEW_CHAR_COUNT = 48

        @Volatile
        private var INSTANCE: ChatHistoryManager? = null

        fun getInstance(context: Context): ChatHistoryManager {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE
                        ?: ChatHistoryManager(context.applicationContext).also { instance ->
                            INSTANCE = instance
                        }
                }
        }
    }

    // 使用Room数据库
    private val database = AppDatabase.getDatabase(context)
    private val chatDao = database.chatDao()
    private val chatFolderDao = database.chatFolderDao()
    private val chatFolderRepository = ChatFolderRepository(database)
    private val chatBranchRepository = ChatBranchRepository(database)
    private val messageDao = database.messageDao()
    private val messageVariantDao = database.messageVariantDao()
    private val chatContentDao = database.chatContentDao()
    private val chatTodoDao = database.chatTodoDao()
    private val subagentRunDao = database.subagentRunDao()
    private val subagentRunRepository = SubagentRunRepository.getInstance(context)
    private val operitArchiveExportMutex = Mutex()
    private val operitArchiveJson =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }

    private data class ImportCounters(
        var newCount: Int = 0,
        var updatedCount: Int = 0,
        var skippedCount: Int = 0,
        var folderCount: Int = 0,
    )

    private data class LegacyFolderBucketKey(
        val characterCardName: String?,
        val characterGroupId: String?,
        val rawGroup: String,
    )

    private data class LegacyFolderResolutionKey(
        val groupName: String,
        val characterCardName: String?,
        val characterGroupId: String?,
    )

    private data class ArchivedChatMetadata(
        val id: String,
        val title: String,
        val folderId: String?,
        val legacyFolderBucketKey: LegacyFolderBucketKey?,
        val displayOrder: Long,
        val createdAt: Long,
        val hasMessages: Boolean,
        val hasFolderIdField: Boolean,
        val parentChatId: String?,
        val chatKind: String?,
        val hasChatKindField: Boolean,
        val hasTodosField: Boolean,
        val isFavorite: Boolean?,
    )

    private data class ScannedFolder(
        val folder: OperitArchivedFolder,
        val hasRequiredV4Fields: Boolean,
    )

    private data class ScannedArchive(
        val archiveType: String,
        val formatVersion: Int,
        val folders: List<ScannedFolder>,
        val chats: List<ArchivedChatMetadata>,
        val subagentRuns: List<OperitArchivedSubagentRun>,
    )

    private fun OperitArchivedChat.legacyFolderBucketKey(): LegacyFolderBucketKey? {
        val rawGroup = group ?: return null
        if (rawGroup.isBlank()) return null
        return LegacyFolderBucketKey(characterCardName, characterGroupId, rawGroup)
    }

    private fun ChatHistory.legacyFolderBucketKey(): LegacyFolderBucketKey? {
        val rawGroup = group ?: return null
        if (rawGroup.isBlank()) return null
        return LegacyFolderBucketKey(characterCardName, characterGroupId, rawGroup)
    }

    private suspend fun findLegacyFolderId(
        key: LegacyFolderBucketKey,
    ): String? =
        withContext(Dispatchers.IO) {
            val normalizedName = key.rawGroup.trim()
            val candidates =
                chatFolderDao.getFolders().filter {
                    it.id != SYSTEM_UNGROUPED_FOLDER_ID && it.name == normalizedName
                }
            if (candidates.isEmpty()) {
                return@withContext null
            }
            val matchingFolderIds =
                chatDao.getAllChatsDirectly()
                    .asSequence()
                    .filter {
                        it.characterCardName == key.characterCardName &&
                            it.characterGroupId == key.characterGroupId
                    }
                    .mapNotNull { normalizeChatFolderId(it.folderId) }
                    .toSet()
            candidates
                .filter { it.id in matchingFolderIds }
                .minWithOrNull(
                    compareBy<ChatFolderEntity> { it.displayOrder }
                        .thenBy { it.createdAt }
                        .thenBy { it.id }
                )
                ?.id
        }

    private suspend fun createLegacyImportFolders(
        chats: List<ArchivedChatMetadata>,
        counters: ImportCounters,
    ): Map<LegacyFolderBucketKey, String> {
        val grouped = chats.mapNotNull { metadata ->
            metadata.legacyFolderBucketKey
                ?.takeIf { metadata.hasMessages }
                ?.let { it to metadata }
        }.groupBy({ it.first }, { it.second })
        if (grouped.isEmpty()) return emptyMap()

        fun compareNullable(left: String?, right: String?): Int =
            when {
                left == null && right == null -> 0
                left == null -> -1
                right == null -> 1
                else -> left.compareTo(right)
            }
        val keyComparator =
            Comparator<LegacyFolderBucketKey> { left, right ->
                compareNullable(left.characterCardName, right.characterCardName)
                    .takeIf { it != 0 }
                    ?: compareNullable(left.characterGroupId, right.characterGroupId)
                        .takeIf { it != 0 }
                    ?: left.rawGroup.compareTo(right.rawGroup)
            }
        val sorted =
            grouped.entries.sortedWith(
                Comparator { left, right ->
                    left.value.minOf { it.displayOrder }
                        .compareTo(right.value.minOf { it.displayOrder })
                        .takeIf { it != 0 }
                        ?: left.value.minOf { it.createdAt }
                            .compareTo(right.value.minOf { it.createdAt })
                            .takeIf { it != 0 }
                        ?: keyComparator.compare(left.key, right.key)
                }
            )
        val existingFolders = chatFolderDao.getFolders()
        val allocatedIds = existingFolders.mapTo(hashSetOf()) { it.id }
        var nextOrder =
            existingFolders.filter { it.parentFolderId == null }.maxOfOrNull { it.displayOrder }
                ?.plus(1) ?: 0L
        return buildMap {
            sorted.forEach { (key, bucketChats) ->
                findLegacyFolderId(key)?.let { existingId ->
                    put(key, existingId)
                    return@forEach
                }
                var id: String
                do {
                    id = java.util.UUID.randomUUID().toString()
                } while (!allocatedIds.add(id))
                val createdAt = bucketChats.minOf { it.createdAt }
                chatFolderDao.insertFolder(
                    ChatFolderEntity(
                        id = id,
                        name = key.rawGroup.trim(),
                        parentFolderId = null,
                        displayOrder = nextOrder++,
                        createdAt = createdAt,
                    )
                )
                counters.folderCount++
                put(key, id)
            }
        }
    }

    private fun hydrateMessages(
        messageEntities: List<MessageEntity>,
        variants: List<MessageVariantEntity>,
    ): List<ChatMessage> {
        val variantsByTimestamp = variants.groupBy { it.messageTimestamp }
        return messageEntities.map { messageEntity ->
            val baseMessage = messageEntity.toChatMessage()
            val messageVariants = variantsByTimestamp[messageEntity.timestamp].orEmpty()
            val variantCount = messageVariants.size + 1
            if (messageEntity.selectedVariantIndex == 0) {
                baseMessage.copy(
                    selectedVariantIndex = 0,
                    variantCount = variantCount,
                )
            } else {
                val selectedVariant =
                    messageVariants.first { it.variantIndex == messageEntity.selectedVariantIndex }
                selectedVariant.applyTo(baseMessage, variantCount)
            }
        }
    }

    private suspend fun hydrateMessages(
        chatId: String,
        messageEntities: List<MessageEntity>,
    ): List<ChatMessage> {
        if (messageEntities.isEmpty()) {
            return emptyList()
        }
        val visibleTimestamps = messageEntities.map { it.timestamp }
        val variants = chatContentDao.getVariantsForMessages(chatId, visibleTimestamps)
        return hydrateMessages(messageEntities, variants)
    }

    private suspend fun loadDisplayHistory(chatHistory: ChatHistory): ChatHistory {
        val messages = loadChatMessages(chatHistory.id)
        return chatHistory.copy(messages = messages)
    }

    private suspend fun loadDisplayHistories(chatHistories: List<ChatHistory>): List<ChatHistory> {
        val completeHistories = mutableListOf<ChatHistory>()
        for (chatHistory in chatHistories) {
            completeHistories.add(loadDisplayHistory(chatHistory))
        }
        return completeHistories
    }

    private suspend fun buildFolderPathsByChatId(
        chatHistories: List<ChatHistory>,
    ): Map<String, String> {
        val folderById = chatFolderDao.getFolders().associateBy { it.id }
        val pathMemo = mutableMapOf<String, String?>()
        fun resolve(folderId: String, visiting: MutableSet<String>): String? {
            pathMemo[folderId]?.let { return it }
            val folder = folderById[folderId] ?: return null
            if (!visiting.add(folderId)) return null
            val parentPath = folder.parentFolderId?.let { resolve(it, visiting) }
            visiting.remove(folderId)
            val path = if (parentPath.isNullOrBlank()) folder.name else "$parentPath / ${folder.name}"
            pathMemo[folderId] = path
            return path
        }
        return chatHistories.mapNotNull { history ->
            val path = history.folderId?.let { resolve(it, mutableSetOf()) }
            if (path == null) null else history.id to path
        }.toMap()
    }

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getLong(index) }

    private fun Cursor.toSnapshotChatEntity(): ChatEntity =
        ChatEntity(
            id = getString(getColumnIndexOrThrow("id")),
            title = getString(getColumnIndexOrThrow("title")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt")),
            updatedAt = getLong(getColumnIndexOrThrow("updatedAt")),
            inputTokens = getInt(getColumnIndexOrThrow("inputTokens")),
            outputTokens = getInt(getColumnIndexOrThrow("outputTokens")),
            currentWindowSize = getInt(getColumnIndexOrThrow("currentWindowSize")),
            group = stringOrNull("group"),
            folderId = stringOrNull("folderId"),
            displayOrder = getLong(getColumnIndexOrThrow("displayOrder")),
            workspace = stringOrNull("workspace"),
            workspaceEnv = stringOrNull("workspaceEnv"),
            parentChatId = stringOrNull("parentChatId"),
            chatKind =
                stringOrNull("chatKind")
                    ?: if (stringOrNull("parentChatId") == null) {
                        ChatKind.NORMAL.name
                    } else {
                        ChatKind.BRANCH.name
                    },
            characterCardName = stringOrNull("characterCardName"),
            characterGroupId = stringOrNull("characterGroupId"),
            locked = getInt(getColumnIndexOrThrow("locked")) != 0,
            pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
            isFavorite = getInt(getColumnIndexOrThrow("isFavorite")) != 0,
            lastMessageAt = longOrNull("lastMessageAt"),
            isHidden = getInt(getColumnIndexOrThrow("isHidden")) != 0,
            hiddenReason = stringOrNull("hiddenReason"),
            contextPolicyJson = stringOrNull("contextPolicyJson"),
        )

    private fun SQLiteDatabase.materializeSnapshotContent(
        initialChunk: ByteArray,
        contentByteCount: Long,
        entityId: Long,
        chunkQuery: String,
    ): String =
        materializeOperitArchiveUtf8Content(
            initialChunk = initialChunk,
            contentByteCount = contentByteCount,
            entityDescription = "entityId=$entityId",
        ) { startByte, byteCount ->
                rawQuery(
                    chunkQuery,
                    arrayOf(
                        startByte.toString(),
                        byteCount.toString(),
                        entityId.toString(),
                    ),
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "Archived chat row disappeared while reading content" }
                    cursor.getBlob(0)
                }
        }

    private fun Cursor.toSnapshotMessageEntity(snapshot: SQLiteDatabase): MessageEntity {
        val messageId = getLong(getColumnIndexOrThrow("messageId"))
        return MessageEntity(
            messageId = messageId,
            chatId = getString(getColumnIndexOrThrow("chatId")),
            sender = getString(getColumnIndexOrThrow("sender")),
            content =
                snapshot.materializeSnapshotContent(
                    initialChunk = getBlob(getColumnIndexOrThrow("contentChunkBytes")),
                    contentByteCount = getLong(getColumnIndexOrThrow("contentByteCount")),
                    entityId = messageId,
                    chunkQuery =
                        "SELECT $OPERIT_ARCHIVE_CONTENT_CHUNK_EXPRESSION " +
                            "FROM messages WHERE messageId = ?",
                ),
            timestamp = getLong(getColumnIndexOrThrow("timestamp")),
            orderIndex = getInt(getColumnIndexOrThrow("orderIndex")),
            roleName = getString(getColumnIndexOrThrow("roleName")),
            selectedVariantIndex = getInt(getColumnIndexOrThrow("selectedVariantIndex")),
            provider = getString(getColumnIndexOrThrow("provider")),
            modelName = getString(getColumnIndexOrThrow("modelName")),
            inputTokens = getInt(getColumnIndexOrThrow("inputTokens")),
            outputTokens = getInt(getColumnIndexOrThrow("outputTokens")),
            cachedInputTokens = getInt(getColumnIndexOrThrow("cachedInputTokens")),
            sentAt = getLong(getColumnIndexOrThrow("sentAt")),
            outputDurationMs = getLong(getColumnIndexOrThrow("outputDurationMs")),
            waitDurationMs = getLong(getColumnIndexOrThrow("waitDurationMs")),
            completedAt = getLong(getColumnIndexOrThrow("completedAt")),
            displayMode = getString(getColumnIndexOrThrow("displayMode")),
            isFavorite = getInt(getColumnIndexOrThrow("isFavorite")) != 0,
        )
    }

    private fun Cursor.toSnapshotMessageVariantEntity(snapshot: SQLiteDatabase): MessageVariantEntity {
        val variantId = getLong(getColumnIndexOrThrow("variantId"))
        return MessageVariantEntity(
            variantId = variantId,
            chatId = getString(getColumnIndexOrThrow("chatId")),
            messageTimestamp = getLong(getColumnIndexOrThrow("messageTimestamp")),
            variantIndex = getInt(getColumnIndexOrThrow("variantIndex")),
            content =
                snapshot.materializeSnapshotContent(
                    initialChunk = getBlob(getColumnIndexOrThrow("contentChunkBytes")),
                    contentByteCount = getLong(getColumnIndexOrThrow("contentByteCount")),
                    entityId = variantId,
                    chunkQuery =
                        "SELECT $OPERIT_ARCHIVE_CONTENT_CHUNK_EXPRESSION " +
                            "FROM message_variants WHERE variantId = ?",
                ),
            roleName = getString(getColumnIndexOrThrow("roleName")),
            provider = getString(getColumnIndexOrThrow("provider")),
            modelName = getString(getColumnIndexOrThrow("modelName")),
            inputTokens = getInt(getColumnIndexOrThrow("inputTokens")),
            outputTokens = getInt(getColumnIndexOrThrow("outputTokens")),
            cachedInputTokens = getInt(getColumnIndexOrThrow("cachedInputTokens")),
            sentAt = getLong(getColumnIndexOrThrow("sentAt")),
            outputDurationMs = getLong(getColumnIndexOrThrow("outputDurationMs")),
            waitDurationMs = getLong(getColumnIndexOrThrow("waitDurationMs")),
            completedAt = getLong(getColumnIndexOrThrow("completedAt")),
        )
    }

    private fun Cursor.toArchivedSubagentRun(): OperitArchivedSubagentRun =
        OperitArchivedSubagentRun(
            id = getString(getColumnIndexOrThrow("id")),
            parentChatId = getString(getColumnIndexOrThrow("parentChatId")),
            childChatId = getString(getColumnIndexOrThrow("childChatId")),
            parentToolCallId = stringOrNull("parentToolCallId"),
            agentProfileId = getString(getColumnIndexOrThrow("agentProfileId")),
            title = getString(getColumnIndexOrThrow("title")),
            status = getString(getColumnIndexOrThrow("status")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt")),
            startedAt = longOrNull("startedAt"),
            completedAt = longOrNull("completedAt"),
            error = stringOrNull("error"),
            agentConfigSnapshot = stringOrNull("agentConfigSnapshot"),
            modelConfigIdSnapshot = stringOrNull("modelConfigIdSnapshot"),
            modelIndexSnapshot =
                getColumnIndexOrThrow("modelIndexSnapshot").let { index ->
                    if (isNull(index)) null else getInt(index)
                },
            toolInvocationCount = getInt(getColumnIndexOrThrow("toolInvocationCount")),
            externalOwnerType = stringOrNull("externalOwnerType"),
            externalOwnerId = stringOrNull("externalOwnerId"),
            modelRoundCount = getInt(getColumnIndexOrThrow("modelRoundCount")),
            archivedAt = longOrNull("archivedAt"),
        )

    private fun buildOperitArchivedChatFromSnapshot(
        snapshot: SQLiteDatabase,
        chatEntity: ChatEntity,
    ): OperitArchivedChat {
        val messageEntities =
            snapshot.rawQuery(
                """
                SELECT messageId, chatId, sender,
                    $OPERIT_ARCHIVE_CONTENT_INITIAL_PROJECTION,
                    timestamp, orderIndex, roleName, selectedVariantIndex, provider, modelName,
                    inputTokens, outputTokens, cachedInputTokens, sentAt, outputDurationMs,
                    waitDurationMs, completedAt, displayMode, isFavorite
                FROM messages WHERE chatId = ? ORDER BY orderIndex
                """.trimIndent(),
                arrayOf(chatEntity.id),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toSnapshotMessageEntity(snapshot))
                }
            }
        val variantsByTimestamp =
            snapshot.rawQuery(
                """
                SELECT variantId, chatId, messageTimestamp, variantIndex,
                    $OPERIT_ARCHIVE_CONTENT_INITIAL_PROJECTION,
                    roleName, provider, modelName, inputTokens, outputTokens, cachedInputTokens,
                    sentAt, outputDurationMs, waitDurationMs, completedAt
                FROM message_variants
                WHERE chatId = ? ORDER BY messageTimestamp, variantIndex
                """.trimIndent(),
                arrayOf(chatEntity.id),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toSnapshotMessageVariantEntity(snapshot))
                }
            }.groupBy { it.messageTimestamp }
        val archivedMessages =
            messageEntities.map { messageEntity ->
                val messageVariants = variantsByTimestamp[messageEntity.timestamp].orEmpty()
                OperitArchivedMessage(
                    baseMessage =
                        messageEntity.toChatMessage().copy(
                            variantCount = messageVariants.size + 1,
                        ),
                    variants = messageVariants.map(OperitArchivedMessageVariant::fromEntity),
                )
            }
        val archivedTodos =
            snapshot.rawQuery(
                "SELECT content, status, priority FROM chat_todos " +
                    "WHERE chatId = ? ORDER BY position",
                arrayOf(chatEntity.id),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            OperitArchivedTodo(
                                content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                                status =
                                    ChatTodoStatus.valueOf(
                                        cursor.getString(cursor.getColumnIndexOrThrow("status"))
                                    ),
                                priority =
                                    ChatTodoPriority.valueOf(
                                        cursor.getString(cursor.getColumnIndexOrThrow("priority"))
                                    ),
                            )
                        )
                    }
                }
            }
        val history =
            chatEntity.toChatHistory(emptyList()).copy(
                folderId = normalizeChatFolderId(chatEntity.folderId),
            )
        return OperitArchivedChat.fromChatHistory(history, archivedMessages, archivedTodos)
    }

    private fun createOperitArchiveSqliteSnapshot(snapshotFile: File) {
        val sourceFile = context.getDatabasePath("app_database")
        require(sourceFile.isFile) { "Chat database does not exist" }
        SQLiteDatabase.openOrCreateDatabase(snapshotFile, null).use { sqliteDb ->
            sqliteDb.execSQL(
                "ATTACH DATABASE ? AS operit_export_source",
                arrayOf(sourceFile.absolutePath),
            )
            try {
                sqliteDb.beginTransaction()
                try {
                    sqliteDb.execSQL(
                        """
                        CREATE TABLE chats AS
                        SELECT id, title, createdAt, updatedAt, inputTokens, outputTokens,
                               currentWindowSize, `group`, folderId, displayOrder, workspace,
                               workspaceEnv, parentChatId, chatKind, characterCardName, characterGroupId,
                               locked, pinned, isFavorite, lastMessageAt, isHidden, hiddenReason, contextPolicyJson
                        FROM operit_export_source.chats
                        """.trimIndent(),
                    )
                    sqliteDb.execSQL(
                        """
                        CREATE TABLE chat_folders AS
                        SELECT id, name, parentFolderId, displayOrder, createdAt
                        FROM operit_export_source.chat_folders
                        """.trimIndent(),
                    )
                    sqliteDb.execSQL(
                        """
                        CREATE TABLE messages AS
                        SELECT messageId, chatId, sender, content, timestamp, orderIndex, roleName,
                               selectedVariantIndex, provider, modelName, inputTokens, outputTokens,
                               cachedInputTokens, sentAt, outputDurationMs, waitDurationMs,
                               completedAt, displayMode, isFavorite
                        FROM operit_export_source.messages
                        """.trimIndent(),
                    )
                    sqliteDb.execSQL(
                        """
                        CREATE TABLE message_variants AS
                        SELECT variantId, chatId, messageTimestamp, variantIndex, content, roleName,
                               provider, modelName, inputTokens, outputTokens, cachedInputTokens,
                               sentAt, outputDurationMs, waitDurationMs, completedAt
                        FROM operit_export_source.message_variants
                        """.trimIndent(),
                    )
                    sqliteDb.execSQL(
                        """
                        CREATE TABLE subagent_runs AS
                        SELECT ${OPERIT_ARCHIVE_SUBAGENT_RUN_SNAPSHOT_COLUMNS.joinToString()}
                        FROM operit_export_source.subagent_runs
                        """.trimIndent(),
                    )
                    sqliteDb.execSQL(
                        """
                        CREATE TABLE chat_todos AS
                        SELECT ${OPERIT_ARCHIVE_CHAT_TODO_SNAPSHOT_COLUMNS.joinToString()}
                        FROM operit_export_source.chat_todos
                        """.trimIndent(),
                    )
                    sqliteDb.execSQL(
                        "CREATE INDEX messages_export_order ON messages(chatId, orderIndex)"
                    )
                    sqliteDb.execSQL(
                        "CREATE INDEX variants_export_order ON " +
                            "message_variants(chatId, messageTimestamp, variantIndex)"
                    )
                    sqliteDb.execSQL(
                        "CREATE INDEX todos_export_order ON chat_todos(chatId, position)"
                    )
                    sqliteDb.setTransactionSuccessful()
                } finally {
                    sqliteDb.endTransaction()
                }
            } finally {
                sqliteDb.execSQL("DETACH DATABASE operit_export_source")
            }
        }
    }

    private suspend fun exportOperitArchiveJsonStream(
        file: File,
    ) = operitArchiveExportMutex.withLock {
        AppLogger.d(TAG, "开始流式导出 Operit 聊天记录，目标=${file.absolutePath}")
        val snapshotFile =
            File(context.cacheDir, "operit_chat_export_${java.util.UUID.randomUUID()}.db")
        try {
            createOperitArchiveSqliteSnapshot(snapshotFile)
            SQLiteDatabase.openDatabase(
                snapshotFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { snapshot ->
                val archivedFolders =
                    snapshot.rawQuery(
                        "SELECT id, name, parentFolderId, displayOrder, createdAt FROM chat_folders " +
                            "WHERE id != ? ORDER BY displayOrder, createdAt, id",
                        arrayOf(SYSTEM_UNGROUPED_FOLDER_ID),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    OperitArchivedFolder(
                                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                                        parentFolderId = cursor.stringOrNull("parentFolderId"),
                                        displayOrder =
                                            cursor.getLong(
                                                cursor.getColumnIndexOrThrow("displayOrder")
                                            ),
                                        createdAt =
                                            cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
                                    )
                                )
                            }
                        }
                    }
                val chatCount =
                    snapshot.rawQuery("SELECT COUNT(*) FROM chats", emptyArray()).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
                BufferedWriter(
                    OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8),
                ).use { writer ->
                    writer.append("{\n")
                    writer.append("  \"archiveType\": ")
                    writer.append(operitArchiveJson.encodeToString(OperitChatArchive.ARCHIVE_TYPE))
                    writer.append(",\n")
                    writer.append("  \"formatVersion\": ${OperitChatArchive.CURRENT_FORMAT_VERSION},\n")
                    writer.append("  \"exportedAt\": ${System.currentTimeMillis()},\n")
                    writer.append("  \"folders\": ")
                    writer.append(operitArchiveJson.encodeToString(archivedFolders))
                    writer.append(",\n")
                    writer.append("  \"chats\": [")

                    snapshot.rawQuery(
                        "SELECT * FROM chats ORDER BY pinned DESC, displayOrder ASC",
                        emptyArray(),
                    ).use { cursor ->
                        var index = 0
                        while (cursor.moveToNext()) {
                            val archivedChat =
                                buildOperitArchivedChatFromSnapshot(
                                    snapshot,
                                    cursor.toSnapshotChatEntity(),
                                )
                            if (index == 0) writer.append('\n') else writer.append(",\n")
                            writer.append(operitArchiveJson.encodeToString(archivedChat))
                            index++
                            if (index % 20 == 0 || index == chatCount) {
                                AppLogger.d(
                                    TAG,
                                    "流式导出进度: $index/$chatCount，chatId=${archivedChat.id}，messages=${archivedChat.messages.size}",
                                )
                            }
                        }
                    }

                    if (chatCount > 0) writer.append('\n')
                    writer.append("  ],\n")
                    val archivedSubagentRuns =
                        snapshot.rawQuery(
                            "SELECT * FROM subagent_runs ORDER BY createdAt ASC, id ASC",
                            emptyArray(),
                        ).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) {
                                    add(cursor.toArchivedSubagentRun())
                                }
                            }
                        }
                    writer.append("  \"subagentRuns\": ")
                    writer.append(operitArchiveJson.encodeToString(archivedSubagentRuns))
                    writer.append('\n')
                    writer.append("}\n")
                }
                AppLogger.d(
                    TAG,
                    "流式导出 Operit 聊天记录完成，共 $chatCount 个会话，目标=${file.absolutePath}",
                )
            }
        } finally {
            listOf(
                snapshotFile,
                File("${snapshotFile.absolutePath}-journal"),
                File("${snapshotFile.absolutePath}-wal"),
                File("${snapshotFile.absolutePath}-shm"),
            ).forEach { it.delete() }
        }
    }

    private suspend fun consumeImportedArchiveChat(
        archivedChat: OperitArchivedChat,
        folderId: String?,
        formatVersion: Int,
        existingIds: MutableSet<String>,
        counters: ImportCounters,
        importedIndex: Int,
    ) {
        if (archivedChat.messages.isEmpty() && formatVersion < 5) {
            counters.skippedCount++
            AppLogger.w(
                TAG,
                "导入跳过空归档会话: index=$importedIndex, chatId=${archivedChat.id}",
            )
            return
        }
        val existed = existingIds.contains(archivedChat.id)
        if (existed) {
            counters.updatedCount++
        } else {
            counters.newCount++
            existingIds.add(archivedChat.id)
        }

        saveArchivedChatInCurrentTransaction(archivedChat, formatVersion, folderId)

        if (importedIndex % 20 == 0) {
            AppLogger.d(
                TAG,
                "导入进度: index=$importedIndex, archive chatId=${archivedChat.id}, messages=${archivedChat.messages.size}, new=${counters.newCount}, updated=${counters.updatedCount}, skipped=${counters.skippedCount}",
            )
        }
    }

    private data class StagedV4Archive(
        val folders: List<OperitArchivedFolder>,
        val folderIds: Set<String>,
        val chats: List<ArchivedChatMetadata>,
        val orphanParentCount: Int,
        val orphanChatFolderCount: Int,
    )

    private fun stageV4Archive(archive: ScannedArchive): StagedV4Archive {
        fun requireCanonicalUuid(value: String, field: String) {
            require(
                runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
            ) {
                "$field must be a canonical UUID"
            }
        }

        val folders = archive.folders.map { it.folder }
        val folderIds = folders.map { it.id }
        require(folderIds.distinct().size == folderIds.size) {
            "Archive v4 contains duplicate folder IDs"
        }
        folders.forEach { folder ->
            requireCanonicalUuid(folder.id, "Folder ID")
            folder.parentFolderId?.let { requireCanonicalUuid(it, "Parent folder ID") }
            require(folder.name.trim().isNotEmpty()) { "Folder name must not be blank" }
        }
        val chatIds = archive.chats.map { it.id }
        require(chatIds.all { it.isNotBlank() }) { "Chat ID must not be blank" }
        require(chatIds.distinct().size == chatIds.size) {
            "Archive v4 contains duplicate chat IDs"
        }

        val folderIdSet = folderIds.toSet()
        var orphanParentCount = 0
        val normalizedFolders =
            folders.map { folder ->
                val normalizedParent =
                    folder.parentFolderId?.takeIf { parentId ->
                        (parentId in folderIdSet).also { exists ->
                            if (!exists) orphanParentCount++
                        }
                    }
                folder.copy(
                    name = folder.name.trim(),
                    parentFolderId = normalizedParent,
                )
            }
        val orphanChatFolderCount =
            archive.chats.count { metadata ->
                metadata.folderId != null && metadata.folderId !in folderIdSet
            }
        validateFolderGraph(normalizedFolders)
        return StagedV4Archive(
            folders = normalizedFolders,
            folderIds = folderIdSet,
            chats = archive.chats,
            orphanParentCount = orphanParentCount,
            orphanChatFolderCount = orphanChatFolderCount,
        )
    }

    private fun validateFolderGraph(folders: List<OperitArchivedFolder>) {
        val byId = folders.associateBy { it.id }
        fun depth(folderId: String, visiting: MutableSet<String>): Int {
            require(visiting.add(folderId)) { "Archive v4 folder graph contains a cycle" }
            val parentId = byId.getValue(folderId).parentFolderId
            val depth = if (parentId == null) 1 else depth(parentId, visiting) + 1
            visiting.remove(folderId)
            require(depth <= 3) { "Archive v4 folder depth exceeds 3" }
            return depth
        }
        folders.forEach { depth(it.id, hashSetOf()) }
    }

    private fun topologicallySortFolders(
        folders: List<OperitArchivedFolder>,
    ): List<OperitArchivedFolder> {
        val remaining = folders.associateByTo(linkedMapOf()) { it.id }
        val emitted = hashSetOf<String>()
        val result = mutableListOf<OperitArchivedFolder>()
        while (remaining.isNotEmpty()) {
            val ready =
                remaining.values
                    .filter { it.parentFolderId == null || it.parentFolderId in emitted }
                    .sortedWith(
                        compareBy<OperitArchivedFolder> { it.displayOrder }
                            .thenBy { it.createdAt }
                            .thenBy { it.id }
                    )
            require(ready.isNotEmpty()) { "Archive v4 folder graph is not acyclic" }
            ready.forEach { folder ->
                result += folder
                emitted += folder.id
                remaining.remove(folder.id)
            }
        }
        return result
    }

    private suspend fun importV4Archive(
        staged: StagedV4Archive,
        archiveFile: File,
        existingIds: MutableSet<String>,
        counters: ImportCounters,
        formatVersion: Int = 4,
        chatTransform: (OperitArchivedChat) -> OperitArchivedChat = { it },
        afterChatsInTransaction: suspend () -> Unit = {},
    ): Int {
        var importedFolderCount = 0
        database.withTransaction {
            val localChats = chatDao.getAllChatsDirectly()
            existingIds.clear()
            existingIds.addAll(localChats.map { it.id })
            val localFolders = chatFolderDao.getFolders()
            val localFolderIds = localFolders.mapTo(hashSetOf()) { it.id }
            val archiveFolderIds = staged.folders.mapTo(hashSetOf()) { it.id }
            val allocated = hashSetOf<String>().apply {
                addAll(localFolderIds)
                addAll(archiveFolderIds)
            }
            val archiveFolderById = staged.folders.associateBy { it.id }
            val localFolderById = localFolders.associateBy { it.id }
            val archiveChildrenByParent = staged.folders.groupBy { it.parentFolderId }
            val localChildrenByParent = localFolders.groupBy { it.parentFolderId }
            val archiveSubtreeIds = mutableMapOf<String, Set<String>>()
            fun archiveSubtree(folderId: String): Set<String> =
                archiveSubtreeIds.getOrPut(folderId) {
                    buildSet {
                        add(folderId)
                        archiveChildrenByParent[folderId].orEmpty().forEach { child ->
                            addAll(archiveSubtree(child.id))
                        }
                    }
                }
            val localSubtreeIds = mutableMapOf<String, Set<String>>()
            fun localSubtree(folderId: String): Set<String> =
                localSubtreeIds.getOrPut(folderId) {
                    buildSet {
                        add(folderId)
                        localChildrenByParent[folderId].orEmpty().forEach { child ->
                            addAll(localSubtree(child.id))
                        }
                    }
                }
            val archiveSignatures = mutableMapOf<String, String>()
            fun archiveSignature(folderId: String): String =
                archiveSignatures.getOrPut(folderId) {
                    val folder = archiveFolderById.getValue(folderId)
                    val children =
                        archiveChildrenByParent[folderId]
                            .orEmpty()
                            .map { archiveSignature(it.id) }
                            .sorted()
                            .joinToString(separator = ",", prefix = "[", postfix = "]")
                    "${folder.name}\u0000${folder.displayOrder}\u0000${folder.createdAt}$children"
                }
            val localSignatures = mutableMapOf<String, String>()
            fun localSignature(folderId: String): String =
                localSignatures.getOrPut(folderId) {
                    val folder = localFolderById.getValue(folderId)
                    val children =
                        localChildrenByParent[folderId]
                            .orEmpty()
                            .map { localSignature(it.id) }
                            .sorted()
                            .joinToString(separator = ",", prefix = "[", postfix = "]")
                    "${folder.name}\u0000${folder.displayOrder}\u0000${folder.createdAt}$children"
                }
            val localChatFolderIdByChatId =
                localChats.associate { it.id to normalizeChatFolderId(it.folderId) }
            val existingArchivedChatIds =
                staged.chats
                    .asSequence()
                    .filter { it.hasMessages }
                    .map { it.id }
                    .filter(localChatFolderIdByChatId::containsKey)
                    .toSet()
            val reusedLocalFolderIds = hashSetOf<String>()
            val folderIdRemap = linkedMapOf<String, String>()
            topologicallySortFolders(staged.folders).forEach { folder ->
                val resolvedParentId = folder.parentFolderId?.let(folderIdRemap::getValue)
                fun matchesMetadata(local: ChatFolderEntity): Boolean =
                    local.name == folder.name &&
                        local.parentFolderId == resolvedParentId &&
                        local.displayOrder == folder.displayOrder &&
                        local.createdAt == folder.createdAt

                val parentCandidates =
                    localFolders
                        .asSequence()
                        .filter { it.id !in reusedLocalFolderIds }
                        .filter { it.parentFolderId == resolvedParentId }
                        .toList()
                val archivedChatIds =
                    staged.chats
                        .asSequence()
                        .filter { it.hasMessages && it.folderId in archiveSubtree(folder.id) }
                        .map { it.id }
                        .filter { it in existingArchivedChatIds }
                        .toSet()
                val identityCandidates =
                    if (folder.id in localFolderIds) {
                        parentCandidates.filter { candidate ->
                            if (archivedChatIds.isEmpty()) {
                                candidate.createdAt == folder.createdAt
                            } else {
                                val candidateSubtree = localSubtree(candidate.id)
                                existingArchivedChatIds
                                    .filterTo(hashSetOf()) { chatId ->
                                        localChatFolderIdByChatId.getValue(chatId) in candidateSubtree
                                    } == archivedChatIds
                            }
                        }
                    } else {
                        emptyList()
                    }
                var reusableCandidates =
                    if (folder.id in localFolderIds) {
                        identityCandidates.ifEmpty {
                            parentCandidates.filter(::matchesMetadata)
                        }
                    } else {
                        emptyList()
                    }
                identityCandidates
                    .firstOrNull { it.id == folder.id }
                    ?.let { exactIdentity -> reusableCandidates = listOf(exactIdentity) }
                if (reusableCandidates.size > 1) {
                    reusableCandidates =
                        reusableCandidates.filter { candidate ->
                            val candidateSubtree = localSubtree(candidate.id)
                            existingArchivedChatIds
                                .filterTo(hashSetOf()) { chatId ->
                                    localChatFolderIdByChatId.getValue(chatId) in candidateSubtree
                                } == archivedChatIds
                        }
                }
                if (reusableCandidates.size > 1) {
                    val expectedSignature = archiveSignature(folder.id)
                    reusableCandidates =
                        reusableCandidates.filter {
                            localSignature(it.id) == expectedSignature
                        }
                }
                val reusableId =
                    reusableCandidates
                        .sortedWith(
                            compareByDescending<ChatFolderEntity> { it.id == folder.id }
                                .thenBy { it.id }
                        )
                        .firstOrNull()
                        ?.id
                val resolvedId =
                    reusableId
                        ?: if (folder.id !in localFolderIds) {
                            folder.id
                        } else {
                            var candidate: String
                            do {
                                candidate = java.util.UUID.randomUUID().toString()
                            } while (!allocated.add(candidate))
                            candidate
                        }
                if (reusableId != null) {
                    reusedLocalFolderIds += reusableId
                }
                folderIdRemap[folder.id] = resolvedId
            }
            val remappedFolders =
                staged.folders.map { folder ->
                    folder.copy(
                        id = folderIdRemap.getValue(folder.id),
                        parentFolderId = folder.parentFolderId?.let(folderIdRemap::getValue),
                    )
                }
            val remappedFolderIds = remappedFolders.map { it.id }
            require(remappedFolderIds.distinct().size == remappedFolderIds.size) {
                "Remapped archive contains duplicate folder IDs"
            }
            val remappedFolderIdSet = remappedFolderIds.toSet()
            remappedFolders.forEach { folder ->
                require(
                    runCatching {
                        java.util.UUID.fromString(folder.id).toString() == folder.id
                    }.getOrDefault(false)
                ) { "Remapped folder ID must be a canonical UUID" }
                require(folder.name.isNotBlank() && folder.name == folder.name.trim()) {
                    "Remapped folder name is invalid"
                }
                require(
                    folder.parentFolderId == null ||
                        folder.parentFolderId in remappedFolderIdSet
                ) { "Remapped parent folder reference is invalid" }
            }
            validateFolderGraph(remappedFolders)
            topologicallySortFolders(remappedFolders).forEach { folder ->
                if (folder.id !in localFolderIds) {
                    chatFolderDao.insertFolder(folder.toEntity())
                    importedFolderCount++
                } else if (folder.id in reusedLocalFolderIds) {
                    chatFolderDao.updateFolder(folder.toEntity())
                }
            }
            forEachArchivedChat(archiveFile) { archivedChat, _, index ->
                val transformedChat = chatTransform(archivedChat)
                val normalizedFolderId =
                    transformedChat.folderId
                        ?.takeIf { it in staged.folderIds }
                        ?.let(folderIdRemap::getValue)
                consumeImportedArchiveChat(
                    archivedChat = transformedChat,
                    folderId = normalizedFolderId,
                    formatVersion = formatVersion,
                    existingIds = existingIds,
                    counters = counters,
                    importedIndex = index,
                )
            }
            afterChatsInTransaction()
            val foreignKeyViolation =
                database.openHelper.writableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { cursor -> cursor.moveToFirst() }
            check(!foreignKeyViolation) {
                "Archive v$formatVersion import violates foreign keys"
            }
        }
        if (staged.orphanParentCount > 0 || staged.orphanChatFolderCount > 0) {
            AppLogger.w(
                TAG,
                "Archive v4 normalized structural references: parents=${staged.orphanParentCount}, chats=${staged.orphanChatFolderCount}",
            )
        }
        return importedFolderCount
    }

    private fun validateGraphArchive(archive: ScannedArchive): List<OperitArchivedSubagentRun> {
        val formatVersion = archive.formatVersion
        val chatsById = archive.chats.associateBy { it.id }
        archive.chats.forEach { chat ->
            val kind =
                requireNotNull(
                    chat.chatKind?.let { value ->
                        runCatching { ChatKind.valueOf(value) }.getOrNull()
                    }
                ) {
                    "Archive v$formatVersion chat ${chat.id} has an invalid chatKind"
                }
            when (kind) {
                ChatKind.NORMAL ->
                    require(chat.parentChatId == null) {
                        "Archive v$formatVersion NORMAL chat ${chat.id} cannot have a parent"
                    }
                ChatKind.BRANCH,
                ChatKind.SUBAGENT -> {
                    require(chat.parentChatId != null && chat.parentChatId in chatsById) {
                        "Archive v$formatVersion ${kind.name} chat ${chat.id} has an invalid parent"
                    }
                }
            }
        }

        val runIds = archive.subagentRuns.map { it.id }
        require(runIds.all { it.isNotBlank() } && runIds.distinct().size == runIds.size) {
            "Archive v$formatVersion contains blank or duplicate task IDs"
        }
        val childIds = archive.subagentRuns.map { it.childChatId }
        require(childIds.distinct().size == childIds.size) {
            "Archive v$formatVersion contains multiple runs for one child chat"
        }
        val parentCallIds =
            archive.subagentRuns.mapNotNull { run ->
                run.parentToolCallId?.let { callId -> run.parentChatId to callId }
            }
        require(parentCallIds.distinct().size == parentCallIds.size) {
            "Archive v$formatVersion contains duplicate parent tool call IDs within one parent chat"
        }
        archive.subagentRuns.forEach { run ->
            val child =
                requireNotNull(chatsById[run.childChatId]) {
                    "Archive v$formatVersion run ${run.id} references a missing child chat"
                }
            require(run.parentChatId in chatsById) {
                "Archive v$formatVersion run ${run.id} references a missing parent chat"
            }
            require(
                child.chatKind == ChatKind.SUBAGENT.name &&
                    child.parentChatId == run.parentChatId
            ) {
                "Archive v$formatVersion run ${run.id} does not match its child relationship"
            }
            require(
                runCatching { SubagentRunStatus.valueOf(run.status) }.isSuccess
            ) {
                "Archive v$formatVersion run ${run.id} has an invalid status"
            }
        }

        val subagentChildIds =
            archive.chats
                .filterTo(linkedSetOf()) { it.chatKind == ChatKind.SUBAGENT.name }
                .mapTo(linkedSetOf()) { it.id }
        require(childIds.toSet() == subagentChildIds) {
            "Archive v$formatVersion contains a Subagent child without exactly one run record"
        }
        return archive.subagentRuns
    }

    private suspend fun importGraphArchive(
        archive: ScannedArchive,
        archiveFile: File,
        existingIds: MutableSet<String>,
        counters: ImportCounters,
    ): Int {
        val completeRuns = validateGraphArchive(archive)
        val staged = stageV4Archive(archive)
        val localChatIds = chatDao.getAllChatsDirectly().mapTo(hashSetOf()) { it.id }
        val allocatedChatIds =
            hashSetOf<String>().apply {
                addAll(localChatIds)
                addAll(archive.chats.map { it.id })
            }
        val chatIdRemap =
            archive.chats.associate { chat ->
                val resolvedId =
                    if (chat.id !in localChatIds) {
                        chat.id
                    } else {
                        var candidate: String
                        do {
                            candidate = java.util.UUID.randomUUID().toString()
                        } while (!allocatedChatIds.add(candidate))
                        candidate
                    }
                chat.id to resolvedId
            }

        val localRuns = subagentRunDao.getAll()
        val localTaskIds = localRuns.mapTo(hashSetOf()) { it.id }
        val allocatedTaskIds =
            hashSetOf<String>().apply {
                addAll(localTaskIds)
                addAll(completeRuns.map { it.id })
            }
        val taskIdRemap =
            completeRuns.associate { run ->
                val resolvedId =
                    if (run.id !in localTaskIds) {
                        run.id
                    } else {
                        var candidate: String
                        do {
                            candidate = java.util.UUID.randomUUID().toString()
                        } while (!allocatedTaskIds.add(candidate))
                        candidate
                    }
                run.id to resolvedId
            }

        val allocatedParentToolCallIds =
            localRuns.mapNotNullTo(hashSetOf()) { it.parentToolCallId }
        val parentToolCallIdRemap =
            completeRuns
                .mapNotNull { run ->
                    val oldCallId = run.parentToolCallId ?: return@mapNotNull null
                    val resolvedCallId =
                        if (allocatedParentToolCallIds.add(oldCallId)) {
                            oldCallId
                        } else {
                            var candidate: String
                            do {
                                candidate = java.util.UUID.randomUUID().toString()
                            } while (!allocatedParentToolCallIds.add(candidate))
                            candidate
                        }
                    (run.parentChatId to oldCallId) to resolvedCallId
                }
                .toMap()

        fun remapChat(chat: OperitArchivedChat): OperitArchivedChat =
            chat.copy(
                id = chatIdRemap.getValue(chat.id),
                parentChatId = chat.parentChatId?.let(chatIdRemap::getValue),
                chatKind =
                    chat.chatKind
                        ?: if (chat.parentChatId == null) {
                            ChatKind.NORMAL.name
                        } else {
                            ChatKind.BRANCH.name
                        },
                messages =
                    remapArchivedParentToolCallIds(
                        chat = chat,
                        callIdRemap =
                            parentToolCallIdRemap
                                .filterKeys { (parentChatId, _) -> parentChatId == chat.id }
                                .mapKeys { (key, _) -> key.second },
                        taskIdRemap =
                            completeRuns
                                .asSequence()
                                .filter { run -> run.parentChatId == chat.id }
                                .associate { run -> run.id to taskIdRemap.getValue(run.id) },
                    ).messages,
            )

        val remappedStaged =
            staged.copy(
                chats =
                    staged.chats.map { chat ->
                        chat.copy(
                            id = chatIdRemap.getValue(chat.id),
                            parentChatId = chat.parentChatId?.let(chatIdRemap::getValue),
                        )
                    }
            )
        val activeStatuses =
            setOf(
                SubagentRunStatus.CREATED.name,
                SubagentRunStatus.QUEUED.name,
                SubagentRunStatus.RUNNING.name,
            )
        val now = System.currentTimeMillis()
        val remappedRuns =
            completeRuns.map { run ->
                val wasActive = run.status in activeStatuses
                run.copy(
                    id = taskIdRemap.getValue(run.id),
                    parentChatId = chatIdRemap.getValue(run.parentChatId),
                    childChatId = chatIdRemap.getValue(run.childChatId),
                    parentToolCallId =
                        run.parentToolCallId?.let { oldCallId ->
                            parentToolCallIdRemap.getValue(run.parentChatId to oldCallId)
                        },
                    status =
                        if (wasActive) {
                            SubagentRunStatus.INTERRUPTED.name
                        } else {
                            run.status
                        },
                    completedAt = if (wasActive) now else run.completedAt,
                    error =
                        if (wasActive) {
                            SubagentInterruption.ARCHIVE_IMPORT
                        } else {
                            run.error
                        },
                )
            }

        return importV4Archive(
            staged = remappedStaged,
            archiveFile = archiveFile,
            existingIds = existingIds,
            counters = counters,
            formatVersion = archive.formatVersion,
            chatTransform = ::remapChat,
            afterChatsInTransaction = {
                remappedRuns.forEach { run -> subagentRunDao.insert(run.toEntity()) }
            },
        )
    }

    private fun <T> decodeStreamObject(
        reader: JsonReader,
        description: String,
        decode: (String) -> T,
    ): Pair<T, Set<String>> {
        val element = Streams.parse(reader)
        require(element.isJsonObject) { "$description must be an object" }
        val jsonObject = element.asJsonObject
        return decode(jsonObject.toString()) to jsonObject.keySet().toSet()
    }

    private fun scanOperitArchive(file: File): ScannedArchive {
        var archiveType: String? = null
        var formatVersion: Int? = null
        var foldersPresent = false
        var chatsPresent = false
        var subagentRunsPresent = false
        val folders = mutableListOf<ScannedFolder>()
        val chats = mutableListOf<ArchivedChatMetadata>()
        val subagentRuns = mutableListOf<OperitArchivedSubagentRun>()

        JsonReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)).use { reader ->
            reader.isLenient = true
            require(reader.peek() == JsonToken.BEGIN_OBJECT) {
                "Operit archive root must be an object"
            }
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "archiveType" -> archiveType = reader.nextString()
                    "formatVersion" -> formatVersion = reader.nextInt()
                    "folders" -> {
                        foldersPresent = true
                        val requiredFields =
                            setOf("id", "name", "parentFolderId", "displayOrder", "createdAt")
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val (folder, fields) =
                                decodeStreamObject(reader, "Archive folder") {
                                    operitArchiveJson.decodeFromString<OperitArchivedFolder>(it)
                                }
                            folders +=
                                ScannedFolder(
                                    folder = folder,
                                    hasRequiredV4Fields = requiredFields.all(fields::contains),
                                )
                        }
                        reader.endArray()
                    }
                    "chats" -> {
                        chatsPresent = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val (chat, fields) =
                                decodeStreamObject(reader, "Archived chat") {
                                    operitArchiveJson.decodeFromString<OperitArchivedChat>(it)
                                }
                            chat.messages.forEach { message ->
                                if (message.variants.isNotEmpty()) {
                                    validateArchivedMessageVariants(
                                        message.baseMessage,
                                        message.variants,
                                    )
                                }
                            }
                            validateChatTodoSnapshot(
                                chat.todos.map(OperitArchivedTodo::toChatTodo)
                            )
                            chats +=
                                ArchivedChatMetadata(
                                    id = chat.id,
                                    title = chat.title,
                                    folderId = chat.folderId,
                                    legacyFolderBucketKey = chat.legacyFolderBucketKey(),
                                    displayOrder = chat.displayOrder,
                                    createdAt =
                                        chat.createdAt
                                            .atZone(ZoneId.systemDefault())
                                            .toInstant()
                                            .toEpochMilli(),
                                    hasMessages = chat.messages.isNotEmpty(),
                                    hasFolderIdField = "folderId" in fields,
                                    parentChatId = chat.parentChatId,
                                    chatKind = chat.chatKind,
                                    hasChatKindField = "chatKind" in fields,
                                    hasTodosField = "todos" in fields,
                                    isFavorite = chat.isFavorite,
                                )
                        }
                        reader.endArray()
                    }
                    "subagentRuns" -> {
                        subagentRunsPresent = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            subagentRuns +=
                                decodeStreamObject(reader, "Archived Subagent run") {
                                    operitArchiveJson
                                        .decodeFromString<OperitArchivedSubagentRun>(it)
                                }.first
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) {
                "Operit archive contains trailing JSON data"
            }
        }

        val resolvedArchiveType =
            archiveType ?: throw IllegalArgumentException("Operit archive is missing archiveType")
        val resolvedFormatVersion =
            formatVersion
                ?: throw IllegalArgumentException("Operit archive is missing formatVersion")
        require(chatsPresent) { "Operit archive is missing chats" }
        ChatArchiveImportPolicy.validateHeader(
            resolvedArchiveType,
            resolvedFormatVersion,
        )
        if (resolvedFormatVersion >= 3) {
            require(chats.all { it.isFavorite != null }) {
                "Archive v$resolvedFormatVersion contains a chat without isFavorite"
            }
        }
        if (resolvedFormatVersion >= 4) {
            require(foldersPresent) { "Archive v$resolvedFormatVersion is missing folders" }
            require(folders.all { it.hasRequiredV4Fields }) {
                "Archive v$resolvedFormatVersion contains a folder with missing fields"
            }
            require(chats.all { it.hasFolderIdField }) {
                "Archive v$resolvedFormatVersion contains a chat without folderId"
            }
        }
        if (resolvedFormatVersion >= 5) {
            require(subagentRunsPresent) {
                "Archive v$resolvedFormatVersion is missing subagentRuns"
            }
            require(chats.all { it.hasChatKindField }) {
                "Archive v$resolvedFormatVersion contains a chat without chatKind"
            }
        }
        if (resolvedFormatVersion >= 6) {
            require(chats.all { it.hasTodosField }) {
                "Archive v$resolvedFormatVersion contains a chat without todos"
            }
        }
        return ScannedArchive(
            archiveType = resolvedArchiveType,
            formatVersion = resolvedFormatVersion,
            folders = folders,
            chats = chats,
            subagentRuns = subagentRuns,
        )
    }

    private suspend fun forEachArchivedChat(
        file: File,
        consume: suspend (OperitArchivedChat, Set<String>, Int) -> Unit,
    ) {
        var chatsPresent = false
        var importedIndex = 0
        JsonReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "chats") {
                    reader.skipValue()
                    continue
                }
                chatsPresent = true
                reader.beginArray()
                while (reader.hasNext()) {
                    importedIndex++
                    val (chat, fields) =
                        decodeStreamObject(reader, "Archived chat") {
                            operitArchiveJson.decodeFromString<OperitArchivedChat>(it)
                        }
                    consume(chat, fields, importedIndex)
                }
                reader.endArray()
            }
            reader.endObject()
        }
        require(chatsPresent) { "Operit archive is missing chats" }
    }

    private suspend fun forEachArchivedSubagentRun(
        file: File,
        consume: suspend (OperitArchivedSubagentRun) -> Unit,
    ) {
        var runsPresent = false
        JsonReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "subagentRuns") {
                    reader.skipValue()
                    continue
                }
                runsPresent = true
                reader.beginArray()
                while (reader.hasNext()) {
                    consume(
                        decodeStreamObject(reader, "Archived Subagent run") {
                            operitArchiveJson.decodeFromString<OperitArchivedSubagentRun>(it)
                        }.first
                    )
                }
                reader.endArray()
            }
            reader.endObject()
        }
        require(runsPresent) { "Operit archive is missing subagentRuns" }
    }

    private suspend fun importLegacyChatArray(
        file: File,
        existingIds: MutableSet<String>,
        counters: ImportCounters,
    ) {
        val importedFolderIdsByBucket = mutableMapOf<LegacyFolderBucketKey, String>()
        val existingFolderIds = chatFolderDao.getFolders().mapTo(hashSetOf()) { it.id }
        var importedIndex = 0
        JsonReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginArray()
            while (reader.hasNext()) {
                importedIndex++
                val history =
                    decodeStreamObject(reader, "Legacy chat") {
                        operitArchiveJson.decodeFromString<ChatHistory>(it)
                    }.first
                if (history.messages.isEmpty()) {
                    counters.skippedCount++
                } else {
                    val existed = existingIds.contains(history.id)
                    if (existed) counters.updatedCount++ else {
                        counters.newCount++
                        existingIds.add(history.id)
                    }
                    val legacyFolderBucket = history.legacyFolderBucketKey()
                    val normalizedHistory =
                        if (history.folderId == null && legacyFolderBucket != null) {
                            val folderId =
                                importedFolderIdsByBucket[legacyFolderBucket]
                                    ?: (
                                        findLegacyFolderId(legacyFolderBucket)
                                            ?: createFolder(
                                                parentFolderId = null,
                                                name = legacyFolderBucket.rawGroup.trim(),
                                            )
                                    ).also {
                                        importedFolderIdsByBucket[legacyFolderBucket] = it
                                    }
                            if (existingFolderIds.add(folderId)) {
                                counters.folderCount++
                            }
                            history.copy(group = null, folderId = folderId)
                        } else {
                            history.copy(group = null)
                        }
                    saveChatHistoryInternal(
                        history = normalizedHistory,
                        preserveStructure = false,
                        clearTodosAfterTranscriptReplace = true,
                    )
                }
                if (importedIndex % 20 == 0) {
                    AppLogger.d(TAG, "旧版聊天数组导入进度: $importedIndex")
                }
            }
            reader.endArray()
        }
    }

    private suspend fun importOperitChatHistoriesStream(
        inputStream: InputStream,
        existingIds: MutableSet<String>,
    ): ChatImportResult {
        val counters = ImportCounters()
        val importFile = File.createTempFile("operit-chat-import-", ".json", context.cacheDir)
        try {
            FileOutputStream(importFile).use { output -> inputStream.copyTo(output) }
            if (importFile.length() == 0L) {
                throw Exception(context.getString(R.string.chat_history_imported_file_empty))
            }
            val rootToken =
                JsonReader(
                    InputStreamReader(importFile.inputStream(), StandardCharsets.UTF_8)
                ).use { reader ->
                    reader.isLenient = true
                    reader.peek()
                }
            when (rootToken) {
                JsonToken.BEGIN_OBJECT -> {
                    val archive = scanOperitArchive(importFile)
                    if (archive.formatVersion >= 5) {
                        counters.folderCount =
                            importGraphArchive(
                                archive = archive,
                                archiveFile = importFile,
                                existingIds = existingIds,
                                counters = counters,
                            )
                    } else if (archive.formatVersion == 4) {
                        counters.folderCount =
                            importV4Archive(
                                staged = stageV4Archive(archive),
                                archiveFile = importFile,
                                existingIds = existingIds,
                                counters = counters,
                            )
                    } else {
                        database.withTransaction {
                            val folderIdByBucket =
                                createLegacyImportFolders(archive.chats, counters)
                            forEachArchivedChat(importFile) { archivedChat, fields, index ->
                                // v2/v3 distinguish a missing legacy group field from explicit null/blank.
                                val folderId =
                                    if ("group" in fields) {
                                        archivedChat
                                            .legacyFolderBucketKey()
                                            ?.let(folderIdByBucket::get)
                                    } else {
                                        chatDao.getChatById(archivedChat.id)?.folderId
                                    }
                                consumeImportedArchiveChat(
                                    archivedChat = archivedChat,
                                    folderId = folderId,
                                    formatVersion = archive.formatVersion,
                                    existingIds = existingIds,
                                    counters = counters,
                                    importedIndex = index,
                                )
                            }
                        }
                    }
                }
                JsonToken.BEGIN_ARRAY ->
                    importLegacyChatArray(importFile, existingIds, counters)
                JsonToken.END_DOCUMENT ->
                    throw Exception(context.getString(R.string.chat_history_imported_file_empty))
                else ->
                    throw Exception(
                        context.getString(
                            R.string.chat_history_parse_backup_failed,
                            "unexpected json token",
                        )
                    )
            }
        } finally {
            if (!importFile.delete()) {
                importFile.deleteOnExit()
            }
        }

        AppLogger.d(
            TAG,
            "导入完成: new=${counters.newCount}, updated=${counters.updatedCount}, skipped=${counters.skippedCount}",
        )
        return ChatImportResult(
            new = counters.newCount,
            updated = counters.updatedCount,
            skipped = counters.skippedCount,
            foldersCreated = counters.folderCount,
            mayLeavePreviousEmptyFolders = counters.folderCount > 0,
        )
    }

    init {
        // 确保数据库被初始化
        AppLogger.d(TAG, "ChatHistoryManager初始化，预加载数据库")
        // 使用独立的协程作用域触发数据库初始化
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 预先尝试执行一个简单查询
                val chats = chatDao.getAllChats().first()
                AppLogger.d(TAG, "数据库预加载完成，现有聊天数：${chats.size}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "数据库预加载失败", e)
            }
        }
    }

    // 互斥锁用于同步操作
    private val globalMutex = Mutex()
    private val folderStructureMutex = Mutex()
    private val legacyFolderResolutionMutex = Mutex()
    private val reservedLegacyFolderIds = mutableMapOf<LegacyFolderResolutionKey, String>()
    private val chatMutexes = ConcurrentHashMap<String, Mutex>()

    private fun chatMutex(chatId: String): Mutex {
        return chatMutexes.getOrPut(chatId) { Mutex() }
    }

    // DataStore键
    private object PreferencesKeys {
        val CURRENT_CHAT_ID = stringPreferencesKey("current_chat_id")
    }

    // 辅助函数：将ChatEntity转换为ChatHistory
    private fun ChatEntity.toChatHistory(): ChatHistory {
        return toChatHistory(emptyList())
    }

    private val historyFlowScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun Flow<List<ChatEntity>>.toHistoryFlow(): Flow<List<ChatHistory>> =
        map { chatEntities ->
            withContext(Dispatchers.IO) {
                chatEntities.map {
                    it.toChatHistory().copy(folderId = normalizeChatFolderId(it.folderId))
                }
            }
        }

    /** Internal data source used by dispatch, archive, migration and consistency checks. */
    internal val allChatHistoriesUpdatesFlow =
        chatDao
            .getAllChats()
            .toHistoryFlow()

    val allChatHistoriesInternalFlow =
        allChatHistoriesUpdatesFlow
            .stateIn(
                historyFlowScope,
                SharingStarted.Lazily,
                emptyList(),
            )

    /** Default UI data source. Subagent child chats are entered only through task navigation. */
    val chatHistoriesFlow =
        chatDao
            .getVisibleChats()
            .toHistoryFlow()
            .stateIn(
            historyFlowScope,
            SharingStarted.Lazily,
            emptyList()
        )

    /** 隐藏聊天（隐藏入口专用，含阅读伴侣审计聊天）。 */
    val hiddenChatHistoriesFlow =
        chatDao
            .observeHiddenChats()
            .toHistoryFlow()
            .stateIn(
                historyFlowScope,
                SharingStarted.Lazily,
                emptyList(),
            )

    val chatFoldersFlow =
        chatFolderDao
            .observeFolders()
            .onStart { chatFolderRepository.ensureUngroupedFolder() }
            .stateIn(
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            SharingStarted.Lazily,
            emptyList(),
        )

    suspend fun getChatHistoriesSnapshot(): List<ChatHistory> =
        withContext(Dispatchers.IO) {
            chatDao.getAllChatsDirectly().map {
                it.toChatHistory().copy(folderId = normalizeChatFolderId(it.folderId))
            }
        }

    suspend fun getVisibleChatHistoriesSnapshot(): List<ChatHistory> =
        getChatHistoriesSnapshot().filterNot { it.isHidden }

    suspend fun getChatMetadata(chatId: String): ChatHistory? =
        withContext(Dispatchers.IO) {
            chatDao.getChatById(chatId)
                ?.toChatHistory()
                ?.let { history ->
                    history.copy(folderId = normalizeChatFolderId(history.folderId))
                }
        }

    suspend fun getChatFoldersSnapshot(): List<ChatFolderEntity> =
        withContext(Dispatchers.IO) {
            chatFolderRepository.ensureUngroupedFolder()
            chatFolderDao.getFolders()
        }

    suspend fun createFolder(parentFolderId: String?, name: String): String =
        chatFolderRepository.createFolder(parentFolderId, name)

    suspend fun findLegacyFolderId(
        groupName: String,
        characterCardName: String? = null,
        characterGroupId: String? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            val normalizedName = groupName.trim()
            if (normalizedName.isEmpty()) {
                return@withContext null
            }
            val candidates =
                chatFolderDao.getFolders().filter {
                    it.id != SYSTEM_UNGROUPED_FOLDER_ID && it.name == normalizedName
                }
            if (candidates.isEmpty()) {
                return@withContext null
            }
            val normalizedCharacterCardName =
                characterCardName?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedCharacterGroupId =
                characterGroupId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedCharacterCardName == null && normalizedCharacterGroupId == null) {
                return@withContext candidates.minWithOrNull(
                    compareBy<ChatFolderEntity> { it.displayOrder }
                        .thenBy { it.createdAt }
                        .thenBy { it.id }
                )?.id
            }
            val matchingFolderIds =
                chatDao.getAllChatsDirectly()
                    .asSequence()
                    .filter {
                        when {
                            normalizedCharacterGroupId != null ->
                                it.characterGroupId == normalizedCharacterGroupId
                            else ->
                                it.characterCardName == normalizedCharacterCardName &&
                                    it.characterGroupId == null
                        }
                    }
                    .mapNotNull { normalizeChatFolderId(it.folderId) }
                    .toSet()
            candidates
                .filter { it.id in matchingFolderIds }
                .minWithOrNull(
                    compareBy<ChatFolderEntity> { it.displayOrder }
                        .thenBy { it.createdAt }
                        .thenBy { it.id }
                )
                ?.id
        }

    suspend fun resolveOrCreateLegacyFolderId(
        groupName: String,
        characterCardName: String? = null,
        characterGroupId: String? = null,
    ): String {
        val normalizedName = groupName.trim()
        require(normalizedName.isNotEmpty()) { "Group name must not be blank" }
        val normalizedCharacterCardName =
            characterCardName?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedCharacterGroupId =
            characterGroupId?.trim()?.takeIf { it.isNotEmpty() }
        val key =
            LegacyFolderResolutionKey(
                normalizedName,
                normalizedCharacterCardName,
                normalizedCharacterGroupId,
            )
        return legacyFolderResolutionMutex.withLock {
            resolveOrCreateLegacyFolderIdLocked(key)
        }
    }

    private suspend fun resolveOrCreateLegacyFolderIdLocked(
        key: LegacyFolderResolutionKey,
    ): String {
        findLegacyFolderId(
            key.groupName,
            key.characterCardName,
            key.characterGroupId,
        )?.let { folderId ->
            reservedLegacyFolderIds.remove(key)
            return folderId
        }
        reservedLegacyFolderIds[key]
            ?.takeIf { reservedId ->
                chatFolderDao.getFolder(reservedId)?.name == key.groupName
            }
            ?.let { return it }
        reservedLegacyFolderIds.remove(key)
        return createFolder(parentFolderId = null, name = key.groupName).also { folderId ->
            reservedLegacyFolderIds[key] = folderId
        }
    }

    private suspend fun releaseLegacyFolderReservations(folderIds: Collection<String?>) {
        val persistedFolderIds = folderIds.mapNotNull(::normalizeChatFolderId).toSet()
        if (persistedFolderIds.isEmpty()) {
            return
        }
        legacyFolderResolutionMutex.withLock {
            reservedLegacyFolderIds.entries.removeAll { it.value in persistedFolderIds }
        }
    }

    suspend fun renameFolderIfExists(folderId: String, newName: String): Boolean =
        folderStructureMutex.withLock {
            if (
                folderId == SYSTEM_UNGROUPED_FOLDER_ID ||
                chatFolderDao.getFolder(folderId) == null
            ) {
                return@withLock false
            }
            chatFolderRepository.renameFolder(folderId, newName)
            true
        }

    suspend fun renameFolder(folderId: String, newName: String) =
        folderStructureMutex.withLock {
            chatFolderRepository.renameFolder(folderId, newName)
        }

    suspend fun moveFolder(
        folderId: String,
        targetParentFolderId: String?,
        expectedSourceSiblings: List<HistorySiblingSnapshot>,
        expectedTargetSiblings: List<HistorySiblingSnapshot>,
        beforeNodeKey: String? = null,
        afterNodeKey: String? = null,
        allowAppendToNonEmptyTarget: Boolean = true,
    ) =
        chatFolderRepository.moveFolder(
            folderId = folderId,
            targetParentFolderId = targetParentFolderId,
            expectedSourceSiblings = expectedSourceSiblings,
            expectedTargetSiblings = expectedTargetSiblings,
            beforeNodeKey = beforeNodeKey,
            afterNodeKey = afterNodeKey,
            allowAppendToNonEmptyTarget = allowAppendToNonEmptyTarget,
        )

    suspend fun moveChat(
        chatId: String,
        targetFolderId: String?,
        expectedSourceSiblings: List<HistorySiblingSnapshot>,
        expectedTargetSiblings: List<HistorySiblingSnapshot>,
        orderedVisibleNodeKeys: List<String>? = null,
        beforeNodeKey: String? = null,
        afterNodeKey: String? = null,
        allowAppendToNonEmptyTarget: Boolean = true,
    ) =
        chatFolderRepository.moveChat(
            chatId = chatId,
            targetFolderId = targetFolderId,
            orderedVisibleNodeKeys = orderedVisibleNodeKeys,
            beforeNodeKey = beforeNodeKey,
            afterNodeKey = afterNodeKey,
            expectedSourceSiblings = expectedSourceSiblings,
            expectedTargetSiblings = expectedTargetSiblings,
            allowAppendToNonEmptyTarget = allowAppendToNonEmptyTarget,
        )

    suspend fun deleteFolderIfExists(folderId: String): Boolean =
        folderStructureMutex.withLock {
            if (
                folderId == SYSTEM_UNGROUPED_FOLDER_ID ||
                chatFolderDao.getFolder(folderId) == null
            ) {
                return@withLock false
            }
            chatFolderRepository.deleteFolder(folderId)
            true
        }

    suspend fun deleteFolderWithChatsIfExists(
        folderId: String,
        characterCardName: String?,
        characterGroupId: String?,
    ): Boolean =
        folderStructureMutex.withLock {
            if (
                folderId == SYSTEM_UNGROUPED_FOLDER_ID ||
                chatFolderDao.getFolder(folderId) == null
            ) {
                return@withLock false
            }
            val normalizedCharacterCardName =
                characterCardName?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedCharacterGroupId =
                characterGroupId?.trim()?.takeIf { it.isNotEmpty() }
            val expectedChatIds =
                chatFolderRepository.getFolderDeletionChatIds(
                    folderId = folderId,
                    characterCardName = normalizedCharacterCardName,
                    characterGroupId = normalizedCharacterGroupId,
                )
            val deletedChatIds =
                SubagentCoordinator.getInstance(context).withChatDeletionsPrepared(expectedChatIds) {
                    chatFolderRepository.deleteFolderWithChats(
                        folderId = folderId,
                        characterCardName = normalizedCharacterCardName,
                        characterGroupId = normalizedCharacterGroupId,
                        expectedChatIds = expectedChatIds,
                    )
                }
            context.currentChatIdDataStore.edit { preferences ->
                if (preferences[PreferencesKeys.CURRENT_CHAT_ID] in deletedChatIds) {
                    preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
                }
            }
            forgetDeletedChats(deletedChatIds)
            true
        }

    suspend fun deleteFolder(folderId: String) =
        folderStructureMutex.withLock {
            chatFolderRepository.deleteFolder(folderId)
        }

    suspend fun reorderProjectedChats(
        expectedHistories: List<ChatHistory>,
        orderedHistories: List<ChatHistory>,
    ): Boolean =
        folderStructureMutex.withLock {
            val expectedFolderIdsByChatId =
                expectedHistories.associate { it.id to normalizeChatFolderId(it.folderId) }
            val expectedDisplayOrdersByChatId =
                expectedHistories.associate { it.id to it.displayOrder }
            if (
                expectedFolderIdsByChatId.size != expectedHistories.size ||
                    orderedHistories.mapTo(hashSetOf()) { it.id } !=
                        expectedFolderIdsByChatId.keys ||
                    orderedHistories.any {
                        normalizeChatFolderId(it.folderId) !=
                            expectedFolderIdsByChatId[it.id]
                    }
            ) {
                return@withLock false
            }
            chatFolderRepository.reorderProjectedChats(
                expectedChatIds = expectedHistories.map { it.id },
                orderedChatIds = orderedHistories.map { it.id },
                expectedFolderIdsByChatId = expectedFolderIdsByChatId,
                expectedDisplayOrdersByChatId = expectedDisplayOrdersByChatId,
            )
        }

    suspend fun getTotalChatCount(): Int {
        return withContext(Dispatchers.IO) { chatDao.getTotalChatCount() }
    }

    suspend fun getTotalMessageCount(): Int {
        return withContext(Dispatchers.IO) { messageDao.getTotalMessageCount() }
    }

    suspend fun getMessageCountsByChatId(): Map<String, Int> {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.getMessageCountsByChatId().associate { it.chatId to it.count }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get message counts by chatId", e)
                emptyMap()
            }
        }
    }

    // 角色卡聊天统计
    val characterCardStatsFlow: Flow<List<CharacterCardChatStats>> =
        chatDao.getCharacterCardChatStats()
    val characterGroupStatsFlow: Flow<List<CharacterGroupChatStats>> =
        chatDao.getCharacterGroupChatStats()

    /**
     * 根据角色卡过滤聊天历史
     * @param characterCardName 角色卡名称
     * @param isDefault 是否为默认角色卡
     * @return 过滤后的聊天历史Flow
     */
    fun getChatHistoriesByCharacterCard(
        characterCardName: String,
        isDefault: Boolean
    ): Flow<List<ChatHistory>> {
        val sourceFlow = if (isDefault) {
            // 默认角色卡：显示该角色卡名称的对话 + 所有characterCardName为null的对话
            chatDao.getChatsByCharacterCardOrNull(characterCardName)
        } else {
            // 非默认角色卡：只显示该角色卡名称的对话
            chatDao.getChatsByCharacterCard(characterCardName)
        }

        return sourceFlow.map { chatEntities ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                chatEntities.map {
                    it.toChatHistory().copy(folderId = normalizeChatFolderId(it.folderId))
                }
            }
        }
    }

    // 获取当前聊天ID
    private val _currentChatIdFlow: Flow<String?> =
        context.currentChatIdDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences -> preferences[PreferencesKeys.CURRENT_CHAT_ID] }

    // 转换为StateFlow以便共享
    val currentChatIdFlow =
        _currentChatIdFlow.stateIn(
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            SharingStarted.Lazily,
            null
        )

    private fun validateArchivedMessageVariants(
        message: ChatMessage,
        variants: List<OperitArchivedMessageVariant>,
    ) {
        if (variants.isEmpty()) {
            return
        }

        require(message.sender == "ai") {
            "Only AI messages can contain archived variants"
        }
        require(message.selectedVariantIndex >= 0) {
            "Selected variant index must not be negative for message ${message.timestamp}"
        }

        val variantIndices = variants.map { it.variantIndex }
        require(variantIndices.all { it > 0 }) {
            "Variant indices must be positive for message ${message.timestamp}"
        }
        require(variantIndices.distinct().size == variantIndices.size) {
            "Duplicate variant indices found for message ${message.timestamp}"
        }
        require(
            message.selectedVariantIndex == 0 ||
                variantIndices.contains(message.selectedVariantIndex),
        ) {
            "Selected variant ${message.selectedVariantIndex} is missing for message ${message.timestamp}"
        }
    }

    private suspend fun saveChatHistoryInternal(
        history: ChatHistory,
        variantsByTimestamp: Map<Long, List<OperitArchivedMessageVariant>> = emptyMap(),
        archiveFormatVersion: Int? = null,
        archivedFavorite: Boolean? = null,
        preserveStructure: Boolean = true,
        clearTodosAfterTranscriptReplace: Boolean = false,
    ) {
        chatMutex(history.id).withLock {
            database.withTransaction {
                persistChatHistoryInCurrentTransaction(
                    history = history,
                    variantsByTimestamp = variantsByTimestamp,
                    archiveFormatVersion = archiveFormatVersion,
                    archivedFavorite = archivedFavorite,
                    preserveStructure = preserveStructure,
                )
                if (clearTodosAfterTranscriptReplace) {
                    chatTodoDao.deleteByChatId(history.id)
                }
            }
        }
        releaseLegacyFolderReservations(listOf(history.folderId))
    }

    private suspend fun persistChatHistoryInCurrentTransaction(
        history: ChatHistory,
        variantsByTimestamp: Map<Long, List<OperitArchivedMessageVariant>> = emptyMap(),
        archiveFormatVersion: Int? = null,
        archivedFavorite: Boolean? = null,
        preserveStructure: Boolean = true,
    ) {
        val resolvedHistory =
            (
                if (archiveFormatVersion != null) {
                    val localFavorite = chatDao.getChatById(history.id)?.isFavorite ?: false
                    history.copy(
                        isFavorite =
                            ChatArchiveImportPolicy.resolveFavorite(
                                formatVersion = archiveFormatVersion,
                                archivedFavorite = archivedFavorite,
                                localFavorite = localFavorite,
                            )
                    )
                } else {
                    history
                }
            ).copy(folderId = normalizeChatFolderId(history.folderId))
        val chatEntity =
            ChatEntity.fromChatHistory(resolvedHistory).let { entity ->
                if (archiveFormatVersion == null) {
                    entity
                } else {
                    entity.copy(displayOrder = resolvedHistory.displayOrder)
                }
            }
        val existingEntity = chatDao.getChatById(chatEntity.id)
        if (existingEntity == null) {
            chatDao.insertChat(chatEntity)
        } else {
            val merged =
                mergePersistedChatEntity(
                    incoming = chatEntity,
                    existing = existingEntity,
                    preserveStructure = preserveStructure && archiveFormatVersion == null,
                )
            chatDao.updateChat(
                merged.copy(folderId = normalizeChatFolderId(merged.folderId))
            )
        }

        messageDao.deleteAllMessagesForChat(chatEntity.id)
        messageVariantDao.deleteAllVariantsForChat(chatEntity.id)
        val messageEntities =
            resolvedHistory.messages.mapIndexed { index, message ->
                val archivedVariants =
                    variantsByTimestamp[message.timestamp]
                        .orEmpty()
                        .sortedBy { it.variantIndex }
                if (archivedVariants.isNotEmpty()) {
                    validateArchivedMessageVariants(message, archivedVariants)
                }
                MessageEntity.fromChatMessage(
                    chatEntity.id,
                    if (archivedVariants.isEmpty()) {
                        message.copy(selectedVariantIndex = 0, variantCount = 1)
                    } else {
                        message.copy(variantCount = archivedVariants.size + 1)
                    },
                    index,
                )
            }
        messageDao.insertMessages(messageEntities)

        val variantEntities =
            resolvedHistory.messages.flatMap { message ->
                variantsByTimestamp[message.timestamp]
                    .orEmpty()
                    .sortedBy { it.variantIndex }
                    .map { variant ->
                        variant.toEntity(
                            chatId = chatEntity.id,
                            messageTimestamp = message.timestamp,
                        )
                    }
            }
        if (variantEntities.isNotEmpty()) {
            messageVariantDao.insertVariants(variantEntities)
        }
        chatDao.recalculateLastMessageAt(chatEntity.id)
    }

    // 保存聊天历史
    suspend fun saveChatHistory(history: ChatHistory) {
        saveChatHistoryInternal(history)
    }

    private suspend fun saveArchivedChat(
        history: OperitArchivedChat,
        formatVersion: Int,
        folderId: String?,
    ) {
        val variantsByTimestamp =
            history.messages.associate { archivedMessage ->
                archivedMessage.baseMessage.timestamp to archivedMessage.variants
            }
        saveChatHistoryInternal(
            history.toChatHistory(history.isFavorite ?: false, folderId),
            variantsByTimestamp,
            archiveFormatVersion = formatVersion,
            archivedFavorite = history.isFavorite,
        )
    }

    private suspend fun saveArchivedChatInCurrentTransaction(
        history: OperitArchivedChat,
        formatVersion: Int,
        folderId: String?,
    ) {
        val variantsByTimestamp =
            history.messages.associate { archivedMessage ->
                archivedMessage.baseMessage.timestamp to archivedMessage.variants
            }
        persistChatHistoryInCurrentTransaction(
            history = history.toChatHistory(history.isFavorite ?: false, folderId),
            variantsByTimestamp = variantsByTimestamp,
            archiveFormatVersion = formatVersion,
            archivedFavorite = history.isFavorite,
        )
        when (ChatArchiveImportPolicy.todoImportMode(formatVersion)) {
            ChatArchiveImportPolicy.TodoImportMode.CLEAR ->
                chatTodoDao.deleteByChatId(history.id)
            ChatArchiveImportPolicy.TodoImportMode.RESTORE -> {
                val todos = history.todos.map(OperitArchivedTodo::toChatTodo)
                validateChatTodoSnapshot(todos)
                chatTodoDao.replaceForChat(
                    history.id,
                    history.todos.mapIndexed { index, todo -> todo.toEntity(history.id, index) },
                )
            }
        }
    }

    /** 更新聊天锁定状态 */
    suspend fun updateChatLocked(chatId: String, locked: Boolean) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatLocked(chatId, locked)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat locked state for chat $chatId", e)
                throw e
            }
        }
    }

    /** 更新聊天置顶状态 */
    suspend fun updateChatPinned(chatId: String, pinned: Boolean) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatPinned(chatId, pinned)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat pinned state for chat $chatId", e)
                throw e
            }
        }
    }

    /** 收藏状态不修改消息收藏、updatedAt 或 lastMessageAt。 */
    suspend fun updateChatFavorite(chatId: String, isFavorite: Boolean) {
        chatMutex(chatId).withLock {
            chatDao.updateChatFavorite(chatId, isFavorite)
        }
    }

    private suspend fun persistMessageLocked(chatId: String, messageToPersist: ChatMessage): ChatMessage {
        database.withTransaction {
            val nextOrderIndex = (messageDao.getMaxOrderIndex(chatId) ?: -1) + 1
            val messageEntity =
                MessageEntity.fromChatMessage(
                    chatId = chatId,
                    message = messageToPersist,
                    orderIndex = nextOrderIndex,
                )
            messageDao.insertMessage(messageEntity)
            chatDao.advanceLastMessageAt(chatId, messageToPersist.timestamp)

            chatDao.getChatById(chatId)?.let { chat ->
                chatDao.updateChatMetadata(
                    chatId = chatId,
                    title = chat.title,
                    timestamp = System.currentTimeMillis(),
                    inputTokens = chat.inputTokens,
                    outputTokens = chat.outputTokens,
                    currentWindowSize = chat.currentWindowSize
                )
            }
        }

        return messageToPersist
    }

    private suspend fun resolveAnchoredMessageLocked(
        chatId: String,
        message: ChatMessage,
        beforeTimestamp: Long?,
        afterTimestamp: Long?,
    ): ChatMessage? {
        if (beforeTimestamp == null && afterTimestamp == null) {
            val hasAnyMessages = messageDao.existsAnyMessage(chatId)
            return if (hasAnyMessages) {
                AppLogger.w(TAG, "缺少插入锚点，拒绝在非空聊天中插入消息: chatId=$chatId")
                null
            } else {
                message
            }
        }

        val beforeMessage =
            when {
                beforeTimestamp != null -> chatContentDao.getMessageByTimestamp(chatId, beforeTimestamp)
                afterTimestamp != null ->
                    chatContentDao
                        .getMessagesForChatBeforeTimestampExclusiveDesc(
                            chatId,
                            afterTimestamp,
                            1,
                        ).firstOrNull()

                else -> null
            }
        val afterMessage =
            when {
                beforeTimestamp != null && afterTimestamp == null ->
                    chatContentDao
                        .getMessagesForChatAfterTimestampExclusiveAsc(
                            chatId,
                            beforeTimestamp,
                            1,
                        ).firstOrNull()
                afterTimestamp != null -> chatContentDao.getMessageByTimestamp(chatId, afterTimestamp)
                else -> null
            }

        if (beforeTimestamp != null && beforeMessage == null) {
            AppLogger.w(
                TAG,
                "插入消息失败，未找到前置锚点: chatId=$chatId, beforeTimestamp=$beforeTimestamp",
            )
            return null
        }
        if (afterTimestamp != null && afterMessage == null) {
            AppLogger.w(
                TAG,
                "插入消息失败，未找到后置锚点: chatId=$chatId, afterTimestamp=$afterTimestamp",
            )
            return null
        }

        val actualBeforeTimestamp = beforeMessage?.timestamp
        val actualAfterTimestamp = afterMessage?.timestamp

        if (
            actualBeforeTimestamp != null &&
            actualAfterTimestamp != null &&
            actualBeforeTimestamp >= actualAfterTimestamp
        ) {
            AppLogger.w(
                TAG,
                "插入消息失败，前后锚点顺序非法: chatId=$chatId, before=$actualBeforeTimestamp, after=$actualAfterTimestamp",
            )
            return null
        }

        return when {
            actualBeforeTimestamp != null && actualAfterTimestamp != null -> {
                if (actualAfterTimestamp - actualBeforeTimestamp <= 1L) {
                    AppLogger.w(
                        TAG,
                        "插入消息失败，前后锚点时间戳间隔不足: chatId=$chatId, before=$actualBeforeTimestamp, after=$actualAfterTimestamp",
                    )
                    null
                } else {
                    message.copy(
                        timestamp =
                            actualBeforeTimestamp +
                                (actualAfterTimestamp - actualBeforeTimestamp) / 2L,
                    )
                }
            }

            actualBeforeTimestamp != null -> {
                message.copy(timestamp = actualBeforeTimestamp + 1L)
            }

            actualAfterTimestamp != null -> {
                message.copy(timestamp = actualAfterTimestamp - 1L)
            }

            else -> message
        }
    }

    suspend fun addSummaryMessageBetweenSliceNeighbors(
        chatId: String,
        message: ChatMessage,
        beforeTimestamp: Long?,
        afterTimestamp: Long?,
    ): ChatMessage? {
        chatMutex(chatId).withLock {
            try {
                val beforeMessage =
                    when {
                        beforeTimestamp != null -> chatContentDao.getMessageByTimestamp(chatId, beforeTimestamp)
                        afterTimestamp != null ->
                            chatContentDao
                                .getMessagesForChatBeforeTimestampExclusiveDesc(
                                    chatId,
                                    afterTimestamp,
                                    1,
                                ).firstOrNull()
                        else -> null
                    }
                val afterMessage =
                    when {
                        beforeTimestamp != null && afterTimestamp == null ->
                            chatContentDao
                                .getMessagesForChatAfterTimestampExclusiveAsc(
                                    chatId,
                                    beforeTimestamp,
                                    1,
                                ).firstOrNull()
                        afterTimestamp != null -> chatContentDao.getMessageByTimestamp(chatId, afterTimestamp)
                        else -> null
                    }

                if (beforeMessage?.sender == "summary" || afterMessage?.sender == "summary") {
                    AppLogger.w(
                        TAG,
                        "相邻消息已是 summary，取消插入: chatId=$chatId, before=${beforeMessage?.timestamp}, after=${afterMessage?.timestamp}",
                    )
                    return null
                }

                val messageToPersist =
                    resolveAnchoredMessageLocked(
                        chatId = chatId,
                        message = message,
                        beforeTimestamp = beforeTimestamp,
                        afterTimestamp = afterTimestamp,
                    ) ?: return null
                return persistMessageLocked(chatId, messageToPersist)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to add summary message between slice neighbors for chat $chatId", e)
                throw e
            }
        }
    }

    // 添加单条消息，并返回最终持久化的消息
    suspend fun addMessage(chatId: String, message: ChatMessage): ChatMessage {
        chatMutex(chatId).withLock {
            try {
                return persistMessageLocked(chatId, message)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to add message for chat $chatId", e)
                throw e
            }
        }
    }

    /**
     * 删除单条消息.
     * @param chatId 聊天ID
     * @param timestamp 消息时间戳
     */
    suspend fun deleteMessage(chatId: String, timestamp: Long) {
        chatMutex(chatId).withLock {
            try {
                AppLogger.d(TAG, "正在从数据库删除消息. ChatId: $chatId, Timestamp: $timestamp")
                database.withTransaction {
                    messageVariantDao.deleteVariantsForMessage(chatId, timestamp)
                    messageDao.deleteMessageByTimestamp(chatId, timestamp)
                    chatTodoDao.deleteByChatId(chatId)
                    chatDao.recalculateLastMessageAt(chatId)

                    // Update chat metadata
                    chatDao.getChatById(chatId)?.let { chat ->
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = chat.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = chat.inputTokens,
                            outputTokens = chat.outputTokens,
                            currentWindowSize = chat.currentWindowSize
                        )
                    }
                }
                AppLogger.d(TAG, "消息从数据库删除成功.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to delete message with timestamp $timestamp for chat $chatId", e)
                throw e
            }
        }
    }

    suspend fun deleteMessageVariant(
        chatId: String,
        messageTimestamp: Long,
        variantIndex: Int,
    ) {
        chatMutex(chatId).withLock {
            try {
                database.withTransaction {
                    val baseMessage =
                        chatContentDao.getMessageByTimestamp(chatId, messageTimestamp)
                            ?: throw IllegalArgumentException(
                                "Message $messageTimestamp does not exist in chat $chatId",
                            )
                    if (baseMessage.sender != "ai") {
                        throw IllegalArgumentException("Only AI messages can delete variants")
                    }

                    val variants =
                        chatContentDao.getVariantsForMessage(chatId, messageTimestamp)
                            .sortedBy { it.variantIndex }
                    if (variants.isEmpty()) {
                        throw IllegalStateException("Message $messageTimestamp has no deletable variants")
                    }

                    if (variantIndex == 0) {
                        val replacementVariant =
                            variants.firstOrNull()
                                ?: throw IllegalStateException(
                                    "Message $messageTimestamp has no replacement variant",
                                )
                        val promotedBaseMessage =
                            baseMessage.copy(
                                content = replacementVariant.content,
                                roleName = replacementVariant.roleName.ifBlank { baseMessage.roleName },
                                selectedVariantIndex = 0,
                                provider = replacementVariant.provider,
                                modelName = replacementVariant.modelName,
                                inputTokens = replacementVariant.inputTokens,
                                outputTokens = replacementVariant.outputTokens,
                                cachedInputTokens = replacementVariant.cachedInputTokens,
                                sentAt = replacementVariant.sentAt,
                                outputDurationMs = replacementVariant.outputDurationMs,
                                waitDurationMs = replacementVariant.waitDurationMs,
                                completedAt = replacementVariant.completedAt,
                            )
                        messageDao.updateMessage(promotedBaseMessage)
                        messageVariantDao.deleteVariant(
                            chatId = chatId,
                            messageTimestamp = messageTimestamp,
                            variantIndex = replacementVariant.variantIndex,
                        )
                        variants
                            .asSequence()
                            .filter { it.variantIndex > replacementVariant.variantIndex }
                            .forEach { variant ->
                                messageVariantDao.updateVariant(
                                    variant.copy(variantIndex = variant.variantIndex - 1),
                                )
                            }
                    } else {
                        val targetVariant =
                            variants.firstOrNull { it.variantIndex == variantIndex }
                                ?: throw IllegalArgumentException(
                                    "Variant $variantIndex does not exist for message $messageTimestamp",
                                )
                        messageVariantDao.deleteVariant(
                            chatId = chatId,
                            messageTimestamp = messageTimestamp,
                            variantIndex = targetVariant.variantIndex,
                        )
                        variants
                            .asSequence()
                            .filter { it.variantIndex > targetVariant.variantIndex }
                            .forEach { variant ->
                                messageVariantDao.updateVariant(
                                    variant.copy(variantIndex = variant.variantIndex - 1),
                                )
                            }
                        val newSelectedVariantIndex =
                            when {
                                variants.any { it.variantIndex > targetVariant.variantIndex } -> targetVariant.variantIndex
                                else -> (targetVariant.variantIndex - 1).coerceAtLeast(0)
                            }
                        messageDao.updateSelectedVariantIndex(
                            chatId = chatId,
                            timestamp = messageTimestamp,
                            selectedVariantIndex = newSelectedVariantIndex,
                        )
                    }

                    chatTodoDao.deleteByChatId(chatId)
                    chatDao.getChatById(chatId)?.let { chat ->
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = chat.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = chat.inputTokens,
                            outputTokens = chat.outputTokens,
                            currentWindowSize = chat.currentWindowSize,
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to delete variant $variantIndex for message $messageTimestamp in chat $chatId",
                    e,
                )
                throw e
            }
        }
    }

    // 更新现有消息
    suspend fun updateMessage(chatId: String, message: ChatMessage) {
        updateMessage(chatId, message, clearTodosAfterUpdate = false)
    }

    suspend fun updateMessage(
        chatId: String,
        message: ChatMessage,
        clearTodosAfterUpdate: Boolean,
    ) {
        chatMutex(chatId).withLock {
            try {
                database.withTransaction {
                    val existingMessage = chatContentDao.getMessageByTimestamp(chatId, message.timestamp)

                    if (existingMessage != null) {
                        if (message.selectedVariantIndex > 0) {
                            val existingVariant =
                                chatContentDao.getVariantForMessage(
                                    chatId,
                                    message.timestamp,
                                    message.selectedVariantIndex,
                                ) ?: throw IllegalStateException(
                                    "Missing variant ${message.selectedVariantIndex} for message ${message.timestamp}",
                                )
                            messageVariantDao.updateVariant(
                                MessageVariantEntity.fromChatMessage(
                                    chatId = chatId,
                                    messageTimestamp = message.timestamp,
                                    variantIndex = message.selectedVariantIndex,
                                    message = message,
                                    variantId = existingVariant.variantId,
                                )
                            )
                            messageDao.updateSelectedVariantIndex(
                                chatId,
                                message.timestamp,
                                message.selectedVariantIndex,
                            )
                            if (clearTodosAfterUpdate) {
                                chatTodoDao.deleteByChatId(chatId)
                            }
                            chatDao.getChatById(chatId)?.let { chat ->
                                chatDao.updateChatMetadata(
                                    chatId = chatId,
                                    title = chat.title,
                                    timestamp = System.currentTimeMillis(),
                                    inputTokens = chat.inputTokens,
                                    outputTokens = chat.outputTokens,
                                    currentWindowSize = chat.currentWindowSize
                                )
                            }
                            return@withTransaction
                        }

                        val shouldUpdateChatMetadata =
                            message.contentStream == null ||
                                (existingMessage.content.isEmpty() && message.content.isNotEmpty())
                        val updatedMessageEntity =
                            MessageEntity.fromChatMessage(
                                chatId = chatId,
                                message = message,
                                orderIndex = existingMessage.orderIndex,
                                messageId = existingMessage.messageId
                            )
                        messageDao.updateMessage(updatedMessageEntity)

                        if (shouldUpdateChatMetadata) {
                            chatDao.getChatById(chatId)?.let { chat ->
                                chatDao.updateChatMetadata(
                                    chatId = chatId,
                                    title = chat.title,
                                    timestamp = System.currentTimeMillis(),
                                    inputTokens = chat.inputTokens,
                                    outputTokens = chat.outputTokens,
                                    currentWindowSize = chat.currentWindowSize
                                )
                            }
                        }
                    } else {
                        val nextOrderIndex = (messageDao.getMaxOrderIndex(chatId) ?: -1) + 1
                        val messageEntity =
                            MessageEntity.fromChatMessage(
                                chatId = chatId,
                                message = message,
                                orderIndex = nextOrderIndex,
                            )
                        messageDao.insertMessage(messageEntity)
                        chatDao.advanceLastMessageAt(chatId, message.timestamp)

                        chatDao.getChatById(chatId)?.let { chat ->
                            chatDao.updateChatMetadata(
                                chatId = chatId,
                                title = chat.title,
                                timestamp = System.currentTimeMillis(),
                                inputTokens = chat.inputTokens,
                                outputTokens = chat.outputTokens,
                                currentWindowSize = chat.currentWindowSize
                            )
                        }
                    }
                    if (clearTodosAfterUpdate) {
                        chatTodoDao.deleteByChatId(chatId)
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun setMessageFavorite(chatId: String, timestamp: Long, isFavorite: Boolean) {
        chatMutex(chatId).withLock {
            try {
                val existingMessage =
                    chatContentDao.getMessageByTimestamp(chatId, timestamp) ?: return@withLock
                if (existingMessage.isFavorite == isFavorite) {
                    return@withLock
                }
                messageDao.updateMessageFavorite(chatId, timestamp, isFavorite)
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to update favorite state for message $timestamp in chat $chatId",
                    e,
                )
                throw e
            }
        }
    }

    suspend fun addMessageVariant(
        chatId: String,
        messageTimestamp: Long,
        message: ChatMessage,
    ): Int {
        return chatMutex(chatId).withLock {
            database.withTransaction {
                val baseMessage =
                    chatContentDao.getMessageByTimestamp(chatId, messageTimestamp)
                        ?: throw IllegalArgumentException("Message $messageTimestamp does not exist in chat $chatId")
                if (baseMessage.sender != "ai") {
                    throw IllegalArgumentException("Only AI messages can have regenerated variants")
                }
                val nextVariantIndex =
                    chatContentDao.getVariantsForMessage(chatId, messageTimestamp).size + 1
                messageVariantDao.insertVariant(
                    MessageVariantEntity.fromChatMessage(
                        chatId = chatId,
                        messageTimestamp = messageTimestamp,
                        variantIndex = nextVariantIndex,
                        message = message.copy(selectedVariantIndex = nextVariantIndex, variantCount = 1),
                    )
                )
                messageDao.updateSelectedVariantIndex(chatId, messageTimestamp, nextVariantIndex)
                chatDao.getChatById(chatId)?.let { chat ->
                    chatDao.updateChatMetadata(
                        chatId = chatId,
                        title = chat.title,
                        timestamp = System.currentTimeMillis(),
                        inputTokens = chat.inputTokens,
                        outputTokens = chat.outputTokens,
                        currentWindowSize = chat.currentWindowSize
                    )
                }
                nextVariantIndex
            }
        }
    }

    suspend fun selectMessageVariant(
        chatId: String,
        messageTimestamp: Long,
        selectedVariantIndex: Int,
    ) {
        chatMutex(chatId).withLock {
            database.withTransaction {
                chatContentDao.getMessageByTimestamp(chatId, messageTimestamp)
                    ?: throw IllegalArgumentException("Message $messageTimestamp does not exist in chat $chatId")
                if (selectedVariantIndex > 0) {
                    chatContentDao.getVariantForMessage(chatId, messageTimestamp, selectedVariantIndex)
                        ?: throw IllegalArgumentException(
                            "Variant $selectedVariantIndex does not exist for message $messageTimestamp",
                        )
                }
                messageDao.updateSelectedVariantIndex(chatId, messageTimestamp, selectedVariantIndex)
                chatTodoDao.deleteByChatId(chatId)
            }
        }
    }

    suspend fun clearTodoSnapshot(chatId: String) {
        chatMutex(chatId).withLock {
            database.withTransaction {
                chatTodoDao.deleteByChatId(chatId)
            }
        }
    }

    /**
     * 从数据库中删除指定时间戳之后的所有消息。 这需要您在MessageDao中添加相应的@Query。
     *
     * 示例:
     * ```
     * @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp >= :timestamp")
     * suspend fun deleteMessagesFrom(chatId: String, timestamp: Long)
     * ```
     */
    suspend fun deleteMessagesFrom(chatId: String, timestamp: Long) {
        chatMutex(chatId).withLock {
            try {
                AppLogger.d(TAG, "正在从数据库删除消息. ChatId: $chatId, Timestamp >=: $timestamp")
                database.withTransaction {
                    messageVariantDao.deleteVariantsFrom(chatId, timestamp)
                    messageDao.deleteMessagesFrom(chatId, timestamp)
                    chatTodoDao.deleteByChatId(chatId)
                    chatDao.recalculateLastMessageAt(chatId)
                    // 更新聊天元数据时间戳
                    chatDao.getChatById(chatId)?.let { chat ->
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = chat.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = chat.inputTokens,
                            outputTokens = chat.outputTokens,
                            currentWindowSize = chat.currentWindowSize
                        )
                    }
                }
                AppLogger.d(TAG, "后续消息从数据库删除成功.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "从 $timestamp 开始为聊天 $chatId 删除消息失败", e)
                throw e
            }
        }
    }

    /**
     * 清除一个聊天中的所有消息，但保留聊天本身。
     *
     * 这需要您在MessageDao中添加相应的@Query。
     * ```
     * @Query("DELETE FROM messages WHERE chatId = :chatId")
     * suspend fun deleteAllMessagesForChat(chatId: String)
     * ```
     */
    suspend fun clearChatMessages(chatId: String) {
        chatMutex(chatId).withLock {
            try {
                database.withTransaction {
                    messageVariantDao.deleteAllVariantsForChat(chatId)
                    messageDao.deleteAllMessagesForChat(chatId)
                    chatTodoDao.deleteByChatId(chatId)
                    chatDao.recalculateLastMessageAt(chatId)
                    // 更新聊天元数据
                    chatDao.getChatById(chatId)?.let { chat ->
                        chatDao.updateChatMetadata(
                            chatId = chatId,
                            title = chat.title,
                            timestamp = System.currentTimeMillis(),
                            inputTokens = 0,
                            outputTokens = 0,
                            currentWindowSize = 0
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "为聊天 $chatId 清除消息失败", e)
                throw e
            }
        }
    }

    // 更新聊天标题
    suspend fun updateChatTitle(chatId: String, title: String) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatTitle(chatId, title)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat title for chat $chatId", e)
                throw e
            }
        }
    }

    // 更新聊天绑定的角色卡
    suspend fun updateChatCharacterCardName(chatId: String, characterCardName: String?) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatCharacterCardName(chatId, characterCardName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat character card for chat $chatId", e)
                throw e
            }
        }
    }

    // 更新聊天的token计数
    suspend fun updateChatTokenCounts(
        chatId: String,
        inputTokens: Int,
        outputTokens: Int,
        currentWindowSize: Int
    ) {
        chatMutex(chatId).withLock {
            try {
                val chat = chatDao.getChatById(chatId)
                if (chat != null) {
                    chatDao.updateChatMetadata(
                        chatId = chatId,
                        title = chat.title,
                        timestamp = System.currentTimeMillis(),
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        currentWindowSize = currentWindowSize
                    )
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    // 设置当前聊天ID
    suspend fun setCurrentChatId(chatId: String) {
        context.currentChatIdDataStore.edit { preferences ->
            preferences[PreferencesKeys.CURRENT_CHAT_ID] = chatId
        }
    }

    // 清除当前聊天ID
    suspend fun clearCurrentChatId() {
        context.currentChatIdDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
        }
    }

    // 检查聊天是否存在
    suspend fun chatExists(chatId: String): Boolean {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                chatDao.getChatById(chatId) != null
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to check chat existence for chat $chatId", e)
                false
            }
        }
    }

    suspend fun canDeleteChatHistory(chatId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val chat = chatDao.getChatById(chatId)
                chat != null && chat.locked != true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to check whether chat $chatId can be deleted", e)
                false
            }
        }
    }

    suspend fun getSubagentChildCount(chatId: String): Int =
        withContext(Dispatchers.IO) {
            subagentRunRepository.countChildren(chatId)
        }

    /**
     * A deleted conversation can never be reviewed again, so its learning progress and prompt snapshot
     * are dropped here. Keeping them would only make the memory coordinator retry them later.
     */
    private suspend fun forgetDeletedChats(chatIds: Collection<String>) {
        chatIds.forEach { id ->
            runCatching {
                com.ai.assistance.operit.ui.common.dynamicui.DynamicUiStateStore.deleteChat(context, id)
            }.onFailure { AppLogger.w(TAG, "Failed to drop card state of deleted chat $id", it) }
            runCatching {
                com.ai.assistance.operit.ui.permissions.PermissionReviewArchive.deleteChat(context, id)
            }.onFailure { AppLogger.w(TAG, "Failed to drop review archive of deleted chat $id", it) }
            // Housekeeping must never turn a completed deletion into a reported failure.
            runCatching {
                // Cancel only: marking a deleted conversation as a live turn would leave the
                // coordinator permanently "busy" because it can never report the turn's end.
                com.ai.assistance.operit.api.chat.library.MemoryLearningCoordinator.forgetChat(id)
                com.ai.assistance.operit.api.chat.library.MemoryLearningJournal.deleteChat(context,id)
                com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository(context,id).delete()
            }.onFailure { AppLogger.w(TAG, "Failed to drop learning state of deleted chat $id", it) }
        }
    }

    // 删除聊天历史
    suspend fun deleteChatHistory(chatId: String): Boolean {
        chatMutex(chatId).withLock {
            try {
                val chat = chatDao.getChatById(chatId)
                if (chat?.locked == true) {
                    AppLogger.w(TAG, "Chat $chatId is locked; skip deletion")
                    return false
                }
                if (chat == null) {
                    return false
                }
                val subtreeChatIds = subagentRunRepository.getChatSubtreeChatIds(chatId).toSet()
                val deleted =
                    SubagentCoordinator.getInstance(context)
                        .withChatDeletionsPrepared(subtreeChatIds) {
                            subagentRunRepository.deleteChatSubtree(
                                chatId = chatId,
                                expectedChatIds = subtreeChatIds,
                            )
                        }
                if (!deleted) {
                    AppLogger.w(TAG, "Chat $chatId subtree deletion refused (locked or missing)")
                    return false
                }
                forgetDeletedChats(subtreeChatIds)
                // 如果删除的是当前聊天，清除当前聊天ID
                val currentChatId = currentChatIdFlow.first()
                if (currentChatId == chatId) {
                    context.currentChatIdDataStore.edit { preferences ->
                        preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
                    }
                }
                return true
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun buildNewChatHistory(
        folderId: String?,
        characterCardName: String?,
        characterGroupId: String?,
        contextPolicyJson: String,
    ): ChatHistory {
        val dateTime = LocalDateTime.now()
        val formattedTime =
            "${dateTime.hour}:${
                dateTime.minute.toString().padStart(2, '0')
            }:${dateTime.second.toString().padStart(2, '0')}"
        val localizedContext = LocaleUtils.getLocalizedContext(context)

        return ChatHistory(
            title = "${localizedContext.getString(R.string.new_conversation)} $formattedTime",
            messages = emptyList(),
            inputTokens = 0,
            outputTokens = 0,
            folderId = folderId,
            characterCardName = characterCardName,
            characterGroupId = characterGroupId,
            contextPolicyJson = contextPolicyJson,
        )
    }

    // 创建新对话
    suspend fun createNewChat(
        folderId: String? = null,
        inheritGroupFromChatId: String? = null,
        characterCardName: String? = null,
        characterGroupId: String? = null,
        setAsCurrentChat: Boolean = true
    ): ChatHistory {
        val contextPolicy = com.ai.assistance.operit.data.preferences.ChatContextPreferences(context)
            .defaults.first().encode()
        val newHistory =
            createAndPersistNewChatHistory(
                chatDao = chatDao,
                chatFolderDao = chatFolderDao,
                folderId = folderId,
                inheritGroupFromChatId = inheritGroupFromChatId,
            ) { finalFolderId ->
                buildNewChatHistory(
                    folderId = finalFolderId,
                    characterCardName = characterCardName,
                    characterGroupId = characterGroupId,
                    contextPolicyJson = contextPolicy,
                )
            }
        releaseLegacyFolderReservations(listOf(newHistory.folderId))

        // 设置为当前聊天
        if (setAsCurrentChat) {
            setCurrentChatId(newHistory.id)
        }

        return newHistory
    }

    /**
     * 创建一个隐藏的普通顶层聊天（chatKind=NORMAL、parentChatId=null、isHidden=true）。
     *
     * 阅读伴侣审计父聊天（每书一个 hiddenReason=READING_COMPANION_AUDIT_ROOT:<bookId>）
     * 使用本入口创建；隐藏聊天不进入普通列表/统计，只能通过隐藏入口查看与删除。
     */
    suspend fun createHiddenNormalChat(title: String, hiddenReason: String): ChatHistory {
        require(hiddenReason.isNotBlank()) { "hiddenReason is required for hidden chats" }
        val now = System.currentTimeMillis()
        val entity =
            ChatEntity(
                title = title,
                createdAt = now,
                updatedAt = now,
                displayOrder = -now,
                chatKind = ChatKind.NORMAL.name,
                isHidden = true,
                hiddenReason = hiddenReason,
            )
        chatDao.insertChat(entity)
        return entity.toChatHistory().copy(folderId = normalizeChatFolderId(entity.folderId))
    }

    /**
     * 切换聊天隐藏状态。hiddenReason 以 [READING_COMPANION_PERMANENT_HIDDEN_REASON_PREFIX]
     * 开头的审计聊天永久隐藏，禁止 hidden=false（只能查看/删除）。
     */
    suspend fun setChatHidden(
        chatId: String,
        hidden: Boolean,
        hiddenReason: String? = null,
    ): Boolean {
        val before = chatDao.getChatById(chatId) ?: return false
        val effectiveReason = before.hiddenReason ?: hiddenReason
        if (
            !hidden &&
            effectiveReason?.startsWith(READING_COMPANION_PERMANENT_HIDDEN_REASON_PREFIX) == true
        ) {
            throw IllegalStateException(
                "Reading companion audit chats are permanently hidden and cannot be unhidden"
            )
        }
        if (hidden && hiddenReason.isNullOrBlank()) {
            throw IllegalArgumentException("hiddenReason is required when hiding a chat")
        }
        chatDao.updateChat(
            before.copy(
                isHidden = hidden,
                hiddenReason = if (hidden) hiddenReason else null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun createFolderWithInitialChat(
        parentFolderId: String?,
        folderName: String,
        characterCardName: String?,
        characterGroupId: String?,
        openingMessage: ChatMessage? = null,
    ): ChatHistory {
        val initialHistory =
            buildNewChatHistory(
                folderId = null,
                characterCardName = characterCardName,
                characterGroupId = characterGroupId,
                contextPolicyJson = com.ai.assistance.operit.data.preferences.ChatContextPreferences(context)
                    .defaults.first().encode(),
            )
        val initialEntity = ChatEntity.fromChatHistory(initialHistory)
        val openingMessageEntity =
            openingMessage?.let {
                MessageEntity.fromChatMessage(
                    chatId = initialHistory.id,
                    message = it,
                    orderIndex = 0,
                )
            }
        val persistedEntity =
            chatFolderRepository.createFolderWithInitialChat(
                parentFolderId = parentFolderId,
                name = folderName,
                initialChat = initialEntity,
                openingMessage = openingMessageEntity,
            )
        return persistedEntity.toChatHistory(listOfNotNull(openingMessage))
    }

    /**
     * Phase-0 repository entry for creating an off-screen Subagent child.
     *
     * This intentionally does not change the globally selected chat and does not use the
     * UI-oriented ChatHistoryDelegate.createNewChat path.
     */
    suspend fun createSubagentSliceChat(
        parentChatId: String,
        title: String,
    ): ChatHistory =
        withContext(Dispatchers.IO) {
            val parent =
                requireNotNull(chatDao.getChatById(parentChatId)) {
                    "Parent chat does not exist: $parentChatId"
                }
            val now = System.currentTimeMillis()
            val child =
                ChatEntity(
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    folderId = parent.folderId,
                    displayOrder = -now,
                    workspace = parent.workspace,
                    workspaceEnv = parent.workspaceEnv,
                    parentChatId = parent.id,
                    chatKind = ChatKind.SUBAGENT.name,
                    characterCardName = parent.characterCardName,
                )
            chatDao.insertChat(child)
            child.toChatHistory(emptyList())
        }

    /** 更新聊天工作区 */
    suspend fun updateChatWorkspace(chatId: String, workspace: String?, workspaceEnv: String?) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatWorkspace(chatId, workspace, workspaceEnv)
                com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository(context, chatId).markChatChanged()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat workspace for chat $chatId", e)
                throw e
            }
        }
    }

    suspend fun renameManagedWorkspace(
        chatId: String,
        newWorkspaceName: String
    ): WorkspaceRenameResult {
        chatMutex(chatId).withLock {
            val trimmedName = newWorkspaceName.trim()
            if (trimmedName.isEmpty()) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_empty))
            }
            if (
                trimmedName == "." ||
                trimmedName == ".." ||
                trimmedName.contains('/') ||
                trimmedName.contains('\\')
            ) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_invalid))
            }

            val chat = chatDao.getChatById(chatId)
                ?: throw IllegalStateException(context.getString(R.string.workspace_rename_chat_missing))
            val workspacePath = chat.workspace
                ?: throw IllegalStateException(context.getString(R.string.chat_not_bound_to_workspace))
            if (!chat.workspaceEnv.isNullOrBlank()) {
                throw IllegalStateException(
                    context.getString(R.string.workspace_rename_only_managed_supported)
                )
            }

            val workspaceRoot = File(context.filesDir, "workspace").apply { mkdirs() }.canonicalFile
            val sourceDir = File(workspacePath).canonicalFile
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                throw IllegalStateException(context.getString(R.string.workspace_directory_invalid))
            }
            if (sourceDir.parentFile?.canonicalFile != workspaceRoot) {
                throw IllegalStateException(
                    context.getString(R.string.workspace_rename_only_managed_supported)
                )
            }

            val targetDir = File(workspaceRoot, trimmedName).canonicalFile
            if (targetDir.parentFile?.canonicalFile != workspaceRoot) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_invalid))
            }
            if (targetDir != sourceDir && targetDir.exists()) {
                throw IllegalArgumentException(context.getString(R.string.workspace_rename_name_exists))
            }

            if (targetDir != sourceDir && !sourceDir.renameTo(targetDir)) {
                throw IOException(context.getString(R.string.workspace_rename_failed))
            }

            chatDao.updateChatTitleAndWorkspace(
                chatId = chatId,
                title = trimmedName,
                workspace = targetDir.absolutePath,
                workspaceEnv = chat.workspaceEnv
            )
            com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository(context, chatId).markChatChanged()

            return WorkspaceRenameResult(
                workspacePath = targetDir.absolutePath,
                workspaceEnv = chat.workspaceEnv,
                workspaceName = trimmedName
            )
        }
    }

    suspend fun updateChatFolder(chatId: String, folderId: String?) {
        chatMutex(chatId).withLock {
            try {
                val normalizedFolderId =
                    normalizeChatFolderId(folderId)
                if (normalizedFolderId != null) {
                    require(chatFolderDao.getFolder(normalizedFolderId) != null) {
                        "Unknown folderId: $normalizedFolderId"
                    }
                }
                chatDao.updateChatFolder(chatId, normalizedFolderId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat folder for chat $chatId", e)
                throw e
            }
        }
    }

    suspend fun updateChatFromWeb(
        chatId: String,
        title: String?,
        updateFolder: Boolean,
        folderId: String?,
        locked: Boolean?,
        pinned: Boolean?,
        updateBinding: Boolean,
        characterCardName: String?,
        characterGroupId: String?,
    ): Boolean {
        val updated =
            chatMutex(chatId).withLock {
                database.withTransaction {
                    if (chatDao.getChatById(chatId) == null) {
                        return@withTransaction false
                    }
                    val normalizedFolderId =
                        normalizeChatFolderId(folderId)
                    if (updateFolder && normalizedFolderId != null) {
                        require(chatFolderDao.getFolder(normalizedFolderId) != null) {
                            "Unknown folderId: $normalizedFolderId"
                        }
                    }
                    val timestamp = System.currentTimeMillis()
                    title?.let { chatDao.updateChatTitle(chatId, it, timestamp) }
                    if (updateFolder) {
                        chatDao.updateChatFolder(chatId, normalizedFolderId, timestamp)
                    }
                    locked?.let { chatDao.updateChatLocked(chatId, it, timestamp) }
                    pinned?.let { chatDao.updateChatPinned(chatId, it, timestamp) }
                    if (updateBinding) {
                        chatDao.updateChatCharacterBinding(
                            chatId = chatId,
                            characterCardName = characterCardName,
                            characterGroupId = characterGroupId,
                            timestamp = timestamp,
                        )
                    }
                    true
                }
            }
        if (updated && updateFolder) {
            releaseLegacyFolderReservations(listOf(folderId))
        }
        return updated
    }

    suspend fun getChatTitle(chatId: String): String? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                chatDao.getChatById(chatId)?.title
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get chat title for chat $chatId", e)
                null
            }
        }
    }

    // 直接加载聊天消息
    suspend fun loadChatMessagesOrThrow(chatId: String): List<ChatMessage> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            hydrateMessages(chatId, chatContentDao.getMessagesForChat(chatId))
        }

    suspend fun loadChatMessages(chatId: String): List<ChatMessage> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                // AppLogger.d(TAG, "直接从数据库加载聊天 $chatId 的消息")
                val messageEntities = chatContentDao.getMessagesForChat(chatId)
                // AppLogger.d(TAG, "聊天 $chatId 共加载 ${messages.size} 条消息")
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessages(
        chatId: String,
        order: String? = null,
        limit: Int? = null
    ): List<ChatMessage> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val normalizedOrder = order?.trim()?.lowercase()
                val effectiveLimit = limit?.coerceAtLeast(1)

                val messageEntities = when (normalizedOrder) {
                    "desc" -> {
                        if (effectiveLimit != null) {
                            chatContentDao.getMessagesForChatDesc(chatId, effectiveLimit)
                        } else {
                            chatContentDao.getMessagesForChat(chatId).asReversed()
                        }
                    }

                    else -> {
                        if (effectiveLimit != null) {
                            chatContentDao.getMessagesForChatAsc(chatId, effectiveLimit)
                        } else {
                            chatContentDao.getMessagesForChat(chatId)
                        }
                    }
                }

                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesRange(
        chatId: String,
        order: String? = null,
        start: Int,
        end: Int,
    ): List<ChatMessage> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val normalizedOrder = order?.trim()?.lowercase()
                val limit = end - start + 1

                val messageEntities = when (normalizedOrder) {
                    "desc" -> chatContentDao.getMessagesForChatDescRange(chatId, start, limit)
                    else -> chatContentDao.getMessagesForChatAscRange(chatId, start, limit)
                }

                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按区间加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    /** 搜索包含特定关键词的聊天ID列表 */
    suspend fun searchChatIdsByContent(query: String): Set<String> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                if (query.isBlank()) {
                    return@withContext emptySet()
                }
                val escapedQuery =
                    query
                        .trim()
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")

                val chatIds = messageDao.searchChatIdsByContent(escapedQuery)
                chatIds.toSet()
            } catch (e: Exception) {
                AppLogger.e(TAG, "搜索聊天内容失败: $query", e)
                emptySet()
            }
        }
    }

    /**
     * 创建对话分支
     * @param parentChatId 父对话ID
     * @param upToMessageTimestamp 复制消息到指定时间戳（包含该时间戳的消息）
     * @return 新创建的分支对话
     */
    suspend fun createBranch(
        parentChatId: String,
        upToMessageTimestamp: Long? = null
    ): ChatHistory {
        return globalMutex.withLock {
            try {
                // 获取父对话
                val parentChat = chatDao.getChatById(parentChatId)
                    ?: throw IllegalArgumentException(context.getString(R.string.chat_history_parent_not_exist, parentChatId))

                val branchEntity =
                    ChatEntity(
                        title = parentChat.title,
                        inputTokens = parentChat.inputTokens,
                        outputTokens = parentChat.outputTokens,
                        currentWindowSize = parentChat.currentWindowSize,
                        folderId = normalizeChatFolderId(parentChat.folderId),
                        workspace = parentChat.workspace,
                        workspaceEnv = parentChat.workspaceEnv,
                        parentChatId = parentChatId,
                        chatKind = ChatKind.BRANCH.name,
                        contextPolicyJson = parentChat.contextPolicyJson,
                        characterCardName = parentChat.characterCardName,
                        characterGroupId = parentChat.characterGroupId,
                        locked = false,
                        pinned = false,
                    )

                val copyResult =
                    chatBranchRepository.copyBranch(
                        sourceChatId = parentChatId,
                        branch = branchEntity,
                        upToTimestampInclusive = upToMessageTimestamp,
                    )

                // Card selections and live data live beside the chat, keyed by message timestamp.
                copyResult.copiedChatIds.forEach { (sourceId, targetId) ->
                    runCatching {
                        com.ai.assistance.operit.ui.common.dynamicui.DynamicUiStateStore.copyChat(context, sourceId, targetId)
                    }.onFailure { AppLogger.w(TAG, "Failed to copy card state into branch $targetId", it) }
                }

                val branchHistory =
                    copyResult.branch
                        .toChatHistory(emptyList())

                // 设置为当前聊天
                setCurrentChatId(branchHistory.id)

                AppLogger.d(
                    TAG,
                    "创建分支对话: ${branchHistory.id}, 父对话: $parentChatId, " +
                        "消息数: ${copyResult.copiedMessageCount}, " +
                        "Subagent数: ${copyResult.copiedSubagentCount}"
                )
                branchHistory
            } catch (e: Exception) {
                AppLogger.e(TAG, "创建分支对话失败", e)
                throw e
            }
        }
    }

    /**
     * 获取指定对话的所有分支
     * @param parentChatId 父对话ID
     * @return 分支对话列表
     */
    suspend fun getBranches(parentChatId: String): List<ChatHistory> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val branchEntities = chatDao.getBranchesByParentId(parentChatId)
                branchEntities.map { it.toChatHistory() }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取分支对话失败: $parentChatId", e)
                emptyList()
            }
        }
    }

    /**
     * 获取指定对话的所有分支（Flow版本）
     * @param parentChatId 父对话ID
     * @return 分支对话Flow
     */
    fun getBranchesFlow(parentChatId: String): Flow<List<ChatHistory>> {
        return chatDao.getBranchesByParentIdFlow(parentChatId).map { branchEntities ->
            branchEntities.map { it.toChatHistory() }
        }
    }

    /**
     * 导出所有聊天记录到「下载/Operit」目录（默认 JSON 格式）
     * @return 生成的文件绝对路径，失败时返回null
     */
    suspend fun exportChatHistoriesToDownloads(): String? =
        exportChatHistoriesToDownloads(ExportFormat.JSON)

    /**
     * 导出所有聊天记录到「下载/Operit」目录（支持多种格式）
     * @param format 导出格式
     * @return 生成的文件绝对路径，失败时返回null
     */
    suspend fun exportChatHistoriesToDownloads(format: ExportFormat): String? =
        withContext(Dispatchers.IO) {
            try {
                val chatHistoriesBasic = allChatHistoriesInternalFlow.first()
                val folderPathsByChatId = buildFolderPathsByChatId(chatHistoriesBasic)

                val exportDir = OperitBackupDirs.chatDir()

                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                val timestamp = dateFormat.format(Date())

                val exportFile = when (format) {
                    ExportFormat.MARKDOWN -> {
                        val completeHistories = loadDisplayHistories(chatHistoriesBasic)
                        val zipFile = File(exportDir, "chat_backup_$timestamp.zip")
                        val usedNames = HashSet<String>()
                        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                            for (history in completeHistories) {
                                val content =
                                    MarkdownExporter.exportSingle(
                                        context,
                                        history,
                                        folderPathsByChatId[history.id],
                                    )
                                // 处理文件名中的非法字符
                                var safeTitle = history.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                // 避免文件名过长
                                if (safeTitle.length > 50) {
                                    safeTitle = safeTitle.substring(0, 50)
                                }
                                safeTitle = safeTitle.trim()
                                
                                // 确保文件名唯一
                                var baseName = "$safeTitle.md"
                                var counter = 1
                                while (usedNames.contains(baseName)) {
                                    baseName = "$safeTitle ($counter).md"
                                    counter++
                                }
                                usedNames.add(baseName)

                                zos.putNextEntry(ZipEntry(baseName))
                                zos.write(content.toByteArray())
                                zos.closeEntry()
                            }
                        }
                        zipFile
                    }

                    ExportFormat.JSON -> {
                        val file = File(exportDir, "chat_backup_$timestamp.json")
                        exportOperitArchiveJsonStream(file)
                        file
                    }

                    ExportFormat.HTML, ExportFormat.TXT -> {
                        val extension = if (format == ExportFormat.HTML) "html" else "txt"
                        val file = File(exportDir, "chat_backup_$timestamp.$extension")
                        try {
                            writePagedTextExport(file, format, chatHistoriesBasic, folderPathsByChatId)
                        } catch (error: Exception) {
                            file.delete()
                            throw error
                        }
                        file
                    }

                    ExportFormat.CSV -> {
                        val file = File(exportDir, "chat_backup_$timestamp.json")
                        exportOperitArchiveJsonStream(file)
                        file
                    }
                }

                exportFile.absolutePath
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "导出聊天记录失败", e)
                null
            }
        }

    private suspend fun writePagedTextExport(
        file: File,
        format: ExportFormat,
        histories: List<ChatHistory>,
        folderPaths: Map<String, String>,
    ) {
        val html = format == ExportFormat.HTML
        // Keep counts, pages and selected variants in one read view while retaining only a page.
        database.withTransaction {
            val counts = histories.associate { history ->
                history.id to messageDao.countMessagesForChatUpToTimestamp(history.id, null)
            }
            file.bufferedWriter().use { writer ->
                if (html) {
                    HtmlExporter.writeMultipleHeader(context, histories, counts.values.sum(), writer)
                } else {
                    TextExporter.writeMultipleHeader(context, histories, counts.values.sum(), writer)
                }
                for ((index, history) in histories.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val count = counts.getValue(history.id)
                    if (html) {
                        HtmlExporter.writeConversationSeparator(writer, index)
                        HtmlExporter.writeConversationHeader(context, writer, history, count, folderPaths[history.id])
                    } else {
                        TextExporter.writeConversationSeparator(writer, index)
                        TextExporter.writeConversationHeader(context, history, writer, count, folderPaths[history.id])
                    }
                    var offset = 0
                    while (offset < count) {
                        currentCoroutineContext().ensureActive()
                        val rows = chatContentDao.getMessagesForChatAscRange(history.id, offset, 32)
                        check(rows.isNotEmpty()) { "Export page missing for ${history.id}" }
                        for ((pageIndex, message) in hydrateMessages(history.id, rows).withIndex()) {
                            val jobContext = currentCoroutineContext()
                            if (html) {
                                HtmlExporter.appendMessageHtml(context, writer, message) { jobContext.ensureActive() }
                            } else {
                                if (offset + pageIndex > 0) writer.appendLine()
                                TextExporter.writeMessage(context, writer, message) { jobContext.ensureActive() }
                            }
                        }
                        offset += rows.size
                    }
                    if (html) HtmlExporter.writeConversationFooter(writer)
                    else TextExporter.writeConversationFooter(writer)
                }
                if (html) HtmlExporter.writeMultipleFooter(context, writer)
                else TextExporter.writeMultipleFooter(context, writer)
            }
        }
    }

    /**
     * 从指定URI导入聊天记录（指定格式）
     * @param uri 备份文件URI
     * @param format 指定的格式
     * @return 导入结果统计
     */
    suspend fun importChatHistoriesFromUri(uri: Uri, format: ChatFormat): ChatImportResult =
        withContext(Dispatchers.IO) {
            try {
                val chatHistories = mutableListOf<ChatHistory>()
                var isZipProcessed = false

                // 如果是 Markdown 格式，尝试作为 Zip 处理
                if (format == ChatFormat.MARKDOWN) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { fis ->
                            // 尝试作为 Zip 读取
                            // 注意：ZipInputStream 可能会消耗流，如果不是 Zip，后续重读需要重新 openInputStream
                            ZipInputStream(fis).use { zipStream ->
                                var entry = zipStream.nextEntry
                                if (entry != null) {
                                    // 确实是 Zip 文件
                                    do {
                                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".md")) {
                                            val buffer = ByteArrayOutputStream()
                                            val data = ByteArray(4096)
                                            var count: Int
                                            while (zipStream.read(data).also { count = it } != -1) {
                                                buffer.write(data, 0, count)
                                            }
                                            val content = buffer.toString("UTF-8")
                                            if (content.isNotBlank()) {
                                                chatHistories.addAll(convertToOperitFormat(content, ChatFormat.MARKDOWN))
                                            }
                                        }
                                        zipStream.closeEntry()
                                        entry = zipStream.nextEntry
                                    } while (entry != null)
                                    isZipProcessed = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "尝试解析 Zip 失败，将尝试作为普通文件读取: ${e.message}")
                    }
                }

                if (!isZipProcessed) {
                    AppLogger.d(TAG, "使用指定格式导入: $format")
                    if (format == ChatFormat.OPERIT) {
                        val existingIds =
                            allChatHistoriesInternalFlow.first().map { it.id }.toMutableSet()
                        val inputStream =
                            context.contentResolver.openInputStream(uri)
                                ?: return@withContext ChatImportResult(0, 0, 0)
                        inputStream.use { stream ->
                            AppLogger.d(TAG, "开始流式导入 Operit JSON: uri=$uri")
                            return@withContext importOperitChatHistoriesStream(stream, existingIds)
                        }
                    } else {
                        val inputStream = context.contentResolver.openInputStream(uri)
                            ?: return@withContext ChatImportResult(0, 0, 0)
                        val content = inputStream.bufferedReader().use { it.readText() }

                        if (content.isBlank()) {
                            throw Exception(context.getString(R.string.chat_history_imported_file_empty))
                        }

                        // 转换为 ChatHistory 列表
                        chatHistories.addAll(convertToOperitFormat(content, format))
                    }
                }

                if (chatHistories.isEmpty()) {
                    return@withContext ChatImportResult(0, 0, 0)
                }

                // 保存导入的对话
                val existingIds =
                    allChatHistoriesInternalFlow.first().map { it.id }.toMutableSet()

                var newCount = 0
                var updatedCount = 0
                var skippedCount = 0
                val importedFolderIdsByBucket = mutableMapOf<LegacyFolderBucketKey, String>()

                for (chatHistory in chatHistories) {
                    if (chatHistory.messages.isEmpty()) {
                        skippedCount++
                        continue
                    }

                    if (existingIds.contains(chatHistory.id)) {
                        updatedCount++
                    } else {
                        newCount++
                        existingIds.add(chatHistory.id)
                    }

                    val legacyFolderBucket = chatHistory.legacyFolderBucketKey()
                    val normalizedHistory =
                        if (chatHistory.folderId == null && legacyFolderBucket != null) {
                            val folderId =
                                importedFolderIdsByBucket[legacyFolderBucket]
                                    ?: (
                                        findLegacyFolderId(legacyFolderBucket)
                                            ?: createFolder(
                                                parentFolderId = null,
                                                name = legacyFolderBucket.rawGroup.trim(),
                                            )
                                    ).also {
                                        importedFolderIdsByBucket[legacyFolderBucket] = it
                                    }
                            chatHistory.copy(group = null, folderId = folderId)
                        } else {
                            chatHistory.copy(group = null)
                        }
                    saveChatHistoryInternal(
                        history = normalizedHistory,
                        preserveStructure = false,
                        clearTodosAfterTranscriptReplace = true,
                    )
                }

                AppLogger.d(TAG, "导入完成: 新增=$newCount, 更新=$updatedCount, 跳过=$skippedCount")
                ChatImportResult(newCount, updatedCount, skippedCount)
            } catch (e: Exception) {
                AppLogger.e(TAG, "导入聊天记录失败", e)
                throw e
            }
        }

    private fun parseLegacyOperitChatHistories(content: String): List<ChatHistory> {
        try {
            return operitArchiveJson.decodeFromString<List<ChatHistory>>(content)
        } catch (e: Exception) {
            val gson =
                GsonBuilder()
                    .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    .create()
            val type = object : TypeToken<List<ChatHistory>>() {}.type
            return gson.fromJson<List<ChatHistory>>(content, type)
        }
    }

    private fun convertToOperitFormat(content: String, format: ChatFormat): List<ChatHistory> {
        return try {
            when (format) {
                ChatFormat.OPERIT -> {
                    parseLegacyOperitChatHistories(content)
                }
                
                ChatFormat.CHATGPT -> {
                    AppLogger.d(TAG, "使用 ChatGPT 转换器")
                    ChatGPTConverter().convert(content)
                }
                
                ChatFormat.CHATBOX -> {
                    AppLogger.d(TAG, "使用 ChatBox 转换器")
                    ChatBoxConverter(context).convert(content)
                }
                
                ChatFormat.MARKDOWN -> {
                    AppLogger.d(TAG, "使用 Markdown 转换器")
                    MarkdownConverter(context).convert(content)
                }
                
                ChatFormat.GENERIC_JSON -> {
                    AppLogger.d(TAG, "使用通用 JSON 转换器")
                    GenericJsonConverter().convert(content)
                }
                
                ChatFormat.CLAUDE -> {
                    // Claude 格式暂不支持，回退到通用 JSON
                    AppLogger.d(TAG, "Claude 格式回退到通用 JSON 转换器")
                    GenericJsonConverter().convert(content)
                }
                
                else -> {
                    throw ConversionException(context.getString(R.string.chat_history_unsupported_format, format))
                }
            }
        } catch (e: ConversionException) {
            throw Exception(context.getString(R.string.chat_history_convert_format_failed, e.message ?: ""), e)
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.chat_history_parse_backup_failed, e.message ?: ""), e)
        }
    }

    /**
     * 清理绑定已删除角色卡的对话（将characterCardName设为null）
     * @param characterCardName 已删除的角色卡名称
     */
    suspend fun clearCharacterCardBinding(characterCardName: String) {
        try {
            withContext(Dispatchers.IO) {
                chatDao.clearCharacterCardBinding(characterCardName)
                AppLogger.d(TAG, "已清理绑定角色卡 '$characterCardName' 的对话")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "清理角色卡绑定失败: $characterCardName", e)
        }
    }

    /**
     * 将指定角色卡或未绑定的对话转移到新的角色卡
     * @return 受影响的对话数量
     */
    suspend fun reassignChatsToCharacterCard(
            sourceCharacterCardName: String?,
            targetCharacterCardName: String
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val updated = if (sourceCharacterCardName == null) {
                    chatDao.assignCharacterCardToUnbound(targetCharacterCardName)
                } else {
                    chatDao.renameCharacterCardBinding(sourceCharacterCardName, targetCharacterCardName)
                }
                AppLogger.d(
                        TAG,
                        "角色卡聊天重分配: ${sourceCharacterCardName ?: "未绑定"} -> $targetCharacterCardName, 更新 $updated 条记录"
                )
                updated
            } catch (e: Exception) {
                AppLogger.e(
                        TAG,
                        "重命名角色卡绑定失败: ${sourceCharacterCardName ?: "未绑定"} -> $targetCharacterCardName",
                        e
                )
                throw e
            }
        }
    }

    suspend fun getLatestSummaryTimestamp(chatId: String): Long? {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.getLatestSummaryTimestamp(chatId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取最新 summary 时间戳失败", e)
                null
            }
        }
    }

    suspend fun loadMessagesAfterLatestSummaryInRange(
        chatId: String,
        beforeTimestampExclusive: Long? = null,
        upToTimestampInclusive: Long? = null,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val latestSummaryTimestamp =
                    when {
                        beforeTimestampExclusive != null ->
                            messageDao.getLatestSummaryTimestampBefore(
                                chatId,
                                beforeTimestampExclusive,
                            )
                        upToTimestampInclusive != null ->
                            messageDao.getLatestSummaryTimestampUpTo(
                                chatId,
                                upToTimestampInclusive,
                            )
                        else -> messageDao.getLatestSummaryTimestamp(chatId)
                    }
                val messageEntities =
                    chatContentDao.getMessagesForChatInRangeAsc(
                        chatId = chatId,
                        afterTimestampExclusive = latestSummaryTimestamp,
                        beforeTimestampExclusive = beforeTimestampExclusive,
                        upToTimestampInclusive = upToTimestampInclusive,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按总结窗口加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun hasUserMessage(chatId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.existsUserMessage(chatId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查聊天是否存在用户消息失败", e)
                false
            }
        }
    }

    suspend fun repairRepeatedIntermediateMessages(chatId: String): Int =
        chatMutex(chatId).withLock {
            database.withTransaction {
                messageDao.deleteExactDuplicateIntermediateMessages(chatId)
            }
        }

    suspend fun loadRuntimeChatMessages(chatId: String): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                repairRepeatedIntermediateMessages(chatId)
                val latestSummaryTimestamp = messageDao.getLatestSummaryTimestamp(chatId)
                val messageEntities =
                    if (latestSummaryTimestamp != null) {
                        chatContentDao.getMessagesForChatFromTimestampAsc(chatId, latestSummaryTimestamp)
                    } else {
                        chatContentDao.getMessagesForChat(chatId)
                    }
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载运行态聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadRuntimeChatMessagesUpTo(
        chatId: String,
        upToTimestampInclusive: Long
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            val latestSummaryTimestamp =
                messageDao.getLatestSummaryTimestampUpTo(chatId, upToTimestampInclusive)
            val messageEntities =
                if (latestSummaryTimestamp != null) {
                    chatContentDao.getMessagesForChatWindowAsc(
                        chatId = chatId,
                        startTimestampInclusive = latestSummaryTimestamp,
                        endTimestampInclusive = upToTimestampInclusive
                    )
                } else {
                    chatContentDao.getMessagesForChatInRangeAsc(
                        chatId = chatId,
                        afterTimestampExclusive = null,
                        beforeTimestampExclusive = null,
                        upToTimestampInclusive = upToTimestampInclusive
                    )
                }
            hydrateMessages(chatId, messageEntities)
        }
    }

    suspend fun loadChatMessageProcessMetadata(
        chatId: String,
    ): List<com.ai.assistance.operit.data.model.ChatMessageProcessMetadata> =
        withContext(Dispatchers.IO) { messageDao.getProcessMetadata(chatId) }

    suspend fun loadChatMessagesByTimestamps(chatId: String, timestamps: List<Long>): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            timestamps.distinct().chunked(400).flatMap { chunk ->
                hydrateMessages(chatId, chatContentDao.getMessagesByTimestamps(chatId, chunk))
            }.sortedBy { it.timestamp }
        }

    suspend fun loadChatMessageLocatorPreviews(
        chatId: String,
        query: String = "",
    ): List<ChatMessageLocatorPreview> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedQuery = query.trim()
                if (normalizedQuery.isBlank()) {
                    messageDao.getLocatorPreviewsForChat(chatId, LOCATOR_PREVIEW_CHAR_COUNT)
                } else {
                    messageDao.searchLocatorPreviewsForChat(
                        chatId = chatId,
                        query = normalizedQuery,
                        previewCharCount = LOCATOR_PREVIEW_CHAR_COUNT,
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载聊天定位轻量预览失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesFromTimestamp(
        chatId: String,
        startTimestampInclusive: Long,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    chatContentDao.getMessagesForChatFromTimestampAsc(chatId, startTimestampInclusive)
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按起始时间加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesWindow(
        chatId: String,
        startTimestampInclusive: Long,
        endTimestampInclusive: Long,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    chatContentDao.getMessagesForChatWindowAsc(
                        chatId = chatId,
                        startTimestampInclusive = startTimestampInclusive,
                        endTimestampInclusive = endTimestampInclusive,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按时间窗口加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesAscAfter(
        chatId: String,
        afterTimestampExclusive: Long,
        limit: Int,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    chatContentDao.getMessagesForChatAfterTimestampExclusiveAsc(
                        chatId,
                        afterTimestampExclusive,
                        limit,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按起始时间后分页加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadOlderChatMessages(
        chatId: String,
        beforeTimestampExclusive: Long,
        limit: Int,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    chatContentDao
                        .getMessagesForChatBeforeTimestampExclusiveDesc(
                            chatId,
                            beforeTimestampExclusive,
                            limit,
                        ).asReversed()
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载更早聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun hasMessagesBefore(
        chatId: String,
        beforeTimestampExclusive: Long,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.existsMessagesBeforeTimestamp(chatId, beforeTimestampExclusive)
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查是否存在更早聊天消息失败", e)
                false
            }
        }
    }

    suspend fun hasMessagesAfter(
        chatId: String,
        afterTimestampExclusive: Long,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.existsMessagesAfterTimestamp(chatId, afterTimestampExclusive)
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查是否存在更新聊天消息失败", e)
                false
            }
        }
    }

    suspend fun loadChatMessagesDesc(
        chatId: String,
        limit: Int,
        beforeTimestampExclusive: Long? = null,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    if (beforeTimestampExclusive != null) {
                        chatContentDao.getMessagesForChatBeforeTimestampExclusiveDesc(
                            chatId,
                            beforeTimestampExclusive,
                            limit,
                        )
                    } else {
                        chatContentDao.getMessagesForChatDesc(chatId, limit)
                    }
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按倒序分页加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    suspend fun loadChatMessagesDescUpTo(
        chatId: String,
        maxTimestampInclusive: Long,
        limit: Int,
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities =
                    chatContentDao.getMessagesForChatBeforeTimestampDesc(
                        chatId,
                        maxTimestampInclusive,
                        limit,
                    )
                hydrateMessages(chatId, messageEntities)
            } catch (e: Exception) {
                AppLogger.e(TAG, "按截止时间倒序分页加载聊天消息失败", e)
                emptyList()
            }
        }
    }

    /**
     * 将指定角色群组下的对话转移到新的角色群组
     * @return 受影响的对话数量
     */
    suspend fun reassignChatsToCharacterGroup(
        sourceCharacterGroupId: String?,
        targetCharacterGroupId: String
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val updated = if (sourceCharacterGroupId == null) {
                    chatDao.assignCharacterGroupToUnbound(targetCharacterGroupId)
                } else {
                    chatDao.renameCharacterGroupBinding(sourceCharacterGroupId, targetCharacterGroupId)
                }
                AppLogger.d(
                    TAG,
                    "角色群组聊天重分配: ${sourceCharacterGroupId ?: "未绑定"} -> $targetCharacterGroupId, 更新 $updated 条记录"
                )
                updated
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "重命名角色群组绑定失败: ${sourceCharacterGroupId ?: "未绑定"} -> $targetCharacterGroupId",
                    e
                )
                throw e
            }
        }
    }

    // 更新聊天绑定的群组角色卡
    suspend fun updateChatCharacterGroupId(chatId: String, characterGroupId: String?) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatCharacterGroupId(chatId, characterGroupId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat character group for chat $chatId", e)
                throw e
            }
        }
    }

    // 同时更新聊天绑定的角色卡与群组
    suspend fun updateChatCharacterBinding(
        chatId: String,
        characterCardName: String?,
        characterGroupId: String?
    ) {
        chatMutex(chatId).withLock {
            try {
                chatDao.updateChatCharacterBinding(chatId, characterCardName, characterGroupId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update chat character binding for chat $chatId", e)
                throw e
            }
        }
    }

    /**
     * 清理绑定已删除角色群组的对话（将characterGroupId设为null）
     * @return 受影响的对话数量
     */
    suspend fun clearCharacterGroupBinding(characterGroupId: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val updated = chatDao.clearCharacterGroupBinding(characterGroupId)
                AppLogger.d(TAG, "已清理绑定角色群组 '$characterGroupId' 的对话: $updated")
                updated
            } catch (e: Exception) {
                AppLogger.e(TAG, "清理角色群组绑定失败: $characterGroupId", e)
                throw e
            }
        }
    }

    /**
     * 批量删除绑定到缺失角色卡（或未绑定）的未锁定对话
     * @return 实际删除的对话数量
     */
    suspend fun deleteChatsByCharacterCardBinding(sourceCharacterCardName: String?): Int {
        return withContext(Dispatchers.IO) {
            try {
                val currentChatId = currentChatIdFlow.first()
                fun selectDeletionCandidates(allChats: List<ChatEntity>): List<ChatEntity> {
                    val scopedChatIds =
                        allChats
                            .asSequence()
                            .filter { chat ->
                                if (sourceCharacterCardName == null) {
                                    chat.characterCardName == null &&
                                        chat.characterGroupId == null
                                } else {
                                    chat.characterCardName == sourceCharacterCardName
                                }
                            }
                            .mapTo(hashSetOf(), ChatEntity::id)
                    return ChatDeletionGraphPolicy.selectDeletableChats(
                        allChats,
                        scopedChatIds,
                    )
                }
                val expectedChats = selectDeletionCandidates(chatDao.getAllChatsDirectly())
                val expectedChatIds = expectedChats.mapTo(linkedSetOf(), ChatEntity::id)
                val deletedCount =
                    SubagentCoordinator.getInstance(context)
                        .withChatDeletionsPrepared(expectedChatIds) {
                            database.withTransaction {
                                val currentChats =
                                    selectDeletionCandidates(chatDao.getAllChatsDirectly())
                                val currentChatIds =
                                    currentChats.mapTo(linkedSetOf(), ChatEntity::id)
                                check(currentChatIds == expectedChatIds) {
                                    "Character-bound deletion candidates changed while preparing deletion"
                                }
                                ChatDeletionGraphPolicy.orderChildFirst(currentChats).forEach {
                                    chatDao.deleteChat(it.id)
                                }
                                currentChats.count { it.chatKind != ChatKind.SUBAGENT.name }
                            }
                        }

                if (currentChatId in expectedChatIds) {
                    context.currentChatIdDataStore.edit { preferences ->
                        preferences.remove(PreferencesKeys.CURRENT_CHAT_ID)
                    }
                }
                forgetDeletedChats(expectedChatIds)

                AppLogger.d(
                    TAG,
                    "删除缺失角色卡残留对话: ${sourceCharacterCardName ?: "未绑定"}, 删除 $deletedCount 条"
                )
                deletedCount
            } catch (e: Exception) {
                AppLogger.e(TAG, "删除缺失角色卡残留对话失败: ${sourceCharacterCardName ?: "未绑定"}", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天更新角色卡绑定
     * @return 受影响的对话数量
     */
    suspend fun assignCharacterCardToChats(
        chatIds: List<String>,
        targetCharacterCardName: String?
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                chatDao.updateCharacterCardForChats(chatIds, targetCharacterCardName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量更新聊天角色卡失败: $targetCharacterCardName, chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天更新角色群组绑定
     * @return 受影响的对话数量
     */
    suspend fun assignCharacterGroupToChats(
        chatIds: List<String>,
        targetCharacterGroupId: String?
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                chatDao.updateCharacterGroupForChats(chatIds, targetCharacterGroupId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量更新聊天角色群组失败: $targetCharacterGroupId, chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天移除角色群组绑定
     * @return 受影响的对话数量
     */
    suspend fun clearCharacterGroupBindingForChats(
        chatIds: List<String>
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                chatDao.clearCharacterGroupForChats(chatIds)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量清理聊天角色群组失败: chatIds=$chatIds", e)
                throw e
            }
        }
    }

    /**
     * 批量为特定聊天更新文件夹归属。
     * @return 受影响的对话数量
     */
    suspend fun assignFolderToChats(
        chatIds: List<String>,
        targetFolderId: String?,
    ): Int {
        if (chatIds.isEmpty()) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                val normalizedFolderId = normalizeChatFolderId(targetFolderId)
                if (normalizedFolderId != null) {
                    require(chatFolderDao.getFolder(normalizedFolderId) != null) {
                        "Unknown folderId: $normalizedFolderId"
                    }
                }
                chatDao.updateFolderForChats(chatIds, normalizedFolderId)
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "批量更新聊天文件夹失败: folderId=$targetFolderId, chatIds=$chatIds",
                    e,
                )
                throw e
            }
        }
    }

    /**
     * 批量重命名对话中绑定的角色卡名称
     * @return 受影响的对话数量
     */
    suspend fun renameCharacterCardInChats(oldName: String, newName: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                chatDao.renameCharacterCardBinding(oldName, newName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量重命名对话绑定角色卡失败: $oldName -> $newName", e)
                throw e
            }
        }
    }

    /**
     * 批量重命名消息中的角色名称
     * @return 受影响的消息数量
     */
    suspend fun renameRoleNameInMessages(oldName: String, newName: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.renameRoleName(oldName, newName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "批量重命名消息中的角色名失败: $oldName -> $newName", e)
                throw e
            }
        }
    }
}

internal fun mergePersistedChatEntity(
    incoming: ChatEntity,
    existing: ChatEntity,
    preserveStructure: Boolean,
): ChatEntity =
    incoming.copy(
        // The v24 group column is diagnostic-only and must never be revived by a normal save.
        group = existing.group,
        // Folder placement is repository-owned. A streaming/current-chat save may carry a stale
        // UI snapshot and must not undo a completed structural move.
        folderId = if (preserveStructure) existing.folderId else incoming.folderId,
        displayOrder = if (preserveStructure) existing.displayOrder else incoming.displayOrder,
        // Hiding state is repository-owned: a legacy archive without the flag must never
        // unhide an existing hidden chat (audit chats are permanently hidden).
        isHidden = existing.isHidden,
        hiddenReason = existing.hiddenReason,
        contextPolicyJson = if (preserveStructure) existing.contextPolicyJson
            else incoming.contextPolicyJson ?: existing.contextPolicyJson,
    )

data class ChatImportResult(
    val new: Int,
    val updated: Int,
    val skipped: Int,
    val foldersCreated: Int = 0,
    val mayLeavePreviousEmptyFolders: Boolean = false,
) {
    val total: Int
        get() = new + updated
}
