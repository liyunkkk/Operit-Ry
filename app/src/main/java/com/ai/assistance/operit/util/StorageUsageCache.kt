package com.ai.assistance.operit.util

import android.content.Context

internal data class CachedStorageUsage(val usage: StorageUsage, val measuredAt: Long)

/** Small completed snapshots only; interrupted scans never replace the previous result. */
internal class StorageUsageCache(context: Context) {
    private val preferences = context.getSharedPreferences("storage_usage_snapshot", Context.MODE_PRIVATE)

    fun read(): CachedStorageUsage? {
        if (preferences.getInt("version", 0) != 1) return null
        val timestamp = preferences.getLong("measured_at", 0)
        if (timestamp <= 0) return null
        val values = StorageCategory.entries.associateWith {
            preferences.getLong(it.name, -1)
        }
        if (values.values.any { it < 0 }) return null
        return CachedStorageUsage(StorageUsage(values, preferences.getInt("skipped", 0)), timestamp)
    }

    fun write(value: CachedStorageUsage) {
        val editor = preferences.edit().putInt("version", 1)
            .putLong("measured_at", value.measuredAt).putInt("skipped", value.usage.skippedEntries)
        value.usage.bytes.forEach { (category, bytes) -> editor.putLong(category.name, bytes) }
        check(editor.commit()) { "Unable to save storage snapshot" }
    }
}
