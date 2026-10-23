package com.ai.assistance.operit.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchToolsTest {
    @Test
    fun bothLanguagesExposeDirectSearchWithoutModelPlannedSearch() {
        listOf(SystemToolPrompts.fileSystemTools, SystemToolPrompts.fileSystemToolsCn)
            .forEach { category ->
                val names = category.tools.map { it.name }
                assertFalse(names.contains("grep_context"))
                assertTrue(names.contains("grep_code"))
                assertTrue(names.contains("read_file_part"))
            }
    }
}
