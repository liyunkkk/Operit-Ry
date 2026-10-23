package com.ai.assistance.operit.ui.common.dynamicui

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/**
 * Private, atomic sidecars. A conflated writer survives a card scrolling out of composition.
 * Never store protocol actions for replay, credentials, or a copy of the whole conversation.
 */
internal object DynamicUiStateStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = linkedMapOf<File, String>()
    private val ioMutex = Mutex()
    private val deleted = mutableSetOf<File>()
    val failures = MutableStateFlow<Set<File>>(emptySet())
    private var started = false

    fun file(context: Context, owner: DynamicUiOwner, source: String, surface: String): File {
        val chat = DynamicUiProtocol.fingerprint(owner.chatId)
        val key = DynamicUiProtocol.fingerprint("${owner.timestamp}:${owner.variant}:$surface:$source")
        return File(context.filesDir, "dynamic_ui/$chat/$key.json")
    }

    suspend fun read(file: File): JsonObject? = withContext(Dispatchers.IO) {
        val queued = synchronized(pending) { pending[file] }
        runCatching {
            val text = queued ?: ioMutex.withLock {
                AtomicFile(file).openRead().use {
                    // State limit is in UTF-16 characters; UTF-8 may take up to 4 bytes each.
                    require(it.channel.size() <= DynamicUiProtocol.MAX_STATE * 12L)
                    it.bufferedReader().readText()
                }
            }
            Json.parseToJsonElement(text).jsonObject
        }.getOrNull()
    }

    fun save(file: File, record: JsonObject) {
        val text = record.toString()
        require(text.length <= DynamicUiProtocol.MAX_STATE * 3)
        synchronized(pending) {
            if (file.parentFile in deleted) return
            pending[file] = text
            if (!started) {
                started = true
                scope.launch {
                    for (ignored in signal) {
                        while (true) {
                            val next = synchronized(pending) {
                                pending.entries.firstOrNull()?.let { (key, value) -> key to value }
                            } ?: break
                            val atomic = AtomicFile(next.first)
                            var stream: java.io.FileOutputStream? = null
                            try {
                                ioMutex.withLock {
                                if (synchronized(pending) { next.first.parentFile in deleted }) return@withLock
                                next.first.parentFile?.mkdirs()
                                stream = atomic.startWrite()
                                stream.write(next.second.toByteArray(Charsets.UTF_8))
                                atomic.finishWrite(stream)
                                failures.update { it - next.first }
                                }
                            } catch (e: Exception) {
                                atomic.failWrite(stream)
                                failures.update { (it + next.first).toList().takeLast(64).toSet() }
                                com.ai.assistance.operit.util.AppLogger.w("DynamicUi", "Unable to persist card state", e)
                            } finally {
                                synchronized(pending) {
                                    if (pending[next.first] == next.second) pending.remove(next.first)
                                }
                            }
                        }
                    }
                }
            }
        }
        signal.trySend(Unit)
    }

    /** Live tool updates acknowledge only after an atomic write, serialized with chat deletion. */
    fun writeNow(file: File, record: JsonObject) = runBlocking(Dispatchers.IO) {
        val text = record.toString()
        require(text.length <= DynamicUiProtocol.MAX_STATE * 3)
        ioMutex.withLock {
            check(synchronized(pending) { file.parentFile !in deleted }) { "Chat was deleted" }
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        }
    }

    private fun directory(context: Context, chatId: String) =
        File(context.filesDir, "dynamic_ui/${DynamicUiProtocol.fingerprint(chatId)}")

    /**
     * Branches copy messages with their original timestamps, so the same state file names resolve
     * in the copy. Queued writes are included so a branch made right after a tap keeps it.
     */
    suspend fun copyChat(context: Context, sourceChatId: String, targetChatId: String) = withContext(Dispatchers.IO) {
        val source = directory(context, sourceChatId)
        val target = directory(context, targetChatId)
        val queued = synchronized(pending) {
            if (source in deleted) return@withContext
            pending.filterKeys { it.parentFile == source }.map { (file, text) -> File(target, file.name) to text }
        }
        ioMutex.withLock {
            // AtomicFile reads recover a write interrupted on the source side (name.json.bak).
            val names = source.list().orEmpty().mapNotNullTo(linkedSetOf()) { name ->
                when {
                    name.endsWith(".json") -> name
                    name.endsWith(".json.bak") -> name.removeSuffix(".bak")
                    else -> null
                }
            }
            names.forEach { name ->
                // One unreadable card must not drop the rest of the branch's state.
                runCatching { writeAtomic(File(target, name), AtomicFile(File(source, name)).readFully()) }
                    .onFailure { com.ai.assistance.operit.util.AppLogger.w("DynamicUi", "Unable to copy card state $name", it) }
            }
            queued.forEach { (file, text) ->
                runCatching { writeAtomic(file, text.toByteArray(Charsets.UTF_8)) }
                    .onFailure { com.ai.assistance.operit.util.AppLogger.w("DynamicUi", "Unable to copy queued card state ${file.name}", it) }
            }
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    suspend fun deleteChat(context: Context, chatId: String) = withContext(Dispatchers.IO) {
        val directory = directory(context, chatId)
        synchronized(pending) {
            deleted.add(directory)
            pending.keys.removeAll { it.parentFile == directory }
        }
        ioMutex.withLock {
            if (directory.exists()) check(directory.deleteRecursively()) { "Unable to delete card state" }
        }
        failures.update { files -> files.filterNot { it.parentFile == directory }.toSet() }
    }
}
