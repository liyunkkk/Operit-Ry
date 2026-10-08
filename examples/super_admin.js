/*
METADATA
{
    "name": "super_admin",
    "display_name": {
        "zh": "超级管理员",
        "en": "Super Admin"
    },
    "description": {
        "zh": "超级管理员工具集，提供终端命令和Shell操作的高级功能。terminal工具运行在Ubuntu环境中（已正确挂载sdcard和storage），shell工具通过Shizuku/Root直接执行Android系统命令。适合需要进行底层系统管理和命令行操作的场景。",
        "en": "Super admin toolkit providing advanced terminal and shell capabilities. The terminal tool runs in an Ubuntu environment (with sdcard/storage mounted). The shell tool executes Android system commands directly via Shizuku/Root. Useful for low-level system administration and CLI operations."
    },
    "enabledByDefault": true,
    "category": "System",
    "tools": [
        {
            "name": "terminal",
            "description": {
                "zh": "在持久 Ubuntu 会话执行命令。默认等待10秒，未结束返回 status=running 和 runId，命令继续运行；用 terminal_wait 查询、terminal_cancel 取消。yieldMs 是本次等待时间，不会中断命令；timeoutMs 是执行总上限，默认30分钟。复用会话保留目录和变量。输出为最近尾部快照，outputTruncated 表示截断；大日志请在命令中重定向到文件。completed 只表示回到提示符，不保证命令退出码为0，应检查输出。不要在复用会话直接 exit，需要时用 (命令; exit $?)。",
                "en": "Run a command in a persistent Ubuntu shell. Waits 10s by default; status=running and runId mean it continues. Use terminal_wait to poll or terminal_cancel to cancel. yieldMs bounds only this wait; timeoutMs bounds execution (default 30 minutes). Preserves cwd and variables. Output is a recent tail snapshot with outputTruncated; redirect large logs to a file. completed means the shell prompt returned, not a verified zero exit code. Use an explicit subshell for exit."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的 Ubuntu 命令；切换目录请在命令中使用 cd。本工具不接受 environment/path/timeout 参数，超时参数名为 timeoutMs。",
                        "en": "Ubuntu command to execute; use cd inside the command to change directory. environment/path/timeout are unsupported; the timeout parameter is timeoutMs."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "background",
                    "description": {
                        "zh": "true：独立后台会话，立即返回可查询的runId。",
                        "en": "true: use a separate background session and immediately return a queryable runId."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": {
                        "zh": "执行总上限（毫秒，至少3000），默认1800000；与短等待yieldMs分开。",
                        "en": "Execution deadline in ms, minimum 3000, default 1800000; independent of yieldMs."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "yieldMs",
                    "description": {
                        "zh": "本次等待毫秒数，0–30000，默认10000。",
                        "en": "Wait for this call in ms, 0–30000, default 10000."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_wait",
            "description": {
                "zh": "查询 terminal 返回的 runId，短暂等待完成并返回最新输出尾部快照。仍运行就返回running，不会中断命令或排入检测命令。不传runId时查询指定sessionId或当前会话最近一次任务。进程重启或记录淘汰后ID失效，不要自动重跑命令。",
                "en": "Poll a runId from terminal and return the latest tail snapshot. A running task stays alive; no probe command is queued. Without runId, polls the latest task in sessionId or the current chat session. IDs expire after app restart or completed-record eviction; do not automatically rerun commands."
            },
            "parameters": [
                {
                    "name": "runId",
                    "description": {
                        "zh": "terminal 返回的任务ID，建议传入。",
                        "en": "Task ID returned by terminal; recommended."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "兼容：不传runId时查询此会话最近任务。",
                        "en": "Compatibility: latest task in this session when runId is omitted."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "yieldMs",
                    "description": {
                        "zh": "本次等待0–30000毫秒，默认10000。",
                        "en": "Wait 0–30000 ms; default 10000."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "timeoutMs",
                    "description": {
                        "zh": "旧参数，作为等待时间使用，最多30000毫秒，不取消任务。",
                        "en": "Legacy wait alias, capped at 30000 ms; never cancels the task."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_getscreen",
            "description": {
                "zh": "获取目标终端会话可见屏幕内容（仅一屏，不包含历史滚动缓冲）。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。",
                "en": "Get the visible screen content for the target terminal session (single screen only, no scrollback history). Pass sessionId to target a session; if omitted, uses the current chat's default session."
            },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。",
                        "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "terminal_input",
            "description": {
                "zh": "向目标终端会话写入输入。可通过 sessionId 指定会话；不传则使用当前对话的默认会话。input 与 control 至少传一个。常见用法：先写 input，再写 control=enter 提交；control=ctrl 且 input=c 可发送 Ctrl+C。",
                "en": "Write input to the target terminal session. Pass sessionId to target a session; if omitted, uses the current chat's default session. Provide at least one of input or control. Typical usage: send input first, then control=enter to submit; use control=ctrl with input=c for Ctrl+C."
            },
            "parameters": [
                {
                    "name": "sessionId",
                    "description": {
                        "zh": "可选目标会话ID。不传则使用当前对话的默认会话；无 chatId 时为 super_admin_default_session。",
                        "en": "Optional target session ID. If omitted, uses the current chat's default session; without chatId it is super_admin_default_session."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "input",
                    "description": {
                        "zh": "写入终端的文本",
                        "en": "Text to write to terminal."
                    },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "control",
                    "description": {
                        "zh": "控制键，例如 enter / tab / esc / ctrl",
                        "en": "Control key, e.g. enter / tab / esc / ctrl."
                    },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "shell",
            "description": {
                "zh": "通过Shizuku/Root权限直接在Android系统中执行Shell命令。运行环境：直接访问Android系统，具有系统级权限，适用于需要操作Android系统底层的场景（如pm、am等系统命令）。",
                "en": "Execute shell commands directly on Android with Shizuku/Root. Environment: direct Android system access with system-level privileges, suitable for low-level commands such as pm/am."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的 Android Shell 命令。本工具仅接受 command，不接受 timeoutMs；不要与 Ubuntu terminal 的参数混用。",
                        "en": "Android Shell command to execute. This tool accepts only command, not timeoutMs; do not reuse Ubuntu terminal parameters."
                    },
                    "type": "string",
                    "required": true
                }
            ]
        },
        {
            "name": "terminal_cancel",
            "description": {
                "zh": "取消指定runId。排队任务只取消自己；执行中任务先中断，无法恢复时关闭故障会话。",
                "en": "Cancel a runId. Queued tasks only cancel themselves; running tasks are interrupted, and an unrecoverable session is closed."
            },
            "parameters": [
                {
                    "name": "runId",
                    "description": {
                        "zh": "terminal 返回的任务ID。",
                        "en": "Task ID from terminal."
                    },
                    "type": "string",
                    "required": true
                }
            ]
        }
    ]
}
*/
const superAdmin = (function () {
    const DEFAULT_TERMINAL_SESSION_NAME = "super_admin_default_session";
    const BACKGROUND_TERMINAL_SESSION_PREFIX = "super_admin_background";
    function getCurrentChatSessionSuffix() {
        const chatId = getChatId();
        if (chatId === undefined) {
            return "";
        }
        const normalizedChatId = chatId.trim();
        if (!normalizedChatId) {
            return "";
        }
        return normalizedChatId.replace(/[^a-zA-Z0-9._-]+/g, "_");
    }
    function getDefaultTerminalSessionName() {
        const chatSuffix = getCurrentChatSessionSuffix();
        return chatSuffix
            ? `${DEFAULT_TERMINAL_SESSION_NAME}_${chatSuffix}`
            : DEFAULT_TERMINAL_SESSION_NAME;
    }
    function getBackgroundTerminalSessionName() {
        const chatSuffix = getCurrentChatSessionSuffix();
        const prefix = chatSuffix
            ? `${BACKGROUND_TERMINAL_SESSION_PREFIX}_${chatSuffix}`
            : BACKGROUND_TERMINAL_SESSION_PREFIX;
        return `${prefix}_${Date.now()}`;
    }
    function waitMs(value, fallback = 10000) {
        if (value === undefined)
            return fallback;
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > 30000) {
            throw new Error("yieldMs must be an integer between 0 and 30000");
        }
        return parsed;
    }
    async function terminal(params) {
        if (!params.command?.trim())
            throw new Error("command is required");
        const timeoutMs = params.timeoutMs === undefined ? 1800000 : Number(params.timeoutMs);
        if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 3000) {
            throw new Error("timeoutMs must be an integer of at least 3000");
        }
        const background = params.background === "true";
        const yieldMs = background ? 0 : waitMs(params.yieldMs);
        const session = await Tools.System.terminal.create(background ? getBackgroundTerminalSessionName() : getDefaultTerminalSessionName());
        const result = await Tools.System.terminal.start(session.sessionId, params.command, { timeoutMs, yieldMs });
        return { ...result, command: params.command, background, timeoutMsUsed: timeoutMs,
            context_preserved: !session.isNewSession && result.status !== "failed" && !result.timedOut };
    }
    async function bash(params) {
        return terminal(params);
    }
    async function terminal_wait(params = {}) {
        if (Object.prototype.hasOwnProperty.call(params, "command")) {
            throw new Error("terminal_wait does not execute commands; use terminal.");
        }
        const legacyWait = params.timeoutMs === undefined ? undefined : Number(params.timeoutMs);
        if (legacyWait !== undefined && (!Number.isSafeInteger(legacyWait) || legacyWait < 0)) {
            throw new Error("timeoutMs must be a non-negative integer");
        }
        const yieldMs = params.yieldMs !== undefined ? waitMs(params.yieldMs)
            : legacyWait === undefined ? 10000 : Math.min(30000, legacyWait);
        const sessionId = params.sessionId ?? (params.runId ? undefined
            : (await Tools.System.terminal.create(getDefaultTerminalSessionName())).sessionId);
        return Tools.System.terminal.poll({ runId: params.runId, sessionId, yieldMs });
    }
    async function terminal_cancel(params) {
        if (!params.runId)
            throw new Error("runId is required");
        return Tools.System.terminal.cancel(params.runId);
    }
    /**
     * 通过Shizuku/Root权限在Android系统中执行Shell命令
     * 运行环境：直接访问Android系统，具有系统级权限
     * @param command - 要执行的Shell命令
     */
    async function shell(params) {
        try {
            if (!params.command) {
                throw new Error("命令不能为空");
            }
            const command = params.command;
            console.log(`执行Shell命令: ${command}`);
            // 通过Shizuku/Root权限执行shell操作
            const result = await Tools.System.shell(`${command}`);
            return {
                command: command,
                output: result.output,
                exitCode: result.exitCode
            };
        }
        catch (error) {
            console.error(`[shell] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 获取目标终端会话可见屏幕内容（仅一屏，不包含历史）
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     */
    async function terminal_getscreen(params = {}) {
        try {
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.screen(sessionId);
            return {
                sessionId: result.sessionId ?? sessionId,
                rows: result.rows,
                cols: result.cols,
                content: result.content
            };
        }
        catch (error) {
            console.error(`[terminal_getscreen] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    /**
     * 向目标终端会话写入输入
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     * @param input - 文本输入
     * @param control - 控制键
     */
    async function terminal_input(params = {}) {
        try {
            if (params.input === undefined && params.control === undefined) {
                throw new Error("input和control至少需要提供一个");
            }
            const session = params.sessionId
                ? { sessionId: params.sessionId }
                : await Tools.System.terminal.create(getDefaultTerminalSessionName());
            const sessionId = session.sessionId;
            const result = await Tools.System.terminal.input(sessionId, {
                input: params.input,
                control: params.control
            });
            return {
                sessionId,
                input: params.input,
                control: params.control,
                result
            };
        }
        catch (error) {
            console.error(`[terminal_input] 错误: ${error.message}`);
            console.error(error.stack);
            throw error;
        }
    }
    return {
        terminal,
        bash,
        terminal_wait,
        terminal_cancel,
        terminal_getscreen,
        terminal_input,
        shell
    };
})();
// 逐个导出
exports.terminal = superAdmin.terminal;
exports.bash = superAdmin.bash;
exports.terminal_wait = superAdmin.terminal_wait;
exports.terminal_cancel = superAdmin.terminal_cancel;
exports.terminal_getscreen = superAdmin.terminal_getscreen;
exports.terminal_input = superAdmin.terminal_input;
exports.shell = superAdmin.shell;
