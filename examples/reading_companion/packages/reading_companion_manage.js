/* METADATA
{
  "name": "reading_companion_manage",
  "display_name": {
    "zh": "阅读伴侣：文件与管理",
    "en": "Reading Companion: Files and Settings"
  },
  "description": {
    "zh": "阅读伴侣管理：书籍文件、摘要设置与段评审计记录。",
    "en": "Reading companion management: persisted files, summary settings and commentary audit history."
  },
  "enabledByDefault": true,
  "category": "AI Reading Companion",
  "tools": [
    {
      "name": "list_summary_files",
      "description": {
        "zh": "列出当前书籍已经持久化的全部章节摘要及其可编辑 Markdown 路径。可能包含当前进度之后的章节；仅在用户明确要求查看、审计或修改摘要时调用。",
        "en": "List every persisted chapter summary and editable Markdown path for the current book. May include unread chapters; call only when the user explicitly asks to inspect or edit summaries."
      },
      "parameters": []
    },
    {
      "name": "summary_batch_prefs",
      "description": {
        "zh": "读取或保存当前书籍的手动摘要范围与单次预算。无参数时读取；传参时保存。目录序号从 1 开始，拆分子节分别计数，不是标题中的原著章号。",
        "en": "Read or save the current book's manual-summary range and per-run budget. No parameters reads; supplied parameters save. Use one-based catalog positions, counting split sections separately, not numbers in chapter titles."
      },
      "parameters": [
        {"name": "book_id", "description": {"zh": "明确指定书籍，省略时使用当前书籍", "en": "Explicit book ID; defaults to the current book"}, "type": "string", "required": false},
        {
          "name": "start_chapter",
          "description": {
            "zh": "起始目录序号",
            "en": "Start catalog position (split sections count separately)"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "end_chapter",
          "description": {
            "zh": "结束目录序号",
            "en": "End catalog position (split sections count separately)"
          },
          "type": "number",
          "required": false
        },
        {
          "name": "clear_start",
          "description": {
            "zh": "清除已保存的起始章号",
            "en": "Clear the saved start chapter"
          },
          "type": "boolean",
          "required": false
        },
        {
          "name": "clear_end",
          "description": {
            "zh": "清除已保存的结束章号",
            "en": "Clear the saved end chapter"
          },
          "type": "boolean",
          "required": false
        },
        {
          "name": "budget",
          "description": {
            "zh": "本次最多生成 1 到 999 章",
            "en": "Maximum chapters to generate this run, 1 to 999"
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "list_persisted_files",
      "description": {
        "zh": "分页列出当前书籍已保存的 book.md、characters.md、ai-memory.md 与各章 content/summary/comments/meta 文件，供界面浏览；不会导出整本书。",
        "en": "List persisted book.md, characters.md, ai-memory.md, and chapter content/summary/comments/meta files with pagination for the in-app browser; never exports the whole book at once."
      },
      "parameters": [
        {
          "name": "offset",
          "description": {
            "zh": "分页偏移，默认 0",
            "en": "Page offset, default 0"
          },
          "type": "number",
          "required": false,
          "default": 0
        },
        {
          "name": "limit",
          "description": {
            "zh": "每页数量，1 到 100，默认 50",
            "en": "Page size from 1 to 100, default 50"
          },
          "type": "number",
          "required": false,
          "default": 50
        }
      ]
    },
    {
      "name": "read_persisted_file",
      "description": {
        "zh": "读取文件浏览器选中的白名单文件；路径必须来自 list_persisted_files。content.md 始终只读。",
        "en": "Read an allowlisted file selected by the file browser; the path must come from list_persisted_files. content.md is always read-only."
      },
      "parameters": [
        {
          "name": "path",
          "description": {
            "zh": "list_persisted_files 返回的文件路径",
            "en": "File path returned by list_persisted_files"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "offset",
          "type": "number",
          "required": false,
          "description": {
            "zh": "字符偏移量，默认 0；续读使用返回的 nextOffset",
            "en": "Character offset, default 0; continue with returned nextOffset"
          }
        },
        {
          "name": "max_characters",
          "type": "number",
          "required": false,
          "description": {
            "zh": "本次读取字符上限，默认 16000",
            "en": "Maximum characters per read, default 16000"
          }
        }
      ]
    },
    {
      "name": "auto_commentary_history",
      "description": {
        "zh": "查看自动段评任务历史；单次详情会显示已生成段评全文、段落锚点、类型、作者和安全调用链，并可打开对应的完整审计对话。",
        "en": "Show auto-commentary history; run details include generated comment text, paragraph anchors, type, author, and a safe operation trace, and can open the full audit chat."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "返回条数，1 到 50，默认 10。",
            "en": "Number of runs, 1 to 50; defaults to 10."
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "auto_commentary_run_detail",
      "description": {
        "zh": "查看单次自动段评任务的段评全文、章节/段落锚点、类型、作者和阶段/实际调用链，并可打开该任务的完整审计对话。",
        "en": "Show one auto-commentary run's generated text, chapter/paragraph anchors, type, author, and stage/operation trace, and open its full audit chat."
      },
      "parameters": [
        {
          "name": "runId",
          "description": {
            "zh": "历史列表中的任务 ID。",
            "en": "Run ID from the history list."
          },
          "type": "number",
          "required": true
        }
      ]
    },
    {
      "name": "list_audit_chats",
      "description": {
        "zh": "按书列出段评审计隐藏聊天（隐藏根与每次任务的子代理对话摘要：chatId、标题、runId、状态），供界面打开对应审计对话。",
        "en": "List commentary audit chats per book (hidden root and per-run subagent chat summaries: chatId, title, runId, status) so the UI can open them."
      },
      "parameters": [
        {
          "name": "bookId",
          "description": {
            "zh": "只返回指定书籍的审计聊天；省略返回全部书籍",
            "en": "Return audit chats only for this book; omit for all books"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "limit",
          "description": {
            "zh": "最多返回最近多少条任务审计聊天（1-50，默认 10）",
            "en": "Maximum number of recent run audit chats to return (1-50, default 10)"
          },
          "type": "number",
          "required": false
        }
      ]
    }
  ]
}
*/


function invokeNative(action, params) {
  const root =
    typeof globalThis !== "undefined"
      ? globalThis
      : typeof window !== "undefined"
        ? window
        : this;
  if (
    typeof NativeInterface === "undefined" ||
    !NativeInterface ||
    typeof NativeInterface.executeReadingCompanionAsync !== "function"
  ) {
    throw new Error("当前 Operit 版本不支持 AI 阅读伴侣原生桥接");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) {
    throw new Error("无法确认阅读伴侣工具调用身份");
  }
  return new Promise((resolve, reject) => {
    const callbackId =
      `__operit_reading_companion_${Date.now()}_` +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      try {
        delete root[callbackId];
      } catch (_deleteError) {
        root[callbackId] = undefined;
      }
      if (isError) {
        reject(new Error(String(resultJson || "阅读伴侣操作失败")));
        return;
      }
      try {
        const response = JSON.parse(String(resultJson || "{}"));
        if (!response || response.success !== true) {
          reject(
            new Error(
              (response && response.message) || "阅读伴侣操作失败",
            ),
          );
          return;
        }
        resolve(response.data);
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeReadingCompanionAsync(
        callId,
        callbackId,
        String(action || ""),
        JSON.stringify(params || {}),
      );
    } catch (error) {
      try {
        delete root[callbackId];
      } catch (_deleteError) {
        root[callbackId] = undefined;
      }
      reject(error);
    }
  });
}

async function run(action, params) {
  try {
    complete({
      success: true,
      data: await invokeNative(action, params),
    });
  } catch (error) {
    complete({
      success: false,
      message: String(error && error.message ? error.message : error),
    });
  }
}

exports.list_summary_files = (params = {}) => run("list_summary_files", params);
exports.summary_batch_prefs = (params = {}) => run("summary_batch_prefs", params);
exports.list_persisted_files = (params = {}) => run("list_persisted_files", params);
exports.read_persisted_file = (params = {}) => run("read_persisted_file", params);
exports.auto_commentary_history = (params = {}) => run("auto_commentary_history", params);
exports.auto_commentary_run_detail = (params = {}) => run("auto_commentary_run_detail", params);
exports.list_audit_chats = (params = {}) => run("list_audit_chats", params);
