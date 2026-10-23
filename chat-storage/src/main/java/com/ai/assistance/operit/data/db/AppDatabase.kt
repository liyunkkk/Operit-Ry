package com.ai.assistance.operit.data.db

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import com.ai.assistance.operit.data.dao.ChatDao
import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatFolderDao
import com.ai.assistance.operit.data.dao.ChatTodoDao
import com.ai.assistance.operit.data.dao.MessageDao
import com.ai.assistance.operit.data.dao.MessageVariantDao
import com.ai.assistance.operit.data.dao.SubagentRunDao
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatFolderEntity
import com.ai.assistance.operit.data.model.ChatTodoEntity
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatCleanupItemEntity
import com.ai.assistance.operit.data.model.TokenStatCleanupOperationEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.model.TokenStatRangeCutoffEntity
import com.ai.assistance.operit.data.model.TokenStatResetCutoffEntity
import com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity
import com.ai.assistance.operit.util.ChatMarkupRegex

/** 应用数据库，包含聊天表和消息表 */
@Database(
    entities = [
        ChatEntity::class,
        ChatFolderEntity::class,
        ChatTodoEntity::class,
        MessageEntity::class,
        com.ai.assistance.operit.data.model.ChatRecallIndex::class,
        MessageVariantEntity::class,
        SubagentRunEntity::class,
        TokenStatEventEntity::class,
        TokenStatIdentityEntity::class,
        TokenStatDisplayModelEntity::class,
        TokenStatPriceOverrideEntity::class,
        TokenStatBaselineEntity::class,
        TokenStatRestoreGenerationEntity::class,
        TokenStatResetCutoffEntity::class,
        TokenStatRangeCutoffEntity::class,
        TokenStatCleanupOperationEntity::class,
        TokenStatCleanupItemEntity::class,
    ],
    version = 35,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    /** 获取聊天DAO */
    abstract fun chatDao(): ChatDao

    abstract fun chatFolderDao(): ChatFolderDao

    abstract fun chatTodoDao(): ChatTodoDao

    /** 获取消息DAO */
    abstract fun messageDao(): MessageDao

    abstract fun chatContentDao(): ChatContentDao

    abstract fun messageVariantDao(): MessageVariantDao

    abstract fun subagentRunDao(): SubagentRunDao

    /** 获取统计账本DAO */
    abstract fun tokenStatsDao(): TokenStatsDao

    companion object {
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN contextPolicyJson TEXT")
            }
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE chats ADD COLUMN contextPolicyJson TEXT")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 定义从版本1到2的迁移
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 创建chats表
                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS `chats` (
                                `id` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `inputTokens` INTEGER NOT NULL DEFAULT 0,
                                `outputTokens` INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent()
                    )

                    // 创建messages表
                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS `messages` (
                                `messageId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `chatId` TEXT NOT NULL,
                                `sender` TEXT NOT NULL,
                                `content` TEXT NOT NULL,
                                `timestamp` INTEGER NOT NULL,
                                `orderIndex` INTEGER NOT NULL,
                                FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent()
                    )

                    // 为messages表创建索引
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId` ON `messages` (`chatId`)")
                }

            }

        // 定义从版本10到11的迁移
        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加workspaceEnv列
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `workspaceEnv` TEXT")
                    } catch (_: Exception) {

                    }
                }
            }

        // 定义从版本11到12的迁移
        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加characterGroupId列（用于绑定群组角色卡）
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `characterGroupId` TEXT")
                    } catch (_: Exception) {

                    }
                }
            }

        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `inputTokens` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `outputTokens` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `cachedInputTokens` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `sentAt` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `outputDurationMs` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                    try {
                        db.execSQL("ALTER TABLE messages ADD COLUMN `waitDurationMs` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {
                    }
                }
            }

        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `problem_records`")
                }
            }

        private val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `selectedVariantIndex` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        """
                            CREATE TABLE IF NOT EXISTS `message_variants` (
                                `variantId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `chatId` TEXT NOT NULL,
                                `messageTimestamp` INTEGER NOT NULL,
                                `variantIndex` INTEGER NOT NULL,
                                `content` TEXT NOT NULL,
                                `roleName` TEXT NOT NULL DEFAULT '',
                                `provider` TEXT NOT NULL DEFAULT '',
                                `modelName` TEXT NOT NULL DEFAULT '',
                                `inputTokens` INTEGER NOT NULL DEFAULT 0,
                                `outputTokens` INTEGER NOT NULL DEFAULT 0,
                                `cachedInputTokens` INTEGER NOT NULL DEFAULT 0,
                                `sentAt` INTEGER NOT NULL DEFAULT 0,
                                `outputDurationMs` INTEGER NOT NULL DEFAULT 0,
                                `waitDurationMs` INTEGER NOT NULL DEFAULT 0,
                                FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON DELETE CASCADE
                            )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_message_variants_chatId_messageTimestamp` ON `message_variants` (`chatId`, `messageTimestamp`)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_variants_chatId_messageTimestamp_variantIndex` ON `message_variants` (`chatId`, `messageTimestamp`, `variantIndex`)"
                    )
                }
            }

        private val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `displayMode` TEXT NOT NULL DEFAULT 'NORMAL'"
                    )
                }
            }

        private val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_messages_chatId_timestamp` ON `messages` (`chatId`, `timestamp`)"
                    )
                }
            }

        private val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        private val MIGRATION_18_19 =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN `completedAt` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "ALTER TABLE message_variants ADD COLUMN `completedAt` INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        private val MIGRATION_19_20 =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chats ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_20_24 =
            object : Migration(20, 24) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE chats ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("ALTER TABLE chats ADD COLUMN `lastMessageAt` INTEGER")
                    db.execSQL(
                        """
                        UPDATE chats
                        SET lastMessageAt = (
                            SELECT MAX(messages.timestamp)
                            FROM messages
                            WHERE messages.chatId = chats.id
                        )
                        """.trimIndent()
                    )
                }
            }

        val MIGRATION_24_25 =
            object : Migration(24, 25) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_folders` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `parentFolderId` TEXT,
                            `displayOrder` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`parentFolderId`) REFERENCES `chat_folders`(`id`)
                                ON UPDATE NO ACTION ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_folders_parentFolderId_displayOrder` " +
                            "ON `chat_folders` (`parentFolderId`, `displayOrder`)"
                    )
                    db.execSQL(
                        "ALTER TABLE `chats` ADD COLUMN `folderId` TEXT " +
                            "REFERENCES `chat_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chats_folderId` ON `chats` (`folderId`)"
                    )

                    data class BucketKey(
                        val characterCardName: String?,
                        val characterGroupId: String?,
                        val rawGroup: String,
                    )
                    data class BucketValue(
                        val chatIds: MutableList<String> = mutableListOf(),
                        var minimumDisplayOrder: Long = Long.MAX_VALUE,
                        var minimumCreatedAt: Long = Long.MAX_VALUE,
                    )

                    val buckets = linkedMapOf<BucketKey, BucketValue>()
                    db.query(
                        """
                        SELECT `id`, `characterCardName`, `characterGroupId`, `group`,
                               `displayOrder`, `createdAt`
                        FROM `chats`
                        WHERE `group` IS NOT NULL
                        """.trimIndent()
                    ).use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow("id")
                        val cardColumn = cursor.getColumnIndexOrThrow("characterCardName")
                        val characterGroupColumn = cursor.getColumnIndexOrThrow("characterGroupId")
                        val rawGroupColumn = cursor.getColumnIndexOrThrow("group")
                        val orderColumn = cursor.getColumnIndexOrThrow("displayOrder")
                        val createdColumn = cursor.getColumnIndexOrThrow("createdAt")
                        while (cursor.moveToNext()) {
                            val rawGroup = cursor.getString(rawGroupColumn)
                            if (rawGroup.isBlank()) continue
                            val key =
                                BucketKey(
                                    characterCardName =
                                        if (cursor.isNull(cardColumn)) null else cursor.getString(cardColumn),
                                    characterGroupId =
                                        if (cursor.isNull(characterGroupColumn)) {
                                            null
                                        } else {
                                            cursor.getString(characterGroupColumn)
                                        },
                                    rawGroup = rawGroup,
                                )
                            val bucket = buckets.getOrPut(key) { BucketValue() }
                            bucket.chatIds += cursor.getString(idColumn)
                            bucket.minimumDisplayOrder =
                                minOf(bucket.minimumDisplayOrder, cursor.getLong(orderColumn))
                            bucket.minimumCreatedAt =
                                minOf(bucket.minimumCreatedAt, cursor.getLong(createdColumn))
                        }
                    }

                    fun compareNullable(left: String?, right: String?): Int =
                        when {
                            left == null && right == null -> 0
                            left == null -> -1
                            right == null -> 1
                            else -> left.compareTo(right)
                        }

                    val typedBucketComparator =
                        Comparator<BucketKey> { left, right ->
                            compareNullable(left.characterCardName, right.characterCardName)
                                .takeIf { it != 0 }
                                ?: compareNullable(left.characterGroupId, right.characterGroupId)
                                    .takeIf { it != 0 }
                                ?: left.rawGroup.compareTo(right.rawGroup)
                        }
                    val sortedBuckets =
                        buckets.entries.sortedWith(
                            Comparator { left, right ->
                                left.value.minimumDisplayOrder.compareTo(right.value.minimumDisplayOrder)
                                    .takeIf { it != 0 }
                                    ?: left.value.minimumCreatedAt.compareTo(right.value.minimumCreatedAt)
                                        .takeIf { it != 0 }
                                    ?: typedBucketComparator.compare(left.key, right.key)
                            }
                        )

                    val allocatedIds = hashSetOf<String>()
                    sortedBuckets.forEachIndexed { index, (key, bucket) ->
                        var folderId: String
                        do {
                            folderId = java.util.UUID.randomUUID().toString()
                        } while (!allocatedIds.add(folderId))
                        db.execSQL(
                            """
                            INSERT INTO `chat_folders`
                                (`id`, `name`, `parentFolderId`, `displayOrder`, `createdAt`)
                            VALUES (?, ?, NULL, ?, ?)
                            """.trimIndent(),
                            arrayOf(
                                folderId,
                                key.rawGroup.trim(),
                                index.toLong(),
                                bucket.minimumCreatedAt,
                            )
                        )
                        bucket.chatIds.forEach { chatId ->
                            db.execSQL(
                                "UPDATE `chats` SET `folderId` = ? WHERE `id` = ?",
                                arrayOf(folderId, chatId),
                            )
                        }
                    }

                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        if (cursor.moveToFirst()) {
                            val violations = mutableListOf<String>()
                            do {
                                violations +=
                                    "${cursor.getString(0)}:${cursor.getLong(1)}:${cursor.getString(2)}"
                            } while (cursor.moveToNext())
                            error("Foreign key violations after v24 to v25 migration: $violations")
                        }
                    }
                }
            }

        val MIGRATION_25_26 =
            object : Migration(25, 26) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `chats` ADD COLUMN `chatKind` TEXT NOT NULL DEFAULT 'NORMAL'"
                    )
                    db.execSQL(
                        "UPDATE `chats` SET `chatKind` = 'BRANCH' WHERE `parentChatId` IS NOT NULL"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chats_chatKind` " +
                            "ON `chats` (`chatKind`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chats_parentChatId_chatKind` " +
                            "ON `chats` (`parentChatId`, `chatKind`)"
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `subagent_runs` (
                            `id` TEXT NOT NULL,
                            `parentChatId` TEXT NOT NULL,
                            `childChatId` TEXT NOT NULL,
                            `parentToolCallId` TEXT,
                            `agentProfileId` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `startedAt` INTEGER,
                            `completedAt` INTEGER,
                            `error` TEXT,
                            `agentConfigSnapshot` TEXT,
                            `modelConfigIdSnapshot` TEXT,
                            `modelIndexSnapshot` INTEGER,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`parentChatId`) REFERENCES `chats`(`id`)
                                ON UPDATE NO ACTION ON DELETE NO ACTION,
                            FOREIGN KEY(`childChatId`) REFERENCES `chats`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_subagent_runs_parentChatId` " +
                            "ON `subagent_runs` (`parentChatId`)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_subagent_runs_childChatId` " +
                            "ON `subagent_runs` (`childChatId`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_subagent_runs_parentChatId_parentToolCallId` " +
                            "ON `subagent_runs` (`parentChatId`, `parentToolCallId`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_subagent_runs_status` " +
                            "ON `subagent_runs` (`status`)"
                    )
                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        check(!cursor.moveToFirst()) {
                            "Foreign key violations after v25 to v26 migration"
                        }
                    }
                }
            }

        val MIGRATION_26_27 =
            object : Migration(26, 27) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `subagent_runs` ADD COLUMN " +
                            "`toolInvocationCount` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "UPDATE `subagent_runs` SET `toolInvocationCount` = 0"
                    )
                    val invocationCounts = mutableMapOf<String, Int>()
                    db.query(
                        """
                        SELECT `subagent_runs`.`id`, `messages`.`content`
                        FROM `subagent_runs`
                        LEFT JOIN `messages`
                            ON `messages`.`chatId` = `subagent_runs`.`childChatId`
                        """.trimIndent()
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val runId = cursor.getString(0)
                            invocationCounts.putIfAbsent(runId, 0)
                            if (!cursor.isNull(1)) {
                                invocationCounts[runId] =
                                    invocationCounts.getValue(runId) +
                                        countFinalToolResults(cursor.getString(1))
                            }
                        }
                    }
                    db.compileStatement(
                        """
                        UPDATE `subagent_runs`
                        SET `toolInvocationCount` = ?
                        WHERE `id` = ?
                        """.trimIndent()
                    ).use { statement ->
                        invocationCounts.forEach { (runId, count) ->
                            statement.bindLong(1, count.toLong())
                            statement.bindString(2, runId)
                            statement.executeUpdateDelete()
                            statement.clearBindings()
                        }
                    }
                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        check(!cursor.moveToFirst()) {
                            "Foreign key violations after v26 to v27 migration"
                        }
                    }
                }
            }

        val MIGRATION_27_28 =
            object : Migration(27, 28) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `subagent_runs` ADD COLUMN `archivedAt` INTEGER")
                }
            }

        /**
         * v28 → v29：新增逐请求统计账本（阶段 1）。
         *
         * 只新增 5 张表与索引，不改动任何既有表；所有语句均可重入
         * （CREATE TABLE/INDEX IF NOT EXISTS），便于 Room 打开时校验。
         * 事件/价格/别名/baseline 全部位于 app_database 同一文件中，
         * 现有整库文件级备份/恢复自动覆盖，无需逐表接线。
         *
         * 同时重写两个 migrate 变体：Room 2.8 在提供 SQLiteDriver 时调用
         * migrate(SQLiteConnection)，未提供驱动（生产默认）时调用
         * migrate(SupportSQLiteDatabase)；两条路径执行同一组 SQL。
         */
        val MIGRATION_28_29 =
            object : Migration(28, 29) {

                override fun migrate(db: SupportSQLiteDatabase) {
                    runMigration { sql -> db.execSQL(sql) }
                    db.query("PRAGMA foreign_key_check").use { cursor ->
                        check(!cursor.moveToFirst()) {
                            "Foreign key violations after v28 to v29 migration"
                        }
                    }
                }

                override fun migrate(connection: SQLiteConnection) {
                    runMigration { sql -> connection.execSQL(sql) }
                    connection.prepare("PRAGMA foreign_key_check").use { statement ->
                        check(!statement.step()) {
                            "Foreign key violations after v28 to v29 migration"
                        }
                    }
                }

                private fun runMigration(execSql: (String) -> Unit) {
                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `token_stat_identities` (
                            `identityId` TEXT NOT NULL,
                            `configId` TEXT NOT NULL,
                            `provider` TEXT NOT NULL,
                            `model` TEXT NOT NULL,
                            `displayModelId` TEXT NOT NULL,
                            PRIMARY KEY(`identityId`)
                        )
                        """.trimIndent()
                    )
                    execSql(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_token_stat_identities_configId_provider_model` " +
                            "ON `token_stat_identities` (`configId`, `provider`, `model`)"
                    )
                    execSql(
                        "CREATE INDEX IF NOT EXISTS `index_token_stat_identities_displayModelId` " +
                            "ON `token_stat_identities` (`displayModelId`)"
                    )

                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `token_stat_display_models` (
                            `displayModelId` TEXT NOT NULL,
                            `normalizedModel` TEXT NOT NULL,
                            `displayName` TEXT NOT NULL,
                            PRIMARY KEY(`displayModelId`)
                        )
                        """.trimIndent()
                    )
                    execSql(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_token_stat_display_models_normalizedModel` " +
                            "ON `token_stat_display_models` (`normalizedModel`)"
                    )

                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `token_stat_price_overrides` (
                            `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `scope` TEXT NOT NULL,
                            `provider` TEXT NOT NULL,
                            `model` TEXT NOT NULL,
                            `configId` TEXT NOT NULL,
                            `billingMode` TEXT NOT NULL,
                            `pricingCurrency` TEXT NOT NULL,
                            `inputPricePerMillion` REAL,
                            `cachedInputPricePerMillion` REAL,
                            `cacheWritePricePerMillion` REAL,
                            `outputPricePerMillion` REAL,
                            `pricePerRequest` REAL
                        )
                        """.trimIndent()
                    )
                    execSql(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_token_stat_price_overrides_scope_provider_model_configId` " +
                            "ON `token_stat_price_overrides` (`scope`, `provider`, `model`, `configId`)"
                    )

                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `token_stat_events` (
                            `eventId` TEXT NOT NULL,
                            `statIdentityId` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `startedAtMs` INTEGER NOT NULL,
                            `endedAtMs` INTEGER NOT NULL,
                            `firstTokenAtMs` INTEGER,
                            `uncachedInputTokens` INTEGER,
                            `cachedInputTokens` INTEGER,
                            `cacheWriteTokens` INTEGER,
                            `outputTokens` INTEGER,
                            `reasoningTokens` INTEGER,
                            `reasoningIncludedInOutput` INTEGER,
                            `billingMode` TEXT NOT NULL,
                            `pricingCurrency` TEXT NOT NULL,
                            `inputPricePerMillion` REAL,
                            `cachedInputPricePerMillion` REAL,
                            `cacheWritePricePerMillion` REAL,
                            `outputPricePerMillion` REAL,
                            `pricePerRequest` REAL,
                            `pricingSource` TEXT NOT NULL,
                            `costInPricingCurrency` REAL,
                            PRIMARY KEY(`eventId`),
                            FOREIGN KEY(`statIdentityId`)
                                REFERENCES `token_stat_identities`(`identityId`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    execSql(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_token_stat_events_statIdentityId_startedAtMs` " +
                            "ON `token_stat_events` (`statIdentityId`, `startedAtMs`)"
                    )
                    execSql(
                        "CREATE INDEX IF NOT EXISTS `index_token_stat_events_startedAtMs` " +
                            "ON `token_stat_events` (`startedAtMs`)"
                    )
                    execSql(
                        "CREATE INDEX IF NOT EXISTS `index_token_stat_events_category_startedAtMs` " +
                            "ON `token_stat_events` (`category`, `startedAtMs`)"
                    )

                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `token_stat_baselines` (
                            `identityId` TEXT NOT NULL,
                            `inputTokens` INTEGER NOT NULL,
                            `cachedInputTokens` INTEGER NOT NULL,
                            `outputTokens` INTEGER NOT NULL,
                            `requestCount` INTEGER NOT NULL,
                            `pricingCurrency` TEXT NOT NULL,
                            `costInPricingCurrency` REAL,
                            `isEstimated` INTEGER NOT NULL,
                            `fingerprint` TEXT NOT NULL,
                            `importedAtMs` INTEGER NOT NULL,
                            `frozenBillingMode` TEXT NOT NULL,
                            `frozenInputPricePerMillion` REAL,
                            `frozenCachedInputPricePerMillion` REAL,
                            `frozenOutputPricePerMillion` REAL,
                            `frozenPricePerRequest` REAL,
                            PRIMARY KEY(`identityId`),
                            FOREIGN KEY(`identityId`)
                                REFERENCES `token_stat_identities`(`identityId`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `token_stat_restore_generations` (
                            `generation` TEXT NOT NULL,
                            `appliedAtMs` INTEGER NOT NULL,
                            PRIMARY KEY(`generation`)
                        )
                        """.trimIndent()
                    )
                }
            }

        /**
         * v29 → v30：事件表增加脱敏诊断列与费用计算所需的**结构化**列：
         * - `totalInputTokens`：provider 明确上报的总输入（拆分未知时费用重估直接读取）；
         * - `cacheWriteSeparateBilling`：缓存写入是否独立计费（重估语义直接读取，
         *   不依赖 JSON 解析或按 provider 推断）；
         * - `diagnosticsJson`：来源标签、usageObserved、usageReportCount 等诊断元数据。
         * 另新增 `token_stat_reset_cutoffs` 表（reset tombstone，见
         * [com.ai.assistance.operit.data.model.TokenStatResetCutoffEntity]）。
         * 全部为纯新增，对既有行无损，可重入（重复执行时列/表已存在即跳过）。
         */
        val MIGRATION_29_30 =
            object : Migration(29, 30) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    addColumns { sql -> db.execSQL(sql) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    addColumns { sql -> connection.execSQL(sql) }
                }

                private fun addColumns(execSql: (String) -> Unit) {
                    try {
                        execSql(
                            "ALTER TABLE `token_stat_events` ADD COLUMN " +
                                "`acceptedGeneration` INTEGER NOT NULL DEFAULT 0"
                        )
                    } catch (_: Exception) {
                        // 列已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            "ALTER TABLE `token_stat_events` ADD COLUMN `totalInputTokens` INTEGER"
                        )
                    } catch (_: Exception) {
                        // 列已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            "ALTER TABLE `token_stat_events` ADD COLUMN " +
                                "`cacheWriteSeparateBilling` INTEGER"
                        )
                    } catch (_: Exception) {
                        // 列已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            "ALTER TABLE `token_stat_events` ADD COLUMN `diagnosticsJson` TEXT"
                        )
                    } catch (_: Exception) {
                        // 列已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            """
                            CREATE TABLE IF NOT EXISTS `token_stat_reset_cutoffs` (
                                `kind` TEXT NOT NULL,
                                `provider` TEXT NOT NULL,
                                `model` TEXT NOT NULL,
                                `generation` INTEGER NOT NULL,
                                PRIMARY KEY(`kind`, `provider`, `model`)
                            )
                            """.trimIndent()
                        )
                    } catch (_: Exception) {
                        // 表已存在（幂等重放），忽略
                    }
                }
            }

        /**
         * v30 → v31：新增 `token_stat_range_cutoffs` 表（阶段 5 范围删除 tombstone，
         * 见 [com.ai.assistance.operit.data.model.TokenStatRangeCutoffEntity]）与
         * legacy cleanup outbox 两张表（operation + items，见
         * [com.ai.assistance.operit.data.model.TokenStatCleanupOperationEntity] /
         * [com.ai.assistance.operit.data.model.TokenStatCleanupItemEntity]）。
         * 纯新增表，对既有行无损，可重入（重复执行时表已存在即跳过）。
         */
        val MIGRATION_30_31 =
            object : Migration(30, 31) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    createTable { sql -> db.execSQL(sql) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    createTable { sql -> connection.execSQL(sql) }
                }

                private fun createTable(execSql: (String) -> Unit) {
                    try {
                        execSql(
                            """
                            CREATE TABLE IF NOT EXISTS `token_stat_range_cutoffs` (
                                `generation` INTEGER NOT NULL,
                                `startMs` INTEGER NOT NULL,
                                `endMs` INTEGER NOT NULL,
                                PRIMARY KEY(`generation`)
                            )
                            """.trimIndent()
                        )
                    } catch (_: Exception) {
                        // 表已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            """
                            CREATE TABLE IF NOT EXISTS `token_stat_cleanup_operations` (
                                `operationId` TEXT NOT NULL,
                                `scope` TEXT NOT NULL,
                                `targetRef` TEXT NOT NULL,
                                `deleteBaselines` INTEGER NOT NULL,
                                `status` TEXT NOT NULL,
                                `createdAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`operationId`)
                            )
                            """.trimIndent()
                        )
                    } catch (_: Exception) {
                        // 表已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            """
                            CREATE TABLE IF NOT EXISTS `token_stat_cleanup_items` (
                                `operationId` TEXT NOT NULL,
                                `identityId` TEXT NOT NULL,
                                `provider` TEXT NOT NULL,
                                `model` TEXT NOT NULL,
                                PRIMARY KEY(`operationId`, `identityId`),
                                FOREIGN KEY(`operationId`)
                                    REFERENCES `token_stat_cleanup_operations`(`operationId`)
                                    ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                    } catch (_: Exception) {
                        // 表已存在（幂等重放），忽略
                    }
                    try {
                        execSql(
                            "CREATE INDEX IF NOT EXISTS `index_token_stat_cleanup_items_operationId` " +
                                "ON `token_stat_cleanup_items` (`operationId`)"
                        )
                    } catch (_: Exception) {
                        // 索引已存在（幂等重放），忽略
                    }
                }
            }

        val MIGRATION_31_32 =
            object : Migration(31, 32) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    createTables { sql -> db.execSQL(sql) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    createTables { sql -> connection.execSQL(sql) }
                }

                private fun createTables(execSql: (String) -> Unit) {
                    execSql(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_todos` (
                            `chatId` TEXT NOT NULL,
                            `position` INTEGER NOT NULL,
                            `content` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `priority` TEXT NOT NULL,
                            PRIMARY KEY(`chatId`, `position`),
                            FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    execSql(
                        "CREATE INDEX IF NOT EXISTS `index_chat_todos_chatId` " +
                            "ON `chat_todos` (`chatId`)"
                    )
                }
            }

        /**
         * v32 → v33：阅读伴侣审计隐藏聊天与跨库弱关联。
         *
         * - `chats.isHidden`/`chats.hiddenReason`：隐藏聊天不进入普通列表；旧行默认可见。
         * - `subagent_runs.externalOwnerType/externalOwnerId/modelRoundCount`：阅读 run 对
         *   subagent run 的弱关联（无跨库 FK，统一由 reconciliation 对账）与模型轮次计数。
         *
         * 全部为纯 ALTER ADD + 索引，对既有行无损、可重入（重复执行时列已存在即跳过）。
         */
        val MIGRATION_32_33 =
            object : Migration(32, 33) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    addColumns { sql -> db.execSQL(sql) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    addColumns { sql -> connection.execSQL(sql) }
                }

                private fun addColumns(execSql: (String) -> Unit) {
                    alterColumn(execSql) {
                        "ALTER TABLE `chats` ADD COLUMN `isHidden` INTEGER NOT NULL DEFAULT 0"
                    }
                    alterColumn(execSql) {
                        "ALTER TABLE `chats` ADD COLUMN `hiddenReason` TEXT"
                    }
                    execSql(
                        "CREATE INDEX IF NOT EXISTS `index_chats_isHidden` " +
                            "ON `chats` (`isHidden`)"
                    )
                    alterColumn(execSql) {
                        "ALTER TABLE `subagent_runs` ADD COLUMN `externalOwnerType` TEXT"
                    }
                    alterColumn(execSql) {
                        "ALTER TABLE `subagent_runs` ADD COLUMN `externalOwnerId` TEXT"
                    }
                    alterColumn(execSql) {
                        "ALTER TABLE `subagent_runs` ADD COLUMN " +
                            "`modelRoundCount` INTEGER NOT NULL DEFAULT 0"
                    }
                    execSql(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_subagent_runs_externalOwnerType_externalOwnerId` " +
                            "ON `subagent_runs` (`externalOwnerType`, `externalOwnerId`)"
                    )
                }

                /**
                 * 幂等重放只容忍"列已存在"（duplicate column name）；其他 SQLite 错误必须上抛，
                 * 不能把真实迁移失败误当成可重入。
                 */
                private fun alterColumn(
                    execSql: (String) -> Unit,
                    sql: () -> String,
                ) {
                    try {
                        execSql(sql())
                    } catch (error: Exception) {
                        val message = error.message.orEmpty()
                        val sqliteFailure =
                            error is SQLiteException || error is androidx.sqlite.SQLiteException
                        val duplicateColumn = duplicateColumnRegex.containsMatchIn(message)
                        if (!(sqliteFailure && duplicateColumn)) throw error
                    }
                }
            }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) = createRecallIndex { db.execSQL(it) }
            override fun migrate(connection: SQLiteConnection) = createRecallIndex { connection.execSQL(it) }
        }

        private fun createRecallIndex(exec: (String) -> Unit) {
            exec("CREATE VIRTUAL TABLE IF NOT EXISTS `chat_recall_fts` USING FTS4(`content` TEXT NOT NULL, tokenize=unicode61, content=`messages`)")
            exec("INSERT INTO chat_recall_fts(chat_recall_fts) VALUES('rebuild')")
            exec("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_recall_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `chat_recall_fts` WHERE `docid`=OLD.`rowid`; END")
            exec("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_recall_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `chat_recall_fts` WHERE `docid`=OLD.`rowid`; END")
            exec("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_recall_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `chat_recall_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END")
            exec("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chat_recall_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `chat_recall_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END")
        }
        private val duplicateColumnRegex = Regex("(?i)duplicate column name")

        private val finalTrueAttributeRegex =
            Regex("""\bfinal\s*=\s*["']true["']""", RegexOption.IGNORE_CASE)

        private fun countFinalToolResults(content: String): Int =
            ChatMarkupRegex.toolResultTagWithAttrs
                .findAll(content)
                .count { match ->
                    finalTrueAttributeRegex.containsMatchIn(match.groupValues[2])
                }

        // 定义从版本2到3的迁移
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加group列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `group` TEXT")
                }
            }

        // 定义从版本3到4的迁移
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加displayOrder列，并用updatedAt填充现有数据
                    db.execSQL(
                        "ALTER TABLE chats ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("UPDATE chats SET displayOrder = updatedAt")
                }
            }

        // 定义从版本4到5的迁移
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加workspace列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `workspace` TEXT")
                }
            }

        // 定义从版本5到6的迁移
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 检查currentWindowSize列是否已存在，如果不存在则添加
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `currentWindowSize` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {

                    }
                }
            }

        // 定义从版本6到7的迁移
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向messages表添加roleName列
                    db.execSQL("ALTER TABLE messages ADD COLUMN `roleName` TEXT NOT NULL DEFAULT ''")
                }
            }

        // 定义从版本7到8的迁移
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加parentChatId列
                    db.execSQL("ALTER TABLE chats ADD COLUMN `parentChatId` TEXT")
                    // 向chats表添加characterCardName列（用于绑定角色卡）
                    db.execSQL("ALTER TABLE chats ADD COLUMN `characterCardName` TEXT")
                }
            }

        // 定义从版本8到9的迁移
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向messages表添加provider列（供应商）
                    db.execSQL("ALTER TABLE messages ADD COLUMN `provider` TEXT NOT NULL DEFAULT ''")
                    // 向messages表添加modelName列（模型名称）
                    db.execSQL("ALTER TABLE messages ADD COLUMN `modelName` TEXT NOT NULL DEFAULT ''")
                }
            }

        // 定义从版本9到10的迁移
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 向chats表添加locked列（锁定聊天，禁止删除）
                    try {
                        db.execSQL("ALTER TABLE chats ADD COLUMN `locked` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {

                    }
                }
            }

        /** 获取数据库实例，单例模式 */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE
                ?: synchronized(this) {
                    val instance =
                        Room.databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "app_database"
                        )
                            .addMigrations(
                                MIGRATION_1_2,
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_4_5,
                                MIGRATION_5_6,
                                MIGRATION_6_7,
                                MIGRATION_7_8,
                                MIGRATION_8_9,
                                MIGRATION_9_10,
                                MIGRATION_10_11,
                                MIGRATION_11_12,
                                MIGRATION_12_13,
                                MIGRATION_13_14,
                                MIGRATION_14_15,
                                MIGRATION_15_16,
                                MIGRATION_16_17,
                                MIGRATION_17_18,
                                MIGRATION_18_19,
                                MIGRATION_19_20,
                                MIGRATION_20_24,
                                MIGRATION_24_25,
                                MIGRATION_25_26,
                                MIGRATION_26_27,
                                MIGRATION_27_28,
                                MIGRATION_28_29,
                                MIGRATION_29_30,
                                MIGRATION_30_31,
                                MIGRATION_31_32,
                                MIGRATION_32_33,
                                MIGRATION_33_34,
                                MIGRATION_34_35,
                            ) // 添加新的迁移
                            // personal/dev briefly shipped experimental schemas 21-23. Only those
                            // development inputs are intentionally rebuilt; stable v20 is migrated.
                            .fallbackToDestructiveMigrationFrom(true, 21, 22, 23)
                            .build()
                    INSTANCE = instance
                    instance
                }
        }

        fun closeDatabase() {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } finally {
                    INSTANCE = null
                }
            }
        }
    }
}
