package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** 聊天实体类，用于Room数据库存储聊天元数据 */
@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["folderId"]),
        Index(value = ["chatKind"]),
        Index(value = ["parentChatId", "chatKind"]),
        Index(value = ["isHidden"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
)
data class ChatEntity(
        @PrimaryKey val id: String = UUID.randomUUID().toString(),
        val title: String,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val currentWindowSize: Int = 0,
        /** v25 前的只读诊断列。运行时组织必须使用 [folderId]。 */
        val group: String? = null,
        val folderId: String? = null,
        val displayOrder: Long = -createdAt,
        val workspace: String? = null,
        val workspaceEnv: String? = null,
        val parentChatId: String? = null,
        @ColumnInfo(defaultValue = "'NORMAL'")
        val chatKind: String = ChatKind.NORMAL.name,
        val characterCardName: String? = null,
        val characterGroupId: String? = null,
        val locked: Boolean = false,
        val pinned: Boolean = false,
        val isFavorite: Boolean = false,
        val lastMessageAt: Long? = null,
        /** 隐藏聊天不进入普通列表/统计，仅供隐藏入口与按 ID 打开使用。 */
        val isHidden: Boolean = false,
        /** 隐藏原因；以 READING_COMPANION_AUDIT_ 开头的聊天永久隐藏，不可取消。 */
        val hiddenReason: String? = null,
        val contextPolicyJson: String? = null,
) {
    companion object
}
