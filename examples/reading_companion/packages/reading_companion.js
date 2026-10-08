/* METADATA
{
  "name": "reading_companion",
  "display_name": {
    "zh": "阅读伴侣",
    "en": "Reading Companion"
  },
  "description": {
    "zh": "阅读伴侣：当前阅读上下文、人物回顾与 AI 伴读记忆。生成任务请用 reading_companion_tasks，文件与设置用 reading_companion_manage。",
    "en": "Reading companion context, recall and AI companion memory. Use reading_companion_tasks for generation and reading_companion_manage for files/settings."
  },
  "enabledByDefault": true,
  "category": "AI Reading Companion",
  "tools": [
    {
      "name": "usage_advice",
      "description": {
        "zh": "AI 阅读伴侣\n- 仅当用户正在谈论书籍、小说、阅读进度或 Legado 时使用以下能力；其他问题按普通聊天处理，不要引导伴读工具。\n- 常规上下文工具只读取 Legado 当前阅读位置之前的内容；用户明确要求提前阅读时，可以通过 get_local_files 访问已经生成的未来章节文件。\n- 用户想交流、吐槽“这段”或刚发生的情节时，先调用 reading_companion:get_context；它会返回当前章安全前缀、最近已读章节、本地摘要/结构化知识、读者记忆和该角色已解锁的 AI 段评。\n- companionMemoryPath 指向当前角色自己的 AI 伴读记忆 ai-memory.md。回复前主动读取，以第一人称延续你的看法、感受和共同话题。你可以并且应该主动维护它，无需等待用户要求或逐次确认；阅读和讨论产生值得延续的新看法、猜测、疑问、情绪、共同话题或互动经验时，用 edit 整合更新，修正过时认识、合并重复内容，不必机械记录每轮对话。\n- get_context 返回 charactersPath 时，用独立的 characters.md 维护主要人物的身份、别名、人物关系、当前变化和你的简短看法。需要延续人物认知时先读取；只有当前安全阅读证据让主要人物信息发生实质变化时才用 edit 整合更新，不要写成逐章流水账。\n- 阅读伴侣文件可能包含未来章节摘要。除非用户明确要求查看或修改未来章节，否则不得打开、搜索、引用或透露当前章节及之后章节的文件。这是提示词层面的默认防剧透约束，不是文件系统技术隔离。\n- 主聊天检索与生成任务是独立流程。生成任务由程序提供固定提示词、目标章、近期前文和角色信息，不需要你事先搜索或准备材料。复杂聊天检索可调用 task 委派检索 subagent，传入本书真实文件路径和问题，要求返回原文证据、文件位置与读取范围。需要直接访问文件时，先调用 reading_companion:get_local_files 获取当前书籍的真实路径。普通回顾只能 grep safeSearchPaths、charactersPath 和 companionMemoryPath；用户明确要求提前阅读时改用 allCurrentSearchPaths。绝不能直接 grep chaptersRootPath，因为其中可能保留已失效章节目录。再用 read_file 读取命中的 content.md、summary.md、comments.json、characters.md 或 ai-memory.md。content.md 是最近一次成功从 Legado 获取的正文只读快照；之后 Legado 正文或净化规则可能变化，只有插件再次实际处理该章时才会刷新。绝不能 edit content.md；只有 characters.md、ai-memory.md 或用户明确允许的摘要可以 edit。\n- ai-memory.md 的记忆主体是你这个 AI 角色，不是用户；它记录你自己的伴读经历，不是用户记忆档案或客观剧情数据库。记录当前看法、明确标为未证实的猜测、共同话题和互动偏好；绝不能写入后台段评提前读取、但用户尚未读到的剧情。\n- 用户询问你之前写过什么段评、或提到你的某条段评时，调用 reading_companion:get_recent_comments；只能把工具返回的已解锁段评视为你自己写过的内容。\n- 用户询问前文人物、事件、原因、对话、地点或物品时，先调用 reading_companion:get_local_files，只 grep safeSearchPaths 以及返回的人物/记忆路径，再用 read_file 读取命中文件；不得使用 chaptersRootPath，也不要调用独立语义搜索工具。\n- 回顾章节或人物时，优先使用 reading_companion:get_chapter_summary、reading_companion:get_recent_summaries 或 reading_companion:get_character。\n- 具体小说事实只能依据 reading_companion 工具返回的证据或 get_local_files 返回的本地文件；不得联网搜索剧情或依赖外部剧情知识。若用户要求当前精确原文，应明确 content.md 是最近一次成功获取的快照，不得声称已经实时向 Legado 核验。\n- readerMemories 是另一类独立的读者笔记、反应、问题或预测，不是 companionMemoryPath 中的 AI 记忆，也不是小说已确认事实；companionComments 是你自己已经解锁的段评，也不是小说事实。\n- 没有安全依据时明确说未检索到，不要猜测。",
        "en": "AI READING COMPANION\n- Use these instructions only when the user is asking about a book, novel, reading progress, or Legado. For unrelated requests, behave like a normal chat and ignore this section.\n- Normal context tools read Legado only up to the reader's current position. If the user explicitly asks you to read ahead, get_local_files may expose future generated files for that purpose.\n- For reactions, banter, or discussion about \"this part\", call reading_companion:get_context first. It returns the current safe prefix, recent read chapters, local summaries/structured knowledge, reader memories, and this role's unlocked AI comments.\n- companionMemoryPath points to this role's own AI companion memory, ai-memory.md. Proactively read it before replying and continue your views, feelings, and shared topics in the first person. You can and should maintain it proactively, without waiting for a user request or per-update approval. Use edit to integrate worthwhile new views, predictions, questions, reactions, shared topics, and interaction experience; revise outdated views and merge duplicates rather than mechanically logging every turn.\n- When get_context returns charactersPath, use that separate characters.md for major-character identities, aliases, relationships, current changes, and your concise impressions. Read it when character continuity matters and update it with edit only when the current safe reading evidence materially changes a major character; do not turn it into a chapter-by-chapter log.\n- Reading Companion files may contain future summaries. Unless the user explicitly asks to inspect or edit future chapters, do not open, search, quote, or reveal the current chapter's or any later chapter's files. This is a best-effort anti-spoiler rule, not a technical filesystem boundary.\n- When you need direct file access, call reading_companion:get_local_files first. For ordinary recall, grep only safeSearchPaths plus charactersPath and companionMemoryPath. If the user explicitly asks to read future chapters, grep allCurrentSearchPaths instead. Never grep chaptersRootPath directly because it may retain inactive chapter directories. Read only matching content.md, summary.md, comments.json, characters.md, or ai-memory.md files. content.md is the last successfully fetched read-only snapshot; Legado content or cleanup rules may have changed since it was fetched, and it refreshes only when the plugin actually processes that chapter again. Never edit content.md. Edit only characters.md, ai-memory.md, or a user-approved summary.\n- ai-memory.md belongs to you, the AI role, and records your own companion experience; it is not the user's memory profile or an objective plot database. Keep current views, clearly unconfirmed predictions, shared topics, and interaction preferences. Never write knowledge obtained by background commentary generation before the reader reaches it.\n- If the user asks what you commented before, or refers to one of your paragraph comments, call reading_companion:get_recent_comments. Treat only returned unlocked comments as your own past comments.\n- For earlier characters, events, causes, dialogue, places, or items, call reading_companion:get_local_files first, grep only safeSearchPaths plus the returned character/memory paths, then read_file the matches. Do not use chaptersRootPath or a separate semantic search tool for ordinary recall.\n- For chapter recap or character profiles, prefer reading_companion:get_chapter_summary, reading_companion:get_recent_summaries, or reading_companion:get_character.\n- Novel facts must come only from reading_companion tool evidence or files returned by get_local_files. Do not use web search or outside plot knowledge. If the user asks for the exact current wording, state that content.md is the last successfully fetched snapshot and do not claim it was verified against Legado in real time.\n- readerMemories are separate reader notes, reactions, questions, or predictions; they are neither the AI memory at companionMemoryPath nor confirmed novel facts. companionComments are your own unlocked commentary, not novel facts.\n- If no safe evidence is returned, say so instead of guessing.\n- Chat retrieval and generation are independent. The program supplies generation tasks with fixed instructions, target chapter, recent context and persona. Do not prepare generation materials in the main chat first. For complex chat retrieval, delegate task with this book's real paths and question, requesting original evidence and file ranges."
      },
      "parameters": [],
      "advice": true
    },
    {
      "name": "list_books",
      "description": {
        "zh": "列出 Legado 书架，并显示当前采用自动最近阅读还是手动选书。",
        "en": "List the Legado bookshelf and the current automatic or manual selection mode."
      },
      "parameters": []
    },
    {
      "name": "select_book",
      "description": {
        "zh": "按完整书名或 book_id 手动选择伴读书籍；automatic=true 恢复自动跟随最近阅读。",
        "en": "Select a book by full name or book_id; automatic=true restores recent-book tracking."
      },
      "parameters": [
        {
          "name": "book",
          "description": {
            "zh": "书名或 book_id；自动模式可省略",
            "en": "Book name or book_id; omit for automatic mode"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "automatic",
          "description": {
            "zh": "是否恢复自动跟随最近阅读",
            "en": "Restore automatic recent-book tracking"
          },
          "type": "boolean",
          "required": false,
          "default": false
        }
      ]
    },
    {
      "name": "get_current_book",
      "description": {
        "zh": "获取当前伴读书籍、章节和安全阅读位置。",
        "en": "Get the current companion book, chapter, and safe reading position."
      },
      "parameters": []
    },
    {
      "name": "get_context",
      "description": {
        "zh": "获取已读边界内的对话上下文及当前角色的 AI 记忆路径 companionMemoryPath：正文、前文、摘要、独立的读者笔记和已解锁段评。主动读取并维护自己的 AI 记忆。用户讨论“这段”或刚发生的剧情时使用；根据 positionSource 区分实时页面与历史进度，无法确认时不得把旧快照冒充当前情节。",
        "en": "Get spoiler-safe context and this role's AI memory path companionMemoryPath: text, prior chapters, summaries, separate reader notes, and unlocked comments. Proactively read and maintain your AI memory. For current-scene discussion, use positionSource to distinguish the live viewport from saved progress; never pass old snapshots off as the current scene."
      },
      "parameters": [
        {
          "name": "max_characters",
          "description": {
            "zh": "完整工具结果的序列化字符预算，8000 到 96000，默认 16000；超出时硬裁剪",
            "en": "Serialized complete-result budget, 8000 to 96000; defaults to 16000 with hard trimming for overflow"
          },
          "type": "number",
          "required": false,
          "default": 16000
        }
      ]
    },
    {
      "name": "get_recent_comments",
      "description": {
        "zh": "读取当前角色自己已经写过、且用户已阅读解锁的近期段评。用户询问“你刚才怎么评价”“你之前写过什么段评”时使用。",
        "en": "Read this character's own recent comments that the user has already unlocked by reading."
      },
      "parameters": [
        {
          "name": "limit",
          "description": {
            "zh": "返回 1 到 50 条，默认 20",
            "en": "Return 1 to 50 comments; defaults to 20"
          },
          "type": "number",
          "required": false,
          "default": 20
        }
      ]
    },
    {
      "name": "get_chapter_summary",
      "description": {
        "zh": "读取当前或已读章节已有的摘要和人物、事件、地点、物品、关系变化、伏笔候选等结构化知识；缺失时不会自动调用模型。",
        "en": "Read existing structured knowledge for the current or a read chapter; missing summaries never trigger a model call automatically."
      },
      "parameters": [
        {
          "name": "chapter_number",
          "description": {
            "zh": "目录序号，从 1 开始（拆分子节分别计数，不是标题中的原著章号）；省略表示当前章",
            "en": "One-based catalog position, counting split sections separately; omit for current chapter"
          },
          "type": "number",
          "required": false
        }
      ]
    },
    {
      "name": "get_character",
      "description": {
        "zh": "从当前书籍的 characters.md 与有效已读章节文件查找人物证据；不依赖旧结构化索引，不调用模型。",
        "en": "Find character evidence in characters.md and active read chapter files; no legacy character index or model calls."
      },
      "parameters": [
        {
          "name": "name",
          "description": {
            "zh": "人物名或称呼",
            "en": "Character name or alias"
          },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "get_recent_summaries",
      "description": {
        "zh": "获取最近若干个已读章节的摘要，用于快速回顾前文。",
        "en": "Get recent read-chapter summaries for a quick recap."
      },
      "parameters": [
        {
          "name": "count",
          "description": {
            "zh": "数量 1 到 10，默认 5",
            "en": "Count from 1 to 10, default 5"
          },
          "type": "number",
          "required": false,
          "default": 5
        }
      ]
    },
    {
      "name": "get_local_files",
      "description": {
        "zh": "返回普通回顾专用的 safeSearchPaths、用户明确要求提前阅读时使用的 allCurrentSearchPaths，以及 book.md、characters.md 和当前角色 ai-memory.md 路径。禁止直接 grep chaptersRootPath，因为它可能保留已失效目录。content.md 是最近一次成功从 Legado 获取的正文只读快照；之后正文或净化规则可能变化，只有插件再次实际处理该章时才刷新，禁止 edit。",
        "en": "Return safeSearchPaths for ordinary recall, allCurrentSearchPaths for explicit read-ahead requests, and paths for book.md, characters.md, and this role's ai-memory.md. Never grep chaptersRootPath directly because it may retain inactive directories. content.md is the last successfully fetched read-only chapter snapshot; Legado content or cleanup rules may change afterward, and it refreshes only when the plugin actually processes that chapter again. Never edit it."
      },
      "parameters": []
    },
    {
      "name": "refresh_progress",
      "description": {
        "zh": "刷新 Legado 进度并增量索引新读正文；摘要只会在用户明确发起手动批量操作时生成。",
        "en": "Refresh Legado progress and incrementally index newly read text; summaries are generated only by an explicit manual batch."
      },
      "parameters": [
        {
          "name": "max_chapters",
          "description": {
            "zh": "本次最多索引章节数，默认 3",
            "en": "Maximum chapters indexed now, default 3"
          },
          "type": "number",
          "required": false,
          "default": 3
        }
      ]
    },
    {
      "name": "add_memory",
      "description": {
        "zh": "保存读者自己的笔记、情绪、猜测或待验证问题。它与小说事实索引分开，不能作为已确认剧情。",
        "en": "Save a reader note, reaction, prediction, or question separately from confirmed novel facts."
      },
      "parameters": [
        {
          "name": "type",
          "description": {
            "zh": "note、reaction、prediction 或 question",
            "en": "note, reaction, prediction, or question"
          },
          "type": "string",
          "required": false,
          "default": "note"
        },
        {
          "name": "content",
          "description": {
            "zh": "记忆内容",
            "en": "Memory content"
          },
          "type": "string",
          "required": true
        },
        {
          "name": "chapter_number",
          "description": {
            "zh": "目录序号，从 1 开始（拆分子节分别计数，不是标题中的原著章号）；省略表示当前章",
            "en": "One-based catalog position, counting split sections separately; omit for current chapter"
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

exports.list_books = (params = {}) => run("list_books", params);
exports.select_book = (params = {}) => run("select_book", params);
exports.get_current_book = (params = {}) => run("get_current_book", params);
exports.get_context = (params = {}) => run("get_context", params);
exports.get_recent_comments = (params = {}) => run("get_recent_comments", params);
exports.get_chapter_summary = (params = {}) => run("chapter_summary", params);
exports.get_character = (params = {}) => run("get_character", params);
exports.get_recent_summaries = (params = {}) => run("get_recent_summaries", params);
exports.get_local_files = (params = {}) => run("get_local_files", params);
exports.refresh_progress = (params = {}) => run("refresh_progress", params);
exports.add_memory = (params = {}) => run("save_reader_memory", params);
