package com.ai.assistance.operit.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.api.voice.VoiceServiceFactory
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.ui.components.ResourceDownloadConfirmActivity
import com.ai.assistance.operit.ui.components.ResourceDownloadDialogHost
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "OnDemandResources"

/** Progress of one download group, sized over every file the group still needs. */
data class ResourceDownloadState(
    val id: String,
    val name: String,
    val downloaded: Long,
    val total: Long,
    val itemIndex: Int,
    val itemCount: Int,
    val error: String? = null,
    /** Set when the user (or a timeout) aborted instead of the download failing. */
    val cancelled: Boolean = false,
    /** The confirmation dialog went unanswered, which is not the same as a plain cancel. */
    val unconfirmed: Boolean = false,
    val collapsed: Boolean = false
)

/** A group waiting for the user to approve its download, shown by whichever host is available. */
data class PendingResourceRequest(
    val id: String,
    val name: String,
    val bytes: Long,
    val hostInApp: Boolean
)

/** A required download could not be completed; [message] is already written for the user. */
open class ResourceDownloadException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** The user declined or cancelled the required download. */
class ResourceDownloadDeclinedException(message: String) : ResourceDownloadException(message)

/** The download itself failed; [cause] keeps the raw reason for logs only. */
class ResourceDownloadUnavailableException(message: String, cause: Throwable) :
    ResourceDownloadException(message, cause)

/** Free space cannot hold the remaining bytes. */
internal class ResourceSpaceException : IOException("Insufficient storage")

private class DownloadCancelledException : IOException("Download cancelled by the user")

/**
 * Whether a decline should still silence prompts. Long enough to swallow a feature's own retry
 * loop, short enough not to block the user's next attempt. Pure policy so it stays testable.
 */
internal fun shouldSuppressPrompt(lastDeclinedAtMs: Long?, nowMs: Long): Boolean =
    lastDeclinedAtMs != null && nowMs - lastDeclinedAtMs < DECLINE_SUPPRESSION_MS

internal const val DECLINE_SUPPRESSION_MS = 30 * 1000L

/** How long a speech-preparation reason still describes the failure being reported. */
internal const val SPEECH_FAILURE_WINDOW_MS = 10 * 1000L

/** A user-facing reason with the time it was produced, so staleness can be judged. */
private class FreshMessage(val text: String, val atMs: Long)

/** Cancellation state of one running download, so a late cancel never leaks into the next run. */
private class DownloadRun {
    @Volatile var cancelled = false
    @Volatile var call: Call? = null
}

/** One downloadable file inside a group. */
internal data class DownloadItem(val target: File, val resource: DownloadResource)

/** Files a feature needs together; approved, tracked and retried as one download. */
internal data class DownloadGroup(
    val id: String,
    val labelRes: Int,
    val items: List<DownloadItem>
)

/** Fixed, checksummed feature resources; nothing downloads until the user approves it. */
object OnDemandResources {
    private val templateFiles = ConcurrentHashMap<String, Mutex>()
    private val terminalArchiveFiles = Mutex()

    /** Keep a template pinned until its reader has finished, including APK repacking. */
    suspend fun <T> withExportTemplate(context: Context, android: Boolean, action: suspend (File) -> T): T {
        val id = if (android) androidTemplate.id else windowsTemplate.id
        return templateFiles.getOrPut(id) { Mutex() }.withLock { action(exportTemplate(context, android)) }
    }

    internal fun componentGroups(context: Context): List<DownloadGroup> = listOf(
        DownloadGroup(ubuntu.id, R.string.resource_terminal,
            listOf(DownloadItem(File(context.filesDir, "downloaded_resources/terminal/${ubuntu.id}"), ubuntu))),
        DownloadGroup(SPEECH_DIRECTORY, R.string.resource_offline_speech,
            speech.map { DownloadItem(File(File(OperitPaths.sherpaNcnnModelsDir(context), SPEECH_DIRECTORY), it.id), it) }),
    ) + LocalVoiceModels.models.map { model ->
        DownloadGroup("voice-${model.id}", R.string.voice_local_models,
            model.files.map { DownloadItem(File(model.directory(context), it.id), it) })
    } + listOf(
        DownloadGroup(androidTemplate.id, R.string.resource_android_export,
            listOf(DownloadItem(File(context.filesDir, "downloaded_resources/templates-v1/${androidTemplate.id}"), androidTemplate))),
        DownloadGroup(windowsTemplate.id, R.string.resource_windows_export,
            listOf(DownloadItem(File(context.filesDir, "downloaded_resources/templates-v1/${windowsTemplate.id}"), windowsTemplate))),
    )

    internal fun terminalInstalled(context: Context): Boolean {
        val root = File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        return File(root, ".operit_installed_ok").isFile || File(root, "etc/issue.net").isFile
    }

    /** The settings download button grants approval without changing the chosen speech profile. */
    internal suspend fun downloadComponent(context: Context, id: String) {
        val group = componentGroups(context).first { it.id == id }
        groups[id] = group
        declinedAt.remove(id)
        synchronized(approvalLock) { approvals[id] }?.complete(true)
        groupLocks.getOrPut(id) { Mutex() }.withLock {
            startDownload(context.applicationContext, group).await()
        }
    }

    internal suspend fun deleteComponent(context: Context, id: String) = withContext(Dispatchers.IO) {
        val group = componentGroups(context).first { it.id == id }
        check(!isGroupActive(id)) { context.getString(R.string.voice_model_delete_busy) }
        when {
            id.startsWith("voice-") -> {
                val modelId = id.removePrefix("voice-")
                check(modelId !in selectedVoiceModels(context)) {
                    context.getString(R.string.voice_model_delete_selected)
                }
                LocalVoiceModels.delete(context, modelId)
            }
            id == SPEECH_DIRECTORY -> {
                check(id !in selectedVoiceModels(context)) {
                    context.getString(R.string.voice_model_delete_selected)
                }
                deleteLegacySpeech(context)
            }
            id == ubuntu.id -> {
                check(terminalArchiveFiles.tryLock()) { context.getString(R.string.component_in_use) }
                try { deleteComponentFiles(context, group) } finally { terminalArchiveFiles.unlock() }
            }
            else -> {
                val lock = templateFiles.getOrPut(id) { Mutex() }
                check(lock.tryLock()) { context.getString(R.string.component_in_use) }
                try { deleteComponentFiles(context, group) } finally { lock.unlock() }
            }
        }
    }

    private fun deleteComponentFiles(context: Context, group: DownloadGroup) =
        deleteInactiveGroup(context, group.id) {
            group.items.forEach { item ->
                listOf(item.target, partOf(item.target)).forEach { file ->
                    check(!file.exists() || (file.isFile && file.delete())) {
                        context.getString(R.string.voice_model_delete_failed)
                    }
                }
                verified.remove(item.resource.id)
            }
        }

    internal suspend fun selectedVoiceModels(context: Context): Set<String> {
        val prefs = SpeechServiceProfilesPreferences(context)
        val stt = prefs.getCurrentSttProfile()
        val tts = prefs.getCurrentTtsProfile()
        return buildSet {
            if (stt.serviceType == SpeechServiceFactory.SpeechServiceType.SHERPA_ONNX)
                add(stt.localModelId)
            if (stt.serviceType == SpeechServiceFactory.SpeechServiceType.SHERPA_NCNN)
                add(SPEECH_DIRECTORY)
            if (tts.serviceType == VoiceServiceFactory.VoiceServiceType.SHERPA_ONNX)
                add(tts.localModelId)
        }
    }

    private val legacySpeechFiles = Mutex()
    internal suspend fun <T> withLegacySpeechFiles(action: suspend () -> T): T =
        legacySpeechFiles.withLock { action() }
    internal fun deleteVoiceModel(context: Context, id: String, deleteFiles: () -> Unit) {
        deleteInactiveGroup(context, "voice-$id", deleteFiles)
    }
    private fun deleteInactiveGroup(context: Context, groupId: String, deleteFiles: () -> Unit) {
        // Retry also starts through this monitor. Never unlink files beneath a live download.
        synchronized(running) {
            check(!isGroupActive(groupId)) { context.getString(R.string.voice_model_delete_busy) }
            deleteFiles()
            groups.remove(groupId)
            dismiss(groupId)
        }
    }
    internal suspend fun legacySpeechBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val directory = File(OperitPaths.sherpaNcnnModelsDir(context), SPEECH_DIRECTORY)
        directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }
    internal suspend fun deleteLegacySpeech(context: Context) = withContext(Dispatchers.IO) {
        withLegacySpeechFiles {
        com.ai.assistance.operit.api.speech.SpeechServiceFactory.withUnusedLegacyModel(context) {
            deleteInactiveGroup(context, SPEECH_DIRECTORY) {
                val directory = File(OperitPaths.sherpaNcnnModelsDir(context), SPEECH_DIRECTORY)
                directory.listFiles()?.forEach {
                    check(it.isFile && it.delete()) { context.getString(R.string.voice_model_delete_failed) }
                }
                check(!directory.exists() || directory.delete()) {
                    context.getString(R.string.voice_model_delete_failed)
                }
            }
        }
        }
    }
    internal suspend fun ensureVoiceModel(context: Context, id: String, directory: File,
                                         files: List<DownloadResource>): File {
        ensureGroup(context, DownloadGroup("voice-$id", R.string.voice_local_models,
            files.map { DownloadItem(File(directory, it.id), it) }))
        return directory
    }
    private const val CONFIRM_TIMEOUT_MS = 3 * 60 * 1000L
    private const val SPACE_SLACK_BYTES = 8L * 1024 * 1024

    const val SPEECH_DIRECTORY = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13"
    private const val SPEECH_BASE =
        "https://huggingface.co/csukuangfj/$SPEECH_DIRECTORY/resolve/05945efc40afe4b572542f01104ca5c413a9f6e1/"
    private val speech =
        listOf(
            speech("decoder_jit_trace-pnnx.ncnn.bin", 6412296, "dc4df2d8e1ddee1b90ac72a2de982eb1d320ee6c9a70e1dee4d23d9acfc8b978"),
            speech("decoder_jit_trace-pnnx.ncnn.param", 439, "cb88f5894978fd3e85369d2f8ea55621809fceb2b5158243fb0cd025eb4f1aaf"),
            speech("encoder_jit_trace-pnnx.ncnn.bin", 127364056, "4ed65f05b78c0106d3d176018ab01e26a15c200604490d3d49b08cc75a122dd0"),
            speech("encoder_jit_trace-pnnx.ncnn.param", 161888, "97ad0954fb2cb4730f87a7eb66401b024f756752ece246e4b2063f870ebf3e18"),
            speech("joiner_jit_trace-pnnx.ncnn.bin", 7350724, "0e6c4370017394de5d74128756233d2e4451209e63ac2abd525da3b089e8bee1"),
            speech("joiner_jit_trace-pnnx.ncnn.param", 490, "46c339f3869136c2f6d9d9d6983a6cbc2bfbcd0e3dab0f76ae25e9477f00a360"),
            speech("tokens.txt", 56317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3")
        )

    private fun speech(name: String, bytes: Long, sha: String) =
        DownloadResource(name, SPEECH_BASE + name, bytes, sha)

    private val ubuntu =
        DownloadResource(
            "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
            "https://raw.githubusercontent.com/CATMIAOZHI/OperitTerminalCore/cd5d53cb2c99b7913d01c7919e1d93bb55ca76c4/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
            64133552,
            "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"
        )

    private val androidTemplate =
        DownloadResource(
            "android.apk",
            "https://github.com/CATMIAOZHI/OperitResources/releases/download/templates-v1/android.apk",
            48139093,
            "c56b23a841a736e028aeec8bee6b48a086b668143ce103095042e622750b86b4"
        )

    private val windowsTemplate =
        DownloadResource(
            "windows.zip",
            "https://github.com/CATMIAOZHI/OperitResources/releases/download/templates-v1/windows.zip",
            11412717,
            "9c2d3e3b5e862334e24f4008572f239c380a73f23c5940054bf3a5142ca46888"
        )

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupLocks = ConcurrentHashMap<String, Mutex>()
    private val groups = ConcurrentHashMap<String, DownloadGroup>()
    private val running = ConcurrentHashMap<String, Deferred<Unit>>()
    private val runs = ConcurrentHashMap<String, DownloadRun>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val declinedAt = ConcurrentHashMap<String, Long>()
    /** Ids already verified in this process; re-hashing 127 MiB on every retry is not free. */
    private val verified = ConcurrentHashMap<String, Boolean>()
    private val approvalLock = Any()

    private val mutableDownloads =
        MutableStateFlow<Map<String, ResourceDownloadState>>(emptyMap())
    val downloads = mutableDownloads.asStateFlow()

    private val mutablePending = MutableStateFlow<List<PendingResourceRequest>>(emptyList())
    val pendingRequests = mutablePending.asStateFlow()

    @Volatile private var ubuntuFailure: String? = null
    @Volatile private var speechFailure: FreshMessage? = null

    suspend fun ensureSpeech(context: Context): File {
        val directory = File(OperitPaths.sherpaNcnnModelsDir(context), SPEECH_DIRECTORY)
        val group =
            DownloadGroup(
                SPEECH_DIRECTORY,
                R.string.resource_offline_speech,
                speech.map { DownloadItem(File(directory, it.id), it) }
            )
        try {
            ensureGroup(context, group)
            speechFailure = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Speech runs from a floating window that cannot show the progress card, so the
            // reason is parked here for the caller that is about to report the failure.
            speechFailure = FreshMessage(failureMessage(context, e), SystemClock.elapsedRealtime())
            throw e
        }
        return directory
    }

    /**
     * Why the offline speech model was last unusable, but only while that is still current: the
     * caller has just failed to start, and an old reason would be a lie.
     */
    fun recentSpeechFailure(): String? {
        val failure = speechFailure ?: return null
        val age = SystemClock.elapsedRealtime() - failure.atMs
        return if (age <= SPEECH_FAILURE_WINDOW_MS) failure.text else null
    }

    /** Whether the offline speech model is already on disk; never downloads or prompts. */
    fun hasSpeechModel(context: Context): Boolean {
        val directory = File(OperitPaths.sherpaNcnnModelsDir(context), SPEECH_DIRECTORY)
        return speech.all { resource ->
            val file = File(directory, resource.id)
            file.isFile && file.length() == resource.bytes
        }
    }

    suspend fun ensureUbuntu(context: Context, target: File) {
        try {
            terminalArchiveFiles.withLock {
                // Older installs may already have a verified staging archive.
                if (!VerifiedResourceStore.isValid(target, ubuntu)) {
                    val group = componentGroups(context).first { it.id == ubuntu.id }
                    ensureGroup(context, group)
                    verifyFreeSpace(context.cacheDir, ubuntu, 0L)
                    val staging = File(context.cacheDir, "${target.name}.copying")
                    try {
                        group.items.single().target.inputStream().use { input ->
                            staging.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        check(staging.renameTo(target)) { "Cannot prepare terminal archive" }
                    } finally {
                        staging.delete()
                    }
                }
            }
            ubuntuFailure = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ubuntuFailure = failureMessage(context, e)
            throw e
        }
    }

    suspend fun exportTemplate(context: Context, android: Boolean): File {
        val resource = if (android) androidTemplate else windowsTemplate
        val target = File(context.filesDir, "downloaded_resources/templates-v1/${resource.id}")
        val group =
            DownloadGroup(
                resource.id,
                if (android) R.string.resource_android_export else R.string.resource_windows_export,
                listOf(DownloadItem(target, resource))
            )
        ensureGroup(context, group)
        return target
    }

    /** Reason the last Ubuntu preparation failed, so callers can report it instead of guessing. */
    fun consumeUbuntuFailure(): String? {
        val failure = ubuntuFailure
        ubuntuFailure = null
        return failure
    }

    fun approve(id: String) {
        synchronized(approvalLock) { approvals[id] }?.complete(true)
    }

    fun decline(id: String) {
        val deferred = synchronized(approvalLock) { approvals[id] }
        declinedAt[id] = SystemClock.elapsedRealtime()
        deferred?.complete(false)
    }

    /** Stops a running download; the waiting feature reports a plain "cancelled" message. */
    fun cancel(id: String) {
        declinedAt[id] = SystemClock.elapsedRealtime()
        val run = runs[id] ?: return
        run.cancelled = true
        run.call?.cancel()
    }

    fun dismiss(id: String) {
        mutableDownloads.update { it - id }
    }

    fun setCollapsed(id: String, collapsed: Boolean) {
        mutableDownloads.update { states ->
            val state = states[id] ?: return@update states
            states + (id to state.copy(collapsed = collapsed))
        }
    }

    /** True while a group is still waiting for approval or downloading. */
    fun isGroupActive(id: String): Boolean =
        running[id]?.isActive == true || approvals.containsKey(id)

    /** Explicit user retry from the progress card; the tap itself is the approval. */
    fun retry(context: Context, id: String) {
        val group = groups[id] ?: return
        declinedAt.remove(id)
        mutableDownloads.update { it - id }
        scope.launch {
            try {
                startDownload(context.applicationContext, group, retry = true).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The progress card already carries the localized reason.
            }
        }
    }

    private suspend fun ensureGroup(context: Context, group: DownloadGroup) {
        groups[group.id] = group
        if (missingItems(group).isEmpty()) {
            discardLeftoverParts(group)
            return
        }
        // A download already in flight carries its own approval; join it instead of asking again.
        val inFlight = running[group.id]
        if (inFlight != null && inFlight.isActive) {
            inFlight.await()
            if (missingItems(group).isEmpty()) {
                discardLeftoverParts(group)
                return
            }
        }
        // Approval is asked for outside the group lock: waiting for a human must never block a
        // later attempt that needs the same resource.
        requestApproval(context, group)
        if (missingItems(group).isEmpty()) {
            discardLeftoverParts(group)
            return
        }
        groupLocks.getOrPut(group.id) { Mutex() }.withLock {
            if (missingItems(group).isEmpty()) {
                discardLeftoverParts(group)
                return
            }
            startDownload(context.applicationContext, group).await()
        }
    }

    /** A complete resource never needs its partial file, so stale bytes are reclaimed. */
    private fun discardLeftoverParts(group: DownloadGroup) {
        group.items.forEach { item ->
            val part = partOf(item.target)
            if (part.isFile && item.target.isFile) part.delete()
        }
    }

    private suspend fun missingItems(group: DownloadGroup): List<DownloadItem> =
        group.items.filterNot { isReady(it) }

    /**
     * Bytes a resumed download still has to fetch: only the files that are still missing, and
     * only their un-downloaded tail. Reporting the full group size would overstate a download
     * that keeps most of its progress across retries.
     */
    private suspend fun remainingBytes(group: DownloadGroup): Long =
        missingItems(group).sumOf { item ->
            val part = partOf(item.target)
            (item.resource.bytes - VerifiedResourceStore.resumeOffset(part, item.resource))
                .coerceAtLeast(0L)
        }

    private suspend fun isReady(item: DownloadItem): Boolean {
        val id = item.resource.id
        if (verified[id] == true && item.target.isFile && item.target.length() == item.resource.bytes) {
            return true
        }
        val valid = VerifiedResourceStore.isValid(item.target, item.resource)
        if (valid) verified[id] = true else verified.remove(id)
        return valid
    }

    /** Blocks until the user approves or declines; declines are not re-prompted automatically. */
    /** Throws when the download may not start, so callers always get a user-facing reason. */
    private suspend fun requestApproval(context: Context, group: DownloadGroup) {
        val label = context.getString(group.labelRes)
        val bytes = remainingBytes(group)
        val shared: CompletableDeferred<Boolean>
        var created = false
        synchronized(approvalLock) {
            val existing = approvals[group.id]
            if (existing != null) {
                shared = existing
            } else {
                val declinedRecently =
                    shouldSuppressPrompt(
                        declinedAt[group.id],
                        SystemClock.elapsedRealtime()
                    )
                if (declinedRecently) {
                    throw ResourceDownloadDeclinedException(
                        context.getString(R.string.resource_download_declined, label)
                    )
                }
                shared = CompletableDeferred()
                approvals[group.id] = shared
                created = true
            }
        }
        if (created) {
            val hostInApp = AppActivityTracker.resumedActivity is ResourceDownloadDialogHost
            // Publish before launching so the host always finds the request it was started for.
            mutablePending.update { requests ->
                requests.filterNot { it.id == group.id } +
                    PendingResourceRequest(group.id, label, bytes, hostInApp)
            }
            if (!hostInApp && !showConfirmActivity(context)) {
                synchronized(approvalLock) { approvals.remove(group.id, shared) }
                mutablePending.update { requests -> requests.filterNot { it.id == group.id } }
                throw ResourceDownloadUnavailableException(
                    context.getString(R.string.resource_download_prompt_unavailable, label),
                    IllegalStateException("No download confirmation host available")
                )
            }
        }
        val approved = try {
            // An unanswered dialog must not park the calling feature forever.
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { shared.await() }
        } finally {
            synchronized(approvalLock) { approvals.remove(group.id, shared) }
            mutablePending.update { requests -> requests.filterNot { it.id == group.id } }
        }
        if (approved == true) return
        if (approved == null) {
            // Nobody answered: leave a retryable card so the feature is not a dead end.
            declinedAt[group.id] = SystemClock.elapsedRealtime()
            publishAborted(context, group, label)
            throw ResourceDownloadDeclinedException(
                context.getString(R.string.resource_download_unconfirmed, label)
            )
        }
        // Declined, but another caller may have started this group while the dialog was open;
        // joining it beats reporting a cancellation that is not happening.
        val inFlight = running[group.id]
        if (inFlight != null && inFlight.isActive) {
            declinedAt.remove(group.id)
            inFlight.await()
            return
        }
        declinedAt[group.id] = SystemClock.elapsedRealtime()
        throw ResourceDownloadDeclinedException(
            context.getString(R.string.resource_download_declined, label)
        )
    }

    /** Card for a download that stopped without failing, e.g. cancelled or never confirmed. */
    private suspend fun publishAborted(context: Context, group: DownloadGroup, label: String) {
        val remaining = missingItems(group)
        mutableDownloads.update { states ->
            states +
                (group.id to
                    ResourceDownloadState(
                        group.id,
                        label,
                        0L,
                        remaining.sumOf { it.resource.bytes },
                        1,
                        remaining.size.coerceAtLeast(1),
                        context.getString(R.string.resource_download_unconfirmed_detail),
                        cancelled = true,
                        unconfirmed = true,
                        collapsed = states[group.id]?.collapsed ?: false
                    ))
        }
    }

    private fun showConfirmActivity(context: Context): Boolean =
        try {
            context.startActivity(
                Intent(context, ResourceDownloadConfirmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            true
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Cannot show the download confirmation dialog", e)
            false
        }

    private fun startDownload(context: Context, group: DownloadGroup, retry: Boolean = false): Deferred<Unit> =
        synchronized(running) {
            if (retry && groups[group.id] !== group) {
                throw CancellationException("Download was removed or replaced")
            }
            val existing = running[group.id]
            if (existing != null && existing.isActive) {
                existing
            } else {
                val run = DownloadRun()
                runs[group.id] = run
                val deferred =
                    scope.async {
                        runDownload(context, group, run)
                    }
                running[group.id] = deferred
                deferred.invokeOnCompletion {
                    running.remove(group.id, deferred)
                    runs.remove(group.id, run)
                }
                deferred
            }
        }

    private suspend fun runDownload(context: Context, group: DownloadGroup, run: DownloadRun) {
        val label = context.getString(group.labelRes)
        val missing = missingItems(group)
        if (missing.isEmpty()) {
            mutableDownloads.update { it - group.id }
            return
        }
        val total = missing.sumOf { it.resource.bytes }
        var completed = 0L
        var itemIndex = 1
        var itemProgress = 0L

        fun publish(
            downloaded: Long,
            error: String? = null,
            cancelled: Boolean = false,
            unconfirmed: Boolean = false
        ) {
            val collapsed = mutableDownloads.value[group.id]?.collapsed ?: false
            mutableDownloads.update {
                it +
                    (group.id to
                        ResourceDownloadState(
                            group.id,
                            label,
                            downloaded.coerceAtMost(total),
                            total,
                            itemIndex,
                            missing.size,
                            error,
                            cancelled,
                            unconfirmed,
                            collapsed
                        ))
            }
        }

        publish(0L)
        try {
            missing.forEachIndexed { index, item ->
                itemIndex = index + 1
                itemProgress = 0L
                downloadItem(context, group, item, run) { written ->
                    itemProgress = written
                    publish(completed + written)
                }
                completed += item.resource.bytes
                itemProgress = 0L
                publish(completed)
            }
            mutableDownloads.update { it - group.id }
        } catch (e: CancellationException) {
            mutableDownloads.update { it - group.id }
            throw e
        } catch (e: Throwable) {
            if (run.cancelled) {
                declinedAt[group.id] = SystemClock.elapsedRealtime()
                // Keep the card so cancelling still leaves a way back into the download.
                publish(
                    completed + itemProgress,
                    context.getString(R.string.resource_download_cancelled_detail),
                    cancelled = true
                )
                throw ResourceDownloadDeclinedException(
                    context.getString(R.string.resource_download_declined, label)
                )
            }
            AppLogger.w(TAG, "Resource group ${group.id} failed: ${e.message}", e)
            val message = failureMessage(context, e)
            publish(completed + itemProgress, message)
            throw ResourceDownloadUnavailableException(message, e)
        }
    }

    private suspend fun downloadItem(
        context: Context,
        group: DownloadGroup,
        item: DownloadItem,
        run: DownloadRun,
        onProgress: (Long) -> Unit
    ) {
        val parent = item.target.parentFile ?: throw IOException("No parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create ${parent.path}")
        val part = partOf(item.target)
        var offset = VerifiedResourceStore.resumeOffset(part, item.resource)
        verifyFreeSpace(parent, item.resource, offset)
        var restarted = false
        while (true) {
            if (run.cancelled) throw DownloadCancelledException()
            val call =
                client.newCall(
                    Request.Builder()
                        .url(item.resource.url)
                        .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                        .build()
                )
            run.call = call
            var rangeRejected = false
            try {
                call.execute().use { response ->
                    if (offset > 0L && response.code != 206) {
                        rangeRejected = true
                    } else {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty response")
                        body.byteStream().use { input ->
                            VerifiedResourceStore.write(
                                item.target,
                                item.resource,
                                input,
                                offset
                            ) { written ->
                                if (run.cancelled) throw DownloadCancelledException()
                                onProgress(written)
                            }
                        }
                        return
                    }
                }
            } finally {
                run.call = null
            }
            if (!rangeRejected || restarted) throw IOException("Server refused a range request")
            // The server answered with the whole file; the retained prefix is unusable.
            part.delete()
            offset = 0L
            restarted = true
        }
    }

    private fun verifyFreeSpace(parent: File, resource: DownloadResource, already: Long) {
        val available = parent.usableSpace
        if (available <= 0L) return
        val needed = (resource.bytes - already).coerceAtLeast(0L) + SPACE_SLACK_BYTES
        if (available < needed) throw ResourceSpaceException()
    }

    private fun partOf(target: File) = File(target.parentFile, "${target.name}.part")

    private fun failureMessage(context: Context, error: Throwable): String =
        when {
            error is ResourceDownloadException ->
                error.message ?: context.getString(R.string.resource_download_failed_generic)
            error is ResourceSpaceException ->
                context.getString(R.string.resource_download_failed_space)
            error is ResourceContentException ->
                context.getString(R.string.resource_download_failed_checksum)
            error is UnknownHostException ||
                error is SocketTimeoutException ||
                error is ConnectException ->
                context.getString(R.string.resource_download_failed_network)
            error is IOException && error.message?.startsWith("HTTP ") == true ->
                context.getString(R.string.resource_download_failed_server)
            else -> context.getString(R.string.resource_download_failed_generic)
        }
}
