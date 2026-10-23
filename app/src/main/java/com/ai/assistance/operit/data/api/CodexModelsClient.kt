package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.model.ModelOption
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** The account's picker-visible Codex catalog, following the Codex client's /models request. */
class CodexModelsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetch(accessToken: String, accountId: String, residency: String?): List<ModelOption> =
        withContext(Dispatchers.IO) {
            val clientVersion = BuildConfig.VERSION_NAME.substringBefore('-')
            val request = Request.Builder()
                .url("${CodexOAuthProtocol.CODEX_MODELS_ENDPOINT}?client_version=$clientVersion")
                .get()
                .header("Authorization", "Bearer $accessToken")
                .header("ChatGPT-Account-ID", accountId)
                .header("originator", "operit")
                .header("User-Agent", "Operit/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .apply {
                    residency?.let { header("x-openai-internal-codex-residency", it) }
                }
                .build()
            val json = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CodexHttpException(
                    response.code, isCodexExpiredToken(response.code, response.peekBody(16_384).string()))
                val source = response.body?.source() ?: throw IOException("Codex model catalog is empty")
                if (source.request(MAX_BYTES + 1)) throw IOException("Codex model catalog is too large")
                source.readUtf8()
            }
            parseModels(json)
        }

    companion object {
        private const val MAX_BYTES = 10L * 1024 * 1024

        internal fun parseModels(json: String): List<ModelOption> {
            val array = JSONObject(json).getJSONArray("models")
            val models = mutableListOf<Triple<Int, String, ModelOption>>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("visibility") != "list") continue
                val id = item.optString("slug").trim()
                if (id.isBlank()) continue
                val name = item.optString("display_name").trim().ifBlank { id }
                val priority = item.optInt("priority", Int.MAX_VALUE)
                models += Triple(priority, id, ModelOption(id, name))
                val speedTiers = item.optJSONArray("additional_speed_tiers")
                val serviceTiers = item.optJSONArray("service_tiers")
                val hasFast =
                    (speedTiers != null &&
                        (0 until speedTiers.length()).any { speedTiers.optString(it) == "fast" }) ||
                        (serviceTiers != null &&
                            (0 until serviceTiers.length()).any {
                                serviceTiers.optJSONObject(it)?.optString("id") == "priority"
                            })
                if (hasFast) {
                    models += Triple(priority, "$id-fast", ModelOption("$id-fast", "$name Fast"))
                }
            }
            return models.sortedWith(compareBy<Triple<Int, String, ModelOption>> { it.first }.thenBy { it.second })
                .map { it.third }.distinctBy { it.id }
        }
    }
}
