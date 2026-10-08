const readingTools = require("../reading_client.js");
const historyTools = require("../history_shared.js");
const TOOL_PACKAGE = "reading_companion";
const AUTO_COMMENTARY_PACKAGE = "reading_companion_auto_commentary";
const HISTORY_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_history";
const SUMMARIES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_summaries";
const FILES_ROUTE =
  "toolpkg:com.operit.reading_companion:ui:reading_companion_files";

function useStateValue(ctx, key, initialValue) {
  const pair = ctx.useState(key, initialValue);
  return { value: pair[0], set: pair[1] };
}

function useEnglishLocale() {
  return String(getLang() || "")
    .trim()
    .toLowerCase()
    .startsWith("en");
}

function toErrorText(error) {
  if (error && typeof error === "object" && error.message) {
    return String(error.message);
  }
  return String(error || "");
}

const unwrapToolResult = readingTools.unwrapToolResult;

function getText(useEnglish) {
  if (useEnglish) {
    return {
      title: "AI Reading Companion",
      description:
        "Use Legado reading tools in any ordinary chat, with plot reactions, recall, and optional AI paragraph commentary.",
      basicTitle: "Reading companion",
      basicOn: "Connected to Legado and available in ordinary chats when the topic is reading.",
      basicOff: "Turn this on before asking an ordinary chat about a Legado book.",
      autoTitle: "AI auto commentary",
      autoOn:
        "On. The selected character pre-generates comments for the chapters ahead. See the current setup below.",
      autoOff:
        "Off. Turn on to pre-generate chapters of commentary ahead; this reads later chapters and spends model tokens.",
      prefetchLabel: "Pre-generate distance",
      prefetchHint:
        "Background and manual runs fill the next {n} chapters (1–10). The change applies right away.",
      prefetchSaved: "Pre-generate distance updated.",
      prefetchFailed: "Could not update the pre-generate distance: ",
      configTitle: "Current commentary setup",
      configCharacter: "Writer",
      configModel: "Model",
      configTrigger:
        "Trigger · about 20 seconds after reading progress changes, or manually below",
      configReading:
        "Reads · target chapter and four preceding chapters on demand through a multi-turn subagent",
      configDensity:
        "Density · usually 0–3 comments; hard limit 6; silence is allowed",
      configDelivery:
        "Delivery · saved to Legado in advance and revealed only when the anchored paragraph is reached",
      modelSourceCaller: "calling ordinary chat model",
      modelSourceCharacter: "model fixed by the character card",
      modelSourceGlobal: "global dialogue model",
      modelSourceUnknown: "resolved when generation starts",
      changeModelHint:
        "Change the model in the character card binding or the global Dialogue model mapping.",
      personaTitle: "Commentary character",
      personaHint:
        "This character writes the comments and appears as their author in Legado. It does not change any chat character.",
      personaSelect: "Select a character card",
      personaLoading: "Loading character cards…",
      personaNoCards: "No character cards are available.",
      personaSelected: "Commentary character updated.",
      personaRequired: "Select a commentary character first.",
      personaLoadFailed: "Could not load character cards: ",
      personaSaveFailed: "Could not select the commentary character: ",
      loading: "Checking Legado and companion status…",
      disconnectedTitle: "Legado is not ready",
      disconnectedBody:
        "Install or open Legado, open a book, then return here and refresh.",
      connectedTitle: "Current book",
      chapterPrefix: "Chapter",
      preciseProgress: "Precise reading position available",
      chapterProgress: "Only chapter-level progress is available",
      history: "View all records",
      summaries: "View chapter summaries",
      files: "Browse saved book files",
      manualTitle: "Manual generation",
      manualHint:
        "A specified commentary range may include read, current, or unread chapters and replaces existing commentary after a successful regeneration. Read-ahead generation skips fresh results; summaries cover already-read chapters. These batches run only when you tap them.",
      manualComments: "Generate ahead commentary",
      manualReadComments: "Regenerate specified chapters",
      manualSummaries: "Generate summary batch",
      readCommentaryTitle: "Specified chapter commentary",
      readCommentaryHint:
        "Choose 1–10 catalog entries, counting split sections separately, not numbers in chapter titles. Unread entries are allowed; successful generation replaces their old commentary.",
      aheadCommentaryTitle: "Read-ahead commentary",
      aheadCommentaryHint:
        "Choose only how many chapters to fill after the current chapter. The configured pre-generation window is the hard boundary.",
      aheadCountRequired: "Enter a chapter count from 1 to 10.",
      batchSummaryHint:
        "Generates summaries for current and read chapters. Use Legado's catalog positions, including split sections, not numbers in chapter titles. Fresh summaries are skipped.",
      batchCount: "Count (required)",
      batchBudget: "Max to generate this run",
      batchCommentStart: "Start catalog position (required)",
      batchCommentEnd: "End catalog position (required)",
      readRangeRequired: "Enter both the start and end chapter.",
      readRangeTooLarge: "Choose no more than 10 chapters per run.",
      batchStart: "Start catalog position (optional)",
      batchEnd: "End catalog position (optional)",
      batchCalls: (count) =>
        `Up to ${count} chapter subagent task(s); existing chapters are skipped and each task may use multiple model turns`,
      batchRange: (start, end, count) =>
        `Catalog positions ${start}–${end} · ${count} selected`,
      batchDone: "Manual batch completed.",
      batchSuperseded:
        "The book or chapter list changed, so this batch stopped before generating the changed target. Refresh and try again.",
      batchSummaryDone:
        "Manual batch completed; no missing summaries remain in the range.",
      batchBudgetExhausted: (budget, remaining) =>
        `Reached this run's generation limit (${budget} chapters); ${remaining} chapters in the range are still missing. Tap again to continue.`,
      batchUnavailable: (count) =>
        `${count} chapters could not be read and were skipped; completed summaries were kept.`,
      batchReadableDoneWithUnavailable: (count) =>
        `All currently readable chapters are complete; ${count} chapters could not be read yet.`,
      fillAllRead: "Fill read range (using this run's limit)",
      batchPartial: "Manual batch partially completed.",
      batchNoMore: "No more eligible chapters were found in this range.",
      batchStopped: "Stopped after the current chapter. Completed chapters were kept.",
      batchFailed: "Manual batch failed: ",
      batchRunning: "Running the selected manual batch…",
      batchProgress: (done, total) =>
        `Finished ${done}/${total} requested chapters. Completed chapters are saved immediately.`,
      batchStop: "Stop after current chapter",
      batchStopQueued: "The batch will stop after the current chapter finishes.",
      batchFailureLine: (chapter, error) =>
        `Chapter ${chapter} failed: ${error || "unknown_error"}`,
      filesHint:
        "Open the local book workspace without leaving the app. content.md is read-only.",
      auditTitle: "Audit chats",
      auditListSummary: (total, shown) =>
        `${total} audit chats in total, showing the most recent ${shown}.`,
      auditOpened: "Audit chat opened.",
      auditOpenFailed: "Could not open the audit chat: ",
      auditDeleteHint:
        "These chats are permanently hidden; they can only be viewed or deleted from the hidden chat list.",
      refresh: "Refresh status",
      manage: "Open package management",
      regenerate: "Generate or prefill commentary now",
      regenerating: "Generating comments with the selected model…",
      latestRun: "Latest generation task",
      flowTitle: "Generation flow",
      flowStages: {
        reading_target: "Read current position and target chapter",
        preparing_context: "Prepare bounded prior context",
        resolving_model: "Resolve character card and model",
        waiting_model: "Send request and wait for the model",
        validating_response: "Validate anchors and comment format",
        saving_comments: "Save comments and notify Legado",
        completed: "Ready in Legado",
      },
      triggerManual: "Manual",
      triggerBackground: "After reading progress",
      durationLabel: "Duration",
      seconds: "s",
      inputLabel: "estimated input",
      targetLabel: "target chapter",
      contextLabel: "prior context",
      characters: "characters",
      contextWindowLabel: "model window",
      tokens: "tokens",
      stageTimeoutHint: "Timed out during: ",
      noRun: "No commentary task has run yet.",
      comments: "comments",
      noNext: "The current book has no next chapter.",
      generated: "Generated",
      cached: "Ready from cache",
      generating: "Generating",
      interrupted: "Interrupted",
      cancelled: "Cancelled",
      failed: "Failed",
      superseded: "Skipped after reading state changed",
      runErrors: {
        model_timeout: "The model request timed out.",
        model_context_too_small:
          "The selected model cannot fit the character card and chapter text. Select a long-context model and try again.",
        legado_not_installed: "Legado is not installed.",
        legado_connection_failed: "Could not connect to Legado.",
        legado_empty_bookshelf: "The Legado bookshelf is empty.",
        legado_no_recent_book: "No recently read Legado book was found.",
        legado_chapter_read_failed: "Could not read the chapter from Legado.",
        legado_unsafe_position: "The reading position could not be read safely.",
        legado_invalid_response: "Legado returned invalid data.",
        invalid_model_response: "The model returned invalid commentary data.",
        role_not_selected: "Select a commentary character for this book first.",
        role_unavailable:
          "The selected commentary character no longer exists. Select another card.",
        cancelled: "The task was cancelled by the system or user.",
        interrupted: "The task was interrupted before it finished.",
        unknown_error: "The task failed for an unknown reason.",
      },
      queuedHint:
        "Background history stays on this device in reading_companion.db. Run details show generated comment text and the real operation trace, and can open the full audit chat.",
      enableBasicFailed: "Could not change reading companion state: ",
      enableAutoFailed: "Could not change auto commentary state: ",
      enableStateMismatch: "The package did not turn on.",
      disableStateMismatch: "The package did not turn off.",
      regenerateFailed: "Could not generate next-chapter comments: ",
      loadFailed: "Status check failed: ",
      readyNotice: "Status refreshed.",
      generatedNotice: "A pre-generated commentary task finished.",
      hint:
        "Enable the ToolPkg, then use any ordinary chat. Reading guidance appears only when the conversation is about books or reading; other chats are unchanged.",
    };
  }
  return {
    title: "AI 阅读伴侣",
    description:
      "开启后，任意普通对话都可在谈到阅读时调用 Legado 伴读工具，并按需开启 AI 自动段评。",
    basicTitle: "阅读伴侣",
    basicOn: "已连接工具能力；普通对话谈到阅读时即可使用。",
    basicOff: "请先开启，再在普通对话中询问 Legado 书籍。",
    autoTitle: "AI 自动段评",
    autoOn:
      "已开启。所选角色会提前生成后续章节段评（提前量可在下方调整）；详细规则见下方“当前段评设置”。",
    autoOff:
      "已关闭。开启后会读取后续章节内容并提前生成段评，产生模型 Token 消耗。",
    prefetchLabel: "提前生成段评（章）",
    prefetchHint:
      "后台与手动生成会补足未来 {n} 章段评（1～10 章），改动立即生效。",
    prefetchSaved: "提前生成章数已更新。",
    prefetchFailed: "更新提前生成章数失败：",
    configTitle: "当前段评设置",
    configCharacter: "作者",
    configModel: "模型",
    configTrigger: "触发 · 阅读进度变化约 20 秒后自动生成，也可在“生成”页手动生成",
    configReading:
      "读取 · 多轮子代理按需查阅目标章及前四章全文",
    configDensity: "频率 · 普通章节通常 0～3 条，硬上限 6 条，允许完全沉默",
    configDelivery: "展示 · 提前写入 Legado，读到对应段落时才显示",
    modelSourceCaller: "发起本次调用的普通对话模型",
    modelSourceCharacter: "角色卡固定模型",
    modelSourceGlobal: "全局“对话”模型",
    modelSourceUnknown: "生成时解析",
    changeModelHint: "需要换模型时，请修改角色卡的模型绑定或全局“对话”模型映射。",
    personaTitle: "段评角色",
    personaHint:
      "由这个角色写段评；Legado 会把角色卡名字显示为段评作者，不会修改任何聊天角色。",
    personaSelect: "选择一个角色卡",
    personaLoading: "正在加载角色卡…",
    personaNoCards: "当前没有可用的角色卡。",
    personaSelected: "段评角色已更新。",
    personaRequired: "请先选择一个段评角色。",
    personaLoadFailed: "加载角色卡失败：",
    personaSaveFailed: "选择段评角色失败：",
    loading: "正在检查 Legado 与伴读状态…",
    disconnectedTitle: "Legado 尚未就绪",
    disconnectedBody: "请安装或打开 Legado，进入一本书阅读后，回来刷新状态。",
    connectedTitle: "当前伴读书籍",
    chapterPrefix: "第",
    preciseProgress: "已获取精确阅读位置",
    chapterProgress: "当前只能获取章节级进度",
    history: "查看全部记录",
    summaries: "查看章节摘要",
    files: "浏览已保存的书籍文件",
    manualTitle: "手动生成",
    manualHint:
      "指定章节段评可覆盖已读、当前或未读章节，成功后替换该章旧段评；提前生成会跳过已有有效结果，摘要仍用于已读章节。本面板只在你点击时执行。",
    manualComments: "提前生成段评",
    manualReadComments: "重新生成指定章节段评",
    manualSummaries: "生成摘要批次",
    readCommentaryTitle: "指定章节段评",
    readCommentaryHint: "按阅读目录序号选择 1～10 节，可包含未读章节。拆分后的子节分别计数，不按标题里的原著章号；成功后替换所选章节的旧段评。",
    aheadCommentaryTitle: "提前生成段评",
    aheadCommentaryHint: "只需填写数量；从当前章之后开始补全，并严格受“提前生成章数”窗口限制。",
    aheadCountRequired: "请输入 1～10 的章节数量。",
    batchSummaryHint: "为当前及已读章节生成摘要，已有有效摘要的跳过。请填阅读目录序号，拆分子节分别计数，不是标题里的原著章号。",
    batchCount: "数量（必填）",
    batchBudget: "本次最多生成",
    batchCommentStart: "起始目录序号（必填）",
    batchCommentEnd: "结束目录序号（必填）",
    readRangeRequired: "请填写起始章节和结束章节。",
    readRangeTooLarge: "每次最多选择 10 章。",
    batchStart: "起始目录序号（可选）",
    batchEnd: "结束目录序号（可选）",
    batchCalls: (count) =>
      `最多 ${count} 个章节子代理任务；已存在的自动跳过，每个任务内部可能有多轮模型调用`,
    batchRange: (start, end, count) =>
      `目录 ${start}～${end} · 共选择 ${count} 节`,
    batchDone: "手动批次已完成。",
    batchSuperseded: "书籍或章节目录已变化，本批次已在生成变更目标前停止；请刷新后重试。",
    batchSummaryDone: "手动批次已完成，范围内已无缺失摘要。",
    batchBudgetExhausted: (budget, remaining) =>
      `已达本次生成上限（${budget} 章），范围内仍缺 ${remaining} 章，可再次点击继续。`,
    batchUnavailable: (count) =>
      `${count} 章暂时无法读取，已跳过；成功生成的摘要均已保留。`,
    batchReadableDoneWithUnavailable: (count) =>
      `当前可读章节已补全，仍有 ${count} 章暂时无法读取。`,
    fillAllRead: "补全已读范围（按本次上限）",
    batchPartial: "手动批次部分完成。",
    batchNoMore: "所选范围内没有更多符合条件的章节。",
    batchStopped: "已在当前章节完成后停止；此前完成的章节均已保留。",
    batchFailed: "手动批次失败：",
    batchRunning: "正在执行所选手动批次…",
    batchProgress: (done, total) =>
      `已完成 ${done}/${total} 个请求章节；每章完成后都会立即保存。`,
    batchStop: "完成当前章后停止",
    batchStopQueued: "将在当前章节完成后停止本批次。",
    batchFailureLine: (chapter, error) =>
      `第 ${chapter} 章失败：${error || "unknown_error"}`,
    filesHint: "无需离开应用即可浏览本地书籍工作区；content.md 只读。",
      auditTitle: "审计对话",
      auditListSummary: (total, shown) =>
        `共 ${total} 条审计对话，当前仅显示最近 ${shown} 条。`,
      auditOpened: "已打开审计对话。",
    auditOpenFailed: "无法打开审计对话：",
    auditDeleteHint: "这些聊天永久隐藏，只能在隐藏聊天列表查看或删除。",
    refresh: "刷新状态",
    manage: "打开包管理",
    regenerate: "立即生成/补全段评",
    regenerating: "正在使用所选模型生成段评…",
    latestRun: "最近一次生成任务",
    flowTitle: "生成流程",
    flowStages: {
      reading_target: "读取当前进度与目标章节",
      preparing_context: "整理有限前情",
      resolving_model: "解析角色卡与模型",
      waiting_model: "发送请求并等待模型",
      validating_response: "校验段落锚点与格式",
      saving_comments: "保存段评并通知 Legado",
      completed: "已可在 Legado 中显示",
    },
    triggerManual: "手动触发",
    triggerBackground: "阅读进度触发",
    durationLabel: "耗时",
    seconds: "秒",
    inputLabel: "预估输入",
    targetLabel: "目标章",
    contextLabel: "前情",
    characters: "字",
    contextWindowLabel: "模型窗口",
    tokens: "Token",
    stageTimeoutHint: "超时发生阶段：",
    noRun: "还没有执行过段评任务。",
    comments: "条段评",
    noNext: "当前书籍已经没有下一章。",
    generated: "已生成",
    cached: "已命中缓存",
    generating: "生成中",
    interrupted: "已中断",
    cancelled: "已取消",
    failed: "失败",
    superseded: "阅读状态已变化，已跳过",
    noValidComments: "模型未返回有效段评",
    runErrors: {
      model_timeout: "模型调用超时。",
      model_context_too_small:
        "所选模型上下文不足以容纳角色卡和本章正文，请换用长上下文模型后重试。",
      legado_not_installed: "Legado 未安装。",
      legado_connection_failed: "无法连接 Legado。",
      legado_empty_bookshelf: "Legado 书架为空。",
      legado_no_recent_book: "未找到最近阅读书籍。",
      legado_chapter_read_failed: "读取章节失败。",
      legado_unsafe_position: "阅读位置不可安全读取。",
      legado_invalid_response: "Legado 返回数据无效。",
      invalid_model_response: "模型返回的段评数据无效。",
      no_valid_comments: "模型返回内容，但没有有效段评被接受。",
      role_not_selected: "请先为当前书籍选择段评角色。",
      role_unavailable: "所选段评角色已不存在，请重新选择。",
      already_generating: "已有段评任务正在生成中，请等待完成后再试。",
      cancelled: "任务被系统或用户取消。",
      interrupted: "任务进程中断。",
      unknown_error: "任务因未知原因失败。",
    },
    queuedHint:
      "后台历史保存在本机 reading_companion.db；详情可显示已生成段评全文与实际调用链，并可打开对应的审计对话。",
    enableBasicFailed: "切换阅读伴侣失败：",
    enableAutoFailed: "切换自动段评失败：",
    enableStateMismatch: "工具包未能开启。",
    disableStateMismatch: "工具包未能关闭。",
    regenerateFailed: "生成下一章段评失败：",
    loadFailed: "状态检查失败：",
    readyNotice: "状态已刷新。",
    generatedNotice: "提前生成的段评任务已完成。",
    hint:
      "开启工具包后，请在任意普通对话中继续使用；只有谈到书籍/阅读时才会引导伴读工具，其他聊天不受影响。",
  };
}

function statusLabel(text, status) {
  const normalized = String(status || "").trim().toLowerCase();
  const labels = {
    generated: text.generated,
    cached: text.cached,
    generating: text.generating,
    running: text.generating,
    interrupted: text.interrupted,
    cancelled: text.cancelled,
    failed: text.failed,
    superseded: text.superseded,
    no_next_chapter: text.noNext,
    no_valid_comments: text.noValidComments,
    already_generating: text.generating,
  };
  return labels[normalized] || normalized || text.noRun;
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
  if (normalized === "generating" || normalized === "running") {
    return "tertiary";
  }
  if (
    normalized === "failed" ||
    normalized === "no_valid_comments" ||
    normalized === "interrupted" ||
    normalized === "cancelled" ||
    normalized === "superseded"
  ) {
    return "error";
  }
  return "onSurfaceVariant";
}

function autoCommentErrorLabel(text, error, stage) {
  const raw = String(error || "").trim();
  const legacyCodes = {
    "模型调用超时": "model_timeout",
    "Legado 未安装": "legado_not_installed",
    "无法连接 Legado": "legado_connection_failed",
    "Legado 书架为空": "legado_empty_bookshelf",
    "未找到最近阅读书籍": "legado_no_recent_book",
    "读取章节失败": "legado_chapter_read_failed",
    "阅读位置不可安全读取": "legado_unsafe_position",
    "Legado 返回数据无效": "legado_invalid_response",
    "模型返回格式无效": "invalid_model_response",
    "模型返回内容，但没有有效段评被接受": "no_valid_comments",
    "任务被系统或用户取消": "cancelled",
    "任务进程中断": "interrupted",
    "未知错误": "unknown_error",
  };
  const code = legacyCodes[raw] || raw;
  const message = text.runErrors[code] || text.runErrors.unknown_error;
  const normalizedStage = String(stage || "").trim();
  return code === "model_timeout" && text.flowStages[normalizedStage]
    ? `${message} ${text.stageTimeoutHint}${text.flowStages[normalizedStage]}`
    : message;
}

function modelSourceLabel(text, source) {
  const labels = {
    caller_chat: text.modelSourceCaller,
    character_card: text.modelSourceCharacter,
    global_chat: text.modelSourceGlobal,
  };
  return labels[source === "parent_conversation" ? "caller_chat" : String(source || "").trim()] || text.modelSourceUnknown;
}

function runTriggerLabel(text, trigger) {
  return historyTools.triggerLabel(trigger, text.seconds === "s");
}

function formatDuration(text, durationMs) {
  const milliseconds = Number(durationMs || 0);
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) {
    return "";
  }
  return `${Math.max(0.1, milliseconds / 1000).toFixed(
    milliseconds >= 10000 ? 0 : 1,
  )}${text.seconds}`;
}

function generationFlowRows(ctx, text, colors, run) {
  if (!run || !run.stage || run.stage === "starting") {
    return [];
  }
  const stages = Object.keys(text.flowStages);
  const currentStage = String(run.stage || "").trim();
  const currentIndex = Math.max(0, stages.indexOf(currentStage));
  const status = String(run.status || "").trim().toLowerCase();
  const succeeded =
    status === "generated" ||
    status === "cached" ||
    status === "no_next_chapter";
  const failed =
    status === "failed" ||
    status === "cancelled" ||
    status === "interrupted" ||
    status === "superseded";
  return stages.map((stage, index) => {
    const complete = succeeded || index < currentIndex;
    const current = !succeeded && index === currentIndex;
    const marker = complete ? "✓" : current ? (failed ? "!" : "●") : "○";
    const color = complete
      ? colors.primary
      : current && failed
        ? colors.error
        : current
          ? colors.tertiary
          : colors.onSurfaceVariant;
    return ctx.UI.Row(
      {
        fillMaxWidth: true,
        spacing: 8,
        verticalAlignment: "center",
      },
      [
        ctx.UI.Text({
          text: marker,
          color,
          fontWeight: current ? "bold" : "normal",
        }),
        ctx.UI.Text({
          text: text.flowStages[stage],
          style: "bodySmall",
          color,
          fontWeight: current ? "medium" : "normal",
        }),
      ],
    );
  });
}

function settingRow(ctx, title, subtitle, checked, enabled, onCheckedChange) {
  return ctx.UI.Row(
    {
      fillMaxWidth: true,
      verticalAlignment: "center",
      horizontalArrangement: "spaceBetween",
    },
    [
      ctx.UI.Column({ weight: 1, spacing: 4 }, [
        ctx.UI.Text({
          text: title,
          style: "titleSmall",
          color: "onSurface",
        }),
        ctx.UI.Text({
          text: subtitle,
          style: "bodySmall",
          color: "onSurfaceVariant",
        }),
      ]),
      ctx.UI.Spacer({ width: 12 }),
      ctx.UI.Switch({
        checked,
        enabled,
        onCheckedChange,
      }),
    ],
  );
}

function readingCompanionEntryScreen(ctx) {
  const useEnglish = useEnglishLocale();
  const text = getText(useEnglish);
  const colors = ctx.MaterialTheme.colorScheme;
  const initializedState = useStateValue(ctx, "initialized", false);
  const loadingState = useStateValue(ctx, "loading", true);
  const busyState = useStateValue(ctx, "busy", false);
  const refreshingState = useStateValue(ctx, "refreshing", false);
  const busyLabelState = useStateValue(ctx, "busyLabel", "");
  const basicEnabledState = useStateValue(ctx, "basicEnabled", false);
  const autoEnabledState = useStateValue(ctx, "autoEnabled", false);
  const readingState = useStateValue(ctx, "readingState", null);
  const autoStatusState = useStateValue(ctx, "autoStatus", null);
  const prefetchState = useStateValue(ctx, "autoPrefetch", 5);
  const historyState = useStateValue(ctx, "history", null);
  const auditGroupsState = useStateValue(ctx, "auditGroups", {
    groups: [],
    totalRunChats: 0,
    shownRunChats: 0,
  });
  const selectedPersonaState = useStateValue(ctx, "selectedPersona", null);
  const availableCardsState = useStateValue(ctx, "availableCards", []);
  const showCardPickerState = useStateValue(ctx, "showCardPicker", false);
  const loadingCardsState = useStateValue(ctx, "loadingCards", false);
  const cardsLoadedState = useStateValue(ctx, "cardsLoaded", false);
  const errorState = useStateValue(ctx, "error", "");
  const noticeState = useStateValue(ctx, "notice", "");
  const batchCommentCountState = useStateValue(ctx, "batchCommentCount", "");
  const batchCommentStartState = useStateValue(ctx, "batchCommentStart", "");
  const batchCommentEndState = useStateValue(ctx, "batchCommentEnd", "");
  const batchSummaryCountState = useStateValue(ctx, "batchSummaryCount", "20");
  const batchSummaryStartState = useStateValue(ctx, "batchSummaryStart", "");
  const batchSummaryEndState = useStateValue(ctx, "batchSummaryEnd", "");
  const tasksState = useStateValue(ctx, "tasks", []);
  const pageState = useStateValue(ctx, "dashboardPage", "reading");
  const page = pageState.value;
  const watchTasks = () => readingTools.watchResource("entry", async () => {
    const [tasks, history] = await Promise.all([
      readingTools.call(ctx, "list_tasks", {limit: 50}),
      readingTools.call(ctx, "auto_commentary_history", {limit: 10}),
    ]);
    return {tasks: tasks.tasks || [], history};
  }, result => {tasksState.set(result.tasks); historyState.set(result.history);},
  error => errorState.set(toErrorText(error)));

  const isBusy = () => busyState.value || refreshingState.value;

  const loadDashboard = async (showNotice, resuming = false) => {
    if (refreshingState.value) return;
    refreshingState.set(true);
    const previousBookId = String(readingState.value && readingState.value.bookId || "");
    if (!readingState.value) loadingState.set(true);
    errorState.set("");
    try {
      const basicEnabled = ctx.isPackageImported
        ? !!(await ctx.isPackageImported(TOOL_PACKAGE))
        : false;
      const autoEnabled = ctx.isPackageImported
        ? !!(await ctx.isPackageImported(AUTO_COMMENTARY_PACKAGE))
        : false;
      basicEnabledState.set(basicEnabled);
      autoEnabledState.set(autoEnabled);

      const errors = [];
      if (basicEnabled) {
        try {
          const currentBook = await readingTools.call(ctx, "get_current_book", {});
          const bookId = String(currentBook && currentBook.bookId || "").trim();
          if (!bookId) throw new Error(text.loadFailed);
          if (!ctx.getReadingCompanionCommentaryCharacter) {
            throw new Error("Commentary character selection is unavailable");
          }
          const persona = await ctx.getReadingCompanionCommentaryCharacter(bookId);
          const prefsResult = await readingTools.call(ctx, "summary_batch_prefs", { book_id: bookId },
          );
          // Publish the book and its controls together; resumed screens keep same-book drafts.
          readingState.set(currentBook);
          selectedPersonaState.set(persona);
          if (previousBookId !== bookId) {
            const savedStart = Number(prefsResult && prefsResult.startChapter);
            const savedEnd = Number(prefsResult && prefsResult.endChapter);
            const savedBudget = Number(prefsResult && prefsResult.budget);
            batchSummaryStartState.set(
              Number.isFinite(savedStart) && savedStart >= 1 ? String(savedStart) : "",
            );
            batchSummaryEndState.set(
              Number.isFinite(savedEnd) && savedEnd >= 1 ? String(savedEnd) : "",
            );
            batchSummaryCountState.set(
              Number.isFinite(savedBudget) && savedBudget >= 1 ? String(savedBudget) : "20",
            );
            batchCommentStartState.set("");
            batchCommentEndState.set("");
            batchCommentCountState.set("");
          }
        } catch (error) {
          errors.push(toErrorText(error));
        }
        try {
          historyState.set(
            await readingTools.call(ctx, "auto_commentary_history",
              { limit: 10 },
            ),
          );
        } catch (error) {
          errors.push(toErrorText(error));
        }
        try {
          const auditResult = await readingTools.call(ctx, "list_audit_chats",
            {},
          );
          auditGroupsState.set(
            auditResult && Array.isArray(auditResult.groups)
              ? auditResult
              : { groups: [], totalRunChats: 0, shownRunChats: 0 },
          );
        } catch (error) {
          errors.push(toErrorText(error));
        }
      }
      if (autoEnabled) {
        try {
          autoStatusState.set(
            await readingTools.call(ctx, "auto_commentary_status",
              {},
            ),
          );
        } catch (error) {
          errors.push(toErrorText(error));
        }
        try {
          const configResult = await readingTools.call(ctx, "auto_commentary_get_config",
            {},
          );
          const configured = Number(
            configResult && configResult.prefetchAheadChapters || 0,
          );
          if (configured >= 1 && configured <= 10) {
            prefetchState.set(configured);
          }
        } catch (error) {
          errors.push(toErrorText(error));
        }
      }
      if (errors.length > 0) {
        errorState.set(`${text.loadFailed}${errors.join(" · ")}`);
      } else if (showNotice) {
        noticeState.set(text.readyNotice);
      }
    } catch (error) {
      errorState.set(`${text.loadFailed}${toErrorText(error)}`);
    } finally {
      loadingState.set(false);
      refreshingState.set(false);
    }
  };

  const setPackageEnabled = async (packageName, enabled) => {
    if (enabled) {
      if (ctx.importPackage) {
        await ctx.importPackage(packageName);
      }
      if (ctx.usePackage) {
        await ctx.usePackage(packageName);
      }
    } else if (ctx.removePackage) {
      await ctx.removePackage(packageName);
    }
    if (!ctx.isPackageImported) {
      throw new Error(
        enabled ? text.enableStateMismatch : text.disableStateMismatch,
      );
    }
    const actualEnabled = !!(await ctx.isPackageImported(packageName));
    if (actualEnabled !== enabled) {
      throw new Error(
        enabled ? text.enableStateMismatch : text.disableStateMismatch,
      );
    }
  };

  const queryCharacterCards = async () => {
    loadingCardsState.set(true);
    try {
      if (
        typeof Tools === "undefined" ||
        !Tools ||
        !Tools.Chat ||
        !Tools.Chat.listCharacterCards
      ) {
        throw new Error("Character card API is unavailable");
      }
      const result = await Tools.Chat.listCharacterCards();
      const cards = (Array.isArray(result && result.cards) ? result.cards : [])
        .map((card) => ({
          id: String(card && card.id || "").trim(),
          name: String(card && card.name || "").trim(),
          description: String(card && card.description || "").trim(),
        }))
        .filter((card) => card.id && card.name);
      availableCardsState.set(cards);
      cardsLoadedState.set(true);
      return cards;
    } finally {
      loadingCardsState.set(false);
    }
  };

  const loadCardPicker = async () => {
    if (showCardPickerState.value) {
      showCardPickerState.set(false);
      return;
    }
    showCardPickerState.set(true);
    try {
      await queryCharacterCards();
    } catch (error) {
      showCardPickerState.set(false);
      errorState.set(`${text.personaLoadFailed}${toErrorText(error)}`);
    }
  };

  const selectCommentaryCharacter = async (card) => {
    const book = readingState.value;
    if (
      isBusy() ||
      !book ||
      !card ||
      !String(card.id || "").trim()
    ) {
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      if (!ctx.setReadingCompanionCommentaryCharacter) {
        throw new Error("Commentary character selection is unavailable");
      }
      const bookId = String(book.bookId || "").trim();
      const selected = await ctx.setReadingCompanionCommentaryCharacter({
        bookId,
        roleCardId: String(card.id).trim(),
      });
      selectedPersonaState.set(selected);
      selectedPersonaState.set(await ctx.getReadingCompanionCommentaryCharacter(bookId));
      showCardPickerState.set(false);
      noticeState.set(text.personaSelected);
    } catch (error) {
      errorState.set(`${text.personaSaveFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
    }
  };

  const toggleBasic = async (checked) => {
    if (isBusy()) {
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      if (!checked && autoEnabledState.value) {
        await setPackageEnabled(AUTO_COMMENTARY_PACKAGE, false);
      }
      await setPackageEnabled(TOOL_PACKAGE, checked);
      await loadDashboard(false);
    } catch (error) {
      const message = `${text.enableBasicFailed}${toErrorText(error)}`;
      await loadDashboard(false);
      errorState.set(message);
    } finally {
      busyState.set(false);
    }
  };

  const toggleAuto = async (checked) => {
    if (isBusy()) {
      return;
    }
    if (
      checked &&
      !String(
        selectedPersonaState.value &&
          selectedPersonaState.value.roleCardId ||
          "",
      ).trim()
    ) {
      errorState.set(text.personaRequired);
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      if (checked && !basicEnabledState.value) {
        await setPackageEnabled(TOOL_PACKAGE, true);
      }
      await setPackageEnabled(AUTO_COMMENTARY_PACKAGE, checked);
      await loadDashboard(false);
    } catch (error) {
      const message = `${text.enableAutoFailed}${toErrorText(error)}`;
      await loadDashboard(false);
      errorState.set(message);
    } finally {
      busyState.set(false);
    }
  };

  const changePrefetch = async (delta) => {
    if (isBusy()) {
      return;
    }
    const next = Math.min(
      10,
      Math.max(1, Number(prefetchState.value || 5) + delta),
    );
    if (next === Number(prefetchState.value || 5)) {
      return;
    }
    busyState.set(true);
    noticeState.set("");
    errorState.set("");
    try {
      const result = await readingTools.call(ctx, "auto_commentary_set_config",
        { prefetchAheadChapters: next },
      );
      const updated = Number(
        result && result.prefetchAheadChapters || next,
      );
      prefetchState.set(updated);
      noticeState.set(text.prefetchSaved);
    } catch (error) {
      errorState.set(`${text.prefetchFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
    }
  };

  const regenerateComments = async () => {
    if (isBusy() || !basicEnabledState.value) {
      return;
    }
    if (
      !String(
        selectedPersonaState.value &&
          selectedPersonaState.value.roleCardId ||
          "",
      ).trim()
    ) {
      errorState.set(text.personaRequired);
      return;
    }
    busyState.set(true);
    busyLabelState.set(text.regenerating);
    noticeState.set("");
    errorState.set("");
    try {
      const book = readingState.value;
      const chapter = Number(book && book.currentChapterNumber || 0) + 1;
      await readingTools.tasks.start(ctx, {
        kind: "commentary", mode: "regenerate", count: 1,
        book_id: String(book && book.bookId || ""), start_chapter: chapter, end_chapter: chapter,
        request_id: `next-${Date.now()}`,
      });
      await watchTasks();
      pageState.set("reading");
      noticeState.set(useEnglish ? "Task started." : "任务已启动。");
    } catch (error) {
      errorState.set(`${text.regenerateFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
      busyLabelState.set("");
    }
  };

  const openHistory = async () => {
    if (!basicEnabledState.value) {
      return;
    }
    await Promise.resolve(ctx.navigate(HISTORY_ROUTE));
  };

  const openSummaries = async () => {
    if (!basicEnabledState.value) {
      return;
    }
    await Promise.resolve(ctx.navigate(SUMMARIES_ROUTE));
  };

  const openFiles = async () => {
    if (!basicEnabledState.value) {
      return;
    }
    await Promise.resolve(ctx.navigate(FILES_ROUTE));
  };

  const optionalChapterNumber = (value) => {
    const normalized = String(value || "").trim();
    if (!normalized) {
      return null;
    }
    const parsed = Number(normalized);
    if (!Number.isInteger(parsed) || parsed < 1) {
      throw new Error(useEnglish ? "Chapter numbers must be positive integers." : "章节号必须是正整数。");
    }
    return parsed;
  };

  const optionalChapterIndex = (value) => {
    const parsed = optionalChapterNumber(value);
    return parsed === null ? null : parsed - 1;
  };

  const batchParameters = (
    kind,
    countValue,
    startValue,
    endValue,
    commentaryScope = "ahead",
  ) => {
    const count = Number(String(countValue || "").trim());
    const isCommentsBatch = kind === "comments";
    const maxCount = isCommentsBatch ? 10 : 999;
    if (!Number.isInteger(count) || count < 1 || count > maxCount) {
      throw new Error(
        isCommentsBatch
          ? useEnglish
            ? "Choose a count from 1 to 10."
            : "数量请选择 1～10。"
          : useEnglish
            ? "Choose a budget from 1 to 999."
            : "预算请选择 1～999。",
      );
    }
    const start = optionalChapterIndex(startValue);
    const end = optionalChapterIndex(endValue);
    if (isCommentsBatch && commentaryScope === "read" && (start === null || end === null)) {
      throw new Error(
        useEnglish
          ? "Specified chapter commentary requires a start chapter and an end chapter."
          : "指定章节段评必须填写起始章节和结束章节。",
      );
    }
    if (start !== null && end !== null && end < start) {
      throw new Error(useEnglish ? "End chapter must not be before start chapter." : "结束章节不能早于起始章节。");
    }
    if (
      isCommentsBatch &&
      commentaryScope === "read" &&
      end - start + 1 > 10
    ) {
      throw new Error(
        useEnglish
          ? "Choose no more than 10 chapters per run."
          : "指定章节段评每次最多选择 10 章。",
      );
    }
    return {
      count,
      start_chapter_index: start,
      end_chapter_index: end,
    };
  };

  const runManualBatch = async (
    kind,
    overrides = null,
    commentaryScope = "ahead",
  ) => {
    if (isBusy() || !book) {
      return;
    }
    const isComments = kind === "comments";
    const isSpecifiedComments = isComments && commentaryScope === "read";
    const countValue = overrides && overrides.countValue !== undefined
      ? overrides.countValue
      : isSpecifiedComments
        ? "10"
        : isComments
          ? batchCommentCountState.value
          : batchSummaryCountState.value;
    const startValue = overrides && overrides.startValue !== undefined
      ? overrides.startValue
      : isSpecifiedComments
        ? batchCommentStartState.value
        : isComments
          ? ""
          : batchSummaryStartState.value;
    const endValue = overrides && overrides.endValue !== undefined
      ? overrides.endValue
      : isSpecifiedComments
        ? batchCommentEndState.value
        : isComments
          ? ""
          : batchSummaryEndState.value;
    busyState.set(true);
    const newBatchId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    busyLabelState.set(text.batchRunning);
    errorState.set("");
    noticeState.set("");
    try {
      const parameters = batchParameters(
        kind,
        countValue,
        startValue,
        endValue,
        commentaryScope,
      );
      // Manual commentary is an explicit user action and must not require enabling the
      // background auto-commentary package (which would otherwise encourage background reads).
      const packageName = TOOL_PACKAGE;
      if (!isComments) {
        // Persist the current form values per book before the run so a later visit restores
        // them, and the run reads exactly what the panel is showing.
        const savedStart = optionalChapterNumber(startValue);
        const savedEnd = optionalChapterNumber(endValue);
        const saveParameters = {
          book_id: String(book && book.bookId || ""),
          budget: parameters.count,
        };
        if (savedStart === null) {
          saveParameters.clear_start = true;
        } else {
          saveParameters.start_chapter = savedStart;
        }
        if (savedEnd === null) {
          saveParameters.clear_end = true;
        } else {
          saveParameters.end_chapter = savedEnd;
        }
        await readingTools.call(ctx, "summary_batch_prefs",
          saveParameters,
        );
      }
      await readingTools.tasks.start(ctx, {
        kind: isComments ? "commentary" : "summary",
        mode: isComments && commentaryScope === "read" ? "regenerate" : "fill_missing",
        count: parameters.count, book_id: String(book.bookId),
        start_chapter: parameters.start_chapter_index == null ? undefined : parameters.start_chapter_index + 1,
        end_chapter: parameters.end_chapter_index == null ? undefined : parameters.end_chapter_index + 1,
        request_id: newBatchId,
      });
      await watchTasks();
      pageState.set("reading");
      noticeState.set(useEnglish ? "Task started." : "任务已启动。");
    } catch (error) {
      errorState.set(`${text.batchFailed}${toErrorText(error)}`);
    } finally {
      busyState.set(false);
      busyLabelState.set("");
    }
  };

  const fillAllReadChapters = async () => {
    if (isBusy() || !book) {
      return;
    }
    errorState.set("");
    noticeState.set("");
    try {
      const budget = batchSummaryCountState.value;
      batchSummaryStartState.set("1");
      const currentChapterNumber = Number(book && book.currentChapterNumber);
      batchSummaryEndState.set(
        Number.isFinite(currentChapterNumber) && currentChapterNumber >= 1
          ? String(currentChapterNumber)
          : "",
      );
      await runManualBatch("summaries", {
        countValue: budget,
        startValue: "1",
        endValue:
          Number.isFinite(currentChapterNumber) && currentChapterNumber >= 1
            ? String(currentChapterNumber)
            : "",
      });
    } catch (error) {
      errorState.set(`${text.batchFailed}${toErrorText(error)}`);
    }
  };


  const openAuditChatByRunId = async (runId) => {
    const normalizedRunId = Number(runId || 0);
    if (!Number.isFinite(normalizedRunId) || normalizedRunId <= 0) {
      return;
    }
    if (!ctx.openReadingAuditChat) {
      errorState.set(
        `${text.auditOpenFailed}${useEnglish ? "unavailable" : "当前版本不支持"}`,
      );
      return;
    }
    noticeState.set("");
    errorState.set("");
    try {
      await ctx.openReadingAuditChat(normalizedRunId);
      noticeState.set(text.auditOpened);
    } catch (openError) {
      errorState.set(`${text.auditOpenFailed}${toErrorText(openError)}`);
    }
  };

  const book = readingState.value;
  const latestRunFromStatus =
    autoStatusState.value &&
    autoStatusState.value.latestRun &&
    typeof autoStatusState.value.latestRun === "object"
      ? autoStatusState.value.latestRun
      : null;
  const latestRunFromHistory =
    historyState.value &&
    Array.isArray(historyState.value.runs) &&
    historyState.value.runs.length > 0 &&
    typeof historyState.value.runs[0] === "object"
      ? historyState.value.runs[0]
      : null;
  const latestRun = latestRunFromHistory || latestRunFromStatus;
  const commentaryConfiguration = selectedPersonaState.value?.configuration || null;
  const selectedRoleCardId = String(
    selectedPersonaState.value &&
      selectedPersonaState.value.roleCardId ||
      "",
  ).trim();
  const selectedRoleCard = availableCardsState.value.find(
    (card) => card.id === selectedRoleCardId,
  );
  const selectedRoleCardName = String(
    selectedRoleCard && selectedRoleCard.name ||
      selectedPersonaState.value &&
        selectedPersonaState.value.roleCardName ||
      "",
  ).trim();

  const pages = [["reading", useEnglish ? "Reading" : "阅读"],
    ["generate", useEnglish ? "Generate" : "生成"],
    ["settings", useEnglish ? "Settings" : "设置"],
    ["records", useEnglish ? "Records" : "记录"]];
  const tabs = ctx.UI.Row({ fillMaxWidth: true }, pages.map(([id, label]) =>
    ctx.UI.Tab({ weight: 1, selected: page === id, onClick: () => pageState.set(id),
      selectedContentColor: colors.primary, unselectedContentColor: colors.onSurfaceVariant,
    }, [ctx.UI.Text({ text: label, padding: 12, fontWeight: page === id ? "bold" : "normal" }),
      ctx.UI.HorizontalDivider({ thickness: 2, color: page === id ? colors.primary : colors.surface })])));
  const children = [];

  if (page === "settings") children.push(ctx.UI.Card(
      {
        fillMaxWidth: true,
        containerColor: colors.surfaceVariant,
      },
      ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 16 }, [
        settingRow(
          ctx,
          text.basicTitle,
          basicEnabledState.value ? text.basicOn : text.basicOff,
          basicEnabledState.value,
          !isBusy(),
          toggleBasic,
        ),
        settingRow(
          ctx,
          text.autoTitle,
          autoEnabledState.value ? text.autoOn : text.autoOff,
          autoEnabledState.value,
          !isBusy(),
          toggleAuto,
        ),
        autoEnabledState.value
          ? ctx.UI.Row(
              {
                fillMaxWidth: true,
                horizontalArrangement: "spaceBetween",
                verticalAlignment: "center",
              },
              [
                ctx.UI.Column({ weight: 1, spacing: 2 }, [
                  ctx.UI.Text({
                    text: text.prefetchLabel,
                    style: "bodyMedium",
                    color: colors.onSurface,
                  }),
                  ctx.UI.Text({
                    text: String(text.prefetchHint || "").replace(
                      "{n}",
                      String(prefetchState.value || 5),
                    ),
                    style: "bodySmall",
                    color: colors.onSurfaceVariant,
                  }),
                ]),
                ctx.UI.Row({ spacing: 8, verticalAlignment: "center" }, [
                  ctx.UI.OutlinedButton(
                    {
                      enabled:
                        !isBusy() &&
                        Number(prefetchState.value || 5) > 1,
                      onClick: () => changePrefetch(-1),
                    },
                    [ctx.UI.Text({ text: "−" })],
                  ),
                  ctx.UI.Text({
                    text: String(prefetchState.value || 5),
                    style: "titleMedium",
                    color: colors.onSurface,
                  }),
                  ctx.UI.OutlinedButton(
                    {
                      enabled:
                        !isBusy() &&
                        Number(prefetchState.value || 5) < 10,
                      onClick: () => changePrefetch(1),
                    },
                    [ctx.UI.Text({ text: "+" })],
                  ),
                ]),
              ],
            )
          : ctx.UI.Spacer({ height: 0 }),
      ]),
    ));


  if (loadingState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.primaryContainer,
        },
        ctx.UI.Row(
          {
            fillMaxWidth: true,
            padding: 16,
            spacing: 12,
            verticalAlignment: "center",
          },
          [
            ctx.UI.CircularProgressIndicator({
              width: 20,
              height: 20,
              strokeWidth: 2,
            }),
            ctx.UI.Text({
              text: text.loading,
              style: "bodyMedium",
              color: colors.onPrimaryContainer,
            }),
          ],
        ),
      ),
    );
  } else if (book && (page === "reading" || page === "generate")) {
    const chapterText = useEnglish
      ? `${text.chapterPrefix} ${book.currentChapterNumber || "?"}`
      : `${text.chapterPrefix}${book.currentChapterNumber || "?"}章`;
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.primaryContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.connectedTitle,
            style: "labelLarge",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: String(book.book || ""),
            style: "titleMedium",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: `${chapterText} · ${String(book.currentChapterTitle || "")}`,
            style: "bodyMedium",
            color: colors.onPrimaryContainer,
          }),
          ctx.UI.Text({
            text: book.preciseCurrentPositionAvailable
              ? text.preciseProgress
              : text.chapterProgress,
            style: "bodySmall",
            color: colors.onPrimaryContainer,
          }),
        ]),
      ),
    );
  } else if (!book && basicEnabledState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.errorContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.disconnectedTitle,
            style: "titleMedium",
            color: colors.onErrorContainer,
          }),
          ctx.UI.Text({
            text: text.disconnectedBody,
            style: "bodyMedium",
            color: colors.onErrorContainer,
          }),
        ]),
      ),
    );
  }

  if (book && page === "reading") children.push(ctx.UI.OutlinedButton({ fillMaxWidth: true,
    enabled: !isBusy() && !tasksState.value.some(task => readingTools.active(task)),
    onClick: async () => {
      try {
        await readingTools.tasks.start(ctx, { kind: "cache", book_id: book.bookId, count: 1 });
        await watchTasks();
      } catch (error) { errorState.set(toErrorText(error)); }
    },
  }, ctx.UI.Text({ text: useEnglish ? "Cache downloaded past chapters / Resume" : "缓存已下载旧章 / 继续缓存" })));

  const bookTasks = tasksState.value.filter(task => book && task.bookId === book.bookId);
  const activeTasks = bookTasks.filter(task => readingTools.active(task));
  const shownTasks = page === "records" ? bookTasks.slice(0, 10)
    : page === "reading" ? (activeTasks.length ? activeTasks : bookTasks.slice(0, 1)) : [];
  for (const task of shownTasks) {
    children.push(ctx.UI.Card({ fillMaxWidth: true }, ctx.UI.Column({ padding: 12, spacing: 6 }, [
      ctx.UI.Text({ text: page === "reading" ? (task.kind === "cache" ? (useEnglish ? "Past chapter cache" : "旧章缓存") : readingTools.taskLabel(task, useEnglish)) : readingTools.taskLabel(task, useEnglish), style: "titleSmall" }),
      ctx.UI.Text({ text: `${readingTools.taskStatusLabel(task, useEnglish)} · ${readingTools.taskProgress(task, useEnglish)}` }),
      ...(readingTools.taskErrors(task, useEnglish) ? [ctx.UI.Text({ text: readingTools.taskErrors(task, useEnglish) })] : []),
      ctx.UI.OutlinedButton({onClick: async () => {
        const attempt = (task.attempts || []).filter(item => !item.archived && item.runId).slice(-1)[0];
        if (attempt) {
          await ctx.setEnv(historyTools.RUN_ID_ENV_KEY,String(attempt.runId));
          await ctx.navigate(historyTools.DETAIL_ROUTE);
        } else await ctx.navigate(HISTORY_ROUTE);
      }},ctx.UI.Text({text: useEnglish ? "View progress" : "查看过程"})),
      ...readingTools.retryChapters(task).map(index => ctx.UI.OutlinedButton({enabled: !isBusy(), onClick: async () => {
        busyState.set(true);
        try {await readingTools.retryChapter(ctx,task,index); await watchTasks();}
        catch (error) {errorState.set(toErrorText(error));}
        finally {busyState.set(false);}
      }},ctx.UI.Text({text: useEnglish ? `Retry chapter ${index+1}` : `重试第 ${index+1} 章`}))),
      ...(readingTools.active(task) ? [ctx.UI.OutlinedButton({ onClick: async () => {
        try { await readingTools.tasks.cancel(ctx, task.task_id); await watchTasks(); }
        catch (error) { errorState.set(toErrorText(error)); }
      } }, ctx.UI.Text({ text: task.kind === "cache" ? (useEnglish ? "Pause caching" : "暂停缓存") : (useEnglish ? "Cancel task" : "取消任务") }))] : []),
    ])));
  }

  if (page === "reading") {
    if (!loadingState.value && !basicEnabledState.value) {
      children.push(ctx.UI.Button({ enabled: !isBusy(), onClick: () => toggleBasic(true) },
        ctx.UI.Text({ text: useEnglish ? "Enable Reading Companion" : "开启阅读伴侣" })));
    }
    children.push(ctx.UI.Row({ fillMaxWidth: true, spacing: 8 }, [
      ctx.UI.OutlinedButton({ weight: 1, enabled: !!book && !isBusy(), onClick: openSummaries }, ctx.UI.Text({ text: useEnglish ? "Summaries" : "章节摘要" })),
      ctx.UI.OutlinedButton({ weight: 1, enabled: !!book && !isBusy(), onClick: openFiles }, ctx.UI.Text({ text: useEnglish ? "Book files" : "书籍文件" })),
    ]));
    children.push(ctx.UI.Card({ fillMaxWidth: true, containerColor: colors.surfaceVariant },
      ctx.UI.Column({ padding: 12, spacing: 6 }, [
        ctx.UI.Text({ text: useEnglish
          ? "Reading Companion only supports the Legado build maintained by CATMIAOZHI. Official Legado and other unadapted builds are not supported."
          : "阅读伴侣仅支持我们维护的 Legado 适配版，不支持官方版及其他未适配版本。", style: "bodySmall" }),
        ctx.UI.OutlinedButton({ onClick: async () => {
          try {
            readingTools.unwrapToolResult(await ctx.callTool("execute_intent", {
              type: "activity", action: "android.intent.action.VIEW",
              uri: "https://github.com/CATMIAOZHI/legado/releases/latest",
            }));
          } catch (error) { errorState.set(toErrorText(error)); }
        } }, ctx.UI.Text({ text: useEnglish ? "Download our Legado build" : "下载 Legado 适配版" })),
      ])));
  }

  if (book && page === "settings") {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.surface,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          ctx.UI.Text({
            text: text.personaTitle,
            style: "titleMedium",
            color: colors.onSurface,
          }),
          ctx.UI.Box({ fillMaxWidth: true }, [
            ctx.UI.OutlinedButton(
              {
                fillMaxWidth: true,
                enabled: !isBusy(),
                onClick: loadCardPicker,
              },
              [
                ctx.UI.Row(
                  {
                    fillMaxWidth: true,
                    horizontalArrangement: "spaceBetween",
                    verticalAlignment: "center",
                  },
                  [
                    ctx.UI.Text({
                      text: selectedRoleCardName || text.personaSelect,
                      color: colors.onSurface,
                      fontWeight: selectedRoleCardName ? "medium" : "normal",
                      maxLines: 1,
                      overflow: "ellipsis",
                    }),
                    ctx.UI.Icon({
                      name: showCardPickerState.value
                        ? "arrowDropUp"
                        : "arrowDropDown",
                      tint: colors.onSurfaceVariant,
                      size: 20,
                    }),
                  ],
                ),
              ],
            ),
            ctx.UI.DropdownMenu(
              {
                expanded: showCardPickerState.value,
                modifier: ctx.Modifier.heightIn({ maxHeight: 520 }),
                properties: {
                  focusable: true,
                  usePlatformDefaultWidth: false,
                },
                onDismissRequest: () => showCardPickerState.set(false),
              },
              loadingCardsState.value
                ? [
                    ctx.UI.Box(
                      {
                        modifier: ctx.Modifier
                          .fillMaxWidth()
                          .padding({ horizontal: 16, vertical: 12 }),
                      },
                      [
                        ctx.UI.Text({
                          text: text.personaLoading,
                          color: colors.onSurfaceVariant,
                        }),
                      ],
                    ),
                  ]
                : availableCardsState.value.length === 0
                  ? [
                      ctx.UI.Box(
                        {
                          modifier: ctx.Modifier
                            .fillMaxWidth()
                            .padding({ horizontal: 16, vertical: 12 }),
                        },
                        [
                          ctx.UI.Text({
                            text: text.personaNoCards,
                            color: colors.onSurfaceVariant,
                          }),
                        ],
                      ),
                    ]
                  : availableCardsState.value.map((card) =>
                      ctx.UI.Box(
                        {
                          modifier: ctx.Modifier
                            .fillMaxWidth()
                            .clickable(() => selectCommentaryCharacter(card))
                            .padding({ horizontal: 16, vertical: 12 }),
                        },
                        [
                          ctx.UI.Row(
                            {
                              fillMaxWidth: true,
                              horizontalArrangement: "spaceBetween",
                              verticalAlignment: "center",
                            },
                            [
                              ctx.UI.Column({ weight: 1, spacing: 2 }, [
                                ctx.UI.Text({
                                  text: card.name,
                                  color: colors.onSurface,
                                  fontWeight:
                                    card.id === selectedRoleCardId
                                      ? "bold"
                                      : "normal",
                                  maxLines: 1,
                                  overflow: "ellipsis",
                                }),
                                card.description
                                  ? ctx.UI.Text({
                                      text: card.description,
                                      style: "bodySmall",
                                      color: colors.onSurfaceVariant,
                                      maxLines: 1,
                                      overflow: "ellipsis",
                                    })
                                  : ctx.UI.Spacer({ height: 0 }),
                              ]),
                              card.id === selectedRoleCardId
                                ? ctx.UI.Icon({
                                    name: "check",
                                    tint: colors.primary,
                                    size: 18,
                                  })
                                : ctx.UI.Spacer({ width: 18 }),
                            ],
                          ),
                        ],
                      ),
                    ),
            ),
          ]),
          ctx.UI.Text({
            text: text.personaHint,
            style: "bodySmall",
            color: colors.onSurfaceVariant,
          }),
        ]),
      ),
    );
  }

  if (book && page === "generate") {
    const commentCount = Number(batchCommentCountState.value || 0);
    const summaryCount = Number(batchSummaryCountState.value || 0);
    const commentStart = String(batchCommentStartState.value || "").trim();
    const commentEnd = String(batchCommentEndState.value || "").trim();
    const summaryStart = String(batchSummaryStartState.value || "").trim();
    const summaryEnd = String(batchSummaryEndState.value || "").trim();
    const parsedCommentStart = Number(commentStart);
    const parsedCommentEnd = Number(commentEnd);
    const readRangeCount =
      Number.isInteger(parsedCommentStart) &&
      Number.isInteger(parsedCommentEnd) &&
      parsedCommentStart >= 1 &&
      parsedCommentEnd >= parsedCommentStart
        ? parsedCommentEnd - parsedCommentStart + 1
        : 0;
    const rangeText = (start, end, count) => {
      if (start && end) {
        return text.batchRange(start, end, Math.max(0, count));
      }
      if (start) {
        return text.batchRange(
          start,
          `${start}+${Math.max(0, count) - 1}`,
          Math.max(0, count),
        );
      }
      return text.batchCalls(Math.max(0, count));
    };
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.tertiaryContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 10 }, [
          ctx.UI.Text({
            text: text.manualTitle,
            style: "titleMedium",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.Text({
            text: text.manualHint,
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.Text({
            text: text.readCommentaryTitle,
            style: "labelLarge",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.Text({
            text: text.readCommentaryHint,
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.Row({ fillMaxWidth: true, spacing: 8 }, [
            ctx.UI.TextField({
              weight: 1,
              label: text.batchCommentStart,
              value: batchCommentStartState.value,
              onValueChange: batchCommentStartState.set,
              singleLine: true,
              enabled: !isBusy(),
            }),
            ctx.UI.TextField({
              weight: 1,
              label: text.batchCommentEnd,
              value: batchCommentEndState.value,
              onValueChange: batchCommentEndState.set,
              singleLine: true,
              enabled: !isBusy(),
            }),
          ]),
          ctx.UI.Text({
            text:
              readRangeCount > 10
                ? text.readRangeTooLarge
                : readRangeCount > 0
                ? text.batchRange(commentStart, commentEnd, readRangeCount)
                : text.readRangeRequired,
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.OutlinedButton({
            fillMaxWidth: true,
            enabled:
              basicEnabledState.value &&
              !!selectedRoleCardId &&
              readRangeCount > 0 &&
              readRangeCount <= 10 &&
              !isBusy(),
            onClick: () => runManualBatch("comments", null, "read"),
          }, ctx.UI.Text({ text: text.manualReadComments })),
          ctx.UI.Text({
            text: text.aheadCommentaryTitle,
            style: "labelLarge",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.Text({
            text: text.aheadCommentaryHint,
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.TextField({
            fillMaxWidth: true,
            label: text.batchCount,
            value: batchCommentCountState.value,
            onValueChange: batchCommentCountState.set,
            singleLine: true,
            enabled: !isBusy(),
          }),
          ctx.UI.Text({
            text:
              Number.isInteger(commentCount) &&
              commentCount >= 1 &&
              commentCount <= 10
                ? text.batchCalls(commentCount)
                : text.aheadCountRequired,
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.OutlinedButton({
            fillMaxWidth: true,
            enabled:
              basicEnabledState.value &&
              !!selectedRoleCardId &&
              Number.isInteger(commentCount) &&
              commentCount >= 1 &&
              commentCount <= 10 &&
              !isBusy(),
            onClick: () => runManualBatch("comments", null, "ahead"),
          }, ctx.UI.Text({ text: text.manualComments })),
          ctx.UI.Text({
            text: useEnglish ? "Chapter summary batch" : "章节摘要批次",
            style: "labelLarge",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.Text({
            text: text.batchSummaryHint,
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.TextField({
            fillMaxWidth: true,
            label: text.batchBudget,
            value: batchSummaryCountState.value,
            onValueChange: batchSummaryCountState.set,
            singleLine: true,
            enabled: !isBusy(),
          }),
          ctx.UI.Row({ fillMaxWidth: true, spacing: 8 }, [
            ctx.UI.TextField({
              weight: 1,
              label: text.batchStart,
              value: batchSummaryStartState.value,
              onValueChange: batchSummaryStartState.set,
              singleLine: true,
              enabled: !isBusy(),
            }),
            ctx.UI.TextField({
              weight: 1,
              label: text.batchEnd,
              value: batchSummaryEndState.value,
              onValueChange: batchSummaryEndState.set,
              singleLine: true,
              enabled: !isBusy(),
            }),
          ]),
          ctx.UI.Text({
            text: rangeText(summaryStart, summaryEnd, summaryCount),
            style: "bodySmall",
            color: colors.onTertiaryContainer,
          }),
          ctx.UI.OutlinedButton({
            fillMaxWidth: true,
            enabled: !isBusy(),
            onClick: () => runManualBatch("summaries"),
          }, ctx.UI.Text({ text: text.manualSummaries })),
          ctx.UI.OutlinedButton({
            fillMaxWidth: true,
            enabled: !isBusy(),
            onClick: () => fillAllReadChapters(),
          }, ctx.UI.Text({ text: text.fillAllRead })),
        ]),
      ),
    );
  }

  if (page === "settings" && book) {
    const configuredModel = commentaryConfiguration
      ? `${modelSourceLabel(
          text,
          commentaryConfiguration.modelSource,
        )} · ${String(commentaryConfiguration.modelConfigName || "")} / ${String(
          commentaryConfiguration.model || "",
        )}`
      : String(selectedPersonaState.value?.configurationError || text.modelSourceUnknown);
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.secondaryContainer,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.configTitle,
            style: "titleMedium",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: `${text.configCharacter} · ${
              selectedRoleCardName || text.personaSelect
            }`,
            style: "bodyMedium",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: `${text.configModel} · ${configuredModel}`,
            style: "bodyMedium",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configTrigger,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configReading,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configDensity,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.configDelivery,
            style: "bodySmall",
            color: colors.onSecondaryContainer,
          }),
          ctx.UI.Text({
            text: text.changeModelHint,
            style: "labelSmall",
            color: colors.onSecondaryContainer,
          }),
        ]),
      ),
    );
  }

  if (page === "records" && (autoEnabledState.value || latestRun)) {
    const flowRows = generationFlowRows(ctx, text, colors, latestRun);
    const durationText = latestRun
      ? formatDuration(text, latestRun.durationMs)
      : "";
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.surface,
        },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.latestRun,
            style: "titleMedium",
            color: colors.onSurface,
          }),
          ctx.UI.Text({
            text: latestRun
              ? `${statusLabel(text, latestRun.status)}${
                  Number(latestRun.commentCount || 0) > 0
                    ? ` · ${latestRun.commentCount} ${text.comments}`
                    : ""
                }`
              : text.noRun,
            style: "bodyMedium",
            color: latestRun
              ? statusColor(latestRun.status)
              : colors.onSurfaceVariant,
          }),
          latestRun && latestRun.roleCardName
            ? ctx.UI.Text({
                text: `${text.configCharacter} · ${String(
                  latestRun.roleCardName,
                )}\n${text.configModel} · ${String(
                  latestRun.modelConfigName ||
                    latestRun.model ||
                    latestRun.provider ||
                    "",
                )}${
                  latestRun.model &&
                  latestRun.model !== latestRun.modelConfigName
                    ? ` / ${String(latestRun.model)}`
                    : ""
                }`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun
            ? ctx.UI.Text({
                text: `${runTriggerLabel(text, latestRun.trigger)}${
                  durationText ? ` · ${text.durationLabel} ${durationText}` : ""
                }`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && Number(latestRun.targetCharacterCount || 0) > 0
            ? ctx.UI.Text({
                text: `${text.targetLabel} ${Number(
                  latestRun.targetCharacterCount,
                )} ${text.characters} · ${text.contextLabel} ${Number(
                  latestRun.contextChapterCount || 0,
                )} 章 / ${Number(
                  latestRun.contextCharacterCount || 0,
                )} ${text.characters}`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && Number(latestRun.estimatedInputTokens || 0) > 0
            ? ctx.UI.Text({
                text: `${text.inputLabel} ${Number(
                  latestRun.estimatedInputTokens,
                )} ${text.tokens} · ${text.contextWindowLabel} ${Number(
                  latestRun.contextWindowTokens || 0,
                )} ${text.tokens}`,
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
          latestRun && latestRun.error
            ? ctx.UI.Text({
                text: autoCommentErrorLabel(
                  text,
                  latestRun.error,
                  latestRun.stage,
                ),
                style: "bodySmall",
                color: colors.error,
              })
            : ctx.UI.Spacer({ height: 0 }),
          flowRows.length > 0
            ? ctx.UI.Text({
                text: text.flowTitle,
                style: "labelLarge",
                color: colors.onSurface,
              })
            : ctx.UI.Spacer({ height: 0 }),
          ...flowRows,
          ctx.UI.Text({
            text: text.queuedHint,
            style: "bodySmall",
            color: colors.onSurfaceVariant,
          }),
          ctx.UI.OutlinedButton(
            {
              fillMaxWidth: true,
              enabled: !isBusy(),
              onClick: openHistory,
            },
            [
              ctx.UI.Icon({ name: "History", size: 18 }),
              ctx.UI.Text({ text: text.history }),
            ],
          ),
          ctx.UI.OutlinedButton(
            {
              fillMaxWidth: true,
              enabled: !isBusy(),
              onClick: openSummaries,
            },
            [
              ctx.UI.Icon({ name: "Description", size: 18 }),
              ctx.UI.Text({ text: text.summaries }),
            ],
          ),
          ctx.UI.OutlinedButton(
            {
              fillMaxWidth: true,
              enabled: !isBusy(),
              onClick: openFiles,
            },
            [
              ctx.UI.Icon({ name: "FolderOpen", size: 18 }),
              ctx.UI.Text({ text: text.files }),
            ],
          ),
        ]),
      ),
    );
  }

  if (page === "records" && !autoEnabledState.value && !latestRun) {
    children.push(ctx.UI.OutlinedButton({ fillMaxWidth: true, enabled: basicEnabledState.value && !isBusy(), onClick: openHistory },
      ctx.UI.Text({ text: text.history })));
  }

  const auditPayload = auditGroupsState.value;
  const auditGroups =
    auditPayload && Array.isArray(auditPayload.groups) ? auditPayload.groups : [];
  if (page === "records" && auditGroups.length > 0) {
    const auditTotal = Number(
      auditPayload.totalRunChats || 0,
    );
    const auditShown = Number(
      auditPayload.shownRunChats || 0,
    );
    const hasHiddenAuditChats = auditTotal > auditShown && auditShown > 0;
    children.push(
      ctx.UI.Card(
        { fillMaxWidth: true, containerColor: colors.surface },
        ctx.UI.Column({ fillMaxWidth: true, padding: 16, spacing: 8 }, [
          ctx.UI.Text({
            text: text.auditTitle,
            style: "titleMedium",
            color: colors.onSurface,
          }),
          ...auditGroups.map((group) => {
            const groupBook = String(
              (group && (group.bookName || group.bookId)) || "",
            ).trim();
            const root =
              group && group.root && typeof group.root === "object"
                ? group.root
                : null;
            const groupChats =
              group && Array.isArray(group.chats) ? group.chats : [];
            return ctx.UI.Column({ fillMaxWidth: true, spacing: 4 }, [
              ctx.UI.Text({
                text: groupBook || (useEnglish ? "Book" : "书籍"),
                style: "labelLarge",
                color: colors.onSurface,
              }),
              root && root.chatId
                ? ctx.UI.Text({
                    text:
                      (useEnglish
                        ? "Audit parent · "
                        : "审计父聊天 · ") + (root.title || root.chatId),
                    style: "bodySmall",
                    color: colors.onSurfaceVariant,
                    maxLines: 1,
                    overflow: "ellipsis",
                  })
                : ctx.UI.Spacer({ height: 0 }),
              ...groupChats.map((chat) =>
                chat && chat.chatId && chat.runId
                  ? ctx.UI.Row(
                      {
                        fillMaxWidth: true,
                        spacing: 8,
                        verticalAlignment: "center",
                        modifier: ctx.Modifier
                          .fillMaxWidth()
                          .clickable(() => openAuditChatByRunId(chat.runId)),
                      },
                      [
                        ctx.UI.Column({ weight: 1, spacing: 2 }, [
                          ctx.UI.Text({
                            text: String(chat.title || "").trim() ||
                              (useEnglish ? "Run chat" : "任务聊天"),
                            style: "bodyMedium",
                            color: colors.onSurface,
                            maxLines: 1,
                            overflow: "ellipsis",
                          }),
                          ctx.UI.Text({
                            text:
                              `${statusLabel(text, chat.status)} · ` +
                              runTriggerLabel(text, chat.trigger),
                            style: "bodySmall",
                            color: colors.onSurfaceVariant,
                          }),
                        ]),
                        ctx.UI.Text({
                          text: useEnglish ? "Open ›" : "打开 ›",
                          style: "labelMedium",
                          color: colors.primary,
                        }),
                      ],
                    )
                  : ctx.UI.Spacer({ height: 0 }),
              ),
            ]);
          }),
          ctx.UI.Text({
            text: text.auditDeleteHint,
            style: "bodySmall",
            color: colors.onSurfaceVariant,
          }),
          hasHiddenAuditChats
            ? ctx.UI.Text({
                text: text.auditListSummary(auditTotal, auditShown),
                style: "bodySmall",
                color: colors.onSurfaceVariant,
              })
            : ctx.UI.Spacer({ height: 0 }),
        ]),
      ),
    );
  }

  if (noticeState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.secondaryContainer,
        },
        ctx.UI.Text({
          text: noticeState.value,
          style: "bodyMedium",
          color: colors.onSecondaryContainer,
          padding: 14,
        }),
      ),
    );
  }

  if (errorState.value) {
    children.push(
      ctx.UI.Card(
        {
          fillMaxWidth: true,
          containerColor: colors.errorContainer,
        },
        ctx.UI.Text({
          text: errorState.value,
          style: "bodyMedium",
          color: colors.onErrorContainer,
          padding: 14,
        }),
      ),
    );
  }

  if (isBusy() && busyLabelState.value) {
    children.push(
      ctx.UI.Column(
        { fillMaxWidth: true, spacing: 8 },
        [
          ctx.UI.Row(
            {
              fillMaxWidth: true,
              spacing: 10,
              verticalAlignment: "center",
            },
            [
              ctx.UI.CircularProgressIndicator({
                width: 18,
                height: 18,
                strokeWidth: 2,
              }),
              ctx.UI.Text({
                text: busyLabelState.value,
                style: "bodyMedium",
                color: colors.onSurfaceVariant,
              }),
            ],
          ),

        ],
      ),
    );
  }

  if (page === "generate" && basicEnabledState.value) {
    children.push(
      ctx.UI.OutlinedButton(
        {
          fillMaxWidth: true,
          enabled: !!book && !isBusy(),
          onClick: regenerateComments,
        },
        [
          ctx.UI.Icon({ name: "AutoMode", size: 20 }),
          ctx.UI.Text({ text: text.regenerate }),
        ],
      ),
    );
  }

  children.push(
    ctx.UI.Row(
      { fillMaxWidth: true, spacing: 10 },
      [
        ctx.UI.OutlinedButton(
          {
            weight: 1,
            enabled: !isBusy(),
            onClick: () => loadDashboard(true),
          },
          [
            ctx.UI.Icon({ name: "Refresh", size: 18 }),
            ctx.UI.Text({ text: text.refresh }),
          ],
        ),
        ...(page === "settings" ? [ctx.UI.OutlinedButton(
          {
            weight: 1,
            onClick: () => ctx.navigate("native.packages"),
          },
          [
            ctx.UI.Icon({ name: "Extension", size: 18 }),
            ctx.UI.Text({ text: text.manage }),
          ],
        )] : []),
      ],
    ),
    ...(page === "settings" ? [ctx.UI.Text({
      text: text.hint,
      style: "bodySmall",
      color: colors.onSurfaceVariant,
    })] : []),
  );

  return ctx.UI.Column(
    {
      onLoad: async () => {
        readingTools.activate("entry");
        if (!initializedState.value) {
          initializedState.set(true);
          await loadDashboard(false);
          await watchTasks();
        }
      },
      onResume: async () => {
        readingTools.activate("entry");
        await watchTasks();
        if (initializedState.value && !isBusy() && !loadingState.value) { await loadDashboard(false, true); await watchTasks(); }
      },
      onPause: () => readingTools.pause("entry"),
      fillMaxSize: true,
      spacing: 0,
    },
    [tabs, ctx.UI.Box({ fillMaxWidth: true, weight: 1 }, pages.map(([id]) =>
      // Distinct slots dispose the previous list so each tab opens at the top; drafts live above.
      page === id ? ctx.UI.LazyColumn({ fillMaxSize: true, padding: 16, spacing: 12 }, children)
        : ctx.UI.Spacer({ width: 0, height: 0 })))],
  );
}

exports.default = readingCompanionEntryScreen;
exports.unwrapToolResult = unwrapToolResult;
