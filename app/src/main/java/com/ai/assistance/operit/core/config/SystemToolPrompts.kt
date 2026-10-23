package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.core.agent.AgentProfile
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolParameterSchema

/**
 * 系统工具提示词管理器
 * 包含所有工具的结构化定义
 */
object SystemToolPrompts {

    private val nativeTodoToolsEn =
        SystemToolPromptCategory(
            categoryName = "Todo",
            tools =
                listOf(
                    ToolPrompt(
                        name = "todowrite",
                        description = "Replace the current chat's native Todo list with a complete updated snapshot. The user sees this list in the chat UI.",
                        parametersStructured =
                            listOf(
                                ToolParameterSchema(
                                    name = "todos",
                                    type = "string",
                                    description = "JSON-encoded complete ordered array of objects: {content, status, priority}; status is pending | in_progress | completed | cancelled; priority is high | medium | low",
                                    required = true,
                                )
                            ),
                        details = """
  - Use this tool for work with at least three distinct steps, multiple user requests, or other non-trivial execution. Skip it for simple one-step or informational responses.
  - Every call replaces the entire list, so always include every Todo item in order. Never send only the changed item.
  - Each agent owns the Todo list of its own chat. Subagent updates do not change the parent or sibling agents' lists; track only your assigned work.
  - At most one item may be `in_progress`. While waiting for user input or an external dependency, unfinished items may all be `pending`. Mark work `completed` only after it and its required verification are actually finished.
  - Update statuses as progress changes instead of batching updates at the end. Keep completed items in the list so the chat can retain its final progress record.
  - This tool is host-managed and read-only to the user.
""",
                    )
                ),
        )

    private val nativeTodoToolsCn =
        SystemToolPromptCategory(
            categoryName = "Todo",
            tools =
                listOf(
                    ToolPrompt(
                        name = "todowrite",
                        description = "用完整的新快照替换当前聊天的原生 Todo 列表；用户会在聊天界面看到该列表。",
                        parametersStructured =
                            listOf(
                                ToolParameterSchema(
                                    name = "todos",
                                    type = "string",
                                    description = "JSON 编码的完整有序对象数组：{content, status, priority}；status 为 pending | in_progress | completed | cancelled；priority 为 high | medium | low",
                                    required = true,
                                )
                            ),
                        details = """
  - 当任务包含至少三个独立步骤、多个用户要求或其他非简单执行时使用；简单的一步操作或仅回答信息时不要使用。
  - 每次调用都会替换整个列表，因此必须按顺序传入全部 Todo，不能只传发生变化的一项。
  - 每个代理独立维护自己聊天的 Todo；子代理更新不会修改父代理或兄弟代理的列表，只跟踪分配给自己的工作。
  - 最多一项为 `in_progress`；等待用户回复或外部条件时，未完成项可以全部为 `pending`。只有任务及必要验证确实完成后，才能标为 `completed`。
  - 进展发生时立即更新状态，不要全部堆到最后更新。保留已完成项，让聊天持久保存最终进度记录。
  - 这是宿主管理、用户只读的工具。
""",
                    )
                ),
        )

    private fun buildSafBookmarksSectionEn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString(", ") { "repo:$it" }
        return """

**Attached Local Storage Repository:**
- environment (optional): you can also use `environment="repo:<repositoryName>"` to operate in an attached local storage repository.
- Paths are absolute (e.g., `/`, `/work/index.html`).
- Available repositories: $listed
""".trimEnd()
    }

    private fun buildSafBookmarksSectionCn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString("、") { "repo:$it" }
        return """

**附加本地储存仓库：**
- environment（可选）：也可以使用 `environment="repo:<仓库名>"` 在附加本地储存仓库中操作。
- 路径使用绝对路径（例如 `/`、`/work/index.html`）。
- 当前可用仓库：$listed
""".trimEnd()
    }
    
    // ==================== 基础工具 ====================
    val basicTools = SystemToolPromptCategory(
        categoryName = "Available tools",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "Demonstration tool that pauses briefly.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "milliseconds, default 1000, >= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "Activate a package for use in the current session.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "package_name",
                        type = "string",
                        description = "name of the package to activate",
                        required = true
                    )
                )
            )
        )
    )
    
    val basicToolsCn = SystemToolPromptCategory(
        categoryName = "可用工具",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "演示工具，短暂暂停。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "毫秒，默认1000，>= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "在当前会话中激活包。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "package_name", type = "string", description = "要激活的包名", required = true)
                )
            )
        )
    )

    // ==================== Subagent 工具 ====================
    val subagentTools: SystemToolPromptCategory
        get() = buildSubagentTools(useChinese = false)

    val subagentToolsCn: SystemToolPromptCategory
        get() = buildSubagentTools(useChinese = true)

    internal fun buildSubagentTools(
        useChinese: Boolean,
        profiles: List<AgentProfile> = AgentProfileRepository.instance.listAvailableSubagents(),
    ): SystemToolPromptCategory {
        val profileIds = profiles.joinToString(if (useChinese) "、" else ", ") { it.id }
        val profileDetails =
            profiles.joinToString(separator = "\n") { profile ->
                "- ${profile.id} (${profile.name}): ${profile.description}"
            }
        val description =
            if (useChinese) {
                "把前台任务委派给独立 Subagent，并等待其最终结果。可用的 subagent_type：$profileIds；传入 task_id 可继续已有 child 对话。\n$profileDetails"
            } else {
                "Delegate a foreground task to an independent Subagent and wait for its final result. Available subagent_type values: $profileIds. Use task_id to continue an existing child conversation.\n$profileDetails"
            }
        val typeDescription =
            if (useChinese) {
                "Subagent 配置 ID：$profileIds"
            } else {
                "Subagent profile ID: $profileIds"
            }
        return SystemToolPromptCategory(
            categoryName = if (useChinese) "Subagent 工具" else "Subagent Tools",
            tools =
                listOf(
                    ToolPrompt(
                        name = "task",
                        description = description,
                        parametersStructured =
                            listOf(
                                ToolParameterSchema(
                                    name = "title",
                                    type = "string",
                                    description =
                                        if (useChinese) {
                                            "显示在工具调用 UI 中的任务名称"
                                        } else {
                                            "task name shown in the tool call UI"
                                        },
                                    required = true,
                                ),
                                ToolParameterSchema(
                                    name = "prompt",
                                    type = "string",
                                    description =
                                        if (useChinese) {
                                            "交给 Subagent 的完整任务说明"
                                        } else {
                                            "complete task instructions for the Subagent"
                                        },
                                    required = true,
                                ),
                                ToolParameterSchema(
                                    name = "subagent_type",
                                    type = "string",
                                    description = typeDescription,
                                    required = true,
                                ),
                                ToolParameterSchema(
                                    name = "task_id",
                                    type = "string",
                                    description =
                                        if (useChinese) {
                                            "可选，用于继续既有任务的稳定 task ID"
                                        } else {
                                            "optional stable task id to continue"
                                        },
                                    required = false,
                                ),
                            ),
                    )
                ),
        )
    }

    // ==================== 文件系统工具 ====================
    val fileSystemTools = SystemToolPromptCategory(
        categoryName = "File System Tools",
        tools = listOf(
            ToolPrompt(
                name = "list_files",
                description = "List files in a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "e.g. \"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "Read file content. For start_line/end_line, use read_file_part instead; this tool does not accept line ranges. Images are loaded on demand: the current model receives the image when it supports vision; otherwise a configured image-recognition model is used, with OCR as fallback.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "path",
                        type = "string",
                        description = "file path",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "optional, execution environment. Values: \"android\" (default, Android file system) | \"linux\" (local Ubuntu 24 terminal environment via proot; Linux paths like /home/... /etc/hosts) | \"repo:<repositoryName>\" (attached local storage repository)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "optional, the question or focus for a backend media-recognition model",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"audio\"> tag for models that support audio",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"video\"> tag for models that support video",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "Read file content by line range.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "starting line number, 1-indexed", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "ending line number, 1-indexed, inclusive, optional", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "create_file",
                description = "Create a new file by delegating to apply_file with type=create.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "full file content for the new file", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "edit_file",
                description = "Edit an existing file by delegating to apply_file with type=replace.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "the exact content to be matched and replaced", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "the new content to insert", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "delete_file",
                description = "Delete a file or directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "target path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "Create a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "Search for files matching a pattern.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path, for Android use /sdcard/..., for Linux use /home/... or /etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "search pattern, e.g. \"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "optional, controls depth of subdirectory search, -1=unlimited", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "Search code content matching a regex pattern in files. Returns matches with surrounding context lines.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "regex pattern", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "lines of context before/after match", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "max matches", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "Download a file from the internet. Two modes: (1) Provide `url` + `destination`. (2) Provide `visit_key` + (`link_number` or `image_number`) + `destination` to download an item by index from a previous `visit_web` result.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, file URL. If omitted, use visit_key + link_number/image_number to download from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, 1-based link index from Results (use with visit_key)", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "optional, 1-based image index from Images (use with visit_key)", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "save path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    val fileSystemToolsCn = SystemToolPromptCategory(
        categoryName = "文件系统工具",
        tools = listOf(
            ToolPrompt(
                name = "list_files",
                description = "列出目录中的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "例如\"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "读取文件内容。按 start_line/end_line 读取时请用 read_file_part，本工具不接受行号范围。图片按需加载：当前模型支持视觉时直接交给当前模型，否则调用已配置的识图模型，均不可用时回退OCR。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "可选，执行环境。取值：\"android\"（默认，Android文件系统）| \"linux\"（本地Ubuntu 24终端环境，通过proot实现；路径用Linux格式，如/home/...、/etc/hosts）| \"repo:<仓库名>\"（附加本地储存仓库）",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "可选，交给后端媒体识别模型的问题或关注点",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"audio\">标签供支持音频的模型直接处理",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"video\">标签供支持视频的模型直接处理",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "按行号范围读取文件内容。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "起始行号，从1开始", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "结束行号，从1开始，包括该行，可选", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "create_file",
                description = "通过委托给 apply_file 且 type=create 来创建新文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "新文件的完整内容", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "edit_file",
                description = "通过委托给 apply_file 且 type=replace 来编辑已存在文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "用于匹配并替换的原始内容", required = true),
                    ToolParameterSchema(name = "new", type = "string", description = "要插入的新内容", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "delete_file",
                description = "删除文件或目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目标路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "创建目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "搜索匹配模式的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径，Android用/sdcard/...，Linux用/home/...或/etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "搜索模式，例如\"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "可选，控制子目录搜索深度，-1=无限", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "在文件中搜索匹配正则表达式的代码内容，返回带上下文的匹配结果。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "正则表达式模式", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "匹配行前后的上下文行数", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "最大匹配数", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "从互联网下载文件。有两种用法：1）提供 `url` + `destination` 直接下载。2）提供 `visit_key` +（`link_number` 或 `image_number`）+ `destination`，从上一次 `visit_web` 的 Results/Images 编号中按序号下载。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选, 文件URL。不传时可使用 visit_key + link_number/image_number 从上一次 visit_web 结果按编号下载", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选, 上一次 visit_web 返回的 visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选, 整数, Results 中的链接编号（从1开始，需要配合 visit_key）", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "可选, 整数, Images 中的图片编号（从1开始，需要配合 visit_key）", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "保存路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    // ==================== HTTP工具 ====================
    val httpTools = SystemToolPromptCategory(
        categoryName = "HTTP Tools",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "Visit a webpage and extract information (including optional image links). Two modes: (1) Provide `url` to visit a new page. (2) Follow a link from a previous visit by providing `visit_key` + `link_number`. The returned text often includes a `Results:` section like `[1] ...`, `[2] ...` — those bracketed numbers are 1-based indices. Use that exact number as `link_number` (range: 1..links.length). If you need images, set `include_image_links=true` and the tool will return an `Images:` section with 1-based indices. IMPORTANT: do NOT use `link_number` to download images; instead use `download_file` with `visit_key` + `image_number`. IMPORTANT: this tool is for webpage browsing/extraction, not a replacement for raw HTTP GET/POST requests; if you use it where you actually need API responses or precise response bodies, it may return empty or incomplete content. NOTE: this tool is browsing-only/read-only and does not perform interactive actions such as login, click, fill, submit, or workflow automation.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, webpage URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, string, the visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, int, 1-based index of the link to follow (matches the `[n]` in Results; range 1..links.length)", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "optional, boolean, when true include extracted image links in the result (imageLinks)", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "optional, quick select user agent: desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "optional, full custom user agent override", required = false)
                )
            )
        )
    )
    
    val httpToolsCn = SystemToolPromptCategory(
        categoryName = "HTTP工具",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "访问网页并提取信息（可选包含图片链接）。有两种用法：1）提供 `url` 访问新页面。2）提供上一次 visit_web 返回的 `visit_key` + `link_number`，用来继续访问结果里的某个链接。返回文本通常会包含 `Results:` 段落，形如 `[1] ...`、`[2] ...` —— 中括号里的数字是从 1 开始的编号，请把该编号原样作为 `link_number`（范围：1..links.length），不要按 0 起始。若需要图片，请设置 `include_image_links=true`，工具会额外返回 `Images:` 段落以及从 1 开始的图片编号。重要：下载图片不要用 `link_number` 乱点页面链接；请使用 `download_file` 的 `visit_key` + `image_number` 按图片编号下载。重要：这个工具用于网页浏览/提取，不能替代原始 HTTP GET/POST 请求；如果你实际需要的是接口返回体或精确响应内容，用它时可能会得到空结果或不完整内容。注意：该工具仅支持浏览/读取操作，不执行登录、点击、填写、提交等交互自动化。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选, 网页URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选, 字符串, 上一次 visit_web 返回的 visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选, 整数, 要继续访问的链接编号（从1开始，对应 Results 里的 `[n]`；范围 1..links.length）", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "可选, boolean, 为 true 时在结果中额外包含提取到的图片链接列表（imageLinks）", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "可选：UA预设，快速选择：desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "可选：完整自定义UA（优先级高于预设）", required = false)
                )
            )
        )
    )
    
    // Memory APIs are exposed only by independently enabled tool packages.
    val memoryTools = SystemToolPromptCategory(categoryName = "Memory", tools = emptyList())
    val memoryToolsCn = SystemToolPromptCategory(categoryName = "记忆", tools = emptyList())

    private val internalToolCategoriesEn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesEn
    private val internalToolCategoriesCn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesCn

    
    /**
     * 获取所有英文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        includeSubagentTools: Boolean = true
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemTools.copy(
            tools = fileSystemTools.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    when {
                        chatModelHasDirectImage ->
                            "Read file content. Images are loaded only when this tool is called and are then sent to the current vision-capable model."
                        hasBackendImageRecognition ->
                            "Read file content. Images are loaded only when this tool is called and are analyzed by the configured image-recognition model; use 'intent' to specify the question or focus."
                        else -> tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionEn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return buildList {
            add(nativeTodoToolsEn)
            add(basicTools)
            if (includeSubagentTools) {
                add(subagentTools)
                add(com.ai.assistance.operit.core.agent.collaboration.CollaborationTools.category(false, AgentProfileRepository.instance.listAvailableSubagents()))
            }
            add(adjustedFileSystemTools)
            add(httpTools)
            add(memoryTools)
        }
    }

    fun getAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        includeSubagentTools: Boolean = true
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesEn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames,
            includeSubagentTools = includeSubagentTools
        ) + internalToolCategoriesEn.map { category ->
            category.copy(tools = category.tools.filterNot {
                it.name in com.ai.assistance.operit.core.tools.phone.PhoneControlTools.retiredNames
            })
        }
    }
    
    /**
     * 获取所有中文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        includeSubagentTools: Boolean = true
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemToolsCn.copy(
            tools = fileSystemToolsCn.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    when {
                        chatModelHasDirectImage ->
                            "读取文件内容。图片仅在调用此工具时加载，随后直接交给当前支持视觉的模型。"
                        hasBackendImageRecognition ->
                            "读取文件内容。图片仅在调用此工具时加载，随后由已配置的识图模型分析；可用 intent 指定问题或关注点。"
                        else -> tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionCn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return buildList {
            add(nativeTodoToolsCn)
            add(basicToolsCn)
            if (includeSubagentTools) {
                add(subagentToolsCn)
                add(com.ai.assistance.operit.core.agent.collaboration.CollaborationTools.category(true, AgentProfileRepository.instance.listAvailableSubagents()))
            }
            add(adjustedFileSystemTools)
            add(httpToolsCn)
            add(memoryToolsCn)
        }
    }

    fun getAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        includeSubagentTools: Boolean = true
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesCn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames,
            includeSubagentTools = includeSubagentTools
        ) + internalToolCategoriesCn.map { category ->
            category.copy(tools = category.tools.filterNot {
                it.name in com.ai.assistance.operit.core.tools.phone.PhoneControlTools.retiredNames
            })
        }
    }

    data class ManageableToolPrompt(
        val categoryName: String,
        val name: String,
        val description: String
    )

    private fun applyToolOrder(
        categories: List<SystemToolPromptCategory>,
        toolOrder: List<String>
    ): List<SystemToolPromptCategory> {
        if (toolOrder.isEmpty()) return categories
        val orderIndex = toolOrder.withIndex().associate { (index, name) -> name to index }
        return categories.map { category ->
            val sortedTools = category.tools.sortedBy { tool ->
                orderIndex[tool.name] ?: Int.MAX_VALUE
            }
            category.copy(tools = sortedTools)
        }
    }

    private fun applyToolVisibility(
        categories: List<SystemToolPromptCategory>,
        toolVisibility: Map<String, Boolean>
    ): List<SystemToolPromptCategory> {
        if (toolVisibility.isEmpty()) return categories
        return categories.mapNotNull { category ->
            val visibleTools = category.tools.filter { tool ->
                toolVisibility[tool.name] ?: true
            }
            if (visibleTools.isEmpty()) {
                null
            } else {
                category.copy(tools = visibleTools)
            }
        }
    }

    fun getManageableToolPrompts(
        useEnglish: Boolean,
        toolOrder: List<String> = emptyList()
    ): List<ManageableToolPrompt> {
        val baseCategories = if (useEnglish) {
            listOf(nativeTodoToolsEn, basicTools, subagentTools, com.ai.assistance.operit.core.agent.collaboration.CollaborationTools.category(false, AgentProfileRepository.instance.listAvailableSubagents()), fileSystemTools, httpTools, memoryTools)
        } else {
            listOf(nativeTodoToolsCn, basicToolsCn, subagentToolsCn, com.ai.assistance.operit.core.agent.collaboration.CollaborationTools.category(true, AgentProfileRepository.instance.listAvailableSubagents()), fileSystemToolsCn, httpToolsCn, memoryToolsCn)
        }

        val result = baseCategories
            .flatMap { category ->
                category.tools.map { tool ->
                    ManageableToolPrompt(
                        categoryName = category.categoryName,
                        name = tool.name,
                        description = tool.description
                    )
                }
            }
            .distinctBy { it.name }

        return if (toolOrder.isNotEmpty()) {
            val orderIndex = toolOrder.withIndex().associate { (index, name) -> name to index }
            result.sortedBy { manageable ->
                orderIndex[manageable.name] ?: Int.MAX_VALUE
            }
        } else {
            result
        }
    }

    fun generateMemoryToolsPromptEn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryTools), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    fun generateMemoryToolsPromptCn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryToolsCn), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    private fun buildToolHookPayload(
        categories: List<SystemToolPromptCategory>
    ): List<Map<String, Any?>> {
        return categories.flatMap { category ->
            category.tools.map { tool ->
                mapOf(
                    "categoryName" to category.categoryName,
                    "categoryHeader" to category.categoryHeader,
                    "categoryFooter" to category.categoryFooter,
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parameters,
                    "details" to tool.details,
                    "notes" to tool.notes,
                    "parametersStructured" to
                        tool.parametersStructured.orEmpty().map { parameter ->
                            mapOf(
                                "name" to parameter.name,
                                "type" to parameter.type,
                                "description" to parameter.description,
                                "required" to parameter.required,
                                "default" to parameter.default
                            )
                        }
                )
            }
        }
    }

    private fun renderToolPromptFromAvailableTools(
        availableTools: List<Map<String, Any?>>
    ): String {
        if (availableTools.isEmpty()) {
            return ""
        }
        return buildToolPromptCategories(availableTools).joinToString("\n\n") { it.toString() }
    }

    private fun buildToolPromptCategories(
        availableTools: List<Map<String, Any?>>
    ): List<SystemToolPromptCategory> {
        val categories = linkedMapOf<String, MutableToolPromptCategory>()
        availableTools.forEach { item ->
            val categoryName = item["categoryName"] as? String ?: return@forEach
            val toolName = item["name"] as? String ?: return@forEach
            val description = item["description"] as? String ?: return@forEach
            val category = categories.getOrPut(categoryName) {
                MutableToolPromptCategory(
                    categoryName = categoryName,
                    categoryHeader = item["categoryHeader"] as? String ?: "",
                    categoryFooter = item["categoryFooter"] as? String ?: ""
                )
            }
            category.tools.add(
                ToolPrompt(
                    name = toolName,
                    description = description,
                    parameters = item["parameters"] as? String ?: "",
                    parametersStructured = parseToolParameterSchemas(item["parametersStructured"]),
                    details = item["details"] as? String ?: "",
                    notes = item["notes"] as? String ?: ""
                )
            )
        }
        return categories.values.map { category ->
            SystemToolPromptCategory(
                categoryName = category.categoryName,
                categoryHeader = category.categoryHeader,
                tools = category.tools,
                categoryFooter = category.categoryFooter
            )
        }
    }

    private fun parseToolParameterSchemas(value: Any?): List<ToolParameterSchema> {
        val items = value as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val parameter = item as? Map<*, *> ?: return@mapNotNull null
            val name = parameter["name"] as? String ?: return@mapNotNull null
            val description = parameter["description"] as? String ?: return@mapNotNull null
            ToolParameterSchema(
                name = name,
                type = parameter["type"] as? String ?: "string",
                description = description,
                required = parameter["required"] as? Boolean ?: true,
                default = (parameter["default"] as? String) ?: parameter["default"]?.toString()
            )
        }
    }

    private data class MutableToolPromptCategory(
        val categoryName: String,
        val categoryHeader: String,
        val categoryFooter: String,
        val tools: MutableList<ToolPrompt> = mutableListOf()
    )
    
    /**
     * 生成完整的工具提示词文本（英文）
     */
    fun generateToolsPromptEn(
        chatId: String? = null,
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        toolOrder: List<String> = emptyList(),
        hookMetadata: Map<String, Any?> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "Memory and Memory Library Tools" }
        }
        val orderedCategories = applyToolOrder(categories, toolOrder)
        val visibleCategories = applyToolVisibility(orderedCategories, toolVisibility)
        val availableTools = buildToolHookPayload(visibleCategories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    chatId = chatId,
                    useEnglish = true,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility,
                            "toolOrder" to toolOrder
                        ) + hookMetadata
                )
            )
        var currentAvailableTools = beforeContext.availableTools
        var prompt = beforeContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        currentAvailableTools = filterContext.availableTools
        prompt = filterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        return afterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(afterContext.availableTools)
    }
    
    /**
     * 生成完整的工具提示词文本（中文）
     */
    fun generateToolsPromptCn(
        chatId: String? = null,
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        toolOrder: List<String> = emptyList(),
        hookMetadata: Map<String, Any?> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "记忆与记忆库工具" }
        }
        val orderedCategories = applyToolOrder(categories, toolOrder)
        val visibleCategories = applyToolVisibility(orderedCategories, toolVisibility)
        val availableTools = buildToolHookPayload(visibleCategories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    chatId = chatId,
                    useEnglish = false,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility,
                            "toolOrder" to toolOrder
                        ) + hookMetadata
                )
            )
        var currentAvailableTools = beforeContext.availableTools
        var prompt = beforeContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        currentAvailableTools = filterContext.availableTools
        prompt = filterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        return afterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(afterContext.availableTools)
    }
}
