const readingTools = require("../reading_client.js");
const {
  HISTORY_ROUTE,
  RUN_ID_ENV_KEY,
  callHistoryTool,
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
} = require("../history_shared.js");

function parseEvidence(value) {
  if (typeof value !== "string") {
    return value && typeof value === "object" ? value : null;
  }
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch (_error) {
    return null;
  }
}

function commentKindLabel(kind, english) {
  const labels = english
    ? {
        reaction: "Reaction",
        banter: "Banter",
        analysis: "Analysis",
        callback: "Callback",
        character: "Character",
        prediction: "Prediction",
      }
    : {
        reaction: "即时反应",
        banter: "吐槽",
        analysis: "分析",
        callback: "细节呼应",
        character: "人物观察",
        prediction: "预测",
      };
  return labels[String(kind || "").trim()] ||
    String(kind || "").trim() ||
    (english ? "Comment" : "段评");
}

function commentEvidenceText(comment, english) {
  const evidence = parseEvidence(comment && comment.evidenceJson);
  if (!evidence) {
    return english ? "No evidence anchor recorded." : "未记录证据锚点。";
  }
  const ids = Array.isArray(evidence.evidenceIds)
    ? evidence.evidenceIds.map((item) => String(item || "").trim()).filter(Boolean)
    : Array.isArray(evidence.paragraphs)
      ? evidence.paragraphs
        .map((item) => {
          const number = Number(item);
          return Number.isFinite(number) && number > 0
            ? `p${String(number).padStart(4, "0")}`
            : "";
        })
        .filter(Boolean)
      : [];
  const anchor = String(evidence.anchorId || "").trim() ||
    (Number.isFinite(Number(comment && comment.paragraphNumber)) &&
    Number(comment.paragraphNumber) > 0
      ? `p${String(Number(comment.paragraphNumber)).padStart(4, "0")}`
      : "");
  const quote = String(evidence.evidenceQuote || evidence.quote || "")
    .trim()
    .slice(0, 160);
  const parts = [];
  if (anchor) {
    parts.push(english ? `Anchor ${anchor}` : `锚点 ${anchor}`);
  }
  if (ids.length) {
    parts.push(english ? `Evidence ${ids.join(", ")}` : `证据 ${ids.join("、")}`);
  }
  if (quote) {
    parts.push(english ? `“${quote}”` : `“${quote}”`);
  }
  return parts.join(" · ") || (english ? "No evidence anchor recorded." : "未记录证据锚点。");
}

function operationMetadataText(operation, english) {
  const metadata = operation && operation.metadata && typeof operation.metadata === "object"
    ? operation.metadata
    : {};
  const parts = [];
  const route = String(metadata.route || "").trim();
  const table = String(metadata.table || "").trim();
  const authority = String(metadata.authority || "").trim();
  const provider = String(metadata.provider || "").trim();
  const model = String(metadata.model || "").trim();
  const usageStatus = String(metadata.usageStatus || "").trim();
  if (route) parts.push(route);
  if (table) parts.push(table);
  if (authority) parts.push(authority);
  if (provider || model) parts.push([provider, model].filter(Boolean).join(":"));
  if (metadata.availableTools && Array.isArray(metadata.availableTools)) {
    parts.push(english ? "tools=[]" : "工具=[]");
  }
  if (usageStatus === "unavailable_estimate_only") {
    parts.push(english ? "usage unavailable; estimate only" : "没有实际用量，仅预估");
  } else if (usageStatus === "provider_reported") {
    parts.push(english ? "provider usage reported" : "Provider 已上报实际用量");
  }
  const error = String(metadata.error || "").trim();
  if (error) parts.push(english ? `error: ${error}` : `错误：${error}`);
  const errorDetail = String(metadata.errorDetail || "").trim();
  if (errorDetail) parts.push(english ? `detail: ${errorDetail}` : `原因：${errorDetail}`);
  return parts.join(" · ");
}

function detailScreen(ctx) {
  const english = useEnglishLocale();
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;
  const [initialized, setInitialized] = ctx.useState(
    "detailInitialized",
    false,
  );
  const [loading, setLoading] = ctx.useState("detailLoading", true);
  const [error, setError] = ctx.useState("detailError", "");
  const [detail, setDetail] = ctx.useState("detailValue", null);
  const [auditNotice, setAuditNotice] = ctx.useState("detailAuditNotice", "");
  const [showDiagnostics, setShowDiagnostics] = ctx.useState("detailDiagnostics", false);
  const runId = Number(ctx.getEnv(RUN_ID_ENV_KEY) || 0);
  const watchKey = `detail_${runId}`;
  const watch = () => {
    if (!Number.isFinite(runId) || runId <= 0) {
      setError(english ? "No run was selected." : "没有选中段评任务。");
      setLoading(false);
      return;
    }
    return readingTools.watchResource(watchKey,
      () => callHistoryTool(ctx,"auto_commentary_run_detail",{runId}),
      result => {setDetail(result); setLoading(false); setError("");},
      error => {setError(toErrorText(error)); setLoading(false);},
      result => !!result?.run && !Number(result.run.finishedAt));
  };

  const backToHistory = async () => {
    await Promise.resolve(ctx.navigate(HISTORY_ROUTE));
  };

  const openAuditChat = async () => {
    if (!ctx.openReadingAuditChat) {
      setAuditNotice(english ? "Opening the audit chat is unavailable." : "无法打开审计对话。");
      return;
    }
    setAuditNotice("");
    try {
      await ctx.openReadingAuditChat(runId);
      setAuditNotice(english ? "Audit chat opened in the main conversation." : "已在主对话打开审计聊天。");
    } catch (openError) {
      setAuditNotice(toErrorText(openError));
    }
  };

  const title = english ? "Generation detail" : "生成详情";
  const children = [];
  if (loading && !detail) {
    children.push(
      UI.Row({ fillMaxWidth: true, padding: 16, spacing: 10, verticalAlignment: "center" }, [
        UI.CircularProgressIndicator({ width: 20, height: 20, strokeWidth: 2 }),
        UI.Text({ text: english ? "Loading task…" : "正在加载任务…", color: colors.onSurfaceVariant }),
      ]),
    );
  } else if (error && !detail) {
    children.push(
      UI.Card({ fillMaxWidth: true, containerColor: colors.errorContainer },
        UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          UI.Text({
            text: english ? "Could not load task" : "加载任务失败",
            style: "titleMedium",
            color: colors.onErrorContainer,
          }),
          UI.Text({ text: error, style: "bodySmall", color: colors.onErrorContainer }),
          UI.OutlinedButton({ onClick: backToHistory }, UI.Text({
            text: english ? "Back to history" : "返回历史",
          })),
        ]),
      ),
    );
  } else {
    const run = detail && detail.run && typeof detail.run === "object"
      ? detail.run
      : null;
    const stages = detail && Array.isArray(detail.stages) ? detail.stages : [];
    const comments = detail && Array.isArray(detail.comments) ? detail.comments : [];
    const operations = detail && Array.isArray(detail.operations) ? detail.operations : [];
    const snapshotAvailability = String(
      detail && detail.snapshotAvailability || "",
    ).trim();
    if (!run) {
      children.push(
        UI.Text({
          text: english ? "This task record is unavailable." : "任务记录不可用。",
          color: colors.onSurfaceVariant,
          padding: 16,
        }),
      );
    } else {
      const source = modelSourceLabel(run.modelSource, english);
      const modelParts = [
        source,
        String(run.modelConfigName || "").trim(),
        String(run.provider || "").trim(),
        String(run.model || "").trim(),
      ].filter(Boolean);
      const targetParts = [
        String(run.chapterTitle || "").trim(),
        run.chapterNumber
          ? (english ? `Chapter ${run.chapterNumber}` : `第 ${run.chapterNumber} 章`)
          : (english ? "Unknown chapter" : "章节未知"),
        Number(run.targetCharacterCount || 0) > 0
          ? (english
            ? `${run.targetCharacterCount} target characters`
            : `目标 ${run.targetCharacterCount} 字`)
          : "",
      ].filter(Boolean);
      const contextParts = [
        Number(run.contextChapterCount || 0) > 0
          ? (english
            ? `${run.contextChapterCount} prior chapters`
            : `前情 ${run.contextChapterCount} 章`)
          : "",
        Number(run.contextCharacterCount || 0) > 0
          ? (english
            ? `${run.contextCharacterCount} prior characters`
            : `前情 ${run.contextCharacterCount} 字`)
          : "",
        Number(run.contextWindowTokens || 0) > 0
          ? (english
            ? `${run.contextWindowTokens} context-window tokens`
            : `上下文窗口 ${run.contextWindowTokens} Token`)
          : "",
        Number(run.estimatedInputTokens || 0) > 0
          ? (english
            ? `${run.estimatedInputTokens} estimated input tokens`
            : `预估输入 ${run.estimatedInputTokens} Token`)
          : "",
        Number(run.actualInputTokens || 0) > 0
          ? (english
            ? `${run.actualInputTokens} actual input tokens`
            : `实际输入 ${run.actualInputTokens} Token`)
          : "",
        Number(run.actualCachedInputTokens || 0) > 0
          ? (english
            ? `${run.actualCachedInputTokens} cached`
            : `缓存命中 ${run.actualCachedInputTokens} Token`)
          : "",
        Number(run.actualOutputTokens || 0) > 0
          ? (english
            ? `${run.actualOutputTokens} actual output tokens`
            : `实际输出 ${run.actualOutputTokens} Token`)
          : "",
        String(run.actualUsageSource || "").trim()
          ? (english
            ? `usage source: ${run.actualUsageSource}`
            : `实际用量来源：${run.actualUsageSource}`)
          : (english ? "actual usage unavailable; estimate is labeled" : "实际用量不可用；仅显示预估"),
      ].filter(Boolean);
      const errorText = String(run.error || "").trim();
      if (error) children.push(UI.Text({text: error, color: colors.error}));
      children.push(
        UI.Card({ fillMaxWidth: true, containerColor: colors.primaryContainer },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
            UI.Text({
              text: String(run.bookName || (english ? "Legado book" : "Legado 书籍")),
              style: "titleLarge",
              color: colors.onPrimaryContainer,
              maxLines: 1,
              overflow: "ellipsis",
            }),
            UI.Text({
              text: `${statusLabel(run.status, english)} · ${triggerLabel(run.trigger, english)}`,
              style: "titleMedium",
              color: colors[statusColor(run.status)] || colors.onPrimaryContainer,
            }),
            UI.Text({
              text: `${formatDate(run.startedAt, english)} · ${formatDuration(run.durationMs, english, !!run.finishedAt)}`,
              style: "bodySmall",
              color: colors.onPrimaryContainer,
            }),
            ...(run.childChatId ? [UI.Button({onClick: openAuditChat},
              UI.Text({text: english ? "View tool conversation" : "查看工具调用对话"}))] : []),
            ...(auditNotice ? [UI.Text({text: auditNotice, style: "bodySmall"})] : []),
          ]),
        ),
        UI.Card({ fillMaxWidth: true, containerColor: colors.surface },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
            UI.Text({
              text: english ? "Resolved model" : "模型解析",
              style: "titleMedium",
              color: colors.onSurface,
            }),
            UI.Text({
              text: modelParts.join(" · ") || (english ? "Unavailable" : "不可用"),
              style: "bodyMedium",
              color: colors.onSurfaceVariant,
            }),
            UI.Text({
              text: english ? "Commentary role" : "段评角色",
              style: "labelLarge",
              color: colors.onSurface,
            }),
            UI.Text({
              text: String(run.roleCardName || run.roleCardId || (english ? "Unavailable" : "未记录")),
              style: "bodyMedium",
              color: colors.onSurfaceVariant,
            }),
          ]),
        ),
        UI.Card({ fillMaxWidth: true, containerColor: colors.surface },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
            UI.Text({
              text: english ? "Target and context" : "目标与前情",
              style: "titleMedium",
              color: colors.onSurface,
            }),
            UI.Text({
              text: targetParts.join(" · ") || (english ? "Unavailable" : "未记录"),
              style: "bodyMedium",
              color: colors.onSurfaceVariant,
            }),
            UI.Text({
              text: contextParts.join(" · ") || (english ? "No prior context" : "没有前情"),
              style: "bodyMedium",
              color: colors.onSurfaceVariant,
            }),
          ]),
        ),
      );
      if (errorText) {
        children.push(
          UI.Card({ fillMaxWidth: true, containerColor: colors.errorContainer },
            UI.Column({ fillMaxWidth: true, padding: 16, spacing: 6 }, [
              UI.Text({
                text: english ? "Result / error" : "结果 / 错误",
                style: "titleMedium",
                color: colors.onErrorContainer,
              }),
              UI.Text({
                text: errorText,
                style: "bodyMedium",
                color: colors.onErrorContainer,
              }),
            ]),
          ),
        );
      } else {
        children.push(
          UI.Card({ fillMaxWidth: true, containerColor: colors.secondaryContainer },
            UI.Text({
              text: !Number(run.finishedAt)
                ? (english ? "Generation in progress. Results have not been published." : "正在生成，结果尚未发布。")
                : run.status !== "generated"
                  ? statusLabel(run.status, english)
                  : run.trigger === "manual_summary"
                    ? (english ? "Chapter summary saved." : "章节摘要已保存。")
                    : (english ? `${Number(run.commentCount || 0)} comments saved for safe unlock in Legado.`
                      : `已保存 ${Number(run.commentCount || 0)} 条段评，按阅读进度在 Legado 解锁。`),
              style: "bodyMedium",
              color: colors.onSecondaryContainer,
              padding: 16,
            }),
          ),
        );
      }
      if (run.trigger !== "manual_summary") children.push(
        UI.Card({ fillMaxWidth: true, containerColor: colors.surface },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
            UI.Text({
              text: english ? "Generated comments" : "本次生成的段评",
              style: "titleMedium",
              color: colors.onSurface,
            }),
             ...(comments.length === 0
               ? [UI.Text({
                  text: snapshotAvailability === "unavailable_legacy"
                    ? (english
                      ? "This legacy run predates immutable snapshots. Its generated text cannot be recovered."
                      : "这是旧版任务，当时尚未保存不可变快照，具体段评内容已无法恢复。")
                    : (english
                      ? "This run did not produce a comment snapshot."
                      : "本次任务没有生成段评快照。"),
                  color: colors.onSurfaceVariant,
                })]
              : comments.map((comment) => {
                  const chapterNumber = Number(comment.chapterNumber);
                  const paragraphNumber = Number(comment.paragraphNumber);
                  const anchor = [
                    Number.isFinite(chapterNumber) && chapterNumber > 0
                      ? (english ? `Chapter ${chapterNumber}` : `第 ${chapterNumber} 章`)
                      : "",
                    Number.isFinite(paragraphNumber) && paragraphNumber > 0
                      ? (english ? `paragraph ${paragraphNumber}` : `段落 ${paragraphNumber}`)
                      : "",
                  ].filter(Boolean).join(" · ");
                  const role = String(
                    comment.roleCardName || comment.roleCardId || "",
                  ).trim() || (english ? "Role unavailable" : "角色未记录");
                  return UI.Card(
                    { fillMaxWidth: true, containerColor: colors.secondaryContainer },
                    UI.Column({ fillMaxWidth: true, padding: 12, spacing: 6 }, [
                      UI.Text({
                        text: anchor || (english ? "Unknown anchor" : "锚点未知"),
                        style: "labelLarge",
                        color: colors.onSecondaryContainer,
                      }),
                      UI.Text({
                        text: String(comment.chapterTitle || "").trim(),
                        style: "bodySmall",
                        color: colors.onSecondaryContainer,
                        maxLines: 2,
                        overflow: "ellipsis",
                      }),
                      UI.Text({
                        text: String(comment.text || ""),
                        style: "bodyMedium",
                        color: colors.onSecondaryContainer,
                      }),
                      UI.Text({
                        text: `${commentKindLabel(comment.kind, english)} · ${
                          english ? "author" : "作者"
                        }：${role}`,
                        style: "bodySmall",
                        color: colors.onSecondaryContainer,
                      }),
                      UI.Text({
                        text: commentEvidenceText(comment, english),
                        style: "bodySmall",
                        color: colors.onSecondaryContainer,
                      }),
                    ]),
                  );
                })),
          ]),
        ),
      );
      children.push(UI.OutlinedButton({onClick: () => setShowDiagnostics(!showDiagnostics)},
        UI.Text({text: showDiagnostics ? (english ? "Hide diagnostics" : "收起诊断记录")
          : (english ? "Show diagnostics" : "展开诊断记录")})));
      if (showDiagnostics) {
      children.push(
        UI.Card({ fillMaxWidth: true, containerColor: colors.surface },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
            UI.Text({
              text: english ? "Operation timeline" : "实际调用链",
              style: "titleMedium",
              color: colors.onSurface,
            }),
            ...(operations.length === 0
              ? [UI.Text({
                  text: english ? "No operation trace was recorded." : "没有调用链记录。",
                  color: colors.onSurfaceVariant,
                })]
              : operations.map((operation) => UI.Row(
                {
                  fillMaxWidth: true,
                  spacing: 10,
                  verticalAlignment: "center",
                },
                [
                  UI.Box({
                    width: 10,
                    height: 10,
                    modifier: ctx.Modifier.background(
                      String(operation.status || "") === "completed"
                        ? colors.primary
                        : String(operation.status || "") === "skipped"
                          ? colors.tertiary
                          : colors.error,
                      { cornerRadius: 5 },
                    ),
                  }),
                  UI.Column({ weight: 1, spacing: 2 }, [
                    UI.Text({
                      text: `${operationLabel(operation.operation, english)} · ${
                        String(operation.status || "").trim() || "unknown"
                      }`,
                      style: "bodyMedium",
                      color: colors.onSurface,
                    }),
                    UI.Text({
                      text: `${formatDate(operation.startedAt, english)} · ${
                        formatDuration(
                          operation.durationMs,
                          english,
                          !!operation.finishedAt,
                        )
                      }`,
                      style: "bodySmall",
                      color: colors.onSurfaceVariant,
                    }),
                    operationMetadataText(operation, english)
                      ? UI.Text({
                          text: operationMetadataText(operation, english),
                          style: "bodySmall",
                          color: colors.onSurfaceVariant,
                        })
                      : null,
                  ].filter(Boolean)),
                ],
              ))),
          ]),
        ),
      );
      children.push(
        UI.Card({ fillMaxWidth: true, containerColor: colors.surface },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
            UI.Text({
              text: english ? "Stage timeline" : "阶段时间线",
              style: "titleMedium",
              color: colors.onSurface,
            }),
            ...(stages.length === 0
              ? [UI.Text({
                  text: english ? "No stage events were recorded." : "没有阶段事件记录。",
                  color: colors.onSurfaceVariant,
                })]
              : stages.map((stage) => UI.Row(
                {
                  fillMaxWidth: true,
                  spacing: 10,
                  verticalAlignment: "center",
                },
                [
                  UI.Box({
                    width: 10,
                    height: 10,
                    modifier: ctx.Modifier.background(
                      String(stage.stage || "") === "completed"
                        ? colors.primary
                        : colors.tertiary,
                      { cornerRadius: 5 },
                    ),
                  }),
                  UI.Column({ weight: 1, spacing: 2 }, [
                    UI.Text({
                      text: stageLabel(stage.stage, english),
                      style: "bodyMedium",
                      color: colors.onSurface,
                    }),
                    UI.Text({
                      text: `${formatDate(stage.startedAt, english)} · ${formatDuration(stage.durationMs, english, !!stage.finishedAt)}`,
                      style: "bodySmall",
                      color: colors.onSurfaceVariant,
                    }),
                  ]),
                ],
              ))),
          ]),
        ),
      );
      }
    }

    if (run && showDiagnostics) {
      children.push(
        UI.Card({ fillMaxWidth: true, containerColor: colors.surface },
          UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
            UI.Text({
              text: english ? "Audit chat" : "审计对话",
              style: "titleMedium",
              color: colors.onSurface,
            }),
            UI.Text({
              text: (english
                ? `mode: ${String(run.executionMode || "direct")}`
                : `模式：${String(run.executionMode || "direct")}`),
              style: "bodySmall",
              color: colors.onSurfaceVariant,
            }),
            UI.Text({
              text: [
                String(run.parentChatId || "").trim(),
                String(run.childChatId || "").trim(),
                String(run.subagentRunId || "").trim(),
              ].filter(Boolean).join(" · ") ||
                (english ? "No linked chat ids." : "没有关联的聊天 id。"),
              style: "bodySmall",
              color: colors.onSurfaceVariant,
            }),
            run.childChatId
              ? UI.Button(
                  {
                    fillMaxWidth: true,
                    onClick: openAuditChat,
                  },
                  [
                    UI.Icon({ name: "History", size: 18 }),
                    UI.Text({ text: english ? "Open subagent chat" : "打开子代理对话" }),
                  ],
                )
              : null,
            auditNotice
              ? UI.Text({
                  text: auditNotice,
                  style: "bodySmall",
                  color: colors.onSurfaceVariant,
                })
              : null,
          ].filter(Boolean)),
        ),
      );
    }
  }

  children.push(
    UI.OutlinedButton({fillMaxWidth: true, onClick: watch},
      UI.Text({text: english ? "Refresh" : "刷新"})),
    UI.OutlinedButton(
      { fillMaxWidth: true, onClick: backToHistory },
      UI.Text({ text: english ? "Back to history" : "返回历史" }),
    ),
  );

  return UI.Box(
    {
      fillMaxSize: true,
      onResume: async () => {readingTools.activate(watchKey); await watch();},
      onPause: () => readingTools.pause(watchKey),
      topBarTitle: UI.Text({ text: title, maxLines: 1, overflow: "ellipsis" }),
      onLoad: async () => {
        if (!initialized) {
          setInitialized(true);
          readingTools.activate(watchKey);
          await watch();
        }
      },
    },
    UI.LazyColumn({ fillMaxSize: true, padding: 16, spacing: 12 }, children),
  );
}

module.exports = detailScreen;
module.exports.default = detailScreen;
