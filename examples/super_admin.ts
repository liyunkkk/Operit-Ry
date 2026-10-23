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
                "zh": "在持久 Ubuntu 会话执行命令，保留目录、变量和函数。默认等待10秒，未结束返回running和runId，用terminal_wait查询或terminal_cancel取消。yieldMs仅限制本次等待；timeoutMs是执行总上限，默认30分钟。output为日志尾部快照，screen为可替换的当前终端画面（可能包含先前输出），不要重复拼接。completed仅表示结束；exitCode为0才表示命令成功，非0为失败，null为未知。sessionExitCode仅表示会话进程退出码，不能当作命令码。大日志请重定向到文件。不要在复用会话直接exit，需要时用子Shell。",
                "en": "Run in a persistent Ubuntu shell, preserving cwd, variables and functions. Waits 10s by default; running and runId mean it continues. Poll with terminal_wait or cancel with terminal_cancel. yieldMs bounds this wait; timeoutMs bounds execution (default 30 minutes). output is a log tail; screen is a replaceable current terminal display and may include earlier output. Do not concatenate snapshots. completed means ended; exitCode=0 means command success, nonzero means failure, null means unknown. sessionExitCode describes only the session process. Redirect large logs to a file. Use a subshell instead of exiting the persistent shell."
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
                "zh": "查询runId，返回日志尾部output和动态画面screen快照；不要重复拼接。新消息到达时可能提前返回running，命令继续执行；稍后用同一runId查询，不要重跑。completed后检查exitCode，null不能视为成功。查询不会中断命令或排入检测命令。不传runId时查询指定sessionId或当前会话最近任务。ID失效时不要自动重跑命令。",
                "en": "Poll runId for output log tail and dynamic screen snapshots; do not concatenate repeats. New input may return running early while the command continues; poll the same runId later instead of rerunning. After completed, inspect exitCode; null is not success. Polling never interrupts or queues a probe command. Without runId, polls the latest task in the specified/current session. Do not rerun automatically if an ID expires."
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
                "zh": "在Android执行独立Shell任务，严格使用用户当前权限（标准/Shizuku/Root）。默认等待10秒，未结束返回running和runId，用shell_wait查询、shell_cancel取消；timeoutMs默认2分钟，限制执行总时长。output是合并正常与错误输出的尾部快照，不要重复拼接。只有completed且exitCode=0才成功；failed/timed_out/cancelled表示失败/超时/取消，cancelling表示仍在清理。新消息可提前结束本次等待，任务继续。每次都是独立环境，不保留cwd/变量，无交互输入。不要用&放后台，改用yieldMs=0；结束会清理本任务进程组。主动脱离进程组的守护进程不受管理。",
                "en": "Run an isolated Android Shell task using the selected STANDARD/Shizuku/Root permission. Default wait 10s; running and runId mean poll with shell_wait or cancel with shell_cancel. timeoutMs defaults to 2 minutes and limits total execution. output is a combined stdout/stderr tail snapshot; do not concatenate snapshots. Only completed with exitCode=0 means success. failed/timed_out/cancelled indicate failure/timeout/cancellation; cancelling means cleanup is pending. New input may end a wait while execution continues. No persistent cwd/variables or interactive stdin. Use yieldMs=0 instead of &. The owned process group is cleaned up on completion; deliberately detached daemons are not managed."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": {
                        "zh": "要执行的 Android Shell 命令，不是 Ubuntu 命令。",
                        "en": "Android Shell command, not an Ubuntu command."
                    },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "timeoutMs",
                    "description": { "zh": "执行总上限，1000～1800000毫秒，默认120000。超时终止任务进程组。", "en": "Execution limit, 1000..1800000 ms; default 120000. Timeout terminates the task process group." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "yieldMs",
                    "description": { "zh": "本次等待0～30000毫秒，默认10000；0立即返回任务编号。", "en": "Wait 0..30000 ms, default 10000; 0 immediately returns a task ID." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "shell_wait",
            "description": { "zh": "等待或查询shell返回的runId，仅结束本次等待，不重新执行命令、不取消任务。", "en": "Wait for or inspect a shell runId. Ending the wait neither reruns nor cancels the command." },
            "parameters": [
                { "name": "runId", "description": { "zh": "shell返回的任务编号。", "en": "Task ID returned by shell." }, "type": "string", "required": true },
                { "name": "yieldMs", "description": { "zh": "等待0～30000毫秒，默认10000。", "en": "Wait 0..30000 ms, default 10000." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "shell_cancel",
            "description": { "zh": "取消本对话指定Shell任务并清理其进程组。若仍为cancelling，用shell_wait确认结果。", "en": "Cancel this chat's Shell task and clean up its process group. If still cancelling, use shell_wait to confirm." },
            "parameters": [
                { "name": "runId", "description": { "zh": "shell返回的任务编号。", "en": "Task ID returned by shell." }, "type": "string", "required": true }
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

    function getCurrentChatSessionSuffix(): string {
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

    function getDefaultTerminalSessionName(): string {
        const chatSuffix = getCurrentChatSessionSuffix();
        return chatSuffix
            ? `${DEFAULT_TERMINAL_SESSION_NAME}_${chatSuffix}`
            : DEFAULT_TERMINAL_SESSION_NAME;
    }

    function getBackgroundTerminalSessionName(): string {
        const chatSuffix = getCurrentChatSessionSuffix();
        const prefix = chatSuffix
            ? `${BACKGROUND_TERMINAL_SESSION_PREFIX}_${chatSuffix}`
            : BACKGROUND_TERMINAL_SESSION_PREFIX;
        return `${prefix}_${Date.now()}`;
    }

    function waitMs(value: string | undefined, fallback = 10000): number {
        if (value === undefined) return fallback;
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > 30000) {
            throw new Error("yieldMs must be an integer between 0 and 30000");
        }
        return parsed;
    }

    async function terminal(params: { command: string, background?: string, timeoutMs?: string, yieldMs?: string }): Promise<any> {
        if (!params.command?.trim()) throw new Error("command is required");
        const timeoutMs = params.timeoutMs === undefined ? 1800000 : Number(params.timeoutMs);
        if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 3000) {
            throw new Error("timeoutMs must be an integer of at least 3000");
        }
        const background = params.background === "true";
        const yieldMs = background ? 0 : waitMs(params.yieldMs);
        const session = await Tools.System.terminal.create(
            background ? getBackgroundTerminalSessionName() : getDefaultTerminalSessionName());
        const result = await Tools.System.terminal.start(session.sessionId, params.command, { timeoutMs, yieldMs });
        return { ...result, command: params.command, background, timeoutMsUsed: timeoutMs,
            context_preserved: !session.isNewSession && result.status !== "failed" && !result.timedOut };
    }

    async function bash(params: { command: string, background?: string, timeoutMs?: string, yieldMs?: string }): Promise<any> {
        return terminal(params);
    }

    async function terminal_wait(params: { runId?: string, sessionId?: string, yieldMs?: string, timeoutMs?: string } = {}): Promise<any> {
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

    async function terminal_cancel(params: { runId: string }): Promise<any> {
        if (!params.runId) throw new Error("runId is required");
        return Tools.System.terminal.cancel(params.runId);
    }

    /**
     * 通过Shizuku/Root权限在Android系统中执行Shell命令
     * 运行环境：直接访问Android系统，具有系统级权限
     * @param command - 要执行的Shell命令
     */
    async function shell(params: { command: string, timeoutMs?: string, yieldMs?: string }): Promise<any> {
        if (!params.command?.trim()) throw new Error("command is required");
        const timeoutMs = params.timeoutMs === undefined ? 120000 : Number(params.timeoutMs);
        if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 1800000) {
            throw new Error("timeoutMs must be an integer between 1000 and 1800000");
        }
        return Tools.System.shellTask.start(params.command, { timeoutMs, yieldMs: waitMs(params.yieldMs) });
    }

    async function shell_wait(params: { runId: string, yieldMs?: string }): Promise<any> {
        if (!params.runId?.trim()) throw new Error("runId is required");
        if (Object.prototype.hasOwnProperty.call(params, "command"))
            throw new Error("shell_wait does not execute commands; use shell.");
        return Tools.System.shellTask.poll(params.runId, waitMs(params.yieldMs));
    }

    async function shell_cancel(params: { runId: string }): Promise<any> {
        if (!params.runId?.trim()) throw new Error("runId is required");
        return Tools.System.shellTask.cancel(params.runId);
    }

    /**
     * 获取目标终端会话可见屏幕内容（仅一屏，不包含历史）
     * @param sessionId - 可选会话ID；不传时使用当前对话的默认会话，无 chatId 时为 super_admin_default_session
     */
    async function terminal_getscreen(params: { sessionId?: string } = {}): Promise<any> {
        try {
            const session =
                params.sessionId
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
        } catch (error) {
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
    async function terminal_input(params: { sessionId?: string, input?: string, control?: string } = {}): Promise<any> {
        try {
            if (params.input === undefined && params.control === undefined) {
                throw new Error("input和control至少需要提供一个");
            }

            const session =
                params.sessionId
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
        } catch (error) {
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
        shell,
        shell_wait,
        shell_cancel
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
exports.shell_wait = superAdmin.shell_wait;
exports.shell_cancel = superAdmin.shell_cancel;
