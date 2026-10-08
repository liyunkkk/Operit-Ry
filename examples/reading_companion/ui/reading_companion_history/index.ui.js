const readingTools = require("../reading_client.js");
const {
  DETAIL_ROUTE,
  RUN_ID_ENV_KEY,
  callHistoryTool,
  formatDate,
  formatDuration,
  statusColor,
  statusLabel,
  toErrorText,
  triggerLabel,
  useEnglishLocale,
  unwrapToolResult,
} = require("../history_shared.js");

function historyScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState(
    "historyInitialized",
    false,
  );
  const [loading, setLoading] = ctx.useState("historyLoading", true);
  const [error, setError] = ctx.useState("historyError", "");
  const [tasks, setTasks] = ctx.useState("historyTasks", []);
  const [runs, setRuns] = ctx.useState("historyRuns", []);
  const [section, setSection] = ctx.useState("historySection", "runs");

  const watchTasks = () => readingTools.watchResource("history", async () => {
    const [tasksResult, history] = await Promise.all([
      readingTools.call(ctx, "list_tasks", {limit: 50}),
      callHistoryTool(ctx, "auto_commentary_history", {limit: 50}),
    ]);
    return {tasks: tasksResult.tasks || [], runs: history.runs || []};
  }, result => {
    setTasks(result.tasks); setRuns(result.runs); setLoading(false); setError("");
  }, error => {setError(toErrorText(error)); setLoading(false);});
  const load = async () => {
    await watchTasks();
  };

  const openRun = async (run) => {
    const runId = Number(run && run.runId || 0);
    if (!Number.isFinite(runId) || runId <= 0) {
      return;
    }
    await Promise.resolve(ctx.setEnv(RUN_ID_ENV_KEY, String(runId)));
    await Promise.resolve(ctx.navigate(DETAIL_ROUTE));
  };

  const title = english ? "Generation history" : "生成历史";
  const empty = english ? "No generation records yet." : "还没有生成记录。";
  const errorTitle = english ? "Could not load history" : "加载历史失败";
  const refresh = english ? "Refresh" : "刷新";

  const tasksLabel = english ? "Tasks" : "任务";
  const runsLabel = english ? "Records" : "记录";
  const emptyTasks = english
    ? "No generation task records yet."
    : "还没有生成任务记录。";

  const tabs = UI.Row({ fillMaxWidth: true }, [
    ["tasks", `${tasksLabel} (${tasks.length})`],
    ["runs", `${runsLabel} (${runs.length})`],
  ].map(([id, label]) =>
    UI.Tab({
      weight: 1,
      selected: section === id,
      onClick: () => setSection(id),
      selectedContentColor: colors.primary,
      unselectedContentColor: colors.onSurfaceVariant,
    }, [
      UI.Text({ text: label, padding: 12, fontWeight: section === id ? "bold" : "normal" }),
      UI.HorizontalDivider({ thickness: 2, color: section === id ? colors.primary : colors.surface }),
    ])));

  const children = [];
  const errorCard = () => UI.Card(
    { fillMaxWidth: true, containerColor: colors.errorContainer },
    UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
      UI.Text({ text: errorTitle, style: "titleMedium", color: colors.onErrorContainer }),
      UI.Text({ text: error, style: "bodySmall", color: colors.onErrorContainer }),
      UI.OutlinedButton({ onClick: load }, UI.Text({ text: refresh })),
    ]),
  );
  if (section === "tasks") {
    if (error) {
      children.push(errorCard());
    } else if (tasks.length === 0) {
      children.push(
        UI.Card(
          { fillMaxWidth: true, containerColor: colors.surfaceVariant },
          UI.Text({ text: emptyTasks, color: colors.onSurfaceVariant, padding: 16 }),
        ),
      );
    }
    for (const task of tasks) {
      children.push(UI.Card({ fillMaxWidth: true }, UI.Column({ padding: 16, spacing: 10 }, [
        UI.Text({ text: readingTools.taskStatusLabel(task, english), style: "titleSmall" }),
        UI.Text({ text: readingTools.taskLabel(task, english), style: "bodySmall" }),
        UI.Text({ text: readingTools.taskProgress(task, english) }),
        ...(readingTools.taskErrors(task, english) ? [UI.Text({ text: readingTools.taskErrors(task, english) })] : []),
        ...readingTools.retryChapters(task).map(index => UI.OutlinedButton({onClick: async () => {
          try {await readingTools.retryChapter(ctx,task,index); await watchTasks();}
          catch(error) {setError(toErrorText(error));}
        }},UI.Text({text: english ? `Retry chapter ${index+1}` : `重试第 ${index+1} 章`}))),
        ...(task.attempts || []).map(attempt => attempt.archived
          ? UI.Text({ text: `${Number(attempt.chapterIndex) + 1} · ${statusLabel(attempt.status, english)} · ${english ? "Detailed trace expired" : "详细记录已过保留期"}` })
          : UI.OutlinedButton({ onClick: () => openRun(attempt) },
          UI.Text({ text: `${Number(attempt.chapterIndex) + 1} · ${attempt.chapterTitle || ""} · ${statusLabel(attempt.status, english)}` }))),
        ["queued", "running", "cancelling"].includes(task.status) ? UI.OutlinedButton({ onClick: async () => {
          try {
            await readingTools.tasks.cancel(ctx, task.task_id);
            await load();
          } catch (cancelError) {
            setError(toErrorText(cancelError));
          }
        } }, UI.Text({ text: english ? "Cancel task" : "取消任务" })) : null,
      ].filter(Boolean))));
    }
  } else if (loading) {
    children.push(
      UI.Row(
        {
          fillMaxWidth: true,
          spacing: 10,
          verticalAlignment: "center",
          padding: 16,
        },
        [
          UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
          UI.Text({
            text: english ? "Loading records…" : "正在加载记录…",
            color: colors.onSurfaceVariant,
          }),
        ],
      ),
    );
  } else if (error) {
    children.push(errorCard());
  } else if (runs.length === 0) {
    children.push(
      UI.Card(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant },
        UI.Text({ text: empty, color: colors.onSurfaceVariant, padding: 16 }),
      ),
    );
  } else {
    runs.forEach((run) => {
      const runStatus = String(run && run.status || "").trim();
      const metadata = [
        triggerLabel(run && run.trigger, english),
        formatDate(run && run.startedAt, english),
      ];
      if (Number(run && run.durationMs || 0) > 0) {
        metadata.push(formatDuration(run.durationMs, english));
      }
      if (Number(run && run.commentCount || 0) > 0) {
        metadata.push(
          english
            ? `${run.commentCount} comments`
            : `${run.commentCount} 条段评`,
        );
      }
      children.push(
        UI.Card(
          {
            key: `run_${String(run && run.runId || "")}`,
            fillMaxWidth: true,
            containerColor: colors.surface,
            modifier: ctx.Modifier.fillMaxWidth().clickable(() => openRun(run)),
          },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
            UI.Row(
              { fillMaxWidth: true, horizontalArrangement: "spaceBetween", verticalAlignment: "center" },
              [
                UI.Column({ weight: 1, spacing: 2 }, [
                  UI.Text({
                    text: String(run && run.bookName || "") ||
                      (english ? "Legado book" : "Legado 书籍"),
                    style: "titleMedium",
                    color: colors.onSurface,
                    maxLines: 1,
                    overflow: "ellipsis",
                  }),
                  UI.Text({
                    text: run && run.chapterNumber
                      ? english
                        ? `Target chapter ${run.chapterNumber}`
                        : `目标第 ${run.chapterNumber} 章`
                      : (english ? "Target unavailable" : "目标章节未知"),
                    style: "bodySmall",
                    color: colors.onSurfaceVariant,
                  }),
                ]),
                UI.Text({
                  text: statusLabel(runStatus, english),
                  style: "labelLarge",
                  color: colors[statusColor(runStatus)] || colors.onSurfaceVariant,
                }),
              ],
            ),
            UI.Text({
              text: metadata.join(" · "),
              style: "bodySmall",
              color: colors.onSurfaceVariant,
              maxLines: 2,
              overflow: "ellipsis",
            }),
            UI.Text({
              text: english ? "Open details ›" : "查看详情 ›",
              style: "labelMedium",
              color: colors.primary,
            }),
          ]),
        ),
      );
    });
  }

  return UI.Column(
    {
      onResume: async () => { readingTools.activate("history"); await watchTasks(); },
      onPause: () => readingTools.pause("history"),
      fillMaxSize: true,
      topBarTitle: UI.Text({ text: title, maxLines: 1, overflow: "ellipsis" }),
      onLoad: async () => {
        readingTools.activate("history");
        if (!initialized) {
          setInitialized(true);
          await load();
        }
      },
    },
    [
      tabs,
      UI.Row({fillMaxWidth: true, padding: 12, horizontalArrangement: "spaceBetween"}, [
        UI.Text({text: english ? "Most recent 50 records" : "最近 50 条记录", style: "bodySmall"}),
        UI.OutlinedButton({onClick: watchTasks}, UI.Text({text: refresh})),
      ]),
      UI.Box({ fillMaxWidth: true, weight: 1 },
        UI.LazyColumn({
          fillMaxSize: true,
          padding: 16,
          spacing: 16,
        }, children)),
    ],
  );
}

module.exports = historyScreen;
module.exports.default = historyScreen;
