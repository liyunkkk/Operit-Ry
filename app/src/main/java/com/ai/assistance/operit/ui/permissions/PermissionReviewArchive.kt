package com.ai.assistance.operit.ui.permissions

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Every finished review, kept with its own conversation.
 *
 * [PermissionReviewEventRepository] holds only the newest reviews across all chats, which is what
 * the reviewer reads; once other chats filled it, an older conversation lost the review line under
 * each of its tool calls. These per-chat files only feed that line, follow their chat and are
 * deleted with it.
 */
internal object PermissionReviewArchive {
    private const val TAG = "PermissionReviewArchive"
    private const val DIRECTORY = "permission_reviews"
    private const val MAX_PER_CHAT = 1000
    private const val MAX_CACHED_CHATS = 16
    private const val FLUSH_DELAY_MS = 500L
    private const val RETRY_DELAY_MS = 5_000L
    private const val MAX_RETRIES = 3

    // One thread owns loading, merging and writing, so a chat's file never sees two writers.
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + writer)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * A null value means the chat is still loading. Guarded by itself. Access order, so the chat
     * on screen, which reads and records most, is the last one dropped.
     */
    private val chats = LinkedHashMap<String, MutableStateFlow<List<PermissionReviewEvent>?>>(16, 0.75f, true)
    // Confined to [writer].
    private val dirty = mutableSetOf<String>()
    private val deleted = mutableSetOf<String>()
    private var flushScheduled = false
    private var retries = 0
    @Volatile private var appContext: Context? = null

    private fun file(context: Context, chatId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(40)
        return File(context.filesDir, "$DIRECTORY/$name.json")
    }

    /** The finished reviews of [chatId], oldest first; null until they are read. */
    fun observe(context: Context, chatId: String): StateFlow<List<PermissionReviewEvent>?> {
        appContext = context.applicationContext
        val (flow, created) =
            synchronized(chats) {
                chats[chatId]?.let { return@synchronized it to false }
                val flow = MutableStateFlow<List<PermissionReviewEvent>?>(null)
                chats[chatId] = flow
                flow to true
            }
        if (created) scope.launch { load(chatId, flow) }
        return flow
    }

    /** Keeps the finished events among [events]; one still being reviewed is recorded once it ends. */
    fun record(context: Context, events: List<PermissionReviewEvent>) {
        val finished = events.filter { it.status != PermissionReviewStatus.IN_PROGRESS }
        if (finished.isEmpty()) return
        appContext = context.applicationContext
        scope.launch {
            finished.groupBy { it.parentChatId }.forEach { (chatId, chatEvents) ->
                if (chatId in deleted) return@forEach
                val flow =
                    synchronized(chats) {
                        chats.getOrPut(chatId) { MutableStateFlow(null) }
                    }
                // An archive that could not be read is left alone: merging into an empty list
                // would overwrite it with only these events. It is read again on the next record.
                if (!load(chatId, flow)) return@forEach
                val replaced = chatEvents.map { it.id }.toSet()
                val current = flow.value.orEmpty()
                flow.value = (current.filterNot { it.id in replaced } + chatEvents).takeLast(MAX_PER_CHAT)
                dirty += chatId
            }
            retries = 0
            scheduleFlush(FLUSH_DELAY_MS)
        }
    }

    /** Runs on [writer]. */
    private fun scheduleFlush(delayMs: Long) {
        if (flushScheduled || dirty.isEmpty()) return
        flushScheduled = true
        scope.launch {
            delay(delayMs)
            flush()
        }
    }

    suspend fun deleteChat(context: Context, chatId: String) =
        withContext(writer) {
            deleted += chatId
            dirty -= chatId
            synchronized(chats) { chats.remove(chatId) }
            val target = file(context, chatId)
            if (target.exists() && !target.delete()) AppLogger.w(TAG, "Unable to delete review archive")
        }

    /**
     * Runs on [writer]. Reads the file once per chat; true when the chat's reviews are in memory.
     * A missing file is an empty archive, a file that cannot be read is not.
     */
    private fun load(chatId: String, flow: MutableStateFlow<List<PermissionReviewEvent>?>): Boolean {
        if (flow.value != null) return true
        val context = appContext ?: return false
        val stored =
            runCatching {
                    val target = file(context, chatId)
                    if (!target.exists()) emptyList()
                    else {
                        val text = AtomicFile(target).openRead().use { it.bufferedReader().readText() }
                        json.decodeFromString<List<PermissionReviewEvent>>(text)
                    }
                }
                .onFailure { AppLogger.w(TAG, "Unable to read review archive", it) }
                .getOrNull() ?: return false
        flow.value = stored
        trimCache()
        return true
    }

    /** Runs on [writer]. */
    private fun flush() {
        flushScheduled = false
        val context = appContext ?: return
        val pending = dirty.toList()
        dirty.clear()
        val failed = mutableListOf<String>()
        pending.forEach { chatId ->
            val events = synchronized(chats) { chats[chatId]?.value } ?: return@forEach
            val atomic = AtomicFile(file(context, chatId))
            var stream: java.io.FileOutputStream? = null
            try {
                atomic.baseFile.parentFile?.mkdirs()
                stream = atomic.startWrite()
                stream.write(json.encodeToString(events).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                failed += chatId
                AppLogger.w(TAG, "Unable to write review archive", e)
            }
        }
        // A failed write keeps its chat pending, so the reviews still reach the disk.
        if (failed.isNotEmpty() && retries < MAX_RETRIES) {
            retries++
            dirty += failed
            scheduleFlush(RETRY_DELAY_MS)
        }
        trimCache()
    }

    /**
     * Runs on [writer]: drops the oldest loaded chats that have nothing left to write and that no
     * screen is showing, so a visible chat keeps receiving its reviews.
     */
    private fun trimCache() {
        synchronized(chats) {
            val extra = chats.size - MAX_CACHED_CHATS
            if (extra <= 0) return
            chats.entries
                .filter { (chatId, flow) -> chatId !in dirty && flow.subscriptionCount.value == 0 }
                .take(extra)
                .forEach { chats.remove(it.key) }
        }
    }
}
