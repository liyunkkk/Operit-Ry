package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.llmprovider.providerSessionIdForScope
import com.ai.assistance.operit.core.agent.*
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A separate tool-using review after a foreground turn; its hidden chat is never searched as user history. */
object MemoryLearningCoordinator {
    const val ACTION = "memory_learning_action"
    const val FINISH = "memory_learning_finish"
    /**
     * Stamped on every run this coordinator creates, so the conversation list can tell a background
     * extraction apart from an agent the user delegated to.
     */
    const val OWNER_TYPE = "memory-learning"
    /**
     * Wall-clock ceiling for one batch, derived from the round budget so the round limit stays
     * reachable instead of being cut short by the clock.
     */
    private val BATCH_TIMEOUT_MS = LEARNING_ROUND_LIMIT * 45_000L
    /**
     * One manual review pass runs up to three batches, so this must not cut a pass short. The extra
     * minute covers the per-batch work outside the batch timeout (checkpoint export, source advance).
     */
    private val MANUAL_TIMEOUT_MS = BATCH_TIMEOUT_MS * 3 + 60_000L
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancellingJobs = ConcurrentHashMap<String, Job>()
    private val automaticJobs = ConcurrentHashMap<String, Job>()
    /** Chats whose batch is already talking to the model, so a foreground turn soft-cancels instead. */
    private val batchStartedAt = ConcurrentHashMap<String,Long>()
    private val jobProfiles = ConcurrentHashMap<String,String>()
    private val deletedProfiles = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLocks = ConcurrentHashMap<String,Mutex>()
    private fun lifecycle(profile: String) = lifecycleLocks.computeIfAbsent(profile) { Mutex() }
    private data class Prepared(val profile: String, val iterations: Int)
    private val prepared = ConcurrentHashMap<Pair<String,String>,Prepared>()
    private val foreground = ConcurrentHashMap.newKeySet<String>()
    private val manualClaims = ConcurrentHashMap.newKeySet<String>()
    /** Backlog that is still pending after a full round keeps draining without another idle wait. */
    private const val BACKLOG_RETRY_MS = 30_000L
    /** How often a busy-foreground check is repeated before the backlog is allowed to run anyway. */
    private const val BUSY_RETRY_MS = 60_000L
    /**
     * A range that has been pending this long is dispatched even while another conversation is
     * generating. Without it a device whose owner chats continuously never reaches an idle state,
     * the backlog grows without bound and no review ever runs.
     */
    private const val MAX_PENDING_AGE_MS = 30 * 60_000L
    /** Progress records are rewritten after each tool call; the clock keeps that from flooding IO. */
    private const val PROGRESS_WRITE_INTERVAL_MS = 2000L
    fun cancelAutomaticReviews() { automaticJobs.values.forEach { it.cancel() } }
    suspend fun deleteSpace(context: Context, profile: String) {
        lifecycle(profile).withLock {
            deletedProfiles.add(profile)
            prepared.entries.removeAll { it.value.profile==profile }
        }
        val targets=jobProfiles.filterValues { it==profile }.keys.flatMap {
            listOfNotNull(jobs[it],cancellingJobs[it])
        }.distinct()
        targets.forEach { it.cancel() }
        targets.forEach { it.join() }
        lifecycle(profile).withLock {
            withContext(Dispatchers.IO) { MemoryLearningJournal.deleteSpace(context,profile) }
        }
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private class Session(val actions: MemoryLearningActions) : AgentRunObserver {
        override val capabilityTools = setOf(ACTION,FINISH)
        val lock = Mutex()
        val requests = AtomicInteger()
        val calls = AtomicInteger()
        @Volatile var finished = false
        /**
         * The model loop appends while the review coroutine reads its own progress snapshot, so the
         * list is synchronised instead of relying on both sides happening to hold the same lock.
         */
        private val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val retryGuard = LearningRetryGuard()
        fun addFailure(detail: String) { failures.add(detail) }
        fun failureList(): List<String> = synchronized(failures) { failures.toList() }
        fun failureCount(): Int = synchronized(failures) { failures.size }
        var persistProgress: suspend () -> Unit = {}
        var recordModelRound: () -> Unit = {}
        val evidenceBytes = AtomicLong()
        var evidenceBudget: Long = Long.MAX_VALUE
        var lastProgressWriteAt: Long = 0
        lateinit var job: Job
        override fun onModelRequest() {
            if (evidenceBytes.get() > evidenceBudget) {
                val reason = "Learning context budget reached; stopped before another model request"
                addFailure(reason)
                error(reason)
            }
            if (requests.incrementAndGet()>LEARNING_ROUND_LIMIT) {
                // Recorded like the context-budget stop so the extraction log says why the batch ended
                // instead of only reporting a bare limit message. The source range stays pending.
                val reason = "Learning round limit reached; the batch was discarded and its source will be reviewed again"
                addFailure(reason)
                error(reason)
            }
            recordModelRound()
        }
        override suspend fun beforeToolBatch(tools: List<AITool>) {
            check(tools.all { it.name in capabilityTools })
        }
        fun roundNotice(): String? = learningRoundNotice(LEARNING_ROUND_LIMIT - requests.get())
    }
    fun foregroundStarted(chatId: String?) {
        chatId?.let { id ->
            foreground.add(id)
            prepared.keys.removeAll { it.first==id }
            // A batch that is already talking to the model is left to finish: cancelling it throws
            // away work that has already been paid for, and the source it reads was fixed at enqueue
            // time so it cannot swallow the new turn. The loop pauses before its next batch instead.
            if (!batchStartedAt.containsKey(id)) cancelReview(id)
        }
    }
    /**
     * Stops every review of a conversation that no longer exists. It must not go through
     * [foregroundStarted]: that one also records a live turn, and a deleted conversation never
     * reports its end, so the entry would keep every other conversation waiting.
     */
    fun forgetChat(chatId: String?) {
        chatId?.let { id ->
            prepared.keys.removeAll { it.first == id }
            cancelReview(id)
        }
    }
    private fun cancelReview(id: String) {
        jobs.remove(id)?.let { job ->
            cancellingJobs[id] = job
            job.invokeOnCompletion { cancellingJobs.remove(id, job) }
            job.cancel()
        }
    }
    fun foregroundEnded(context: Context, chatId: String?) {
        if (chatId != null) {
            foreground.remove(chatId)
            prepared.keys.removeAll { it.first==chatId }
            resumePending(context)
        }
    }
    suspend fun manualReview(context: Context, profileId: String, chatId: String) {
        check(manualClaims.add(chatId)) { "A manual review is already running" }
        try { runManualReview(context,profileId,chatId) }
        finally {
            manualClaims.remove(chatId)
            resumePending(context)
        }
    }
    private suspend fun runManualReview(context: Context, profileId: String, chatId: String) = coroutineScope {
        check(!foreground.contains(chatId)) { "Wait for the current response before manually reviewing it" }
        cancelReview(chatId)
        cancellingJobs[chatId]?.join()
        val task = async(start=CoroutineStart.LAZY) {
            lifecycle(profileId).withLock {
                check(profileId !in deletedProfiles) { "Memory space was deleted" }
                val journal=MemoryLearningJournal(context,profileId,chatId)
                // A pending batch that cannot be imported is reported instead of disappearing;
                // the restart below re-reviews the whole chat, so nothing is lost either way.
                journal.export().forEach { detail ->
                    recordUnreviewable(context,profileId,chatId,listOf("notes","skills"),detail)
                }
                journal.enqueue(true,true,AppDatabase.getDatabase(context).chatContentDao().learningSourceHorizon(chatId),
                    restart=true)
            }
            // An explicit review waits for its backlog, rather than reporting a three-batch prefix
            // as a complete review. Cancellation/time limits retain completed batch checkpoints.
            withTimeout(MANUAL_TIMEOUT_MS) {
                do {
                    review(context,profileId,chatId,manual=true)
                    val remaining=MemoryLearningJournal(context,profileId,chatId)
                } while (remaining.pending("notes") || remaining.pending("skills"))
            }
        }
        jobs[chatId]=task
        jobProfiles[chatId]=profileId
        task.invokeOnCompletion { if (jobs.remove(chatId,task)) jobProfiles.remove(chatId,profileId) }
        if (foreground.contains(chatId)) task.cancel()
        task.start()
        try { task.await() } finally {
            jobs.remove(chatId,task)
        }
    }

    suspend fun prepareReview(context: Context, profileId: String, chatId: String, turnKey: String?,
                              toolIterations: Int = 0) {
        if (profileId in deletedProfiles || !ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return
        if (turnKey != null) prepared[chatId to turnKey]=Prepared(profileId,toolIterations)
    }

    /** Called only after the matching foreground turn's final messages have been persisted. */
    suspend fun sourceCommitted(context: Context, chatId: String, turnKey: String, revisedTimestamp: Long? = null) {
        val metadata=prepared.remove(chatId to turnKey) ?: return
        val profileId=metadata.profile
        val settings = MemorySearchSettingsPreferences(context,profileId)
        cancellingJobs[chatId]?.join()
        val tick = settings.advanceLearningCadence(chatId, metadata.iterations)
        val dao=AppDatabase.getDatabase(context).chatContentDao()
        val horizon=dao.learningSourceHorizon(chatId)
        val revisedId=revisedTimestamp?.let { dao.learningMessageId(chatId,it) }
        lifecycle(profileId).withLock {
            if (profileId in deletedProfiles) return
            val journal=MemoryLearningJournal(context,profileId,chatId)
            revisedId?.let { journal.rewind(it) }
            if (!tick.notes && !tick.skills && !journal.pending("notes") && !journal.pending("skills")) return
            journal.enqueue(tick.notes,tick.skills,horizon)
        }
        foreground.remove(chatId)
        schedule(context,profileId,chatId)
    }

    fun resumePending(context: Context) {
        scope.launch {
            MemoryLearningJournal.pending(context).forEach { (profile,chat) ->
                if (!foreground.contains(chat) && !jobs.containsKey(chat)) schedule(context,profile,chat)
            }
        }
    }

    private fun schedule(context: Context, profileId: String, chatId: String) {
        if (profileId in deletedProfiles || chatId in manualClaims) return
        val settings = MemorySearchSettingsPreferences(context,profileId)
        val job = scope.launch(start=CoroutineStart.LAZY) {
            try {
                // The idle period is waited for once per run; a run that still has backlog keeps
                // draining it instead of paying another full idle wait for every round.
                delay(settings.learningDelayMinutes() * 60_000L)
                while (true) {
                    if (profileId in deletedProfiles) return@launch
                    if (!ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return@launch
                    val journal=MemoryLearningJournal(context,profileId,chatId)
                    val notes=journal.pending("notes") && settings.shouldExtractNewMemory()
                    val skills=journal.pending("skills") && settings.shouldExtractSkills()
                    // A finished batch is exported before anything else so its proposals are never
                    // lost behind a range that is no longer reviewable.
                    if (exportFinishedBatch(context,profileId,chatId,journal)!=null) return@launch
                    if (!notes && !skills) return@launch
                    if (foreground.isNotEmpty() && !journal.pendingForAtLeast(MAX_PENDING_AGE_MS)) {
                        // Any live turn means the machine is not idle, so this waits and re-checks
                        // instead of dropping the backlog. Work that has waited past the age limit is
                        // dispatched anyway, so a constantly busy device cannot starve extraction.
                        delay(BUSY_RETRY_MS)
                        continue
                    }
                    settings.consumePendingLearning(chatId)
                    review(context.applicationContext,profileId,chatId,reviewNotes=notes,reviewSkills=skills)
                    val remaining=MemoryLearningJournal(context,profileId,chatId)
                    if (!(remaining.pending("notes") && settings.shouldExtractNewMemory()) &&
                        !(remaining.pending("skills") && settings.shouldExtractSkills())) return@launch
                    // Backlog is still pending, so the run continues after a short pause rather than
                    // waiting for another full idle period.
                    delay(BACKLOG_RETRY_MS)
                }
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { AppLogger.e("MemoryLearning","Background review failed",e) }
        }
        if (jobs.putIfAbsent(chatId,job)!=null) { job.cancel(); return }
        jobProfiles[chatId]=profileId
        automaticJobs[chatId] = job
        job.invokeOnCompletion {
            if (jobs.remove(chatId,job)) jobProfiles.remove(chatId,profileId)
            automaticJobs.remove(chatId,job)
        }
        if (foreground.contains(chatId) || chatId in manualClaims) { job.cancel(); return }
        job.start()
    }

    suspend fun review(context: Context, profileId: String, chatId: String, manual: Boolean = false,
                       reviewNotes: Boolean = true, reviewSkills: Boolean = true) {
        if (!manual && !ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return
        val db = AppDatabase.getDatabase(context)
        val chat = db.chatDao().getChatById(chatId)
        if (chat == null) {
            // The conversation was deleted while its source range was still pending. It can never be
            // reviewed, so record why and drop the progress instead of retrying on every launch.
            val reason=context.getString(R.string.memory_extraction_source_deleted)
            MemoryLearningJournal.deleteChat(context,chatId)
            recordUnreviewable(context,profileId,chatId,listOf("notes","skills"),reason)
            if (manual) error(reason)
            return
        }
        if (chat.isHidden || chat.parentChatId!=null || chat.chatKind!="NORMAL") {
            // Branch and hidden conversations are not reviewed by design. Leaving the range pending
            // would retry it on every later turn, and this is not a failure worth logging. A finished
            // batch that was never exported is applied first so nothing already reviewed is lost.
            val journal=MemoryLearningJournal(context,profileId,chatId)
            // Nothing here is kept by design, but a batch that could not be applied still belongs in
            // the extraction log rather than vanishing with the abandoned range.
            journal.export().forEach { detail ->
                recordUnreviewable(context,profileId,chatId,listOf("notes","skills"),detail)
            }
            journal.abandon(listOf("notes","skills"))
            if (manual) error(context.getString(R.string.memory_extraction_not_reviewable))
            return
        }
        val settings = MemorySearchSettingsPreferences(context,profileId)
        val notes = manual || (reviewNotes && settings.shouldExtractNewMemory())
        val skills = manual || (reviewSkills && settings.shouldExtractSkills())
        if (!notes && !skills) return
        val journal=MemoryLearningJournal(context,profileId,chatId)
        if (manual) journal.enqueue(notes,skills,db.chatContentDao().learningSourceHorizon(chatId))
        exportFinishedBatch(context,profileId,chatId,journal)?.let { if (manual) error(it); return }
        MemoryLearningSource(context,db.chatContentDao(),chatId,journal.horizon(),
            settings.shouldIncludeThinking()).use { source ->
            repeat(3) {
                // The machine can become busy again between batches, so the pause this run promised is
                // checked here too: without it three batches run back to back and a conversation
                // sharing the same local model waits for all of them. A manual review is the user's
                // explicit request, so it is never paused.
                if (!manual && foreground.isNotEmpty()) return
                val paths=listOfNotNull("notes".takeIf { notes && journal.pending(it) },
                    "skills".takeIf { skills && journal.pending(it) })
                if (paths.isEmpty()) return
                val first=paths.minBy { journal.cursor(it).messageId }
                val selected=paths.filter { journal.cursor(it)==journal.cursor(first) }
                val instructions=buildMemoryLearningInstructions(chatId,"notes" in selected,"skills" in selected,FINISH)
                val config = try {
                    MemoryLearningSnapshot.build(context,emptyList(),instructions.toByteArray().size,
                        settings.shouldIncludeThinking())
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // A window that cannot even hold the instructions will not become reviewable by
                    // retrying, so stop the loop and leave the reason where the user can see it.
                    val reason=context.getString(R.string.memory_extraction_window_too_small)
                    recordUnreviewable(context,profileId,chatId,selected,reason,
                        e.message?.takeIf { it.isNotBlank() } ?: e.toString())
                    journal.abandon(selected)
                    if (manual) error(reason)
                    return
                }
                val batch=try {
                    source.next(journal.cursor(first),
                        MemoryLearningSnapshot.sourceBudget(config.contextWindow,instructions.toByteArray().size))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Source that cannot be read back at all will fail identically on every retry, so
                    // the reason is recorded and the range stops being retried on every idle period.
                    val reason=context.getString(R.string.memory_extraction_source_unreadable)
                    recordUnreviewable(context,profileId,chatId,selected,reason,
                        e.message?.takeIf { it.isNotBlank() } ?: e.toString())
                    journal.abandon(selected)
                    if (manual) error(reason)
                    return
                }
                if (batch.text.isBlank()) {
                    journal.complete(selected,batch.next,batch.more,emptyList())
                    journal.export().forEach { detail ->
                        recordUnreviewable(context,profileId,chatId,selected,detail)
                    }
                } else reviewBatch(context,profileId,chatId,selected,batch,config.contextWindow,journal)
            }
        }
    }

    /**
     * Exports the batch finished by the previous run. A blocked export keeps the pending markers, so
     * the range is retried; returning the reason turns that stuck state into something the user can see.
     */
    private suspend fun exportFinishedBatch(context: Context, profileId: String, chatId: String,
        journal: MemoryLearningJournal): String? {
        val failures = try {
            journal.export()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val reason=context.getString(R.string.memory_extraction_export_blocked)
            recordUnreviewable(context,profileId,chatId,listOf("notes","skills"),reason,
                e.message?.takeIf { it.isNotBlank() } ?: e.toString())
            return reason
        }
        if (failures.isEmpty()) return null
        val reason=context.getString(R.string.memory_extraction_export_blocked)
        recordUnreviewable(context,profileId,chatId,listOf("notes","skills"),reason,failures.joinToString("\n"))
        return reason
    }

    /**
     * Records a source range that could not be reviewed, so the reason shows up in the extraction log
     * instead of only in logcat.
     */
    private suspend fun recordUnreviewable(context: Context, profileId: String, chatId: String,
        paths: List<String>, reason: String, technical: String = "") {
        MemoryExtractionLogRepository(context,profileId).save(MemoryExtractionLog(
            sourceChatId=chatId,graph=false,notes="notes" in paths,skills="skills" in paths,
            status="failed",detail=reason,finishedAt=System.currentTimeMillis(),reviewable=false))
        AppLogger.w("MemoryLearning","Unreviewable source range for $chatId: $reason" +
            technical.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
    }

    private suspend fun reviewBatch(context: Context, profileId: String, chatId: String,
        paths: List<String>, batch: MemoryLearningSource.Batch, contextWindow: Int, journal: MemoryLearningJournal) {
        val notes="notes" in paths
        val skills="skills" in paths
        val logRepo = MemoryExtractionLogRepository(context,profileId)
        // The run does not exist yet, so the first row must not offer an audit view; onRunCreated
        // flips this once the subagent is there to inspect.
        val log = MemoryExtractionLog(sourceChatId=chatId,graph=false,notes=notes,skills=skills,reviewable=false)
        var created = 0
        val staged=mutableListOf<MemoryReviewChange>()
        val session = Session(MemoryLearningActions(context,profileId,chatId,notes,skills,true,staged))
        var childId: String? = null
        var runId = ""
        // Until the subagent exists there is no transcript to audit, so the row must not offer one.
        var auditable = false
        fun snapshot(status: String = "running", finishedAt: Long = 0, detail: String = session.failureList().joinToString("\n")) =
            log.copy(status=status,finishedAt=finishedAt,proposals=created,detail=detail,
                runId=runId,childChatId=childId.orEmpty(),modelRounds=session.requests.get(),toolCalls=session.calls.get(),
                reviewable=auditable)
        session.persistProgress = {
            // Progress rides on every tool call, which is far more IO than this record needs, so it
            // is flushed at most every [PROGRESS_WRITE_INTERVAL_MS]. The final status below is always
            // written, so skipping an intermediate flush loses nothing.
            val now=System.currentTimeMillis()
            if (now-session.lastProgressWriteAt >= PROGRESS_WRITE_INTERVAL_MS) {
                session.lastProgressWriteAt=now
                logRepo.save(snapshot())
            }
        }
        logRepo.save(log)
        batchStartedAt[chatId]=System.currentTimeMillis()
        try {
            // This budget includes reasoning and all model rounds, not just tool execution.
            withTimeout(BATCH_TIMEOUT_MS) {
                session.job = currentCoroutineContext().job
                val instructions = buildMemoryLearningInstructions(chatId, notes, skills, FINISH)
                // Conservative byte accounting bounds repeated history/skill reads as well as SOURCE.
                // Leave room for the scoped tool schema, model output and protocol overhead.
                session.evidenceBudget = MemoryLearningSnapshot.evidenceBudget(contextWindow).toLong()
                session.evidenceBytes.set((batch.text + instructions).toByteArray(Charsets.UTF_8).size.toLong())
                val result = SubagentCoordinator.getInstance(context).runTask(SubagentTaskRequest(
                    parentChatId=chatId,parentToolCallId=null,parentAgentName=null,
                    title=context.getString(R.string.memory_learning_run),prompt="SOURCE BATCH:\n${batch.text}",
                    subagentType="memory-learning",functionType=FunctionType.MEMORY,
                    profileOverride=AgentProfile("memory-learning","Memory learning","",AgentMode.SUBAGENT,instructions,hidden=true),
                    isolatedToolPrompts=prompts(notes, skills),terminalToolNames=setOf(FINISH),
                    // Every batch of one conversation asks under one identity so a provider that caches
                    // prompt prefixes reuses what a sibling batch already warmed instead of paying a cold
                    // prefix on each of them.
                    providerSessionId=providerSessionIdForScope("memory_learning:$chatId"),
                    promptHooksEnabled=false,disableSummary=false,childHidden=true,
                    childHiddenReason="MEMORY_LEARNING",externalOwnerType=OWNER_TYPE,externalOwnerId=log.id,
                    onRunCreated={ run ->
                        childId=run.childChatId
                        runId=run.id
                        auditable = true
                        logRepo.save(snapshot())
                        session.recordModelRound = {
                            runBlocking {
                                com.ai.assistance.operit.data.repository.SubagentRunRepository.getInstance(context)
                                    .incrementModelRoundCountByChildChatId(run.childChatId)
                            }
                        }
                        sessions[run.childChatId]=session
                        AgentRunObservers.register(run.childChatId,session)
                    }
                ))
                check(result is SubagentTaskResult.Completed) { "Learning task did not complete" }
                check(session.finished) { "Learning review did not confirm batch completion; source progress was retained" }
            }
            currentCoroutineContext().ensureActive()
            journal.complete(paths,batch.next,batch.more,staged)
            created=staged.size
            journal.export().forEach { session.addFailure(it) }
            logRepo.save(snapshot(status=if (batch.more && session.failureCount()==0) "batch_complete"
                else memoryLearningFinalStatus(null,created,session.failureCount()),
                finishedAt=System.currentTimeMillis()))
        } catch(e: Exception) {
            withContext(NonCancellable) {
                logRepo.save(snapshot(finishedAt=System.currentTimeMillis(),
                    status=memoryLearningFinalStatus(e,created,session.failureCount()),
                    detail=(session.failureList()+learningFailureDetail("run",e)).joinToString("\n")))
            }
            throw e
        } finally {
            childId?.let { sessions.remove(it); AgentRunObservers.unregister(it) }
            batchStartedAt.remove(chatId)
        }
    }

    fun execute(tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val caller = runtime?.callerChatId
        val session = caller?.let { sessions[it] } ?: return ToolResult(
            toolName=tool.name,success=false,result=StringResultData(""),error="No active learning capability")
        return runBlocking(Dispatchers.IO + session.job + ToolExecutionManager.toolRuntimeContextElement(runtime)) {
        session.lock.withLock {
            currentCoroutineContext().ensureActive()
            check(!session.finished) { "This review is already finished" }
            if (session.calls.incrementAndGet()>LEARNING_TOOL_CALL_LIMIT) {
                val reason = "Learning tool-call limit reached; the batch was discarded and its source will be reviewed again"
                session.addFailure(reason)
                error(reason)
            }
            session.evidenceBytes.addAndGet(tool.parameters.sumOf {
                it.value.toByteArray(Charsets.UTF_8).size.toLong()
            })
            val args = tool.parameters.associate { it.name to it.value }
            val actionName = args["action"].orEmpty()
            // Corrected input must remain executable; retain only a digest of full arguments.
            var repeatKey = learningRetryKey(actionName,args)
            var repeatTarget = ""
            try {
                if(tool.name==FINISH) {
                    session.finished=true
                    ToolResult(toolName=tool.name,success=true,result=StringResultData("Review finished"))
                } else {
                    val json = JSONObject(args["arguments"].orEmpty().ifBlank { "{}" })
                    val params = json.keys().asSequence().associateWith { json.get(it).toString() }
                    val parts = listOf("target","name","path","section").map { params[it].orEmpty() }
                    repeatKey = learningRetryKey(actionName,params)
                    repeatTarget = parts.filter { it.isNotBlank() }.joinToString(" ")
                    check(!session.retryGuard.isBlocked(repeatKey)) {
                        "These identical arguments already failed $LEARNING_REPEAT_FAILURE_LIMIT times. " +
                            "Read the latest content, correct the arguments, or skip this change and call $FINISH."
                    }
                    val result = session.actions.execute(actionName,params)
                    session.retryGuard.onSuccess()
                    session.roundNotice()?.let { result.put("notice",it) }
                    val resultText = result.toString()
                    session.evidenceBytes.addAndGet(resultText.toByteArray(Charsets.UTF_8).size.toLong())
                    ToolResult(toolName=tool.name,success=true,result=StringResultData(resultText))
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                session.addFailure(learningFailureDetail(actionName,e))
                // A run of rejected tool calls is exactly when the reviewer needs to know the budget is
                // nearly gone, so the pacing hint rides on the error too.
                val notice = session.roundNotice()
                val repeats = session.retryGuard.onFailure(repeatKey)
                if (repeats >= LEARNING_REPEAT_FAILURE_LIMIT) {
                    // Rejections are failures, even when we also give a recovery/budget hint.
                    val target = repeatTarget.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
                    val message = "${e.message.orEmpty()} $actionName$target failed with identical arguments $repeats times. " +
                        "Do not resend unchanged arguments. Read the latest content and correct the input, " +
                        "or skip this change and call $FINISH." +
                        (notice?.let { " $it" } ?: "")
                    ToolResult(toolName=tool.name,success=false,result=StringResultData(""),error=message)
                } else ToolResult(toolName=tool.name,success=false,result=StringResultData(""),
                    error=e.message.orEmpty().let { message -> notice?.let { message+" $it" } ?: message })
            } finally { session.persistProgress() }
        }
        }
    }
    fun prompts(notes: Boolean = true, skills: Boolean = true) = listOf(
        ToolPrompt(name=ACTION,description=memoryLearningActionDescription(notes, skills),parametersStructured=listOf(
            ToolParameterSchema("action","string","Operation",true),
            ToolParameterSchema("arguments","string","JSON argument object",false)
        )),
        ToolPrompt(name=FINISH,description="Finish this learning review.",parametersStructured=emptyList())
    )
}

internal fun memoryLearningFinalStatus(error: Throwable?, proposals: Int, toolErrors: Int): String = when {
    error is TimeoutCancellationException -> "timeout"
    error is CancellationException -> "cancelled"
    error != null -> if (proposals > 0) "partial" else "failed"
    toolErrors > 0 -> "warnings"
    else -> "success"
}

internal fun learningFailureDetail(action: String, error: Throwable): String =
    "$action: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}"

/**
 * Pacing hint attached to a tool result while a batch approaches its round limit. The batch is
 * discarded when the limit is hit, so the reviewer is told to reserve a round to submit and confirm.
 */
internal fun learningRoundNotice(remainingRounds: Int, finish: String = MemoryLearningCoordinator.FINISH): String? = when {
    // No rounds left means the next model request is refused, so finish is no longer reachable.
    remainingRounds<=0 -> "No model rounds left; this batch is discarded and its source reviewed again."
    remainingRounds<=2 -> "$remainingRounds model round${if (remainingRounds==1) "" else "s"} left: stop exploring, " +
        "submit the best complete change you already have and call $finish; an unfinished batch is discarded."
    else -> null
}
