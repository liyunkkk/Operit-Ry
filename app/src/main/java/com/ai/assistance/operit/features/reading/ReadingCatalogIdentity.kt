package com.ai.assistance.operit.features.reading

internal const val READING_CATALOG_SOURCES_SQL = """CREATE TABLE IF NOT EXISTS indexed_catalog_sources (
    book_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, source_id TEXT NOT NULL,
    PRIMARY KEY (book_id, chapter_index),
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE CASCADE
)"""

internal fun staleReadingChapterIndices(
    indexed: Set<Int>,
    previous: Map<Int, String>,
    expected: Map<Int, String>,
): Set<Int> = indexed.filterTo(mutableSetOf()) {
    previous[it] == null || previous[it] != expected[it]
}
