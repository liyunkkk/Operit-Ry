package com.ai.assistance.operit.api.chat.library

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatRecallPart
import com.ai.assistance.operit.data.preferences.LearnedSkillRepository
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import com.ai.assistance.operit.data.preferences.MemoryReviewChange
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import com.ai.assistance.operit.data.preferences.SkillDraft
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class MemoryLearningBatchTest {
    @get:Rule val folder=TemporaryFolder()
    private fun context(): Context=mock<Context>().also {
        whenever(it.filesDir).thenReturn(folder.root)
        whenever(it.cacheDir).thenReturn(folder.newFolder())
        whenever(it.applicationContext).thenReturn(it)
        val prefs=mock<SharedPreferences>()
        val editor=mock<SharedPreferences.Editor>()
        whenever(it.getSharedPreferences(any(),any())).thenReturn(prefs)
        whenever(prefs.getBoolean(any(),any())).thenReturn(false)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(),any())).thenReturn(editor)
    }
    /** Leaves a row exactly as an approval that was killed between its write and its decision. */
    private fun reopenApproval(context: Context, id: String) {
        val store=java.io.File(context.filesDir,"memory_reviews").listFiles()!!.single()
        val items=org.json.JSONArray(store.readText())
        for (i in 0 until items.length()) {
            val row=items.getJSONObject(i)
            if (row.optString("id")==id) row.put("status","applying")
        }
        store.writeText(items.toString())
    }
    @Test fun journalPersistsSeparateCoverageAndReplaysProposalIdsOnce() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,true,10)
        val change=MemoryReviewChange("stable-id","notes","memory.md","proposal",sourceChatId="chat")
        journal.complete(listOf("notes"),LearningCursor(7),true,listOf(change))
        // Simulate process death after durable completion, before review history export.
        val restored=MemoryLearningJournal(context,"space","chat")
        assertEquals(7,restored.cursor("notes").messageId)
        assertEquals(0,restored.cursor("skills").messageId)
        restored.export()
        val repo=MemoryReviewRepository(context,"space")
        assertEquals(listOf("stable-id"),repo.list().map { it.id })
        // Replaying the same completed journal does not duplicate a proposal.
        journal.export()
        assertEquals(1,repo.list().size)
    }
    @Test fun cancelledStagingDoesNotCreateReviewHistory() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val change=repo.propose(MemoryReviewChange("","notes","memory.md","proposal"))
        assertEquals("staged",repo.applyAutomaticDecision(context,change).status)
        assertTrue(MemoryReviewRepository(context,"space").list().isEmpty())
        assertEquals(1,staged.size)
    }
    @Test fun stagedBatchKeepsOnlyTheLastChangeForATarget() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val first=repo.propose(MemoryReviewChange("","notes","memory.md","first",
            before="disk",baseVersion="v1",addition="first"))
        val second=repo.propose(MemoryReviewChange("","notes","memory.md","second",
            before="disk2",baseVersion="v2",addition="second"))
        assertEquals(1,staged.size)
        assertEquals(first.id,second.id)
        assertEquals("second",staged.single().body)
        // The replacement already carries the whole text, so the accumulated addition is dropped.
        assertEquals("",staged.single().addition)
        // The batch's first baseline wins, so the applied change still matches the disk it read.
        assertEquals("disk",staged.single().before)
        assertEquals("v1",staged.single().baseVersion)
    }
    @Test fun replacedStagedNotesLandOnceWithoutRepeatingTheEarlierAddition() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        repo.proposeNotes(notes.load(),editText(notes.load().markdown,"add","first note",""),"first note")
        val replaced=repo.proposeNotes(notes.load(),
            editText(staged.single().body,"add","second note",""),"second note")
        assertEquals(1,staged.size)
        assertEquals("",staged.single().addition)
        val live=MemoryReviewRepository(context,"space")
        live.importCompletedBatch(staged)
        live.decide(context,replaced.id,true,"user","Reviewed")
        // The final text lands exactly once: no duplicate of the first addition and no extra append.
        assertEquals("first note\n\nsecond note",notes.load().markdown)
    }
    @Test fun stagedBatchKeepsDistinctTargetsSeparate() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val existing=LearnedSkillRepository.Snapshot("old","v1",true)
        repo.proposeSkillFile("demo-skill","SKILL.md",existing,"rewritten skill")
        repo.proposeSkillFile("demo-skill","references/notes.md",existing,"reference notes")
        repo.proposeSkillFile("other-skill","SKILL.md",existing,"another skill")
        assertEquals(3,staged.size)
        assertEquals(1,staged.count { it.title=="demo-skill" && it.path=="SKILL.md" })
        assertEquals(1,staged.count { it.title=="demo-skill" && it.path=="references/notes.md" })
    }
    @Test fun deletingAnInstalledSkillDiscardsEarlierFileEdits() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val before=LearnedSkillRepository.Snapshot("original","v1",true)
        repo.proposeSkillFile("demo-skill","SKILL.md",before,"new main")
        repo.proposeSkillFile("demo-skill","references/notes.md",before,"new notes")
        repo.proposeSkillFile("other-skill","SKILL.md",before,"other")
        repo.proposeSkillDeletion("demo-skill",before)
        assertEquals(listOf("skill_file","skill_delete"),staged.map { it.kind })
        assertEquals("other-skill",staged.first().title)
        assertEquals("v1",staged.last().baseVersion)
    }
    @Test fun companionFilesSurviveJournalExportAndManualRevision() = runBlocking {
        val context=context()
        val body="A repeatable procedure with prerequisites, steps and checks."
        val change=MemoryReviewChange("draft","skill","demo-skill",body,description="Demo",
            files=mapOf("references/guide.md" to "Full reference"))
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.complete(listOf("skills"),LearningCursor(9),false,listOf(change))
        MemoryLearningJournal(context,"space","chat").export()
        val repo=MemoryReviewRepository(context,"space")
        assertEquals(change.files,repo.list().single().files)
        val revised=repo.revise("draft",body+" Updated.","Updated")
        assertEquals(change.files,revised.files)
        assertEquals(change.files,repo.list().first { it.id==revised.id }.files)
    }
    @Test fun draftCompanionsRejectTraversalAndFileDirectoryConflicts() {
        for(files in listOf(mapOf("references/../outside" to "bad"),
            mapOf("references/a" to "file","references/a/b" to "nested"))) {
            try {
                com.ai.assistance.operit.data.preferences.validateDraftFiles(files)
                fail("Invalid companion paths must be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun stagedSkillCannotSwitchKindMidBatchButCanBeResubmitted() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val body="A repeatable procedure with prerequisites, steps and checks.".repeat(3)
        val created=repo.proposeSkill(SkillDraft("","demo-skill","Demo skill",body,"chat",1L))
        try {
            repo.proposeSkillFile("demo-skill","SKILL.md",LearnedSkillRepository.Snapshot("","v0",false),"rewrite")
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("demo-skill"))
        }
        val again=repo.proposeSkill(SkillDraft("","demo-skill","Demo skill v2",body+"\n- extra step","chat",2L))
        assertEquals(1,staged.size)
        assertEquals(created.id,again.id)
        assertEquals("Demo skill v2",staged.single().description)
        assertTrue(staged.single().body.endsWith("- extra step"))
    }
    @Test fun stagedRevisionReadsBackAndPatchesTheStagedText() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val disk=LearnedSkillRepository.Snapshot("# Skill\n\nalpha beta","v1",true)
        repo.proposeSkillFile("demo-skill","SKILL.md",disk,
            editText(disk.text,"replace","alpha gamma","alpha beta"))
        // The batch reads its own version back, not the untouched file, and patches that version.
        assertEquals("# Skill\n\nalpha gamma",stagedSkillFile(staged,"demo-skill","SKILL.md")!!.body)
        repo.proposeSkillFile("demo-skill","SKILL.md",LearnedSkillRepository.Snapshot("# Skill\n\nalpha beta","v2",true),
            editText(staged.single().body,"replace","done","gamma"))
        assertEquals(1,staged.size)
        assertEquals("# Skill\n\nalpha done",staged.single().body)
        // The staged file is still measured against the baseline the batch first read.
        assertEquals("v1",staged.single().baseVersion)
    }
    @Test fun draftFilesCanBeReadRevisedAndWithdrawnBeforeInstallation() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val body="A repeatable procedure with prerequisites, steps and checks."
        staged.add(MemoryReviewChange("draft","skill","demo-skill",body,description="Demo skill"))
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=false,skillsEnabled=true,
            background=true,stagedChanges=staged)
        val draft=actions.execute("skill_read",mapOf("name" to "demo-skill"))
        assertEquals(body,draft.getString("content"))
        assertTrue(draft.getBoolean("staged"))
        actions.execute("skill_patch",mapOf("name" to "demo-skill","old_text" to "checks",
            "content" to "verification checks"))
        assertTrue(staged.single().body.contains("verification checks"))
        val fileArgs=mapOf("name" to "demo-skill","path" to "references/x.md")
        assertFalse(actions.execute("skill_read",fileArgs).getBoolean("exists"))
        actions.execute("skill_write",fileArgs+("content" to "reference content"))
        assertEquals(1,staged.size)
        assertEquals("reference content",staged.single().files["references/x.md"])
        assertEquals("reference content",actions.execute("skill_read",fileArgs).getString("content"))
        actions.execute("skill_remove_file",fileArgs)
        assertTrue(staged.single().files.isEmpty())
        assertEquals("withdrawn",actions.execute("skill_delete",mapOf("name" to "demo-skill")).getString("status"))
        assertTrue(staged.isEmpty())
        assertTrue(MemoryReviewRepository(context,"space").list().isEmpty())
    }
    @Test fun deletingAConversationDropsItsPendingProgressOnly() = runBlocking {
        val context=context()
        MemoryLearningJournal(context,"space","chat-gone").enqueue(true,true,10)
        MemoryLearningJournal(context,"space","chat-kept").enqueue(true,true,10)
        MemoryLearningJournal(context,"other","chat-gone").enqueue(true,false,10)
        // A deleted conversation can never be reviewed again, so its progress must not survive to be
        // retried on the next launch, while other conversations and spaces stay untouched.
        MemoryLearningJournal.deleteChat(context,"chat-gone")
        assertEquals(setOf("space" to "chat-kept"),MemoryLearningJournal.pending(context).toSet())
    }
    @Test fun aRangeEnqueuedWhileABatchRunsKeepsItsPendingMarker() = runBlocking {
        val context=context()
        val running=MemoryLearningJournal(context,"space","chat")
        running.enqueue(true,false,10)
        // A turn arrives while the batch is talking to the model, so the same range is enqueued again
        // with a wider horizon. The batch that started earlier must not clear that newer marker.
        MemoryLearningJournal(context,"space","chat").enqueue(true,false,20)
        running.complete(listOf("notes"),LearningCursor(7),false,emptyList())
        val restored=MemoryLearningJournal(context,"space","chat")
        assertTrue(restored.pending("notes"))
        assertEquals(20,restored.horizon())
    }
    @Test fun aQuietBatchStillClearsItsOwnPendingMarker() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,false,10)
        // Nothing was enqueued after this range, so draining it must stop the retries.
        journal.complete(listOf("notes"),LearningCursor(7),false,emptyList())
        assertFalse(MemoryLearningJournal(context,"space","chat").pending("notes"))
    }
    @Test fun abandoningDoesNotClearARangeEnqueuedDuringTheBatch() = runBlocking {
        val context=context()
        val running=MemoryLearningJournal(context,"space","chat")
        running.enqueue(true,false,10)
        MemoryLearningJournal(context,"space","chat").enqueue(true,false,20)
        running.abandon(listOf("notes"))
        // The abandoned batch is older than the marker, so the marker has to stay for the newer range.
        assertTrue(MemoryLearningJournal(context,"space","chat").pending("notes"))
    }
    @Test fun anOldJournalWithoutEnqueuedAtIsStampedInsteadOfWaitingForever() = runBlocking {
        val context=context()
        MemoryLearningJournal(context,"space","chat").enqueue(true,false,10)
        val file=java.io.File(context.filesDir,"memory_learning_progress").listFiles()!!.single()
        // A journal written before the field existed carries no age at all.
        file.writeText(org.json.JSONObject(file.readText()).apply { remove("enqueued_at") }.toString())
        assertFalse(MemoryLearningJournal(context,"space","chat").pendingForAtLeast(600_000))
        // The stamp is written back, so the age counts from this read instead of never applying.
        assertTrue(file.readText().contains("enqueued_at"))
    }
    @Test fun aProposalWithHiddenCharactersAppliesOnceEvenWhenRetried() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        val repo=MemoryReviewRepository(context,"space")
        val marked="note with a bidi mark \u202E inside"
        val change=repo.proposeNotes(notes.load(),editText("","add",marked,""),marked)
        // The proposal already carries the text that will be stored, so nothing it holds is removed later.
        assertFalse(change.body.contains('\u202E'))
        assertEquals("approved",repo.decide(context,change.id,true,"user","first").status)
        val stored=notes.load().markdown
        assertFalse(stored.contains('\u202E'))
        // An approval interrupted between the write and the decision is retried for real here: the row
        // is put back to applying, so the retry runs the append instead of stopping at the decided row.
        reopenApproval(context,change.id)
        assertEquals("approved",repo.decide(context,change.id,true,"user","retry").status)
        // The retry recognises its own text in the document and does not append a second copy.
        assertEquals(stored,notes.load().markdown)
    }
    @Test fun aReplacementWithHiddenCharactersFinishesInsteadOfConflicting() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        val repo=MemoryReviewRepository(context,"space")
        val change=repo.proposeNotes(notes.load(),"alpha\u202E gamma","")
        assertEquals("approved",repo.decide(context,change.id,true,"user","first").status)
        val stored=notes.load().markdown
        assertFalse(stored.contains('\u202E'))
        // The comparison against the file and the version check both read the stripped body, so the
        // retry finishes the row instead of failing its version check forever.
        reopenApproval(context,change.id)
        assertEquals("approved",repo.decide(context,change.id,true,"user","retry").status)
        assertEquals(stored,notes.load().markdown)
    }
    @Test fun aRowStagedBeforeTheProposalBoundaryStrippedDoesNotAppendTwice() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        val repo=MemoryReviewRepository(context,"space")
        // A row staged before the proposal boundary stripped its text still carries the mark, so it is
        // built through propose() rather than proposeNotes().
        val change=repo.propose(MemoryReviewChange("","notes","memory.md","",
            addition="legacy note with a bidi mark \u202E inside"))
        assertEquals("approved",repo.decide(context,change.id,true,"user","first").status)
        val stored=notes.load().markdown
        assertFalse(stored.contains('\u202E'))
        // The edit is judged on the stored characters, so the retry recognises the note it already
        // holds instead of appending a second copy of it.
        reopenApproval(context,change.id)
        assertEquals("approved",repo.decide(context,change.id,true,"user","retry").status)
        assertEquals(stored,notes.load().markdown)
    }
    @Test fun anEditStillMatchesADocumentAnOlderVersionWroteWithAMark() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        notes.save("port 22\n\nsecond note",notes.load().version)
        // A version that predates the write point could store the mark, so its file is written that way.
        val file=java.io.File(context.filesDir,"memory_notes").listFiles()!!.single().resolve("memory.md")
        file.writeText("port\u202E 22\n\nsecond note")
        // The note the document already holds is recognised, so it is not appended a second time.
        notes.mutate("add","port 22")
        assertEquals("port 22\n\nsecond note",notes.load().markdown)
        // And a replacement whose old_text spans the mark is still found exactly once.
        file.writeText("port\u202E 22\n\nsecond note")
        notes.mutate("replace","port 2202","port\u202E 22")
        assertEquals("port 2202\n\nsecond note",notes.load().markdown)
    }
    @Test fun aRangeEnqueuedWhileABatchRunsSurvivesItsExport() = runBlocking {
        val context=context()
        val running=MemoryLearningJournal(context,"space","chat")
        running.enqueue(true,true,10)
        // The later instance stands in for the per-turn enqueue of the foreground conversation: it
        // widens the horizon and asks for skills just as this batch finishes draining notes.
        MemoryLearningJournal(context,"space","chat").enqueue(false,true,30)
        running.complete(listOf("notes"),LearningCursor(7),false,
            listOf(MemoryReviewChange("proposal-id","notes","memory.md","proposal",sourceChatId="chat")))
        assertEquals(emptyList<String>(),running.export())
        val restored=MemoryLearningJournal(context,"space","chat")
        // The newer range survives the older batch's write: the path it drained still reports the
        // enqueue that landed while it ran, skills keep their marker, and the horizon is merged
        // instead of the stale copy overwriting the newer one.
        assertEquals(30,restored.horizon())
        assertEquals(7,restored.cursor("notes").messageId)
        assertTrue(restored.pending("notes"))
        assertTrue(restored.pending("skills"))
        // The batch reached the review store, and its export released the barrier, so the next batch
        // can write instead of failing the check that guards a pending export.
        assertEquals(listOf("proposal-id"),MemoryReviewRepository(context,"space").list().map { it.id })
        restored.complete(listOf("skills"),LearningCursor(9),false,emptyList())
    }
    @Test fun abandoningARangeStopsRetriesAndKeepsTheCursor() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,false,10)
        journal.complete(listOf("notes"),LearningCursor(7),true,emptyList())
        journal.export()
        journal.abandon(listOf("notes"))
        // Cleared so nothing reschedules it, and the cursor stays put so a later trigger re-reads it.
        val restored=MemoryLearningJournal(context,"space","chat")
        assertFalse(restored.pending("notes"))
        assertEquals(7,restored.cursor("notes").messageId)
        assertTrue(MemoryLearningJournal.pending(context).isEmpty())
    }
    @Test fun abandoningAFinishedBatchDoesNotStrandItsExportBarrier() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,false,10)
        journal.complete(listOf("notes"),LearningCursor(7),true,emptyList())
        journal.abandon(listOf("notes"))
        assertEquals(7,journal.cursor("notes").messageId)
        assertEquals(0,journal.export().size)
        // The range is abandoned, but the next complete/export cycle must still work normally.
        journal.complete(listOf("notes"),LearningCursor(9),false,emptyList())
        journal.export()
        val restored=MemoryLearningJournal(context,"space","chat")
        assertFalse(restored.pending("notes"))
        assertEquals(9,restored.cursor("notes").messageId)
    }
    @Test fun notesOverCapacityTellTheReviewerToFreeSpaceFirst() = runBlocking {
        val context=context()
        // A document one add away from its cap: the capacity is checked on the staged result, so an
        // add-first attempt is rejected and the message has to say what to do instead.
        val lines=(1..272).map { "note-%03d-%s".format(Locale.ROOT,it,"a".repeat(12)) }
        val notes=MemoryNotesRepository(context,"space")
        notes.save(lines.joinToString("\n"),notes.load().version)
        val staged=mutableListOf<MemoryReviewChange>()
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=true,skillsEnabled=false,
            background=true,stagedChanges=staged)
        actions.execute("memory_read",mapOf("target" to "memory"))
        try {
            actions.execute("memory_change",mapOf("target" to "memory","operation" to "add",
                "content" to "y".repeat(50)))
            fail("an add past the capacity must be rejected")
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            for (detail in listOf("memory.md capacity exceeded", "current_chars=", "proposed_chars=",
                "max_chars=", "over_by=", "memory_read", "shorten or remove", "before retrying")) {
                assertTrue("Missing recovery detail: $detail", message.contains(detail))
            }
        }
        // A rejected change must not be staged, or the reviewer would believe it landed.
        assertTrue(staged.isEmpty())
        // Freeing space in the same batch is what the message asks for, and it must succeed.
        for(line in listOf("note-007-aaaaaaaaaaaa","note-008-aaaaaaaaaaaa")) {
            assertEquals("staged",actions.execute("memory_change",mapOf("target" to "memory",
                "operation" to "remove","old_text" to line)).optString("status"))
        }
        assertEquals("staged",actions.execute("memory_change",mapOf("target" to "memory",
            "operation" to "add","content" to "y".repeat(50))).optString("status"))
    }

    /** Notes edits go through the repository, so its failure reasons are what the reviewer reads. */
    @Test fun notesEditFailuresExplainThemselvesToTheReviewer() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        notes.save("Project A uses Kotlin. Project B uses Kotlin.",notes.load().version)
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=true,skillsEnabled=false,
            background=true,stagedChanges=mutableListOf())
        actions.execute("memory_read",mapOf("target" to "memory"))
        suspend fun change(vararg args: Pair<String,String>): String = try {
            actions.execute("memory_change",mapOf("target" to "memory")+args)
            "accepted"
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        // Explain both why the edit was refused and how to obtain a unique, current span.
        val ambiguous = change("operation" to "remove","old_text" to "Kotlin")
        val missing = change("operation" to "replace","old_text" to "missing","content" to "x")
        for (message in listOf(ambiguous, missing)) {
            assertTrue(message.contains("old_text must match exactly once"))
            assertTrue(message.contains("memory_read"))
            assertTrue(message.contains("unique span"))
        }
        assertTrue(ambiguous.contains("found more than 1 matches"))
        assertTrue(missing.contains("found 0 matches"))
        assertEquals("old_text and content are required",
            change("operation" to "remove","old_text" to " "))
        assertEquals("content is required",
            change("operation" to "add","content" to "   "))
        assertEquals("Use add/replace/remove",
            change("operation" to "append","content" to "x"))
    }

    @Test fun skillFilesCannotRemoveSkillMarkdown() = runBlocking {
        val context=context()
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=false,skillsEnabled=true,
            background=true,stagedChanges=mutableListOf())
        try {
            actions.execute("skill_remove_file",mapOf("name" to "demo-skill","path" to "SKILL.md"))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("skill_delete"))
        }
        // Omitting path means SKILL.md, so the message must point at the companion-file form.
        try {
            actions.execute("skill_remove_file",mapOf("name" to "demo-skill"))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Pass path"))
        }
    }
    @Test fun skillActionBlockOnlyRejectsCallsThatCannotSucceed() {
        assertNull(skillActionBlock("skill_write","SKILL.md"))
        assertNull(skillActionBlock("skill_patch","references/notes.md"))
        assertNull(skillActionBlock("skill_remove_file","references/notes.md"))
        assertNull(skillActionBlock("skill_delete","SKILL.md"))
        assertTrue(skillActionBlock("skill_remove_file","SKILL.md")!!.contains("skill_delete"))
    }
    @Test fun stagedLookupsIgnoreUnrelatedKindsAndPaths() {
        val changes=listOf(
            MemoryReviewChange("1","skill","demo-skill","draft"),
            MemoryReviewChange("2","skill_file","demo-skill","main",path="SKILL.md"),
            MemoryReviewChange("3","skill_file","demo-skill","notes",path="references/notes.md"),
            MemoryReviewChange("4","notes","memory.md","notes body"))
        assertEquals("main",stagedSkillFile(changes,"demo-skill","SKILL.md")!!.body)
        assertEquals("notes",stagedSkillFile(changes,"demo-skill","references/notes.md")!!.body)
        assertNull(stagedSkillFile(changes,"demo-skill","references/other.md"))
        assertEquals("draft",stagedSkillCreate(changes,"demo-skill")!!.body)
        assertNull(stagedSkillCreate(changes,"other-skill"))
        assertEquals("notes body",stagedDocument(changes,false)!!.body)
        assertNull(stagedDocument(changes,true))
    }
    @Test fun continuousBatchesCoverLongUnicodeMessageWithoutOmittingItsTail() = runBlocking {
        val context=context()
        val dao=mock<ChatContentDao>()
        val source="🙂中文".repeat(15000)+"最后的验证结果"
        val bytes=source.toByteArray()
        whenever(dao.nextLearningSource(eq("chat"),any(),eq(1L))).thenAnswer {
            if (it.getArgument<Long>(1)<=1) ChatRecallPart(1,"chat","ai",1,"",0) else null
        }
        whenever(dao.readLearningMessageBytes(eq(1L),any())).thenAnswer {
            val offset=it.getArgument<Long>(1).toInt()
            bytes.copyOfRange(offset,minOf(offset+32768,bytes.size))
        }
        val rebuilt=StringBuilder()
        MemoryLearningSource(context,dao,"chat",1,true).use { reader ->
            var cursor=LearningCursor()
            do {
                val batch=reader.next(cursor,2000)
                assertTrue(batch.text.toByteArray().size<=2000)
                rebuilt.append(batch.text.substringAfter("]\n").removeSuffix("\n"))
                cursor=batch.next
            } while (batch.more)
        }
        assertEquals(source,rebuilt.toString())
    }
}
