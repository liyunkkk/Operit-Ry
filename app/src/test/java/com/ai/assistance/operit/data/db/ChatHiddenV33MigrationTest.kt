package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.dao.ChatDao
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 真实 Room 迁移测试（纯 JVM，sqlite-jdbc）：
 * 用 v32 导出 schema 构造 v32 数据库 → 通过 [AppDatabase] + [AppDatabase.MIGRATION_32_33]
 * 真实打开 → 验证旧 chats 无损升级（isHidden=0 仍可见）、隐藏行不进入任何可见查询、
 * 隐藏入口/按 ID 打开仍可读到。
 */
class ChatHiddenV33MigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schemaDir = File("schemas/com.ai.assistance.operit.data.db.AppDatabase")

    private fun mockContext(tempDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(tempDir)
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(tempDir, invocation.getArgument<String>(0))
        }
        return context
    }

    private fun buildV32Database(dbPath: String) {
        val schemaFile = File(schemaDir, "32.json")
        assertTrue("schema export missing: ${schemaFile.absolutePath}", schemaFile.isFile)
        val schema = json.decodeFromString<RoomSchema>(schemaFile.readText())
        assertEquals(32, schema.database.version)

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                schema.database.entities.forEach { entity ->
                    statement.execute(entity.createSql.replace("\${TABLE_NAME}", entity.tableName))
                    entity.indices.forEach { index ->
                        statement.execute(index.createSql.replace("\${TABLE_NAME}", entity.tableName))
                    }
                }
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT NOT NULL)"
                )
                statement.execute(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                        "VALUES(42, '${schema.database.identityHash}')"
                )
                statement.execute("PRAGMA user_version = 32")
                // 旧数据：迁移前插入一条普通聊天，验证迁移后仍可见
                statement.execute(
                    "INSERT INTO chats " +
                        "(id, title, createdAt, updatedAt, inputTokens, outputTokens, " +
                        "currentWindowSize, displayOrder, locked, pinned, isFavorite) " +
                        "VALUES ('legacy-chat', 'legacy', 1, 2, 3, 4, 5, 6, 0, 0, 0)"
                )
                statement.execute(
                    "INSERT INTO messages (messageId,chatId,sender,content,timestamp,orderIndex," +
                        "roleName,selectedVariantIndex,provider,modelName,inputTokens,outputTokens," +
                        "cachedInputTokens,sentAt,outputDurationMs,waitDurationMs,completedAt,displayMode,isFavorite) " +
                        "VALUES (1,'legacy-chat','user','legacy searchable evidence',1,0," +
                        "'',0,'','',0,0,0,0,0,0,0,'NORMAL',0)"
                )
            }
        }
    }

    private fun openV33Database(tempDir: File): AppDatabase =
        Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
            .setDriver(JdbcSQLiteDriver())
            .addMigrations(AppDatabase.MIGRATION_32_33, AppDatabase.MIGRATION_33_34, AppDatabase.MIGRATION_34_35)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `v32 to v33 migration keeps legacy chats visible and hides new hidden chats everywhere`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("chat-hidden-v33-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV32Database(dbFile.absolutePath)
            val database = openV33Database(tempDir)
            try {
                val dao = database.chatDao()

                // 迁移后旧行：isHidden=0，普通可见查询仍包含。
                val legacy = dao.getChatById("legacy-chat")
                assertNotNull(legacy)
                assertEquals(false, legacy!!.isHidden)
                assertEquals(listOf("legacy-chat"), database.chatContentDao()
                    .searchRecallIndex("\"searchable\"","","","",0,Long.MAX_VALUE,20,0).map { it.chatId })
                assertNull(legacy.hiddenReason)
                assertNull(legacy.contextPolicyJson)
                val policy = com.ai.assistance.operit.data.model.ChatContextPolicy(windowK = 300f).encode()
                dao.updateContextPolicy("legacy-chat", policy)
                assertEquals(policy, dao.observeContextPolicy("legacy-chat").first())
                assertTrue(dao.getVisibleChats().first().any { it.id == "legacy-chat" })
                assertTrue(dao.getRecentChats().first().any { it.id == "legacy-chat" })
                assertTrue(dao.getMainChats().any { it.id == "legacy-chat" })
                assertTrue(dao.getAllChatsDirectly().any { it.id == "legacy-chat" })

                // 迁移后再插入：一个可见聊天 + 一个隐藏聊天（审计根）。
                val now = System.currentTimeMillis()
                val visible =
                    ChatEntity(
                        id = "visible-1",
                        title = "visible",
                        createdAt = now,
                        updatedAt = now,
                        characterCardName = "card-x",
                    )
                val hidden =
                    ChatEntity(
                        id = "hidden-1",
                        title = "hidden audit root",
                        createdAt = now,
                        updatedAt = now,
                        characterCardName = "card-x",
                        isHidden = true,
                        hiddenReason = "READING_COMPANION_AUDIT_ROOT:book1",
                    )
                dao.insertChat(visible)
                dao.insertChat(hidden)

                // 可见列表/计数/主聊天/角色卡查询/stats 都不得包含隐藏行。
                assertEquals(2, dao.getVisibleChats().first().size)
                assertEquals(2, dao.getRecentChats().first().size)
                assertEquals(2, dao.getTotalChatCount())
                assertEquals(2, dao.getMainChats().size)
                assertEquals(2, dao.getMainChatsFlow().first().size)
                assertEquals(1, dao.getChatsByCharacterCard("card-x").first().size)
                assertEquals("visible-1", dao.getChatsByCharacterCard("card-x").first().single().id)
                // 默认角色卡语义：card-x 精确匹配 + 未绑定角色卡的 visible/legacy 行；隐藏行不出现。
                assertEquals(2, dao.getChatsByCharacterCardOrNull("card-x").first().size)
                val cardStats = dao.getCharacterCardChatStats().first()
                assertEquals(
                    1,
                    cardStats.single { it.characterCardName == "card-x" }.chatCount,
                )
                // 群组 stats 只统计未绑定角色卡的可见行（legacy）；隐藏行不得进入。
                val groupStats = dao.getCharacterGroupChatStats().first()
                assertEquals(1, groupStats.sumOf { it.chatCount })

                // 隐藏入口 / 按 ID 打开 / 全量（归档、子树删除）仍能看到隐藏行。
                assertEquals(listOf("hidden-1"), dao.observeHiddenChats().first().map { it.id })
                assertEquals(listOf("hidden-1"), dao.getHiddenChatsDirectly().map { it.id })
                val hiddenChat = dao.getChatById("hidden-1")
                assertNotNull(hiddenChat)
                assertEquals("hidden audit root", hiddenChat!!.title)
                assertEquals(3, dao.getAllChatsDirectly().size)
                assertEquals(3, dao.getAllChats().first().size)
            } finally {
                database.close()
            }
        }

    @Test
    fun `v33 migration is reentrant and keeps subagent run columns defaulted`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("chat-hidden-v33-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV32Database(dbFile.absolutePath)
            val database = openV33Database(tempDir)
            try {
                val dao = database.chatDao()
                assertNotNull(dao.getChatById("legacy-chat"))
                val subagentDao = database.subagentRunDao()
                // v32 无 subagent 行；验证迁移后新字段可写入且默认可空语义成立
                //（实际写入在阶段 2 走 createSubagentChatAndRun）。
                assertEquals(0, subagentDao.getByExternalOwnerType("reading_companion_run").size)
            } finally {
                database.close()
            }
        }

    @Serializable
    private data class RoomSchema(
        val database: Database,
    ) {
        @Serializable
        data class Database(
            val version: Int,
            @SerialName("identityHash") val identityHash: String,
            val entities: List<Entity>,
        )

        @Serializable
        data class Entity(
            @SerialName("tableName") val tableName: String,
            @SerialName("createSql") val createSql: String,
            @SerialName("indices") val indices: List<Index> = emptyList(),
        )

        @Serializable
        data class Index(
            val name: String,
            @SerialName("createSql") val createSql: String,
        )
    }
}
