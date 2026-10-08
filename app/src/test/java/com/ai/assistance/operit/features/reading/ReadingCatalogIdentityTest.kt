package com.ai.assistance.operit.features.reading

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingCatalogIdentityTest {
    @Test fun `changed removed and legacy entries invalidate but unchanged entries remain`() {
        assertEquals(setOf(1, 2, 4), staleReadingChapterIndices(
            setOf(1, 2, 3, 4), mapOf(1 to "old", 2 to "removed", 3 to "same"),
            mapOf(1 to "new-byte-range", 3 to "same", 4 to "legacy"),
        ))
    }

    @Test fun `catalog identity migration preserves existing data and isolates books`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA foreign_keys=ON")
                sql.execute("CREATE TABLE books(book_id TEXT PRIMARY KEY)")
                sql.execute("INSERT INTO books VALUES('a'),('b')")
                sql.execute(READING_CATALOG_SOURCES_SQL)
                sql.execute(READING_CATALOG_SOURCES_SQL)
                sql.execute("INSERT INTO indexed_catalog_sources VALUES('a',0,'range-a'),('b',0,'range-b')")
                sql.execute("DELETE FROM books WHERE book_id='a'")
                sql.executeQuery("SELECT book_id,source_id FROM indexed_catalog_sources").use {
                    it.next()
                    assertEquals("b", it.getString(1))
                    assertEquals("range-b", it.getString(2))
                }
            }
        }
    }
}
