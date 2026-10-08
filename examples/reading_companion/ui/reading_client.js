// One executable contract for every reading UI. Business failures are never retried under another name.
const OWNERS = {
  "list_books": "reading_companion",
  "select_book": "reading_companion",
  "get_current_book": "reading_companion",
  "get_context": "reading_companion",
  "get_recent_comments": "reading_companion",
  "get_chapter_summary": "reading_companion",
  "get_character": "reading_companion",
  "get_recent_summaries": "reading_companion",
  "get_local_files": "reading_companion",
  "refresh_progress": "reading_companion",
  "add_memory": "reading_companion",
  "auto_commentary_status": "reading_companion_auto_commentary",
  "auto_commentary_get_config": "reading_companion_auto_commentary",
  "auto_commentary_set_config": "reading_companion_auto_commentary",
  "list_summary_files": "reading_companion_manage",
  "summary_batch_prefs": "reading_companion_manage",
  "list_persisted_files": "reading_companion_manage",
  "read_persisted_file": "reading_companion_manage",
  "auto_commentary_history": "reading_companion_manage",
  "auto_commentary_run_detail": "reading_companion_manage",
  "list_audit_chats": "reading_companion_manage",
  "start_task": "reading_companion_tasks",
  "get_task": "reading_companion_tasks",
  "cancel_task": "reading_companion_tasks",
  "list_tasks": "reading_companion_tasks"
};
function unwrapToolResult(value) {
  const parse = v => { if (typeof v !== "string") return v; try { return JSON.parse(v); } catch (_) { return v; } };
  let current = parse(value);
  for (let i = 0; i < 6 && current && typeof current === "object"; i++) {
    if (current.success === false) throw new Error(String(current.message || current.error || "Operation failed"));
    if (typeof current.task_id === "string") return current;
    if (Object.prototype.hasOwnProperty.call(current, "data")) current = parse(current.data);
    else if (Object.prototype.hasOwnProperty.call(current, "result")) current = parse(current.result);
    else break;
  }
  return current;
}
async function call(ctx, action, parameters = {}) {
  const packageName = OWNERS[action];
  if (!packageName) throw new Error(`Unknown reading tool: ${action}`);
  if (ctx.usePackage) await ctx.usePackage(packageName);
  const resolved = ctx.resolveToolName ? String(await ctx.resolveToolName({ packageName, toolName: action, preferImported: true }) || "").trim() : "";
  // Resolve once. Any subsequent error belongs to this operation, not to tool discovery.
  return unwrapToolResult(await ctx.callTool(resolved || `${packageName}:${action}`, parameters));
}
const active = task => ["queued", "running", "cancelling"].includes(task.status);
const watchers = new Map();
const paused = new Set();
function activate(key) { paused.delete(key); }
function pause(key) { paused.add(key); stop(key); }
function stop(key) {
  const old = watchers.get(key);
  if (old) { old.stopped = true; if (old.timer) clearTimeout(old.timer); watchers.delete(key); }
}
async function watch(ctx, key, onUpdate, onError) {
  return watchResource(key, () => call(ctx, "list_tasks", { limit: 50 }),
    result => onUpdate(Array.isArray(result.tasks) ? result.tasks : []), onError);
}
async function watchResource(key, fetch, onUpdate, onError, keepWatching = () => true) {
  if (paused.has(key)) return;
  stop(key);
  const state = { stopped: false, timer: null };
  watchers.set(key, state);
  const refresh = async () => {
    try {
      const result = await fetch();
      if (!state.stopped) {
        await onUpdate(result);
        if (!keepWatching(result)) { stop(key); return; }
      }
    } catch (error) { if (!state.stopped && onError) onError(error); }
    if (!state.stopped) state.timer = setTimeout(refresh, 4000);
  };
  await refresh();
}
function taskStatusLabel(task, english) {
  if (task.kind === "cache" && task.status === "running") return english ? "Caching" : "缓存中";
  const labels = { queued: ["等待中", "Queued"], running: ["生成中", "Running"],
    cancelling: ["取消中", "Cancelling"], cancelled: ["已取消", "Cancelled"],
    interrupted: ["已中断", "Interrupted"], superseded: ["阅读状态变化，已跳过", "Skipped after reading state changed"],
    completed: ["已完成", "Completed"], completed_with_failures: ["部分完成", "Partially completed"], failed: ["失败", "Failed"] };
  return (labels[task.status] || [task.status, task.status])[english ? 1 : 0];
}
function taskProgress(task, english) {
  const p = task.result || task.progress || {};
  if (task.kind !== "cache") return `${english ? "Completed" : "已完成"}: ${task.completedCount || 0}`;
  return `${english ? "Scanned" : "已检查"} ${p.processedCount || 0}/${p.totalCount || 0} · ${english ? "Cached" : "已有缓存"} ${p.completedCount || 0} · ${english ? "Not downloaded, skipped" : "未下载，已跳过"} ${p.unavailableCount || 0}`;
}
function taskErrors(task, english) {
  const messages = task.error ? [String(task.error)] : [];
  const failures = task.result && task.result.failures;
  if (Array.isArray(failures)) failures.forEach(failure => {
    messages.push(`${failure.chapterIndex == null && failure.chapterNumber == null ? "" : `${english ? "Chapter" : "第"} ${failure.chapterNumber == null ? Number(failure.chapterIndex) + 1 : Number(failure.chapterNumber)} ${english ? "" : "章"}: `}${failure.error || failure.message || (english ? "Generation failed" : "生成失败")}`);
  });
  return messages.join("\n");
}
function taskLabel(task, english) {
  const request = task.request || {};
  const first = request.startChapterIndex, last = request.endChapterIndex;
  const range = first == null && last == null ? (english ? "Automatic range" : "自动选章")
    : `${first == null ? "…" : Number(first) + 1}–${last == null ? "…" : Number(last) + 1}`;
  return `${task.kind === "cache" ? (english ? "Chapter cache" : "旧章缓存") : task.kind === "summary" ? (english ? "Summary" : "摘要") : (english ? "Commentary" : "段评")} · ${task.bookName || task.bookId} · ${range} · ${request.mode === "regenerate" ? (english ? "Regenerate" : "重生成") : (english ? "Fill missing" : "补缺")}`;
}
function retryChapters(task) {
  if (active(task) || task.kind === "cache") return [];
  const completed = new Set((task.completedChapterIndices || []).map(Number));
  const result = task.result || task.progress || {};
  const failures = [...(result.failures || []), ...(task.attempts || []).filter(
    item => ["failed","interrupted","cancelled"].includes(item.status))];
  return [...new Set(failures.filter(item => item.chapterIndex != null).map(item => Number(item.chapterIndex)).filter(
    index => Number.isInteger(index) && index >= 0 && !completed.has(index)))];
}
const pendingRetries = new Map();
function retryChapter(ctx, task, index) {
  const key = `${task.task_id}_${index}`;
  if (pendingRetries.has(key)) return pendingRetries.get(key);
  const request = call(ctx, "start_task", {kind: task.kind, book_id: task.bookId,
    mode: task.kind === "commentary" ? "regenerate" : "fill_missing",
    count: 1, start_chapter: index + 1, end_chapter: index + 1,
    request_id: `retry_${task.task_id}_${index}_${Date.now()}`})
    .finally(() => pendingRetries.delete(key));
  pendingRetries.set(key, request);
  return request;
}
module.exports = { call, unwrapToolResult, OWNERS, active, watch, watchResource, stop, activate, pause, retryChapters, retryChapter, taskLabel, taskStatusLabel, taskErrors, taskProgress,
  tasks: { start: (ctx, p) => call(ctx, "start_task", p), get: (ctx, id) => call(ctx, "get_task", { task_id: id }),
    cancel: (ctx, id) => call(ctx, "cancel_task", { task_id: id }) } };
