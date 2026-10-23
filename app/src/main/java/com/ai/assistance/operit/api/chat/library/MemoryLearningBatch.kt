package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.preferences.MemoryReviewChange
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import com.ai.assistance.operit.data.repository.RecallEvidenceReader
import com.ai.assistance.operit.ui.common.dynamicui.CardTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LearningCursor(
    val messageId: Long = 0, val byteOffset: Long = 0,
    val revision: String = "", val thinking: Boolean = false
) {
    fun json() = JSONObject().put("id",messageId).put("offset",byteOffset)
        .put("revision",revision).put("thinking",thinking)
    companion object {
        fun parse(obj: JSONObject?) = if (obj == null) LearningCursor() else LearningCursor(
            obj.getLong("id"),obj.getLong("offset"),obj.getString("revision"),obj.getBoolean("thinking"))
    }
}

/**
 * The completed source range and the entire change set share one atomic journal write.
 * A crash during export replays the same proposal IDs, never generates new approvals.
 */
internal class MemoryLearningJournal(private val context: Context, val profile: String, val chat: String) {
    private val root = File(context.filesDir, "memory_learning_progress")
    private val file = File(root, java.util.UUID.nameUUIDFromBytes("$profile\u0000$chat".toByteArray()).toString()+".json")
    private val reviews = MemoryReviewRepository(context,profile)
    /**
     * One lock per journal file. A batch now spans turns, so the per-turn [enqueue] of another instance
     * can land while a review is running; every change re-reads inside this lock so a stale snapshot
     * can never overwrite what the other side just wrote.
     */
    private val lock = locks.computeIfAbsent(file.absolutePath) { Any() }
    private var state = readState()
    private fun readState(): JSONObject = if (file.exists()) JSONObject(file.readText()) else
        JSONObject().put("profile",profile).put("chat",chat).put("horizon",0)
    private fun reload() { state = readState() }
    /**
     * These read the cached copy without reloading on purpose: a batch compares the generation it
     * started from with the one on disk when it finishes, so adding a reload here would silently
     * disable that protection and let an old batch clear a newer range's pending marker.
     */
    fun cursor(path: String) = LearningCursor.parse(state.optJSONObject(path))
    fun pending(path: String) = state.optBoolean("pending_$path")
    fun horizon() = state.optLong("horizon")
    fun rewind(messageId: Long) {
        synchronized(lock) {
            reload()
            listOf("notes","skills").forEach {
                val old=cursor(it)
                if (old.messageId>messageId || old.messageId==messageId && old.byteOffset>0)
                    state.put(it,LearningCursor(messageId).json())
            }
            save()
        }
    }
    /** Callers hold [lock]. */
    private fun save() {
        root.mkdirs()
        val temp = File.createTempFile(".learning-", ".tmp", root)
        try {
            temp.outputStream().use { it.write(state.toString().toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        com.ai.assistance.operit.data.preferences.syncDirectory(file.parentFile!!)
    }
    fun enqueue(notes: Boolean, skills: Boolean, horizon: Long, restart: Boolean = false) {
        synchronized(lock) {
            reload()
            state.put("horizon",maxOf(horizon,horizon()))
            if (restart) {
                if (notes) state.put("notes",LearningCursor().json())
                if (skills) state.put("skills",LearningCursor().json())
            }
            // The age of the oldest pending marker is what lets a chatty device dispatch a range that
            // has waited far too long, so it is set only when the range first becomes pending.
            if ((notes && !state.optBoolean("pending_notes")) || (skills && !state.optBoolean("pending_skills")))
                state.put("enqueued_at",System.currentTimeMillis())
            if (notes) state.put("pending_notes",true)
            if (skills) state.put("pending_skills",true)
            // Marks that a range was added. A batch that started before this enqueue must not clear the
            // new range's pending marker when it finishes, and the counters are the only way to tell.
            state.put("generation",state.optInt("generation",0)+1)
            save()
        }
    }
    /** True while pending work has been waiting at least [millis]. */
    fun pendingForAtLeast(millis: Long): Boolean {
        synchronized(lock) {
            reload()
            if (!pending("notes") && !pending("skills") && !state.has("export")) return false
            var since = state.optLong("enqueued_at",0)
            if (since <= 0) {
                // Journals written before this field existed carry no age. Stamping them now keeps the
                // age backstop working instead of leaving them to wait behind a busy device forever.
                since = System.currentTimeMillis()
                state.put("enqueued_at",since)
                save()
            }
            return System.currentTimeMillis()-since >= millis
        }
    }
    fun complete(paths: List<String>, next: LearningCursor, more: Boolean, changes: List<MemoryReviewChange>) {
        synchronized(lock) {
            val reviewedGeneration = state.optInt("generation",0)
            reload()
            check(!state.has("export")) { "Previous batch must be exported first" }
            // A range enqueued while this batch was running is newer than what it reviewed, so its
            // pending marker survives instead of being cleared by this write.
            val newerEnqueued = state.optInt("generation",0) > reviewedGeneration
            paths.forEach {
                state.put(it,next.json()).put("pending_$it",more || (newerEnqueued && state.optBoolean("pending_$it")))
            }
            state.put("export",JSONArray().apply { changes.forEach { put(reviews.toJson(it)) } })
            save()
        }
    }
    /**
     * Stops retrying a range that can never be reviewed, keeping each cursor where it is so the next
     * normal trigger covers the same range again once the cause is gone.
     */
    fun abandon(paths: List<String>) {
        synchronized(lock) {
            val reviewedGeneration = state.optInt("generation",0)
            reload()
            val newerEnqueued = state.optInt("generation",0) > reviewedGeneration
            paths.forEach {
                state.put("pending_$it",newerEnqueued && state.optBoolean("pending_$it"))
            }
            save()
        }
    }
    suspend fun export(): List<String> {
        // The import below can suspend, so the journal is only locked for the read and the release.
        val array = synchronized(lock) {
            reload()
            state.optJSONArray("export")
        } ?: return emptyList()
        val changes = (0 until array.length()).map { reviews.fromJson(array.getJSONObject(it)) }
        // Keep the durable export on failure. The caller stops this attempt and reports the error;
        // a later trigger can import the same IDs without regenerating or losing the reviewed batch.
        reviews.importCompletedBatch(changes)
        val failures = mutableListOf<String>()
        for (change in changes) {
            currentCoroutineContext().ensureActive()
            try {
                // A rejected or manually revised proposal is already settled and must stay so.
                val stored = reviews.list().first { it.id == change.id }
                if (stored.status in setOf("pending","applying")) reviews.applyAutomaticDecision(context,stored)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failures += learningFailureDetail("apply ${change.id}",e) }
        }
        // Failed writes remain visible in the review UI; evidence has still been reviewed.
        synchronized(lock) {
            reload()
            // Only this call's batch is released: a batch staged in the meantime keeps its proposals.
            if (state.optJSONArray("export")?.toString() == array.toString()) state.remove("export")
            save()
        }
        return failures
    }
    companion object {
        /** One lock per journal file, shared by every instance in this process. */
        private val locks = java.util.concurrent.ConcurrentHashMap<String,Any>()
        fun deleteSpace(context: Context, profile: String) {
            File(context.filesDir,"memory_learning_progress").listFiles()?.filter { it.extension=="json" }
                ?.forEach { if (JSONObject(it.readText()).getString("profile")==profile) it.delete() }
        }
        /**
         * Drops the progress of a conversation that no longer exists, in every memory space. The
         * deleted conversation can never be reviewed again, so keeping the file would only make the
         * coordinator retry it on each launch.
         */
        suspend fun deleteChat(context: Context, chat: String) = withContext(Dispatchers.IO) {
            File(context.filesDir,"memory_learning_progress").listFiles()?.filter { it.extension=="json" }
                ?.forEach {
                    runCatching { if (JSONObject(it.readText()).optString("chat")==chat) it.delete() }
                }
        }
        fun pending(context: Context): List<Pair<String,String>> =
            File(context.filesDir,"memory_learning_progress").listFiles()?.filter { it.extension=="json" }
                ?.mapNotNull {
                    runCatching {
                        val obj=JSONObject(it.readText())
                        if (obj.optBoolean("pending_notes") || obj.optBoolean("pending_skills") || obj.has("export"))
                            obj.getString("profile") to obj.getString("chat") else null
                    }.getOrNull()
                }.orEmpty()
    }
}

internal class MemoryLearningSource(
    private val context: Context, private val dao: ChatContentDao, private val chat: String,
    private val horizon: Long, private val thinking: Boolean
) : java.io.Closeable {
    data class Batch(val text: String, val next: LearningCursor, val more: Boolean)
    private data class Prepared(val file: File, val revision: String)
    private val files = mutableMapOf<Long,Prepared>()

    suspend fun next(cursor: LearningCursor, budget: Int): Batch {
        require(budget >= 256)
        var position = cursor
        val output = StringBuilder()
        var remaining = budget
        while (remaining >= 200) {
            currentCoroutineContext().ensureActive()
            val row = dao.nextLearningSource(chat,position.messageId,horizon) ?: break
            val prepared = files[row.messageId] ?: run {
                val file = File.createTempFile("learning-source-", ".txt", context.cacheDir)
                try {
                    // Cards become their readable text in full, so the learning model sees what the user
                    // saw. The prefix marks offsets as positions in this converted text: cursors saved
                    // against the raw text no longer match and restart the message instead of drifting.
                    // Bump TEXT_VERSION whenever CardTranscript's output changes, for the same reason.
                    val evidence = StringBuilder()
                    val page = RecallEvidenceReader.read({ dao.readLearningMessageBytes(row.messageId,it) },
                        row.sender=="ai",thinking,limit=1,onEvidence={ evidence.append(it) })
                        ?: error("Source message disappeared during review")
                    file.writeText(CardTranscript.forModel(evidence.toString(), Int.MAX_VALUE), Charsets.UTF_8)
                    Prepared(file,"$TEXT_VERSION:${page.revision}").also { files[row.messageId]=it }
                } catch (e: Exception) { file.delete(); throw e }
            }
            var offset = if (position.messageId==row.messageId && position.revision==prepared.revision &&
                position.thinking==thinking) position.byteOffset else 0
            require(offset in 0..prepared.file.length()) { "Invalid learning source cursor" }
            val header = "\n[message_id=${row.messageId}; role=${row.sender}; source_byte_offset=$offset]\n"
            val headerBytes = header.toByteArray().size
            val bytes = ByteArray(minOf((remaining-headerBytes-1).coerceAtLeast(0).toLong(),
                prepared.file.length()-offset).toInt())
            RandomAccessFile(prepared.file,"r").use { it.seek(offset); it.readFully(bytes) }
            var size=bytes.size
            if (offset+size < prepared.file.length() && size>0) {
                var head=size-1
                while (head>0 && (bytes[head].toInt() and 0xc0)==0x80) head--
                val lead=bytes[head].toInt() and 0xff
                val width=when { lead>=0xf0 -> 4; lead>=0xe0 -> 3; lead>=0xc0 -> 2; else -> 1 }
                if (head+width>size) size=head
            }
            if (size>0) {
                output.append(header).append(String(bytes,0,size,Charsets.UTF_8)).append('\n')
                remaining-=headerBytes+size+1
            }
            offset+=size
            position = if (offset==prepared.file.length()) LearningCursor(row.messageId+1,thinking=thinking)
                else LearningCursor(row.messageId,offset,prepared.revision,thinking)
            if (offset<prepared.file.length()) break
        }
        return Batch(output.toString(),position,dao.nextLearningSource(chat,position.messageId,horizon)!=null)
    }
    override fun close() { files.values.forEach { it.file.delete() } }
    private companion object { const val TEXT_VERSION = "cards1" }
}
