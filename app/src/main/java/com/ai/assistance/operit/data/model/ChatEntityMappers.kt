package com.ai.assistance.operit.data.model

import java.time.Instant
import java.time.ZoneId

/** 转换为ChatHistory对象（供UI层使用） */
fun ChatEntity.toChatHistory(messages: List<ChatMessage>): ChatHistory {
    val createdAt = Instant.ofEpochMilli(this.createdAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()

    val updatedAt = Instant.ofEpochMilli(this.updatedAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()

    val lastMessageAt = this.lastMessageAt?.let {
        Instant.ofEpochMilli(it)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }

    return ChatHistory(
            id = id,
            title = title,
            messages = messages,
            createdAt = createdAt,
            updatedAt = updatedAt,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            currentWindowSize = currentWindowSize,
            group = null,
            folderId = folderId,
            displayOrder = displayOrder,
            workspace = workspace,
            workspaceEnv = workspaceEnv,
            parentChatId = parentChatId,
            chatKind = chatKind,
            characterCardName = characterCardName,
            characterGroupId = characterGroupId,
            locked = locked,
            pinned = pinned,
            isFavorite = isFavorite,
            lastMessageAt = lastMessageAt,
            isHidden = isHidden,
            hiddenReason = hiddenReason,
            contextPolicyJson = contextPolicyJson,
            createdAtEpochMillis = this.createdAt,
            lastMessageAtEpochMillis = this.lastMessageAt,
    )
}


/** 从ChatHistory创建ChatEntity */
fun ChatEntity.Companion.fromChatHistory(chatHistory: ChatHistory): ChatEntity {
    val now = System.currentTimeMillis()
    return ChatEntity(
            id = chatHistory.id,
            title = chatHistory.title,
            createdAt =
                    chatHistory.createdAtEpochMillis
                            ?: chatHistory
                                    .createdAt
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli(),
            updatedAt =
                    chatHistory
                            .updatedAt
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
            inputTokens = chatHistory.inputTokens,
            outputTokens = chatHistory.outputTokens,
            currentWindowSize = chatHistory.currentWindowSize,
            group = null,
            folderId = chatHistory.folderId,
            displayOrder = if (chatHistory.displayOrder != 0L) chatHistory.displayOrder else -now,
            workspace = chatHistory.workspace,
            workspaceEnv = chatHistory.workspaceEnv,
            parentChatId = chatHistory.parentChatId,
            chatKind = chatHistory.chatKind,
            characterCardName = chatHistory.characterCardName,
            characterGroupId = chatHistory.characterGroupId,
            locked = chatHistory.locked,
            pinned = chatHistory.pinned,
            isFavorite = chatHistory.isFavorite,
            isHidden = chatHistory.isHidden,
            hiddenReason = chatHistory.hiddenReason,
            contextPolicyJson = chatHistory.contextPolicyJson,
            lastMessageAt =
                    chatHistory.lastMessageAt?.let {
                        chatHistory.lastMessageAtEpochMillis
                            ?: it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
    )
}

/** 转换为ChatMessage对象（供UI层使用） */
fun MessageEntity.toChatMessage(): ChatMessage {
    return ChatMessage(
        sender = sender,
        content = content,
        timestamp = timestamp,
        roleName = roleName,
        selectedVariantIndex = selectedVariantIndex,
        provider = provider,
        modelName = modelName,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = cachedInputTokens,
        sentAt = sentAt,
        outputDurationMs = outputDurationMs,
        waitDurationMs = waitDurationMs,
        completedAt = completedAt,
        displayMode =
            runCatching { ChatMessageDisplayMode.valueOf(displayMode) }
                .getOrDefault(ChatMessageDisplayMode.NORMAL),
        isFavorite = isFavorite,
    )
}


/** 从ChatMessage创建MessageEntity */
fun MessageEntity.Companion.fromChatMessage(
    chatId: String,
    message: ChatMessage,
    orderIndex: Int,
    messageId: Long = 0
): MessageEntity {
    return MessageEntity(
            messageId = messageId,
            chatId = chatId,
            sender = message.sender,
            content = message.content,
            timestamp = message.timestamp,
            orderIndex = orderIndex,
            roleName = message.roleName,
            selectedVariantIndex = message.selectedVariantIndex,
            provider = message.provider,
            modelName = message.modelName,
            inputTokens = message.inputTokens,
            outputTokens = message.outputTokens,
            cachedInputTokens = message.cachedInputTokens,
            sentAt = message.sentAt,
            outputDurationMs = message.outputDurationMs,
            waitDurationMs = message.waitDurationMs,
            completedAt = message.completedAt,
            displayMode = message.displayMode.name,
            isFavorite = message.isFavorite,
    )
}

fun MessageVariantEntity.applyTo(baseMessage: ChatMessage, variantCount: Int): ChatMessage {
    return baseMessage.copy(
        content = content,
        roleName = roleName.ifBlank { baseMessage.roleName },
        selectedVariantIndex = variantIndex,
        variantCount = variantCount,
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


fun MessageVariantEntity.Companion.fromChatMessage(
    chatId: String,
    messageTimestamp: Long,
    variantIndex: Int,
    message: ChatMessage,
    variantId: Long = 0,
): MessageVariantEntity {
    return MessageVariantEntity(
        variantId = variantId,
        chatId = chatId,
        messageTimestamp = messageTimestamp,
        variantIndex = variantIndex,
        content = message.content,
        roleName = message.roleName,
        provider = message.provider,
        modelName = message.modelName,
        inputTokens = message.inputTokens,
        outputTokens = message.outputTokens,
        cachedInputTokens = message.cachedInputTokens,
        sentAt = message.sentAt,
        outputDurationMs = message.outputDurationMs,
        waitDurationMs = message.waitDurationMs,
        completedAt = message.completedAt,
    )
}
