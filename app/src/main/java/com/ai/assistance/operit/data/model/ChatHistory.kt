package com.ai.assistance.operit.data.model

import com.ai.assistance.operit.util.LocalDateTimeSerializer
import java.util.UUID
import java.time.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatHistory(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage>,
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Serializable(with = LocalDateTimeSerializer::class)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val currentWindowSize: Int = 0,
    /** 仅供 v2/v3 旧归档转换器使用；v25 运行时组织不得读取。 */
    val group: String? = null,
    val folderId: String? = null,
    val displayOrder: Long = 0L,
    val workspace: String? = null,
    val workspaceEnv: String? = null,
    val parentChatId: String? = null,
    val chatKind: String = ChatKind.NORMAL.name,
    val characterCardName: String? = null,
    val characterGroupId: String? = null,
    val locked: Boolean = false,
    val pinned: Boolean = false,
    val isFavorite: Boolean = false,
    /** 隐藏聊天不进入普通列表/统计，仅供隐藏入口与按 ID 打开使用。 */
    val isHidden: Boolean = false,
    /** 隐藏原因；以 READING_COMPANION_AUDIT_ 开头的聊天永久隐藏，不可取消。 */
    val hiddenReason: String? = null,
    val contextPolicyJson: String? = null,
    @Serializable(with = LocalDateTimeSerializer::class)
    val lastMessageAt: LocalDateTime? = null,
    /** Room 运行时保留的绝对时间；不进入归档格式。 */
    @Transient val createdAtEpochMillis: Long? = null,
    /** Room 运行时保留的绝对时间；不进入归档格式。 */
    @Transient val lastMessageAtEpochMillis: Long? = null,
)
