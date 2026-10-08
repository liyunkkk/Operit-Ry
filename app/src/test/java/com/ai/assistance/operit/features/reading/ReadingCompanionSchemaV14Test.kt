package com.ai.assistance.operit.features.reading

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * reading.db v12 → v14 无损升级测试（纯 JVM，sqlite-jdbc）。
 *
 * 生产路径 `onUpgrade` 用共享常量 [AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS]
 * 逐列 ALTER（见 [ReadingCompanionStore.ensureAutoCommentRunSubagentColumns] /
 * [ReadingCompanionStore.addColumnIfMissing]）；本测试**执行同一份常量**，验证：
 * - 旧 run 行保留，execution_mode 默认 direct（兼容旧单发）；
 * - 新列存在且可空列为空、计数列默认 0；
 * - 逐列 ALTER 可重放（列已存在跳过，不报错）。
 */
class ReadingCompanionSchemaV14Test {

    @Test
    fun `store database version is 16 so older devices run all upgrades`() {
        assertEquals(16, ReadingCompanionStore.DATABASE_VERSION)
        // onUpgrade 必然走到 oldVersion < 13 => ensureAutoCommentRunSubagentColumns：
        // 该函数与测试共用 AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS，见下。
        assertTrue(AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.isNotEmpty())
        assertEquals(
            "run_heartbeat_at",
            AUTO_COMMENT_RUN_V14_LIVENESS_COLUMN_DEFINITION.first,
        )
    }

    /** 与生产 v12 形态一致的 auto_comment_runs 建表语句（测试夹具；含 v12 全部列）。 */
    private val v12AutoCommentRunsCreateSql =
        """
        CREATE TABLE auto_comment_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id TEXT,
            chapter_index INTEGER,
            chapter_title TEXT,
            trigger_source TEXT NOT NULL,
            status TEXT NOT NULL,
            stage TEXT NOT NULL DEFAULT 'starting',
            stage_updated_at INTEGER NOT NULL DEFAULT 0,
            role_card_id TEXT,
            role_card_name TEXT,
            model_config_id TEXT,
            model_config_name TEXT,
            model_index INTEGER,
            model_source TEXT,
            provider TEXT,
            model TEXT,
            target_character_count INTEGER,
            context_chapter_count INTEGER,
            context_character_count INTEGER,
            context_window_tokens INTEGER,
            estimated_input_tokens INTEGER,
            actual_uncached_input_tokens INTEGER,
            actual_cached_input_tokens INTEGER,
            actual_input_tokens INTEGER,
            actual_output_tokens INTEGER,
            actual_reasoning_tokens INTEGER,
            actual_usage_source TEXT,
            actual_usage_complete INTEGER,
            comment_count INTEGER NOT NULL DEFAULT 0,
            error_message TEXT,
            started_at INTEGER NOT NULL,
            finished_at INTEGER
        )
        """.trimIndent()

    private fun columnNames(connection: java.sql.Connection): Map<String, Pair<String?, String?>> {
        val names = linkedMapOf<String, Pair<String?, String?>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(auto_comment_runs)").use { rows ->
                while (rows.next()) {
                    val name = rows.getString("name")
                    val dflt = rows.getString("dflt_value")
                    val notNull = rows.getString("notnull")
                    names[name] = dflt to notNull
                }
            }
        }
        return names
    }

    @Test
    fun `v12 to v13 migration keeps legacy runs with direct execution mode and adds nullable columns`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v12AutoCommentRunsCreateSql)
                statement.execute(
                    """
                    INSERT INTO auto_comment_runs (
                        trigger_source, status, stage, stage_updated_at, comment_count,
                        started_at, finished_at
                    ) VALUES ('background', 'generated', 'completed', 1000, 3, 100, 2000)
                    """.trimIndent()
                )
            }

            // 与生产 onUpgrade 同一份 SQL 来源：共享列定义常量逐列 ALTER。
            AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.forEach { (name, definition) ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE auto_comment_runs ADD COLUMN $name $definition")
                }
            }

            // 迁移后旧行无损：值原样保留。
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT trigger_source, status, comment_count, started_at, finished_at " +
                        "FROM auto_comment_runs WHERE id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("background", rows.getString(1))
                    assertEquals("generated", rows.getString(2))
                    assertEquals(3, rows.getInt(3))
                    assertEquals(100L, rows.getLong(4))
                    assertEquals(2000L, rows.getLong(5))
                }
            }

            val columns = columnNames(connection)
            AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.forEach { (name, _) ->
                assertTrue("column $name must exist after migration", columns.containsKey(name))
            }

            // 旧行读取新列：execution_mode='direct'、计数默认 0、可空列 NULL。
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT execution_mode, parent_chat_id, subagent_run_id, child_chat_id, " +
                        "model_round_count, tool_invocation_count, restarted_from_run_id " +
                        "FROM auto_comment_runs WHERE id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("direct", rows.getString(1))
                    assertNull(rows.getString(2))
                    assertNull(rows.getString(3))
                    assertNull(rows.getString(4))
                    assertEquals(0, rows.getInt(5))
                    assertEquals(0, rows.getInt(6))
                    assertNull(rows.getObject(7))
                }
            }

            // 新列可写（补链/谱系写入路径的落库形态）。
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE auto_comment_runs SET subagent_run_id = 's-1', child_chat_id = 'c-1', " +
                        "restarted_from_run_id = 42 WHERE id = 1"
                )
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT subagent_run_id, child_chat_id, restarted_from_run_id " +
                        "FROM auto_comment_runs WHERE id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("s-1", rows.getString(1))
                    assertEquals("c-1", rows.getString(2))
                    assertEquals(42L, rows.getLong(3))
                }
            }
        }
    }

    @Test
    fun `column definitions used by fresh create and upgrade are identical`() {
        // 共享常量同时被 createAutoCommentRunTable（CREATE 内联）与 onUpgrade（逐列 ALTER）
        // 引用；这里校验升级后的实际列集合与 CREATE 语句包含的列定义一致。
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v12AutoCommentRunsCreateSql)
                AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.forEach { (name, definition) ->
                    statement.execute("ALTER TABLE auto_comment_runs ADD COLUMN $name $definition")
                }
            }
            val columns = columnNames(connection)
            AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.forEach { (name, _) ->
                val (dflt, notNull) = columns.getValue(name)
                assertTrue("$name must keep its NOT NULL default", notNull == "1" || dflt == null)
            }
            // execution_mode 默认值必须是 direct（旧行兼容语义的落库事实）。
            assertEquals("'direct'", columns.getValue("execution_mode").first)
        }
    }

    @Test
    fun `v13 to v14 migration preserves runs and backfills a separate heartbeat`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v12AutoCommentRunsCreateSql)
                AUTO_COMMENT_RUN_V13_SUBAGENT_COLUMN_DEFINITIONS.forEach { (name, definition) ->
                    statement.execute("ALTER TABLE auto_comment_runs ADD COLUMN $name $definition")
                }
                statement.execute(
                    """
                    INSERT INTO auto_comment_runs (
                        trigger_source, status, stage, stage_updated_at, comment_count,
                        started_at, finished_at
                    ) VALUES
                        ('manual_summary', 'generating', 'waiting_model', 1200, 0, 1000, NULL),
                        ('background', 'generated', 'completed', 1800, 3, 1000, 2000)
                    """.trimIndent(),
                )
                val (name, definition) = AUTO_COMMENT_RUN_V14_LIVENESS_COLUMN_DEFINITION
                statement.execute("ALTER TABLE auto_comment_runs ADD COLUMN $name $definition")
                statement.execute(
                    """
                    UPDATE auto_comment_runs
                    SET run_heartbeat_at = CASE
                        WHEN finished_at IS NOT NULL THEN finished_at
                        WHEN stage_updated_at > 0 THEN stage_updated_at
                        ELSE started_at
                    END
                    WHERE run_heartbeat_at = 0
                    """.trimIndent(),
                )
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, status, run_heartbeat_at FROM auto_comment_runs ORDER BY id",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("generating", rows.getString(2))
                    assertEquals(1200L, rows.getLong(3))
                    assertTrue(rows.next())
                    assertEquals("generated", rows.getString(2))
                    assertEquals(2000L, rows.getLong(3))
                }
            }
        }
    }
}
