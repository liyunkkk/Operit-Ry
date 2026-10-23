package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.DownloadResource
import com.ai.assistance.operit.util.OnDemandResources
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Reviewed immutable model revisions. Downloads always use the shared confirmation/checksum flow. */
internal object LocalVoiceModels {
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withModelFiles(context: Context, id: String, block: suspend (File) -> T): T =
        fileLocks.getOrPut(id) { Mutex() }.withLock {
            block(ensure(context, id))
        }

    suspend fun storedBytes(context: Context, id: String): Long = withContext(Dispatchers.IO) {
        val directory = find(id).directory(context)
        directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        fileLocks.getOrPut(id) { Mutex() }.withLock {
            val directory = find(id).directory(context)
            OnDemandResources.deleteVoiceModel(context, id) {
                // Only direct files in a catalog-owned directory, including partial downloads.
                directory.listFiles()?.forEach {
                    check(it.isFile && it.delete()) { context.getString(R.string.voice_model_delete_failed) }
                }
                check(!directory.exists() || directory.delete()) {
                    context.getString(R.string.voice_model_delete_failed)
                }
            }
        }
    }
    data class Model(
        val id: String, val name: String, val descriptionRes: Int, val recognition: Boolean,
        val repo: String, val revision: String, val files: List<DownloadResource>,
    ) {
        val bytes: Long get() = files.sumOf { it.bytes }
        fun directory(context: Context) = File(context.filesDir, "local_voice_models/$id")
        fun downloaded(context: Context): Boolean =
            files.all { File(directory(context), it.id).let { f -> f.isFile && f.length() == it.bytes } }
    }
    private fun model(id: String, name: String, description: Int, asr: Boolean,
                      repo: String, revision: String, vararg entries: Triple<String, Long, String>) =
        Model(id, name, description, asr, repo, revision, entries.map { (file, size, sha) ->
            DownloadResource(file, "https://huggingface.co/csukuangfj/$repo/resolve/$revision/$file", size, sha)
        })
    private val rules = arrayOf(
        Triple("date.fst",59154L,"eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
        Triple("number.fst",64482L,"743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
        Triple("phone.fst",88630L,"1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
    )
    val models = listOf(
        model("sensevoice-int8","SenseVoice Small",R.string.voice_model_sensevoice,true,
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17","2365baeacb507f821a0c8120fcee3d484dba7a07",
            Triple("model.int8.onnx",239233841L,"c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            Triple("tokens.txt",315894L,"f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")),
        model("paraformer-small-int8","Paraformer Small",R.string.voice_model_paraformer,true,
            "sherpa-onnx-paraformer-zh-small-2024-03-09","63ddc3cd0f2810b68289a7b3876e62ef5d53d6df",
            Triple("model.int8.onnx",81828675L,"3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec"),
            Triple("tokens.txt",75352L,"4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa")),
        model("aishell3-int8","AISHELL3",R.string.voice_model_aishell,false,
            "vits-zh-aishell3","e3e808eaab2385b812286c6707323362251bba65",
            Triple("vits-aishell3.int8.onnx",39870124L,"5ef667dbbe0795688da93d779828cf38e6ad8ef8e82aa7dc5804873a50556057"),
            Triple("tokens.txt",1671L,"50b45a7b7de1752fd3c7b4755661c285f1547f59186eca2281089a81307ad953"),
            Triple("lexicon.txt",2042943L,"ab2e61d357551e7b24ddd965d924aca784c20165ff58c150794e539c6b5e9e35"), *rules),
        model("melo-int8","Melo TTS",R.string.voice_model_melo,false,
            "vits-melo-tts-zh_en","a0d5c6a264c0ef92d70d8661d8cc502d79627cd6",
            Triple("model.int8.onnx",53517430L,"f085f5079e05f039b800aeb542f5253c26a303211b0c6465d0d9387977855a63"),
            Triple("tokens.txt",655L,"d18664a7e12bd7ea1022ddaf951e534e136815016c5a809d6b64156bffb4369d"),
            Triple("lexicon.txt",6837671L,"7236884b02435ac5d10cf69b4be40a61b45aa676b5300f0e412f185748fee528"), *rules),
    )
    fun find(id: String) = models.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown local speech model: $id")
    suspend fun ensure(context: Context, id: String): File {
        val model = find(id)
        return OnDemandResources.ensureVoiceModel(context, model.id, model.directory(context), model.files)
    }
}
