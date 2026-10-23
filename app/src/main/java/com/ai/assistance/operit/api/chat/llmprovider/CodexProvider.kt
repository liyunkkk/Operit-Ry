package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.data.api.CodexOAuthProtocol
import com.ai.assistance.operit.data.api.CodexModelsClient
import com.ai.assistance.operit.data.api.CodexAuthLease
import com.ai.assistance.operit.data.api.CodexAuthenticationException
import com.ai.assistance.operit.data.api.isCodexExpiredToken
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelProtocolCatalogRepository
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private class CodexAccessTokenProvider(
    private val authManager: CodexAuthManager,
) : ApiKeyProvider {
    override suspend fun getApiKey(): String = authManager.getValidAccessToken()

    override suspend fun getCandidateKeyCount(): Int =
        if (authManager.authState.value == null) 0 else 1
}

class CodexProvider(
    private val authManager: CodexAuthManager,
    modelName: String,
    private val httpClient: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    supportsFiles: Boolean = false,
    enableToolCall: Boolean = false,
) : OpenAIResponsesProvider(
    responsesApiEndpoint = CodexOAuthProtocol.CODEX_RESPONSES_ENDPOINT,
    apiKeyProvider = CodexAccessTokenProvider(authManager),
    modelName = modelName,
    client = httpClient,
    customHeaders = customHeaders,
    responsesProviderType = ApiProviderType.OPENAI_CODEX,
    supportsVision = supportsVision,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    supportsFiles = supportsFiles,
    enableToolCall = enableToolCall,
) {
    override val requiresStreamingResponse: Boolean = true

    override suspend fun recoverUnauthorizedAuthentication(
        request: Request, status: Int, body: String, recoveryAllowed: Boolean,
    ): Boolean {
        if (!isCodexExpiredToken(status, body)) {
            throw CodexAuthenticationException(authManager.authMessages.loginRequired)
        }
        if (!recoveryAllowed) {
            com.ai.assistance.operit.util.AppLogger.w("CodexAuthManager", "401 recovery rejected; stopping retries")
            throw CodexAuthenticationException(authManager.authMessages.recoveryRejected)
        }
        val lease = request.tag(CodexAuthLease::class.java)
            ?: throw CodexAuthenticationException(authManager.authMessages.loginChanged)
        authManager.refreshAfterUnauthorized(lease)
        com.ai.assistance.operit.util.AppLogger.i("CodexAuthManager", "401 recovery ready; rebuilding request")
        return true
    }

    override fun checkAuthenticationContinuity(previous: Request?, current: Request) {
        val first = previous?.tag(CodexAuthLease::class.java) ?: return
        val latest = current.tag(CodexAuthLease::class.java)
        if (latest == null || first.generation != latest.generation ||
            first.state.accountId != latest.state.accountId) {
            throw CodexAuthenticationException(authManager.authMessages.loginChanged)
        }
    }

    override suspend fun authenticateRequest(builder: Request.Builder) {
        val lease = authManager.credentials()
        super.applyAuthenticationHeaders(builder, lease.state.accessToken)
        val account = lease.state
        builder.tag(CodexAuthLease::class.java, lease)
        builder.header("ChatGPT-Account-ID", account.accountId)
        builder.header("originator", "operit")
        builder.header("User-Agent", "Operit/${BuildConfig.VERSION_NAME}")
        builder.header("session-id", currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: sessionId)
        account.residency?.let { residency ->
            builder.header("x-openai-internal-codex-residency", residency)
        }
    }

    override fun customizeFinalRequestObject(
        requestObject: JSONObject,
        messagesArray: JSONArray,
        toolsJson: String?,
    ) {
        super.customizeFinalRequestObject(requestObject, messagesArray, toolsJson)
        CodexModelVariant.applyRequestParameters(requestObject, modelName)
        requestObject.put("store", false)
        requestObject.put("parallel_tool_calls", false)
        CodexRequestAdapter.moveSystemInstructions(requestObject)
        val include = requestObject.optJSONArray("include") ?: JSONArray().also {
            requestObject.put("include", it)
        }
        if (!containsString(include, "reasoning.encrypted_content")) {
            include.put("reasoning.encrypted_content")
        }
    }

    override suspend fun getModelsList(_context: Context): Result<List<ModelOption>> {
        return CodexModelListFetcher.getModelsList(_context)
    }

    private val sessionId = UUID.randomUUID().toString()

    private fun containsString(array: JSONArray, value: String): Boolean {
        for (index in 0 until array.length()) {
            if (array.optString(index) == value) return true
        }
        return false
    }
}

object CodexModelPolicy {
    private val explicitlyAllowed = setOf(
        "gpt-5.5",
        "gpt-5.3-codex-spark",
        "gpt-5.4",
        "gpt-5.4-mini",
    )
    private val explicitlyDisallowed = setOf("gpt-5.5-pro")
    private val versionPattern = Regex("^gpt-(\\d+\\.\\d+)")

    fun allows(modelId: String, reasoningMode: String? = null): Boolean {
        val normalized = modelId.trim().lowercase()
        if (reasoningMode?.trim()?.equals("pro", ignoreCase = true) == true) return false
        if (normalized in explicitlyDisallowed) return false
        if (normalized in explicitlyAllowed) return true
        if (normalized == "gpt-5.6") return false
        val version = versionPattern.find(normalized)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return false
        return version > 5.4
    }
}

internal object CodexModelVariant {
    private const val FAST_SUFFIX = "-fast"

    fun applyRequestParameters(requestObject: JSONObject, modelName: String) {
        val apiModelId = apiModelId(modelName)
        if (apiModelId == modelName) return

        requestObject.put("model", apiModelId)
        requestObject.put("service_tier", "priority")
    }

    fun apiModelId(modelName: String): String = modelName.removeSuffix(FAST_SUFFIX)
}

object CodexModelListFetcher {
    // The bundled models.dev directory can omit OpenAI. Keep the upstream Codex baseline usable
    // offline; this list is not a claim about the signed-in account's model entitlement.
    private val bundledModels = listOf(
        ModelOption("gpt-5.5", "GPT-5.5"),
        ModelOption("gpt-5.4", "GPT-5.4"),
        ModelOption("gpt-5.4-mini", "GPT-5.4 Mini"),
        ModelOption("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark"),
    )
    suspend fun getModelsList(
        context: Context,
    ): Result<List<ModelOption>> {
        return try {
            val auth = CodexAuthManager.getInstance(context)
            if (auth.authState.value != null) {
                val models = auth.withAuthRecovery { account ->
                    CodexModelsClient().fetch(account.accessToken, account.accountId, account.residency)
                }
                return Result.success(models)
            }
            val responseBody = ModelProtocolCatalogRepository(context).loadCatalogJson()
            Result.success(parseModels(responseBody))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    internal fun parseModels(responseBody: String): List<ModelOption> {
        val root = JSONObject(responseBody)
        val openAiProvider = root.optJSONObject("openai")
            ?: return bundledModels
        val models = openAiProvider.optJSONObject("models")
            ?: return bundledModels
        val result = mutableListOf<ModelOption>()
        val modelKeys = models.keys()
        while (modelKeys.hasNext()) {
            val modelKey = modelKeys.next()
            val model = models.optJSONObject(modelKey) ?: continue
            val id = model.optString("id").trim().ifBlank { modelKey }
            val name = model.optString("name").trim().ifBlank { id }
            if (id.isBlank() || name.isBlank() || !CodexModelPolicy.allows(id)) {
                continue
            }
            result += ModelOption(id = id, name = name)

            val modes = model.optJSONObject("experimental")?.optJSONObject("modes") ?: continue
            val modeKeys = modes.keys()
            while (modeKeys.hasNext()) {
                val mode = modeKeys.next().trim()
                if (mode.isBlank()) continue
                val modeConfig = modes.optJSONObject(mode) ?: continue
                val reasoningMode = modeConfig
                    .optJSONObject("provider")
                    ?.optJSONObject("body")
                    ?.optJSONObject("reasoning")
                    ?.optString("mode")
                    ?.trim()
                if (!CodexModelPolicy.allows(id, reasoningMode)) continue

                result += ModelOption(
                    id = "$id-$mode",
                    name = "$name ${mode.replaceFirstChar { it.uppercaseChar() }}",
                )
            }
        }
        return result.ifEmpty { bundledModels }
    }
}

internal object CodexRequestAdapter {
    fun moveSystemInstructions(request: JSONObject) {
        val input = request.optJSONArray("input") ?: JSONArray()
        val retained = JSONArray()
        val instructions = mutableListOf<String>()
        request.optString("instructions").takeIf { it.isNotBlank() }?.let(instructions::add)
        for (index in 0 until input.length()) {
            val item = input.optJSONObject(index)
            // The generic Responses adapter maps system messages to developer messages first.
            if (item != null && item.optString("role") in setOf("system", "developer")) {
                when (val content = item.opt("content")) {
                    is String -> instructions.add(content)
                    is JSONArray -> for (partIndex in 0 until content.length()) {
                        content.optJSONObject(partIndex)?.optString("text")
                            ?.takeIf { it.isNotEmpty() }?.let(instructions::add)
                    }
                }
            } else retained.put(input.get(index))
        }
        request.put("instructions", instructions.joinToString("\n\n"))
        request.put("input", retained)
    }
}
