package com.ai.assistance.operit.api.chat.llmprovider

/**
 * Antigravity 公开模型名到 Cloud Code Assist 运行时模型名的路由表。
 *
 * 意图：CCA 的 wire 模型 ID 与 Antigravity 设置界面展示的公开 ID 并不一致，
 * 只按原名发送会命中不存在的模型。
 * 不这么做后果：部分模型（尤其是 tiered flash 与 thinking 后缀的 Claude 系列）会返回 404/404 类错误。
 *
 * 来源：dsh-antigravity 的静态路由表。
 */
internal object AntigravityModelRouting {
    private val runtimeModelIds = mapOf(
        "gemini-3.7-flash" to "gemini-3.7-flash-tiered",
        "gemini-3.6-flash" to "gemini-3.6-flash",
        "gemini-3.5-flash" to "gemini-3.5-flash",
        "gemini-3.1-pro" to "gemini-3.1-pro",
        "gemini-3.1-flash-image" to "gemini-3.1-flash-image",
        "gemini-3-flash" to "gemini-3-flash",
        "gemini-2.5-pro" to "gemini-2.5-pro",
        "gemini-2.5-flash" to "gemini-2.5-flash",
        "claude-opus-4-6" to "claude-opus-4-6-thinking",
        "claude-sonnet-4-6" to "claude-sonnet-4-6-thinking",
        "gpt-oss-120b" to "gpt-oss-120b",
    )

    fun runtimeModelId(publicModelId: String): String {
        val normalized = publicModelId.trim()
        return runtimeModelIds[normalized] ?: normalized
    }
}
