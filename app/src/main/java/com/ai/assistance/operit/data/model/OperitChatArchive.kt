package com.ai.assistance.operit.data.model

import com.ai.assistance.operit.util.LocalDateTimeSerializer
import java.time.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class OperitChatArchive(
    val archiveType: String = ARCHIVE_TYPE,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val folders: List<OperitArchivedFolder> = emptyList(),
    val chats: List<OperitArchivedChat>,
    val subagentRuns: List<OperitArchivedSubagentRun> = emptyList(),
) {
    companion object {
        const val ARCHIVE_TYPE = "operit_chat_archive"
        const val CURRENT_FORMAT_VERSION = 6
    }
}

@Serializable
data class OperitArchivedFolder(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val displayOrder: Long = 0L,
    val createdAt: Long,
) {
    fun toEntity(): ChatFolderEntity =
        ChatFolderEntity(
            id = id,
            name = name,
            parentFolderId = parentFolderId,
            displayOrder = displayOrder,
            createdAt = createdAt,
        )

    companion object {
        fun fromEntity(entity: ChatFolderEntity): OperitArchivedFolder =
            OperitArchivedFolder(
                id = entity.id,
                name = entity.name,
                parentFolderId = entity.parentFolderId,
                displayOrder = entity.displayOrder,
                createdAt = entity.createdAt,
            )
    }
}

@Serializable
data class OperitArchivedChat(
    val id: String,
    val title: String,
    val messages: List<OperitArchivedMessage>,
    val todos: List<OperitArchivedTodo> = emptyList(),
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Serializable(with = LocalDateTimeSerializer::class)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val currentWindowSize: Int = 0,
    val group: String? = null,
    val folderId: String? = null,
    val displayOrder: Long = 0L,
    val workspace: String? = null,
    val workspaceEnv: String? = null,
    val parentChatId: String? = null,
    val chatKind: String? = null,
    val characterCardName: String? = null,
    val characterGroupId: String? = null,
    val locked: Boolean = false,
    val pinned: Boolean = false,
    val isFavorite: Boolean? = null,
    val isHidden: Boolean = false,
    val hiddenReason: String? = null,
    val contextPolicyJson: String? = null,
) {
    fun toChatHistory(resolvedFavorite: Boolean, folderId: String? = this.folderId): ChatHistory {
        contextPolicyJson?.let(ChatContextPolicy::decode)
        return ChatHistory(
            id = id,
            title = title,
            messages = messages.map { it.baseMessage },
            createdAt = createdAt,
            updatedAt = updatedAt,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            currentWindowSize = currentWindowSize,
            folderId = folderId,
            displayOrder = displayOrder,
            workspace = workspace,
            workspaceEnv = workspaceEnv,
            parentChatId = parentChatId,
            chatKind =
                chatKind
                    ?: if (parentChatId == null) {
                        ChatKind.NORMAL.name
                    } else {
                        ChatKind.BRANCH.name
                    },
            characterCardName = characterCardName,
            characterGroupId = characterGroupId,
            locked = locked,
            pinned = pinned,
            isFavorite = resolvedFavorite,
            isHidden = isHidden,
            hiddenReason = hiddenReason,
            contextPolicyJson = contextPolicyJson,
        )
    }

    companion object {
        fun fromChatHistory(
            history: ChatHistory,
            messages: List<OperitArchivedMessage>,
            todos: List<OperitArchivedTodo> = emptyList(),
        ): OperitArchivedChat {
            return OperitArchivedChat(
                id = history.id,
                title = history.title,
                messages = messages,
                todos = todos,
                createdAt = history.createdAt,
                updatedAt = history.updatedAt,
                inputTokens = history.inputTokens,
                outputTokens = history.outputTokens,
                currentWindowSize = history.currentWindowSize,
                group = null,
                folderId = history.folderId,
                displayOrder = history.displayOrder,
                workspace = history.workspace,
                workspaceEnv = history.workspaceEnv,
                parentChatId = history.parentChatId,
                chatKind = history.chatKind,
                characterCardName = history.characterCardName,
                characterGroupId = history.characterGroupId,
                locked = history.locked,
                pinned = history.pinned,
                isFavorite = history.isFavorite,
                isHidden = history.isHidden,
                hiddenReason = history.hiddenReason,
                contextPolicyJson = history.contextPolicyJson,
            )
        }
    }
}

@Serializable
data class OperitArchivedTodo(
    val content: String,
    val status: ChatTodoStatus,
    val priority: ChatTodoPriority,
) {
    fun toChatTodo(): ChatTodo =
        ChatTodo(
            content = content,
            status = status,
            priority = priority,
        )

    fun toEntity(chatId: String, position: Int): ChatTodoEntity =
        ChatTodoEntity(
            chatId = chatId,
            position = position,
            content = content,
            status = status.name,
            priority = priority.name,
        )
}

@Serializable
data class OperitArchivedSubagentRun(
    val id: String,
    val parentChatId: String,
    val childChatId: String,
    val parentToolCallId: String? = null,
    val agentProfileId: String,
    val title: String,
    val status: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
    val agentConfigSnapshot: String? = null,
    val modelConfigIdSnapshot: String? = null,
    val modelIndexSnapshot: Int? = null,
    val toolInvocationCount: Int = 0,
    val externalOwnerType: String? = null,
    val externalOwnerId: String? = null,
    val modelRoundCount: Int = 0,
    val archivedAt: Long? = null,
) {
    fun toEntity(): SubagentRunEntity =
        SubagentRunEntity(
            id = id,
            parentChatId = parentChatId,
            childChatId = childChatId,
            parentToolCallId = parentToolCallId,
            agentProfileId = agentProfileId,
            title = title,
            status = status,
            createdAt = createdAt,
            startedAt = startedAt,
            completedAt = completedAt,
            error = error,
            agentConfigSnapshot = agentConfigSnapshot,
            modelConfigIdSnapshot = modelConfigIdSnapshot,
            modelIndexSnapshot = modelIndexSnapshot,
            toolInvocationCount = toolInvocationCount,
            externalOwnerType = externalOwnerType,
            externalOwnerId = externalOwnerId,
            modelRoundCount = modelRoundCount,
            archivedAt = archivedAt,
        )

    companion object {
        fun fromEntity(entity: SubagentRunEntity): OperitArchivedSubagentRun =
            OperitArchivedSubagentRun(
                id = entity.id,
                parentChatId = entity.parentChatId,
                childChatId = entity.childChatId,
                parentToolCallId = entity.parentToolCallId,
                agentProfileId = entity.agentProfileId,
                title = entity.title,
                status = entity.status,
                createdAt = entity.createdAt,
                startedAt = entity.startedAt,
                completedAt = entity.completedAt,
                error = entity.error,
                agentConfigSnapshot = entity.agentConfigSnapshot,
                modelConfigIdSnapshot = entity.modelConfigIdSnapshot,
                modelIndexSnapshot = entity.modelIndexSnapshot,
                toolInvocationCount = entity.toolInvocationCount,
                externalOwnerType = entity.externalOwnerType,
                externalOwnerId = entity.externalOwnerId,
                modelRoundCount = entity.modelRoundCount,
                archivedAt = entity.archivedAt,
            )
    }
}

@Serializable
data class OperitArchivedMessage(
    val baseMessage: ChatMessage,
    val variants: List<OperitArchivedMessageVariant> = emptyList(),
)

@Serializable
data class OperitArchivedMessageVariant(
    val variantIndex: Int,
    val content: String,
    val roleName: String = "",
    val provider: String = "",
    val modelName: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val sentAt: Long = 0L,
    val outputDurationMs: Long = 0L,
    val waitDurationMs: Long = 0L,
    val completedAt: Long = 0L,
) {
    fun toEntity(chatId: String, messageTimestamp: Long): MessageVariantEntity {
        return MessageVariantEntity(
            chatId = chatId,
            messageTimestamp = messageTimestamp,
            variantIndex = variantIndex,
            content = content,
            roleName = roleName,
            provider = provider,
            modelName = modelName,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            sentAt = sentAt,
            outputDurationMs = outputDurationMs,
            waitDurationMs = waitDurationMs,
            completedAt = completedAt,
        )
    }

    companion object {
        fun fromEntity(entity: MessageVariantEntity): OperitArchivedMessageVariant {
            return OperitArchivedMessageVariant(
                variantIndex = entity.variantIndex,
                content = entity.content,
                roleName = entity.roleName,
                provider = entity.provider,
                modelName = entity.modelName,
                inputTokens = entity.inputTokens,
                outputTokens = entity.outputTokens,
                cachedInputTokens = entity.cachedInputTokens,
                sentAt = entity.sentAt,
                outputDurationMs = entity.outputDurationMs,
                waitDurationMs = entity.waitDurationMs,
                completedAt = entity.completedAt,
            )
        }
    }
}
