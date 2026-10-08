const readingTools = require("./reading_client.js");
const TOOL_PACKAGE = "reading_companion_manage";
const RUN_ID_ENV_KEY = "OPERIT_READING_COMPANION_RUN_ID_V1";
const HISTORY_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_history";
const DETAIL_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_run_detail";
const FILES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_files";
const FILE_VIEW_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_file_view";
const FILE_VIEW_PATH_ENV_KEY = "OPERIT_READING_COMPANION_FILE_VIEW_PATH_V1";
const FILE_VIEW_NAME_ENV_KEY = "OPERIT_READING_COMPANION_FILE_VIEW_NAME_V1";
const FILE_VIEW_RELATIVE_ENV_KEY =
  "OPERIT_READING_COMPANION_FILE_VIEW_RELATIVE_V1";
const FILE_VIEW_READONLY_ENV_KEY =
  "OPERIT_READING_COMPANION_FILE_VIEW_READONLY_V1";

function parseJson(value) {
  if (typeof value !== "string") {
    return value;
  }
  const raw = value.trim();
  if (!raw) {
    return "";
  }
  try {
    return JSON.parse(raw);
  } catch (_error) {
    return value;
  }
}

function toErrorText(error) {
  if (error && typeof error === "object" && error.message) {
    return String(error.message);
  }
  return String(error || "");
}

/**
 * Runs an async tool call with a hard deadline so a stuck native bridge can never leave the
 * panel on an infinite spinner. The underlying promise keeps running; only the UI gives up.
 */
async function callWithTimeout(task, timeoutMessage, timeoutMs) {
  const deadlineMs = Number(timeoutMs) > 0 ? Number(timeoutMs) : 15000;
  let timer = null;
  try {
    return await Promise.race([
      task(),
      new Promise((_resolve, reject) => {
        timer = setTimeout(() => {
          reject(new Error(String(timeoutMessage || "Timed out")));
        }, deadlineMs);
      }),
    ]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
}

const unwrapToolResult = readingTools.unwrapToolResult;
const callHistoryTool = (ctx, action, params) => readingTools.call(ctx, action, params);

function useEnglishLocale() {
  return String(getLang() || "")
    .trim()
    .toLowerCase()
    .startsWith("en");
}

function statusLabel(status, english) {
  const labels = english
    ? {
        generated: "Generated",
        cached: "Ready from cache",
        generating: "Generating",
        interrupted: "Interrupted",
        cancelled: "Cancelled",
        failed: "Failed",
        superseded: "Skipped after reading state changed",
        no_next_chapter: "No next chapter",
        already_generating: "Already generating",
        no_valid_comments: "Model returned no valid comments",
      }
    : {
        generated: "已生成",
        cached: "已命中缓存",
        generating: "生成中",
        interrupted: "已中断",
        cancelled: "已取消",
        failed: "失败",
        superseded: "阅读状态已变化，已跳过",
        no_next_chapter: "没有下一章",
        already_generating: "已有任务生成中",
        no_valid_comments: "模型未返回有效段评",
      };
  const normalized = String(status || "").trim().toLowerCase();
  return labels[normalized] || normalized || (english ? "Unknown" : "未知");
}

function statusColor(status) {
  const normalized = String(status || "").trim().toLowerCase();
  if (
    normalized === "generated" ||
    normalized === "cached" ||
    normalized === "no_next_chapter"
  ) {
    return "primary";
  }
  if (normalized === "generating") {
    return "tertiary";
  }
  if (
    normalized === "failed" ||
    normalized === "interrupted" ||
    normalized === "cancelled" ||
    normalized === "superseded" ||
    normalized === "no_valid_comments"
  ) {
    return "error";
  }
  return "onSurfaceVariant";
}

function triggerLabel(trigger, english) {
  const labels = {
    manual: ["手动段评", "Manual commentary"],
    manual_summary: ["手动摘要", "Manual summary"],
    conversation: ["对话发起", "From chat"],
    reading_progress: ["阅读进度触发", "After reading progress"],
    background: ["阅读进度触发", "After reading progress"],
  };
  return (labels[String(trigger || "").trim()] || [String(trigger || "未知来源"), String(trigger || "Unknown trigger")])[english ? 1 : 0];
}

function stageLabel(stage, english) {
  const labels = english
    ? {
        starting: "Starting",
        reading_target: "Read current position and target",
        preparing_context: "Prepare bounded prior context",
        resolving_model: "Resolve role and model",
        waiting_model: "Wait for model",
        validating_response: "Validate response",
        saving_comments: "Save comments",
        completed: "Ready in Legado",
      }
    : {
        starting: "开始任务",
        reading_target: "读取当前进度与目标",
        preparing_context: "整理有限前情",
        resolving_model: "解析角色与模型",
        waiting_model: "等待模型",
        validating_response: "校验模型结果",
        saving_comments: "保存段评",
        completed: "已可在 Legado 显示",
      };
  const normalized = String(stage || "").trim();
  return labels[normalized] || normalized || (english ? "Unknown stage" : "未知阶段");
}

function operationLabel(operation, english) {
  const labels = english
    ? {
        legado_reading_state: "Legado reading state",
        legado_reading_state_recheck: "Legado state re-check",
        legado_chapter_list: "Legado chapter list",
        legado_annotation_read: "Legado chapter read",
        local_context_prepare: "Prepare local context",
        model_direct_call: "Direct model call",
        db_save_comments: "Save comments and immutable snapshot",
        legado_annotation_notify: "Notify Legado provider",
      }
    : {
        legado_reading_state: "读取 Legado 阅读进度",
        legado_reading_state_recheck: "复核 Legado 阅读进度",
        legado_chapter_list: "读取 Legado 章节列表",
        legado_annotation_read: "读取 Legado 章节正文",
        local_context_prepare: "整理本地伴读前情",
        model_direct_call: "直接调用模型",
        db_save_comments: "保存段评与不可变快照",
        legado_annotation_notify: "通知 Legado Provider",
      };
  const normalized = String(operation || "").trim();
  return labels[normalized] || normalized || (english ? "Operation" : "操作");
}

function formatDate(timestamp, english) {
  const value = Number(timestamp || 0);
  if (!Number.isFinite(value) || value <= 0) {
    return english ? "Unknown time" : "时间未知";
  }
  try {
    return new Date(value).toLocaleString(english ? "en-US" : "zh-CN");
  } catch (_error) {
    return String(value);
  }
}

function formatDuration(milliseconds, english, finished) {
  const value = Number(milliseconds || 0);
  if (!Number.isFinite(value) || value <= 0) {
    if (finished) {
      return english ? "<0.1s" : "不到 0.1 秒";
    }
    return english ? "in progress" : "进行中";
  }
  const seconds = value / 1000;
  return english
    ? `${seconds >= 10 ? seconds.toFixed(0) : seconds.toFixed(1)}s`
    : `${seconds >= 10 ? seconds.toFixed(0) : seconds.toFixed(1)} 秒`;
}

function modelSourceLabel(source, english) {
  const labels = english
    ? {
        caller_chat: "Caller chat model",
        character_card: "Character-card fixed model",
        global_chat: "Global Dialogue model",
      }
    : {
        caller_chat: "当前普通对话模型",
        character_card: "角色卡固定模型",
        global_chat: "全局“对话”模型",
      };
  return labels[source === "parent_conversation" ? "caller_chat" : String(source || "").trim()] ||
    (english ? "Resolved at generation time" : "生成时解析");
}

module.exports = {
  unwrapToolResult,
  DETAIL_ROUTE,
  FILES_ROUTE,
  FILE_VIEW_ROUTE,
  FILE_VIEW_PATH_ENV_KEY,
  FILE_VIEW_NAME_ENV_KEY,
  FILE_VIEW_RELATIVE_ENV_KEY,
  FILE_VIEW_READONLY_ENV_KEY,
  HISTORY_ROUTE,
  RUN_ID_ENV_KEY,
  callHistoryTool,
  callWithTimeout,
  formatDate,
  formatDuration,
  modelSourceLabel,
  operationLabel,
  stageLabel,
  statusColor,
  statusLabel,
  toErrorText,
  triggerLabel,
  useEnglishLocale,
};
