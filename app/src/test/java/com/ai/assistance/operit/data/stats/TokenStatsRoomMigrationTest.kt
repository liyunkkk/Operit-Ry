package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 真实 Room 迁移测试（纯 JVM，sqlite-jdbc）：
 * 用 v28 导出 schema 构造 v28 数据库 → 通过 [AppDatabase] + [AppDatabase.MIGRATION_28_29]
 * 真实打开 → 验证迁移、schema 校验（Room 会做 identityHash/TableInfo 校验）、
 * 旧数据保留、新表 DAO 读写与幂等语义。
 *
 * 事件/价格/别名/baseline 与聊天等旧表位于同一个 app_database 文件，因此现有
 * 整库文件级备份/恢复自动覆盖它们（无需逐表接线）。
 */
class TokenStatsRoomMigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schemaDir = File("schemas/com.ai.assistance.operit.data.db.AppDatabase")

    private fun mockContext(tempDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        // DataStore 委托在 coordinator 排空时按 filesDir 定位偏好文件（隔离到临时目录）
        whenever(context.filesDir).thenReturn(tempDir)
        // 模拟 Android Context 的数据库目录解析：<tempDir>/<name>
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(tempDir, invocation.getArgument<String>(0))
        }
        return context
    }

    /** 注入 ApiPreferences 单例（排空协议测试隔离；null 还原）。 */
    private fun injectApiPreferences(instance: com.ai.assistance.operit.data.preferences.ApiPreferences?) {
        val field =
            com.ai.assistance.operit.data.preferences.ApiPreferences::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
        field.set(null, instance)
    }

    /** 用导出的 v28 schema JSON 构造一个真实的 v28 数据库文件。 */
    private fun buildV28Database(dbPath: String): RoomSchema {
        val schemaFile = File(schemaDir, "28.json")
        assertTrue("schema export missing: ${schemaFile.absolutePath}", schemaFile.isFile)
        val schema = json.decodeFromString<RoomSchema>(schemaFile.readText())
        assertEquals(28, schema.database.version)

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                schema.database.entities.forEach { entity ->
                    statement.execute(entity.createSql.replace("\${TABLE_NAME}", entity.tableName))
                    entity.indices.forEach { index ->
                        statement.execute(index.createSql.replace("\${TABLE_NAME}", entity.tableName))
                    }
                }
                // 与真实 v28 Room 数据库一致的 master 表
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT NOT NULL)"
                )
                statement.execute(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                        "VALUES(42, '${schema.database.identityHash}')"
                )
                statement.execute("PRAGMA user_version = 28")
                // 旧数据：迁移前插入一条聊天，验证迁移后数据保留
                statement.execute(
                    "INSERT INTO chats " +
                        "(id, title, createdAt, updatedAt, inputTokens, outputTokens, " +
                        "currentWindowSize, displayOrder, locked, pinned, isFavorite) " +
                        "VALUES ('legacy-chat', 'legacy', 1, 2, 3, 4, 5, 6, 0, 0, 0)"
                )
            }
        }
        return schema
    }

    @Test
    fun `v28 database opens through real Room migration and preserves legacy data`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV28Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_28_29,
                        AppDatabase.MIGRATION_29_30,
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                // 触发打开与迁移（Room 内部校验 identityHash 与 TableInfo）
                val legacyChat = database.chatDao().getChatById("legacy-chat")
                assertNotNull("migration must preserve legacy rows", legacyChat)
                assertEquals("legacy", legacyChat!!.title)

                // 新表已存在（独立连接读取同一文件）
                val tables = queryTables(dbFile.absolutePath)
                assertTrue("token_stat_events", tables.contains("token_stat_events"))
                assertTrue("token_stat_identities", tables.contains("token_stat_identities"))
                assertTrue("token_stat_display_models", tables.contains("token_stat_display_models"))
                assertTrue("token_stat_price_overrides", tables.contains("token_stat_price_overrides"))
                assertTrue("token_stat_baselines", tables.contains("token_stat_baselines"))

                // 迁移可重入（CREATE IF NOT EXISTS）：以驱动变体再跑一次；
                // Room 打开时已应用 28→29→30→31→32→33→34，重放 28→29 不改变版本号
                JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                    AppDatabase.MIGRATION_28_29.migrate(connection)
                    assertEquals(35, userVersion(connection))
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `stats dao roundtrips with identity fk and idempotent event inserts`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV28Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_28_29,
                        AppDatabase.MIGRATION_29_30,
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                val dao = database.tokenStatsDao()

                val identityId =
                    TokenStatIdentityResolver.identityId("", "DEEPSEEK", "deepseek-chat")
                dao.insertIdentityIfAbsent(
                    TokenStatIdentityEntity(
                        identityId = identityId,
                        configId = "",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        displayModelId = "deepseek-chat",
                    )
                )
                dao.upsertDisplayModel(
                    TokenStatDisplayModelEntity(
                        displayModelId = "deepseek-chat",
                        normalizedModel = "deepseek-chat",
                        displayName = "deepseek-chat",
                    )
                )
                assertEquals(identityId, dao.getIdentityByTriple("", "DEEPSEEK", "deepseek-chat")?.identityId)

                val event =
                    TokenStatEventEntity(
                        eventId = "req-1",
                        statIdentityId = identityId,
                        category = TokenStatCategory.CHAT.name,
                        status = TokenStatStatus.COMPLETED.name,
                        startedAtMs = 1000L,
                        endedAtMs = 2000L,
                        firstTokenAtMs = 1200L,
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
cacheWriteTokens = 100L,
outputTokens = 500L,
reasoningTokens = 50L,
                        reasoningIncludedInOutput = true,
                        billingMode = BillingMode.TOKEN.name,
                        pricingCurrency = "USD",
                        inputPricePerMillion = 1.0,
                        cachedInputPricePerMillion = 0.5,
                        cacheWritePricePerMillion = 0.75,
                        outputPricePerMillion = 2.0,
                        pricingSource = PricingSource.DEFAULT.name,
                        costInPricingCurrency = 0.001975,
                    )
                dao.insertEvent(event)
                dao.insertEvent(event) // 重复 eventId 必须被忽略
                assertEquals(1, dao.countEvents())
                val readBack = dao.getEvent("req-1")!!
                assertEquals(0.001975, readBack.costInPricingCurrency!!, 1e-12)
                assertEquals(100L, readBack.cacheWriteTokens)
                assertEquals(0.75, readBack.cacheWritePricePerMillion!!, 1e-12)

                // 未知分量以 null 落库，0 是确认值：null vs 0 必须可区分
                val nullFieldsEvent =
                    event.copy(
                        eventId = "req-2",
                        uncachedInputTokens = null,
                        cachedInputTokens = null,
                        cacheWriteTokens = null,
                        outputTokens = null,
                        costInPricingCurrency = null,
                    )
                dao.insertEvent(nullFieldsEvent)
                val nullReadBack = dao.getEvent("req-2")!!
                assertNull(nullReadBack.uncachedInputTokens)
                assertNull(nullReadBack.cachedInputTokens)
                assertNull(nullReadBack.cacheWriteTokens)
                assertNull(nullReadBack.outputTokens)
                assertNull(nullReadBack.costInPricingCurrency)
                assertEquals(2, dao.countEvents())

                // baseline 以 identityId 为键整体替换（幂等；baseline 无子表，REPLACE 安全）
                val baseline =
                    TokenStatBaselineEntity(
                        identityId = identityId,
                        inputTokens = 1000L,
                        cachedInputTokens = 200L,
                        outputTokens = 500L,
                        requestCount = 3L,
                        pricingCurrency = "USD",
                        costInPricingCurrency = 1.9,
                        isEstimated = true,
                        fingerprint = "fp-1",
                        importedAtMs = 100L,
                        frozenBillingMode = BillingMode.TOKEN.name,
                        frozenInputPricePerMillion = 1.0,
                        frozenCachedInputPricePerMillion = 0.5,
                        frozenOutputPricePerMillion = 2.0,
                    )
                dao.upsertBaseline(baseline)
                dao.upsertBaseline(baseline.copy(costInPricingCurrency = 2.5, fingerprint = "fp-2"))
                assertEquals(1, dao.countBaselines())
                assertEquals("fp-2", dao.getBaseline(identityId)!!.fingerprint)

                // 价格覆盖：唯一写入入口是 upsertPriceOverride（校验 scope 枚举 +
                // 规范化字段）。大小写/空白不同的原始输入 → 规范化后同一业务列 →
                // 唯一索引冲突 REPLACE 覆盖，表内只能一行。
                dao.upsertPriceOverride(
                    scope = "PROVIDER_MODEL",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    configId = null,
                    billingMode = BillingMode.TOKEN.name,
                    pricingCurrency = "USD",
                    inputPricePerMillion = 3.0,
                    cachedInputPricePerMillion = 1.5,
                    cacheWritePricePerMillion = 0.75,
                    outputPricePerMillion = 6.0,
                )
                dao.upsertPriceOverride(
                    scope = "PROVIDER_MODEL",
                    provider = "  deepseek ",
                    model = "  DeepSeek-Chat ",
                    configId = null,
                    billingMode = BillingMode.TOKEN.name,
                    pricingCurrency = "USD",
                    inputPricePerMillion = 9.0,
                    cachedInputPricePerMillion = 4.5,
                    cacheWritePricePerMillion = 2.25,
                    outputPricePerMillion = 18.0,
                )
                assertEquals(1, dao.getAllPriceOverrides().size)
                // 落库的必须是规范化后的业务列
                val override =
                    dao.getPriceOverride("PROVIDER_MODEL", "deepseek", "deepseek-chat", "")
                assertNotNull(override)
                assertEquals("deepseek", override!!.provider)
                assertEquals("deepseek-chat", override.model)
                assertEquals("", override.configId)
                assertEquals(9.0, override.inputPricePerMillion!!, 1e-9)
                assertEquals(2.25, override.cacheWritePricePerMillion!!, 1e-9)
                // rowId 是内部主键，行内容按业务列解析，与 rowId 无关
                assertTrue(override.rowId > 0)

                // CONFIG 范围与 PROVIDER_MODEL 范围同 provider/model 可并存
                dao.upsertPriceOverride(
                    scope = "CONFIG",
                    provider = "DEEPSEEK",
                    model = "deepseek-chat",
                    configId = " cfg-1 ",
                    billingMode = BillingMode.TOKEN.name,
                    pricingCurrency = "USD",
                    inputPricePerMillion = 12.0,
                )
                assertEquals(2, dao.getAllPriceOverrides().size)
                val configOverride =
                    dao.getPriceOverride("CONFIG", "deepseek", "deepseek-chat", "cfg-1")
                assertNotNull(configOverride)
                assertEquals(12.0, configOverride!!.inputPricePerMillion!!, 1e-9)

                // 业务列错配不命中：另一 provider 的查询不会读到该行
                assertNull(dao.getPriceOverride("PROVIDER_MODEL", "openai", "deepseek-chat", ""))
                assertNull(dao.getPriceOverride("CONFIG", "deepseek", "deepseek-chat", "other-cfg"))

                // 非法 scope 必须在写入边界被拒绝（不落库）
                try {
                    dao.upsertPriceOverride(
                        scope = "BOGUS_SCOPE",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        configId = null,
                        billingMode = BillingMode.TOKEN.name,
                        pricingCurrency = "USD",
                        inputPricePerMillion = 1.0,
                    )
                    fail("expected IllegalArgumentException for illegal scope")
                } catch (e: IllegalArgumentException) {
                    // expected
                }
                assertEquals(2, dao.getAllPriceOverrides().size)

                // 恢复 generation 幂等锚点：重复插入同 generation 被忽略
                dao.insertRestoreGeneration(
                    com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity(
                        generation = "gen-1",
                        appliedAtMs = 1000L,
                    )
                )
                dao.insertRestoreGeneration(
                    com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity(
                        generation = "gen-1",
                        appliedAtMs = 2000L,
                    )
                )
                assertEquals(1, dao.restoreGenerationExists("gen-1"))
                assertEquals(0, dao.restoreGenerationExists("gen-2"))

                // 按身份删除事件与 baseline（重置语义）
                assertEquals(2, dao.deleteEventsByIdentity(identityId))
                assertEquals(1, dao.deleteBaseline(identityId))
                assertEquals(0, dao.countEvents())
                assertEquals(0, dao.countBaselines())

                // 外键级联：删除身份时事件与 baseline 跟随删除
                dao.insertEvent(event)
                dao.upsertBaseline(baseline)
                JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                    // 关闭日志模式，避免 Windows 上删除 journal 文件的 IOERR_DELETE 抖动
                    connection.prepare("PRAGMA journal_mode = OFF").use { it.step() }
                    connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
                    connection.prepare(
                        "DELETE FROM token_stat_identities WHERE identityId = '$identityId'"
                    ).use { it.step() }
                }
                assertEquals(0, dao.countEvents())
                assertEquals(0, dao.countBaselines())
            } finally {
                database.close()
            }
        }

    @Test
    fun `reset by provider model deletes events across config identities and baseline`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV28Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_28_29,
                        AppDatabase.MIGRATION_29_30,
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                val dao = database.tokenStatsDao()

                // 同一 provider/model 的多个配置实例身份 + 一个无关模型
                val identities =
                    listOf("", "cfg-1", "cfg-2").mapIndexed { index, configId ->
                        TokenStatIdentityEntity(
                            identityId =
                                TokenStatIdentityResolver.identityId(configId, "DEEPSEEK", "deepseek-chat"),
                            configId = configId,
                            provider = "DEEPSEEK",
                            model = "deepseek-chat",
                            displayModelId = "deepseek-chat",
                        ).also { dao.insertIdentityIfAbsent(it) }
                    }
                val otherIdentity =
                    TokenStatIdentityEntity(
                        identityId = TokenStatIdentityResolver.identityId("", "OPENAI", "gpt-4o"),
                        configId = "",
                        provider = "OPENAI",
                        model = "gpt-4o",
                        displayModelId = "gpt-4o",
                    ).also { dao.insertIdentityIfAbsent(it) }

                fun eventOf(id: String, identity: TokenStatIdentityEntity) =
                    TokenStatEventEntity(
                        eventId = id,
                        statIdentityId = identity.identityId,
                        category = TokenStatCategory.CHAT.name,
                        status = TokenStatStatus.COMPLETED.name,
                        startedAtMs = 1000L,
                        endedAtMs = 2000L,
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
cacheWriteTokens = 100L,
outputTokens = 500L,
                        billingMode = BillingMode.TOKEN.name,
                        pricingCurrency = "USD",
                        inputPricePerMillion = 1.0,
                        cachedInputPricePerMillion = 0.5,
                        cacheWritePricePerMillion = 0.75,
                        outputPricePerMillion = 2.0,
                        pricingSource = PricingSource.DEFAULT.name,
                        costInPricingCurrency = 0.0002,
                    )
                identities.forEachIndexed { index, identity ->
                    dao.insertEvent(eventOf("deepseek-event-$index", identity))
                }
                dao.insertEvent(eventOf("openai-event", otherIdentity))
                // 旧系统 baseline 身份 configId 为空串，属于被重置范围
                dao.upsertBaseline(
                    TokenStatBaselineEntity(
                        identityId = identities.first().identityId,
                        inputTokens = 100L,
                        cachedInputTokens = 0L,
                        outputTokens = 50L,
                        requestCount = 1L,
                        pricingCurrency = "USD",
                        costInPricingCurrency = 0.0002,
                        isEstimated = true,
                        fingerprint = "fp",
                        importedAtMs = 1L,
                        frozenBillingMode = BillingMode.TOKEN.name,
                        frozenInputPricePerMillion = 1.0,
                        frozenOutputPricePerMillion = 2.0,
                    )
                )
                assertEquals(4, dao.countEvents())
                assertEquals(1, dao.countBaselines())

                // 按 provider/model 重置：所有配置实例的事件 + 全部匹配 baseline 一起清。
                // 通过 daoProvider 注入缝把真实 DAO 交给协调器（生产路径用
                // AppDatabase.withTransaction 包同一组删除）。P1 闭环：删除后协调器
                // 排空 legacy cleanup——DataStore 侧注入 mock 隔离（真实键级协议由
                // TokenStatsCleanupOutboxTest 覆盖，此处聚焦 Room 语义与删除矩阵）。
                TokenStatsResetCoordinator.daoProvider = { dao }
                val prefsMock = mock<com.ai.assistance.operit.data.preferences.ApiPreferences>()
                injectApiPreferences(prefsMock)
                try {
                    TokenStatsResetCoordinator
                        .resetStatisticsForProviderModel(mockContext(tempDir), "DEEPSEEK:deepseek-chat")
                } finally {
                    TokenStatsResetCoordinator.daoProvider = null
                    injectApiPreferences(null)
                }

                assertEquals(1, dao.countEvents())
                assertEquals("openai-event", dao.getEvent("openai-event")!!.eventId)
                assertEquals(0, dao.countBaselines())
            } finally {
                database.close()
            }
        }

    @Test
    fun `identity reinsert and display model update never cascade delete events`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV28Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_28_29,
                        AppDatabase.MIGRATION_29_30,
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                val dao = database.tokenStatsDao()
                val identityId =
                    TokenStatIdentityResolver.identityId("", "DEEPSEEK", "deepseek-chat")
                val identity =
                    TokenStatIdentityEntity(
                        identityId = identityId,
                        configId = "",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        displayModelId = "deepseek-chat",
                    )
                dao.insertIdentityIfAbsent(identity)
                val event =
                    TokenStatEventEntity(
                        eventId = "req-1",
                        statIdentityId = identityId,
                        category = TokenStatCategory.CHAT.name,
                        status = TokenStatStatus.COMPLETED.name,
                        startedAtMs = 1000L,
                        endedAtMs = 2000L,
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
outputTokens = 500L,
                        billingMode = BillingMode.TOKEN.name,
                        pricingCurrency = "USD",
                        inputPricePerMillion = 1.0,
                        cachedInputPricePerMillion = 0.5,
                        outputPricePerMillion = 2.0,
                        pricingSource = PricingSource.DEFAULT.name,
                        costInPricingCurrency = 0.0019,
                    )
                dao.insertEvent(event)
                assertEquals(1, dao.countEvents())

                // 重导路径：同一身份再次插入 → INSERT IGNORE，绝不可 REPLACE 删除
                //（REPLACE = DELETE + INSERT 会通过外键级联删除该身份的事件）
                dao.insertIdentityIfAbsent(identity)
                dao.insertIdentitiesIfAbsent(listOf(identity))
                assertEquals(1, dao.countEvents())
                assertEquals(identityId, dao.getIdentity(identityId)!!.identityId)

                // 分组变更走显式安全 UPDATE，同样不得级联删除事件
                dao.updateIdentityDisplayModel(identityId, "merged-group")
                assertEquals(1, dao.countEvents())
                assertEquals("merged-group", dao.getIdentity(identityId)!!.displayModelId)
                val readBack = dao.getEvent("req-1")!!
                assertEquals(800L, readBack.uncachedInputTokens)
                assertEquals(0.0019, readBack.costInPricingCurrency!!, 1e-12)
            } finally {
                database.close()
            }
        }

    /** 用导出的 v29 schema JSON 构造一个真实的 v29 数据库文件（含一条事件行）。 */
    private fun buildV29Database(dbPath: String) {
        val schemaFile = File(schemaDir, "29.json")
        assertTrue("schema export missing: ${schemaFile.absolutePath}", schemaFile.isFile)
        val schema = json.decodeFromString<RoomSchema>(schemaFile.readText())
        assertEquals(29, schema.database.version)

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
                statement.execute("PRAGMA user_version = 29")
                // 旧数据：迁移前插入一条事件，验证迁移后数据保留（含价格快照）
                statement.execute(
                    "INSERT INTO token_stat_identities " +
                        "(identityId, configId, provider, model, displayModelId) " +
                        "VALUES ('identity-1', '', 'DEEPSEEK', 'deepseek-chat', 'deepseek-chat')"
                )
                statement.execute(
                    "INSERT INTO token_stat_events " +
                        "(eventId, statIdentityId, category, status, startedAtMs, endedAtMs, " +
                        "firstTokenAtMs, uncachedInputTokens, cachedInputTokens, cacheWriteTokens, " +
                        "outputTokens, reasoningTokens, reasoningIncludedInOutput, billingMode, " +
                        "pricingCurrency, inputPricePerMillion, cachedInputPricePerMillion, " +
                        "cacheWritePricePerMillion, outputPricePerMillion, pricePerRequest, " +
                        "pricingSource, costInPricingCurrency) " +
                        "VALUES ('evt-v29', 'identity-1', 'CHAT', 'COMPLETED', 1000, 2000, 1200, " +
                        "800, 200, 100, 500, 50, 1, 'TOKEN', 'USD', 1.0, 0.5, 2.0, 3.0, NULL, " +
                        "'DEFAULT', 0.0019)"
                )
            }
        }
    }

    @Test
    fun `v29 database migrates through v30 to v31 keeping events and adding diagnostics column`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV29Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_29_30,
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                // 触发打开与迁移（Room 内部校验 identityHash 与 TableInfo，包括新列）
                val dao = database.tokenStatsDao()
                val readBack = dao.getEvent("evt-v29")
                assertNotNull("migration must preserve legacy event rows", readBack)
                assertEquals(800L, readBack!!.uncachedInputTokens)
                assertEquals("DEFAULT", readBack.pricingSource)
                assertNull("v29 rows have no diagnostics", readBack.diagnosticsJson)
                // v30 新增的结构化列对旧行保持 null（未知），与新写入可区分
                assertNull(readBack.totalInputTokens)
                assertNull(readBack.cacheWriteSeparateBilling)

                // 新列可写
                dao.insertEvent(
                    readBack.copy(
                        eventId = "evt-v30",
totalInputTokens = 1000L,
                        cacheWriteSeparateBilling = false,
                        diagnosticsJson = "{\"source\":\"openai_chat_completions\",\"usageObserved\":true}",
                    )
                )
                val v30Event = dao.getEvent("evt-v30")!!
                assertEquals(1000L, v30Event.totalInputTokens)
                assertEquals(false, v30Event.cacheWriteSeparateBilling)
                assertTrue(v30Event.diagnosticsJson!!.contains("\"source\":\"openai_chat_completions\""))

                // 迁移可重入（ALTER 幂等）：以驱动变体再跑一次
                JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                    AppDatabase.MIGRATION_29_30.migrate(connection)
                    assertEquals(35, userVersion(connection))
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `v30 database migrates to v31 keeping data and adding range cutoff table`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV30Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                // 触发打开与迁移（Room 内部校验 identityHash 与 TableInfo）
                val dao = database.tokenStatsDao()
                assertNotNull("migration must preserve v30 event rows", dao.getEvent("evt-v30"))
                assertNotNull("migration must preserve v30 identity rows", dao.getIdentity("identity-1"))

                // v31 新增范围删除 tombstone 表可读写
                dao.deleteRangeEventsTx(100L, 200L)
                assertEquals(1, dao.rangeCutoffs().size)
                assertEquals(1L, dao.currentResetGeneration())

                // v31 新增 legacy cleanup outbox 两张表真实存在
                val tables = queryTables(dbFile.absolutePath)
                assertTrue("token_stat_cleanup_operations", tables.contains("token_stat_cleanup_operations"))
                assertTrue("token_stat_cleanup_items", tables.contains("token_stat_cleanup_items"))

                // 迁移可重入（CREATE IF NOT EXISTS）：以驱动变体再跑一次
                JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                    AppDatabase.MIGRATION_30_31.migrate(connection)
                    assertEquals(35, userVersion(connection))
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `v31 cleanup outbox tables enforce foreign key and cascade on operation delete`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
            val dbFile = File(tempDir, "app_database")
            buildV30Database(dbFile.absolutePath)

            val database =
                Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                    .setDriver(JdbcSQLiteDriver())
                    .addMigrations(
                        AppDatabase.MIGRATION_30_31,
                        AppDatabase.MIGRATION_31_32,
                        AppDatabase.MIGRATION_32_33,
                        AppDatabase.MIGRATION_33_34,
                        AppDatabase.MIGRATION_34_35,
                    )
                    .allowMainThreadQueries()
                    .build()

            try {
                val dao = database.tokenStatsDao()
                // 通过删除事务（真实路径）创建 operation + items
                dao.insertIdentityIfAbsent(
                    TokenStatIdentityEntity(
                        identityId = "id-legacy",
                        configId = "",
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        displayModelId = "deepseek-chat",
                    )
                )
                dao.upsertDisplayModel(
                    TokenStatDisplayModelEntity(
                        displayModelId = "deepseek-chat",
                        normalizedModel = "deepseek-chat",
                        displayName = "deepseek-chat",
                    )
                )
                val result = dao.deleteDisplayModelEventsTx("deepseek-chat", deleteBaselines = true)
                val op = result.cleanupOperation!!
                assertEquals(1, dao.getCleanupItems(op.operationId).size)
                assertEquals(1, dao.countPendingCleanupOperations())

                // 外键：孤儿 item（operation 不存在）必须被拒绝
                JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                    connection.prepare("PRAGMA journal_mode = OFF").use { it.step() }
                    connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
                    val orphan =
                        runCatching {
                            connection.prepare(
                                "INSERT INTO token_stat_cleanup_items " +
                                    "(operationId, identityId, provider, model) " +
                                    "VALUES ('no-such-op', 'id', 'P', 'M')"
                            ).use { it.step() }
                        }
                    assertTrue("orphan item must violate the FK", orphan.isFailure)
                }
                // 级联：删除 operation → items 跟随删除（生产保留历史，此处验证 FK 行为）
                JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                    connection.prepare("PRAGMA journal_mode = OFF").use { it.step() }
                    connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
                    connection.prepare(
                        "DELETE FROM token_stat_cleanup_operations " +
                            "WHERE operationId = '${op.operationId}'"
                    ).use { it.step() }
                }
                assertEquals(0, dao.getCleanupItems(op.operationId).size)
            } finally {
                database.close()
            }
        }

    /** 用导出的 v30 schema JSON 构造一个真实的 v30 数据库文件（含一条事件行）。 */
    private fun buildV30Database(dbPath: String) {
        val schemaFile = File(schemaDir, "30.json")
        assertTrue("schema export missing: ${schemaFile.absolutePath}", schemaFile.isFile)
        val schema = json.decodeFromString<RoomSchema>(schemaFile.readText())
        assertEquals(30, schema.database.version)

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
                statement.execute("PRAGMA user_version = 30")
                // 旧数据：迁移前插入一条事件，验证迁移后数据保留（含 v30 结构化列）
                statement.execute(
                    "INSERT INTO token_stat_identities " +
                        "(identityId, configId, provider, model, displayModelId) " +
                        "VALUES ('identity-1', '', 'DEEPSEEK', 'deepseek-chat', 'deepseek-chat')"
                )
                statement.execute(
                    "INSERT INTO token_stat_events " +
                        "(eventId, statIdentityId, category, status, acceptedGeneration, " +
                        "startedAtMs, endedAtMs, firstTokenAtMs, uncachedInputTokens, " +
                        "cachedInputTokens, cacheWriteTokens, totalInputTokens, outputTokens, " +
                        "reasoningTokens, reasoningIncludedInOutput, cacheWriteSeparateBilling, " +
                        "billingMode, pricingCurrency, inputPricePerMillion, " +
                        "cachedInputPricePerMillion, cacheWritePricePerMillion, " +
                        "outputPricePerMillion, pricePerRequest, pricingSource, " +
                        "costInPricingCurrency, diagnosticsJson) " +
                        "VALUES ('evt-v30', 'identity-1', 'CHAT', 'COMPLETED', 0, 1000, 2000, " +
                        "1200, 800, 200, 100, 1100, 500, 50, 1, 0, 'TOKEN', 'USD', 1.0, 0.5, 2.0, " +
                        "3.0, NULL, 'DEFAULT', 0.0019, NULL)"
                )
            }
        }
    }

    @Test
    fun `production support sqlite migration variant runs the shared sql on a real v28 database`() {
        val tempDir = kotlin.io.path.createTempDirectory("room-migration-test").toFile()
        val dbFile = File(tempDir, "app_database")
        buildV28Database(dbFile.absolutePath)

        JvmSupportSQLiteDatabase.open(dbFile.absolutePath).use { supportDb ->
            // 生产默认路径：migrate(SupportSQLiteDatabase) 变体
            AppDatabase.MIGRATION_28_29.migrate(supportDb)

            // 新表真实存在且旧数据保留
            val tables = queryTables(dbFile.absolutePath)
            assertTrue("token_stat_events", tables.contains("token_stat_events"))
            assertTrue("token_stat_identities", tables.contains("token_stat_identities"))
            assertTrue("token_stat_display_models", tables.contains("token_stat_display_models"))
            assertTrue("token_stat_price_overrides", tables.contains("token_stat_price_overrides"))
            assertTrue("token_stat_baselines", tables.contains("token_stat_baselines"))

            JdbcSQLiteConnection(dbFile.absolutePath).use { connection ->
                connection.prepare("SELECT title FROM chats WHERE id = 'legacy-chat'").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("legacy", statement.getText(0))
                }
                // 事件表带缓存写入列：prepare 即校验列存在（空表无行）
                connection.prepare(
                    "SELECT cacheWriteTokens, cacheWritePricePerMillion " +
                        "FROM token_stat_events LIMIT 1"
                ).use { statement ->
                    assertFalse(statement.step())
                }
            }

            // 迁移可重入（CREATE IF NOT EXISTS）
            AppDatabase.MIGRATION_28_29.migrate(supportDb)
        }
    }

    private fun queryTables(dbPath: String): Set<String> {
        val tables = mutableSetOf<String>()
        JdbcSQLiteConnection(dbPath).use { connection ->
            connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
                while (statement.step()) {
                    tables += statement.getText(0)
                }
            }
        }
        return tables
    }

    private fun userVersion(connection: androidx.sqlite.SQLiteConnection): Int =
        connection.prepare("PRAGMA user_version").use { statement ->
            statement.step()
            statement.getLong(0).toInt()
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
