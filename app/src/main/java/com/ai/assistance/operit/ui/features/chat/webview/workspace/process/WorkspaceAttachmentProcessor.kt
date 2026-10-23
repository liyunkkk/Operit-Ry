package com.ai.assistance.operit.ui.features.chat.webview.workspace.process

import android.content.Context
import com.ai.assistance.operit.R

object WorkspaceAttachmentProcessor {
    /**
     * 工作区附着文本随每条消息下发。
     *
     * 系统提示词里的工作区指南只在重建前缀时生成，中途附着工作区不会重建（保护上下文缓存），
     * 所以这里必须带上工作区路径，模型才能在没有重建的情况下知道工作区根目录。
     */
    fun generateWorkspaceAttachment(
        context: Context,
        workspaceEnv: String?,
        workspacePath: String?,
    ): String {
        val workspaceTag = workspaceEnv?.trim().orEmpty().ifBlank { "android" }
        val workspaceRoot = workspacePath?.trim().orEmpty()
        return buildString {
            appendLine(context.getString(R.string.workspace_attachment_attached))
            if (workspaceRoot.isNotEmpty()) {
                appendLine(context.getString(R.string.workspace_attachment_path, escapeText(workspaceRoot)))
            }
            appendLine(context.getString(R.string.workspace_attachment_environment, escapeText(workspaceTag)))
        }.trim()
    }

    private fun escapeText(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
