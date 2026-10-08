package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.data.repository.ChatRecallRepository
import org.json.JSONArray
import org.json.JSONObject

/** Scoped capabilities, shared by the isolated reviewer and foreground package tools. */
class MemoryLearningActions(
    private val context: Context, val profileId: String, private val sourceChatId: String,
    private val notesEnabled: Boolean, private val skillsEnabled: Boolean,
    private val background: Boolean,
    private val stagedChanges: MutableList<MemoryReviewChange>? = null,
    private val onCreated: () -> Unit = {}
) {
    private val skills = LearnedSkillRepository(context)
    private val reviews = MemoryReviewRepository(context,profileId,stagedChanges)
    /**
     * Versions this space has already read, kept across instances because the foreground package tool
     * builds a new [MemoryLearningActions] for every call and a per-instance map would leave it with
     * nothing to compare: the caller would have to copy a 64-character hash back by hand. A stale
     * entry is harmless, because every write still compares it with the version on disk and refuses
     * the change when the target moved on.
     */
    private companion object {
        private const val READ_VERSION_LIMIT = 128
        private val readVersions = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String,String>(READ_VERSION_LIMIT,0.75f,true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,String>?) =
                    size > READ_VERSION_LIMIT
            })
    }
    private fun readVersion(target: String): String? = readVersions["$profileId\u0000$target"]
    private fun rememberVersion(target: String, version: String) {
        readVersions["$profileId\u0000$target"] = version
    }
    private fun forgetVersion(target: String) { readVersions.remove("$profileId\u0000$target") }
    suspend fun execute(action: String, args: Map<String,String>): JSONObject {
        fun arg(name:String) = args[name].orEmpty()
        val name = arg("name")
        val path = arg("path").ifBlank { "SKILL.md" }
        return when(action) {
            "history" -> ChatRecallRepository(context).execute(args, filterAssistantThinking = true,
                includeThinking = MemorySearchSettingsPreferences(context, profileId).shouldIncludeThinking())
            "memory_read" -> {
                check(notesEnabled) { "Note extraction is not scheduled for this run. Do not retry memory operations; continue skill work or finish." }
                val user = arg("target")=="user"
                val disk = if(user) UserProfileDocumentRepository.getInstance(context).load()
                    else MemoryNotesRepository(context,profileId).load().markdown
                val staged = stagedDocument(stagedChanges, user)
                val content = staged?.body ?: disk
                // The version stays the on-disk one so a revision inside the batch keeps passing its check.
                val version = LearnedSkillRepository.version(disk)
                rememberVersion(if(user) "user" else "memory", version)
                JSONObject().put("content",content).put("version",version).put("staged",staged!=null).apply {
                    val limit = if (user) UserProfileDocumentRepository.MAX_CONTENT_CHARS else MemoryNotesRepository.MAX_CHARS
                    put("current_chars",content.length).put("max_chars",limit)
                        .put("remaining_chars",(limit-content.length).coerceAtLeast(0))
                    if (user) {
                        val sections = UserProfileSections.parse(content)
                        put("sections", JSONObject().put("profile", sections.profile)
                            .put("preferences", sections.preferences)
                            .put("interaction_rules", sections.interactionRules))
                    }
                }
            }
            "memory_change" -> {
                check(notesEnabled) { "Note extraction is not scheduled for this run. Do not retry memory operations; continue skill work or finish." }
                val user = arg("target")=="user"
                val disk = if(user) UserProfileDocumentRepository.getInstance(context).load()
                    else MemoryNotesRepository(context,profileId).load().markdown
                val key = if(user) "user" else "memory"
                // The version this run already read is the authority; an explicit argument is only a
                // fallback, so the reviewer never has to copy a 64-character hash by hand.
                check((readVersion(key) ?: arg("version"))==LearnedSkillRepository.version(disk)) {
                    "Read the current document with memory_read before changing it"
                }
                val operation = arg("operation")
                val section = arg("section")
                val content = stripInvisibleCharacters(arg("content"))
                val base = stagedDocument(stagedChanges, user)?.body ?: disk
                val after = if (user && section.isNotBlank()) {
                    val sections = UserProfileSections.parse(base)
                    sections.with(section, editText(sections.get(section), operation,
                        content, arg("old_text"))).markdown()
                } else if (user) editText(base,operation,content,arg("old_text"))
                else notesEdit(base,operation,content,arg("old_text"))
                // Emptying a whole document is never what a review meant to express, and it is the
                // one edit whose result cannot be told apart from a lost file afterwards.
                require(disk.isBlank() || after.isNotBlank()) {
                    "Refusing to empty ${if (user) "user.md" else "memory.md"}; remove the wrong entries " +
                        "instead of the whole document"
                }
                val change = if(user) {
                    require(after.length<=UserProfileDocumentRepository.MAX_CONTENT_CHARS) {
                        memoryCapacityError("user.md",base.length,after.length,UserProfileDocumentRepository.MAX_CONTENT_CHARS)
                    }
                    reviews.proposeUser(disk,after,sourceChatId,onCreated)
                } else {
                    val repo = MemoryNotesRepository(context,profileId)
                    val before = repo.load()
                    check(before.markdown==disk)
                    require(after.length<=MemoryNotesRepository.MAX_CHARS) {
                        memoryCapacityError("memory.md",base.length,after.length,MemoryNotesRepository.MAX_CHARS)
                    }
                    reviews.proposeNotes(before,after,if(operation=="add") content else "",sourceChatId,onCreated)
                }
                val applied = reviews.applyAutomaticDecision(context, change)
                if (stagedChanges==null) forgetVersion(key)
                reviews.toModelJson(applied)
            }
            "skill_list" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                val owned = skills.owned(profileId)
                JSONObject().put("skills",JSONArray().apply {
                    SkillManager.getInstance(context).getAvailableSkills().values.forEach {
                        put(JSONObject().put("name",it.name).put("description",it.description)
                            .put("learned_in_this_space",it.name in owned))
                    }
                    stagedChanges?.filter { it.kind=="skill" }?.forEach {
                        put(JSONObject().put("name",it.title).put("description",it.description)
                            .put("learned_in_this_space",true).put("staged",true))
                    }
                })
            }
            "skill_read" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                val created = stagedSkillCreate(stagedChanges, name)
                if (created != null) {
                    LearnedSkillRepository.validatePath(path)
                    val content = if(path=="SKILL.md") created.body else created.files[path]
                    rememberVersion("$name/$path", "draft")
                    return JSONObject().put("content", content.orEmpty()).put("version","draft")
                        .put("exists",content!=null).put("staged",true)
                        .put("files",JSONArray(listOf("SKILL.md")+created.files.keys))
                        .put("directory_version","")
                }
                val snapshot = skills.read(name,path)
                rememberVersion("$name/$path", snapshot.version)
                val staged = stagedSkillFile(stagedChanges,name,path)
                JSONObject().put("content",staged?.body ?: snapshot.text).put("version",snapshot.version)
                    .put("exists",snapshot.exists).put("files",JSONArray(skills.files(name)))
                    .put("staged",staged!=null)
                    .put("directory_version",skills.readDirectory(name).also { rememberVersion("$name/", it.version) }.version)
            }
            "skill_create" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                require(Regex("[a-z][a-z0-9-]{2,63}").matches(name)) {
                    "Skill name must be 3-64 lowercase ASCII letters, digits or hyphens, start with a letter; underscores are not allowed"
                }
                require(arg("description").trim().length in 1..LearnedSkillRepository.MAX_SKILL_DESCRIPTION_CHARS &&
                    !arg("description").contains('\n')) {
                    "Skill description must be one line, 1-${LearnedSkillRepository.MAX_SKILL_DESCRIPTION_CHARS} characters"
                }
                // Frontmatter is composed for you, so a supplied header is stripped before the
                // length check; otherwise this would accept a draft the installer rejects.
                val body = stripSkillFrontmatter(stripInvisibleCharacters(arg("content").trim()))
                validateDraftBody(body)
                val parsed = parseSkillDrafts(JSONArray().put(JSONObject().put("name",name)
                    .put("description",arg("description")).put("body",body)),sourceChatId)
                require(parsed.size==1) { "Invalid skill draft" }
                check(SkillManager.getInstance(context).getAvailableSkills()[name]==null) { "Update the existing skill instead" }
                reviews.toModelJson(reviews.applyAutomaticDecision(context, reviews.proposeSkill(parsed.single(),onCreated,
                    stagedSkillCreate(stagedChanges,name)?.files.orEmpty())))
            }
            "skill_delete" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                if (stagedSkillCreate(stagedChanges,name)!=null) {
                    stagedChanges!!.removeAll { it.title==name && it.kind in setOf("skill","skill_file") }
                    return JSONObject().put("status","withdrawn").put("name",name)
                        .put("message","Draft withdrawn. No installed skill was deleted.")
                }
                if (background) check(name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()) {
                    "Background deletion is limited to enabled learned skills in this space"
                }
                val before=skills.readDirectory(name)
                check((readVersion("$name/") ?: arg("version"))==before.version) {
                    "Call skill_read with this name before proposing deletion, then retry"
                }
                reviews.toModelJson(reviews.applyAutomaticDecision(context,
                    reviews.proposeSkillDeletion(name,before,sourceChatId,onCreated)))
            }
            "skill_write","skill_patch","skill_remove_file" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                skillActionBlock(action,path)?.let { error(it) }
                val content = stripInvisibleCharacters(arg("content"))
                stagedSkillCreate(stagedChanges,name)?.let { created ->
                    LearnedSkillRepository.validatePath(path)
                    check(readVersion("$name/$path")=="draft") {
                        skillReadRequired(name,path)
                    }
                    val before = if(path=="SKILL.md") created.body else created.files[path].orEmpty()
                    val text = if(action=="skill_patch") editText(before,"replace",content,arg("old_text"))
                        else content
                    val updated = if(path=="SKILL.md") {
                        val body=stripSkillFrontmatter(text.trim())
                        validateDraftBody(body)
                        created.copy(body=body)
                    } else {
                        val files=created.files.toMutableMap()
                        if(action=="skill_remove_file") files.remove(path) else files[path]=text
                        validateDraftFiles(files)
                        created.copy(files=files)
                    }
                    return reviews.toModelJson(reviews.applyAutomaticDecision(context,reviews.propose(updated)))
                }
                check(stagedChanges?.none { it.kind=="skill_delete" && it.title==name } != false) {
                    "This batch already deletes the whole skill; do not edit its files."
                }
                val before = skills.read(name,path)
                check((readVersion("$name/$path") ?: arg("version"))==before.version) {
                    skillReadRequired(name,path)
                }
                val remove = action=="skill_remove_file"
                // A revision patches the version this batch staged, not the untouched file on disk.
                val text = if(action=="skill_patch") editText(stagedSkillFile(stagedChanges,name,path)?.body ?: before.text,"replace",content,arg("old_text"))
                    else content
                val automatic = background && !remove && name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()
                if (background) check(name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()) {
                    "Background revision is limited to enabled learned skills in this space"
                }
                val change = reviews.proposeSkillFile(name,path,before,text,remove,automatic,sourceChatId,onCreated)
                val applied = reviews.applyAutomaticDecision(context,change)
                if (stagedChanges==null) forgetVersion("$name/$path")
                reviews.toModelJson(applied)
            }
            "memory_learning_finish" -> error("memory_learning_finish is a separate tool, not an action. Call it directly without arguments; this call did not finish the review.")
            else -> error("Unknown learning action; use an action listed in memory_learning_action's description")
        }
    }
}

/**
 * Note edits use the repository's own rules, so the extraction tool and the direct writer cannot
 * disagree about duplicates, ambiguity or empty text. Only the failure wording is local.
 */
private fun notesEdit(base: String, operation: String, content: String, oldText: String): String =
    try {
        MemoryNotesRepository.applyEdit(base, operation, content, oldText)
    } catch (e: MemoryNotesRepository.NotesException) {
        // Kept as IllegalArgumentException: that is the type the notes path raised before this was
        // single-sourced, and the surrounding tool layers only ever catch the message.
        throw IllegalArgumentException(when (e.reason) {
            MemoryNotesRepository.Failure.EMPTY ->
                if (operation == "add") "content is required" else "old_text and content are required"
            MemoryNotesRepository.Failure.NOT_UNIQUE -> exactEditError(
                stripInvisibleCharacters(base),stripInvisibleCharacters(oldText))
            MemoryNotesRepository.Failure.CONFLICT -> "memory.md changed while this batch ran; read it again"
            MemoryNotesRepository.Failure.FULL -> MemoryNotesRepository.OVERFLOW_MESSAGE
            MemoryNotesRepository.Failure.INVALID -> "Use add/replace/remove"
        })
    }

/** What this batch staged for a skill file, so reads and revisions see it instead of the disk copy. */
internal fun stagedSkillFile(changes: List<MemoryReviewChange>?, name: String, path: String) =
    changes?.lastOrNull { it.kind == "skill_file" && it.title == name && it.path == path }
/** A skill created earlier in this batch; it is not on disk yet, so it has no version or file list. */
internal fun stagedSkillCreate(changes: List<MemoryReviewChange>?, name: String) =
    changes?.lastOrNull { it.kind == "skill" && it.title == name }
internal fun stagedDocument(changes: List<MemoryReviewChange>?, user: Boolean) =
    changes?.lastOrNull { it.kind == if (user) "user" else "notes" }
/**
 * SKILL.md is never removable on its own, whether staged or installed.
 */
internal fun skillActionBlock(action: String, path: String): String? = when {
    action=="skill_remove_file" && path=="SKILL.md" ->
        "Pass path to remove one companion file under references/, scripts/, templates/ or assets/, " +
            "or delete the whole skill with skill_delete."
    else -> null
}

private fun validateDraftBody(body: String) {
    require(body.length in LearnedSkillRepository.MIN_SKILL_BODY_CHARS..LearnedSkillRepository.MAX_SKILL_BODY_CHARS) {
        "Skill body has ${body.length} characters; expected ${LearnedSkillRepository.MIN_SKILL_BODY_CHARS}-" +
            "${LearnedSkillRepository.MAX_SKILL_BODY_CHARS}. Keep a concise main procedure; move longer material " +
            "to companion files with skill_write after skill_create. YAML frontmatter is added automatically."
    }
}

internal fun editText(current: String, operation: String, content: String, old: String): String = when(operation) {
    "add" -> {
        require(content.isNotBlank())
        if (("\n\n$current\n\n").contains("\n\n${content.trim()}\n\n")) current
        else listOf(current.trimEnd(),content.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    "replace","remove" -> {
        require(old.isNotBlank()) { "old_text is required; read the current content before editing" }
        val at = current.indexOf(old)
        require(at>=0 && current.indexOf(old,at+1)<0) { exactEditError(current,old) }
        current.replaceRange(at,at+old.length,if(operation=="remove") "" else content)
    }
    else -> error("Use add/replace/remove")
}

internal fun memoryCapacityError(document: String, before: Int, after: Int, limit: Int) =
    "$document capacity exceeded: current_chars=$before, proposed_chars=$after, max_chars=$limit, " +
        "over_by=${after-limit}. Read the latest staged content with memory_read, then shorten or remove " +
        "existing text by at least ${after-limit} characters before retrying. A replacement can also exceed the limit."

internal fun exactEditError(current: String, old: String): String {
    val match = if (old.isEmpty() || !current.contains(old)) "0" else "more than 1"
    return "old_text must match exactly once; found $match matches. Read the latest staged content with " +
        "memory_read or skill_read and copy an exact, unique span (include more surrounding text if ambiguous)."
}

private fun skillReadRequired(name: String, path: String) =
    "Read the current file first: action=skill_read, arguments=" +
        JSONObject().put("name",name).put("path",path).toString() + "; then retry the edit."
