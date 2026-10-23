/* METADATA
{
  "name": "dynamic_ui",
  "display_name": {"zh": "动态界面", "en": "Dynamic UI"},
  "description": {
    "zh": "在回答正文中嵌入原生步骤图、流程图、图表、表格和指标，与文字自然穿插。只要是给用户讲解、介绍、分析或对比内容（概念、原理、流程、方案、数据、产品等），就先加载本包（use_package）并读 read_guide，不必等用户要求。加载后由你决定用不用：多步骤过程、数据趋势或对比、多项属性对照、需要用户选择/输入/试算时，用界面块配合文字；简单定义、观点、简短回答、代码、普通列表和只为装饰的内容，读完后直接写 Markdown。纯展示即可就不加按钮或表单。汇报有依据的状态或结果（如构建通过/失败、任务完成/进行中、风险等级）时，也可在句子里加行内徽章或小图标。",
    "en": "Embed native step diagrams, flows, charts, tables and metrics inside the answer, interleaved with prose. Whenever you explain, introduce, analyze or compare something for the user (concepts, how things work, processes, plans, data, products), load this package (use_package) and read read_guide first, without being asked. After loading, you decide whether to use it: use UI blocks alongside prose for multi-step processes, data trends or comparisons, side-by-side attributes, or when the user must choose, enter or calculate; for simple definitions, opinions, short answers, code, plain lists or decoration, answer in plain Markdown after reading. Add no buttons or forms when display alone suffices. When reporting an evidence-backed status or result (build passed/failed, task done/running, risk level), you may also add inline badges or small icons inside sentences."
  },
  "category": "UI",
  "enabledByDefault": true,
  "tools": [
    {
      "name": "prepare_html",
      "description": {"zh": "仅当用户明确要求高级视觉效果或 HTML/CSS 卡片时使用，不得为了美化主动调用。普通内容用原生 UI。创建渐变、纹理、动画等离线 HTML/CSS 绘制块；用户点击后才加载。禁止作者 JS、联网或工具调用。返回短引用。", "en": "Only use when the user explicitly requests advanced visual effects or an HTML/CSS card; never invoke proactively for decoration. Prefer native UI for normal content. Offline HTML/CSS visual, loaded only after the user taps. No author JS, network or tool access. Returns a reference."},
      "parameters": [
        {"name":"label","type":"string","required":true,"description":"Short descriptive title."},
        {"name":"html","type":"string","required":true,"description":"HTML body fragment, up to 8192 characters; no scripts, frames, links or forms. Radio/checkbox, labels, details and inline SVG are supported."},
        {"name":"css","type":"string","required":false,"description":"Inline CSS, up to 8192 characters. Gradients, keyframes, filters and responsive layouts are supported; no external resources."},
        {"name":"max_height","type":"number","required":false,"description":"Height limit in dp, 120..900, default720; fits content until this limit, then scrolls."}
      ]
    },
    {
      "name": "list_icons",
      "description": {"zh": "按英文名称或关键词搜索 Lucide 图标，返回可用名称；需要图标时按需查找，无需读取全库。", "en": "Search Lucide icons by English name or keyword; returns supported names. Look up only what you need."},
      "parameters": [
        {"name": "query", "type": "string", "required": false, "description": {"zh": "英文关键词，如 orbit、brain、music", "en": "English keyword, e.g. orbit, brain, music"}},
        {"name": "limit", "type": "number", "required": false, "description": {"zh": "每页 1～100 个，默认 30", "en": "Results per page, 1..100, default 30"}},
        {"name": "offset", "type": "number", "required": false, "description": {"zh": "分页起点，使用返回的 next_offset，默认 0", "en": "Pagination offset; use returned next_offset, default 0"}}
      ]
    },
    {
      "name": "read_guide",
      "description": {"zh": "加载后先读，读完再决定用不用：何时用文字/纯展示/交互、Express 语法、组件参数和排版；也包含段落内徽章和小图标的写法。", "en": "Read right after loading, then decide whether to use UI: when to use prose, display-only or interactive UI, Express syntax, components and layout; also covers inline badge and icon syntax."},
      "parameters": []
    },
    {
      "name": "prepare_card",
      "description": {"zh": "编译并校验一个 Express 界面块，返回短引用。把引用放在正文中它应出现的位置；失败按行列修正源码；不要抄写 JSON。", "en": "Compile and validate one Express UI block and return a short reference. Place the reference where the block belongs in the answer. Fix reported locations on failure; never copy JSON."},
      "parameters": [
        {"name": "source", "type": "string", "required": true, "description": {"zh": "Express 源码，不含围栏；参数使用 name=value，语法见 read_guide", "en": "Express source without fences; use name=value arguments. See read_guide."}},
        {"name": "live", "type": "boolean", "required": false, "description": {"zh": "允许本轮更新 $/agent 数据；必须初始化该对象。", "en": "Allow current-turn updates to initialized $/agent data."}}
      ]
    },
    {
      "name": "patch_card_data",
      "description": {"zh": "原位替换本轮 live 卡片的 Agent 数据，不改界面结构或用户输入。成功后无需再次展示引用。", "en": "Replace Agent data in a current-turn live card without changing layout or user input. Do not display the reference again."},
      "parameters": [
        {"name": "reference", "type": "string", "required": true, "description": {"zh": "本轮 live 卡片引用", "en": "Current-turn live card reference."}},
        {"name": "expected_version", "type": "number", "required": true, "description": {"zh": "最近返回的版本号，初始为 0", "en": "Last returned version, initially 0."}},
        {"name": "data", "type": "object", "required": true, "description": {"zh": "完整的新 Agent 数据对象，最多 8K 字符", "en": "Complete new Agent data object, at most 8K characters."}}
      ]
    },
    {
      "name": "update_card",
      "description": {"zh": "局部修改本轮卡片的命名声明，校验后返回新版本引用；保留旧卡片及用户填写内容。", "en": "Revise named declarations of a card from this turn. Return a validated new reference, preserving the old card and its user state."},
      "parameters": [
        {"name": "reference", "type": "string", "required": true, "description": {"zh": "本轮 prepare_card 或 update_card 返回的引用", "en": "Reference returned by prepare_card or update_card in this turn."}},
        {"name": "changes", "type": "string", "required": true, "description": {"zh": "新增或替换的 Express 声明，其他声明保持不变", "en": "Express declarations to add or replace; other declarations remain unchanged."}},
        {"name": "remove", "type": "array", "required": false, "description": {"zh": "要删除的声明名称数组", "en": "Array of declaration names to remove."}}
      ]
    },
    {
      "name": "read_example",
      "description": {"zh": "按需学习或展示示例。source_only=true 只返回 Express 源码，不生成卡片；默认编译演示并返回短引用。自定义后用 prepare_card。", "en": "Learn from or display a demo. source_only=true returns Express source without creating a card; default compiles the demo and returns a reference. Use prepare_card after adapting it."},
      "parameters": [
        {"name": "name", "type": "string", "required": true, "description": {"zh": "完整交互：shopping_list（人数联动购物清单）、trip_budget（出行费用试算）、filtered_chart（地区筛选图表）；背景：backgrounds、surfaces（HTML，需用户明确要求）；布局：profile、responsive；展示：flow_steps、flowchart、data_story、stats、charts、info、vector、curves；其他：mixed_branch、interactive_flow、calculator、cost_calculator、form、progress、data、repeat、p2_controls、p2_lists", "en": "Working tools: shopping_list (portions and checklist), trip_budget (travel cost estimate), filtered_chart (region filter); backgrounds: backgrounds, surfaces (HTML, explicit user request required); layout: profile, responsive; display: flow_steps, flowchart, data_story, stats, charts, info, vector, curves; other: mixed_branch, interactive_flow, calculator, cost_calculator, form, progress, data, repeat, p2_controls, p2_lists"}},
        {"name": "source_only", "type": "boolean", "required": false, "description": {"zh": "true：只学习源码，不创建展示引用；默认 false。按需读一个相关例子。", "en": "true: inspect source without creating a display reference; default false. Read only a relevant example."}}
      ]
    }
  ]
}
*/

const guide = String.raw`
DECIDE FIRST
Reading this guide does not oblige you to create a block. If Markdown explains the answer well,
answer in Markdown and call nothing else. Typical no-UI requests: definitions ("what is X"),
opinions, short facts, code changes, plain lists. Typical UI requests: multi-step processes,
several numbers over time or across categories, records with many attributes, parameters to try.

WHAT THIS IS
Native rich content embedded INSIDE your normal answer, not a separate card app. Each reference
renders as a borderless block exactly where you place it between Markdown paragraphs, using the
chat theme. One answer may hold several blocks: text -> flow -> text -> chart/table -> text.

HTML/CSS VISUALS
STRICT OPT-IN: only use Html, prepare_html or the surfaces example when the user explicitly requests
advanced visual effects or HTML/CSS. A generic request for a card, diagram, attractive layout or icons
does NOT authorize HTML. Use native components by default. Never substitute HTML for a native error.
The user sees "This is an HTML card. Tap to load." first; no WebView is created before that tap.
Offscreen/background visuals are destroyed; they reload on return and local CSS state resets.
For custom gradients, patterns, glass-like layers or CSS animations, use prepare_html(label,html,css).
It returns the same a2ui-ref placement as a native card. Supply a body fragment plus CSS; no document
wrapper, JS, scripts, frames, forms, links, remote images/fonts or network requests. Inline SVG
basic shapes, details/summary, and radio/checkbox+label CSS selectors support local visual interaction.
Use native components for tool actions, AI requests, data-entry forms or persistent state.
Keep widths responsive (100%, grid minmax, media queries), avoid viewport heights/fixed page widths.
Height fits content up to max_height (120..900dp, default720), then scrolls. At most four per card.
Styles are isolated to this block: arbitrary CSS colors are allowed here. Use --operit-text,
--operit-surface and --operit-accent for theme defaults, or explicitly pair text with custom backgrounds.
Respect prefers-reduced-motion. Blur/backdrop-filter support depends on the installed Android WebView.
CSS toggle state lasts while the visual stays visible; it is not saved in the native card state.
read_example(name="surfaces") shows four locally switchable complex backgrounds without Javascript.
Advanced composition can use Html(label="...",html="...",css="...",maxHeight=720) inside an Express tree.
Each html/css string <=8192 characters, max256 HTML elements; source is validated before display.

CHOOSE THE LIGHTEST FORM (most blocks should be display-only)
1. Prose/Markdown when words are clear. Lists, code, short tables and math stay in Markdown.
2. Display-only UI when visual structure is clearer: Flow for steps/linear processes with arrows,
   Chart for trends/comparisons, Table for records, Grid+Display for key metrics, InfoRow for
   attributes, Badge rows for status, Vector for small custom diagrams, Image with text.
   Display-only blocks need NO $/ state, NO Event, NO Button. Never initialize unused data.
3. Interactive UI only when the user must choose, enter, filter, calculate or explore. Make only
   that part interactive and keep the rest of the block static.
Use display blocks proactively when they clarify steps, trends, comparisons or relationships.
Do not add buttons, forms, toggles or "tap to..." hints that nothing needs. Keep explanations in
Markdown around blocks rather than inside them. Trends: line; categories: bar; shares: pie;
numeric relationships: scatter. Branching or many-node graphs: Mermaid in Markdown, or Vector.
Use actual user/tool data for factual charts; label demos and assumptions. Never invent measurements.

INLINE IN PARAGRAPHS (no prepare_card)
Short status inside a sentence stays in Markdown: write the syntax directly in the paragraph.
Badge :badge[label]{tone=success icon=check} marks an evidence-backed status or result: build
passed/failed, task done/running, supported or not, risk level. Label <=16 chars (badges never wrap);
tone and icon are optional.
Icon :icon[time] is a small marker before the text it labels: ":icon[time] 2 min",
":icon[folder] /sdcard/Download", ":icon[calendar] Oct 12". Add color with :icon[check]{tone=success}.
Lucide works here too: :icon[lucide:orbit]{tone=accent size=lg}. Optional size=sm|md|lg scales to
0.85/1/1.25 times the surrounding text (default md); baseline stays centered. Icon names <=64 chars.
An icon never carries meaning alone; always follow it with text.
Example: "Build :badge[passed]{tone=success icon=check}, took :icon[time] **2 min**."
Tones and icon names as in FLOW (unknown icons are dropped). Only badges and icons go inline; buttons,
charts and layouts are blocks. Use 0-2 per answer, never as decoration. Skip them for general
explanations, hypothetical results, or when the user asks for plain text, code or JSON.
Copy and speech keep the badge label and skip icons. Never put the syntax in backticks; it would stay literal.

VISUAL RULES
Keep heading levels clear: never h1/h2 inside blocks. Display blocks introduced by prose need no title;
Header is for self-contained blocks (tools, records, live status); Heading marks a sub-section.
Repeated items use the same component, text size and order. Badges and tones carry meaning
(status, risk, result), never decoration; at most a few per block. Do not wrap summaries,
recommendations or conclusions in colored panels; write them as prose. Every Button must do
something real. Images only when they help understanding. One block per idea; avoid stacking
several blocks that repeat the same information.

FRAMING
Blocks have no border, background or padding by default and line up with paragraphs.
Do not start every block with a Header; the surrounding text usually introduces it.
Use root=Card(child=...) only for a self-contained tool or record (calculator, form, status record)
that benefits from a visible boundary. Panel groups a sub-area. Never nest frames for decoration.

PLAN THE WHOLE BLOCK BEFORE CHOOSING COMPONENTS
Start with the user's question and the information they need to see first. Arrange a short overview,
the main evidence/result, then optional controls/details in reading order. Use named declarations
for meaningful regions; keep one idea per region. Do not make a grid of unrelated boxes.
For local exploration, put the controls next to or above the result they change. Keep inputs and
their results in ONE card; identify which state path drives which text, list or chart before coding.
Store editable inputs and source records; derive totals, labels and chart values from those same
records with pure functions. Never hard-code a second total or maintain a duplicate result state.
Named declarations refer to components only; put calculations in properties (no formula aliases).
Every control must change a visible result or collect a value needed by an explicitly requested
submission. A slider/checklist/filter needs no "Ask AI" or "Apply" button for a local calculation.
Use ai_submit only for a new request requiring reasoning or external information.
Choose Row(weights=[0,1]) for a small icon beside growing text, and auto Grid(minItemWidth=160)
for comparable metrics or features. Preserve reading order when stacked. Keep essential text free
to wrap; fixed heights/Frame are for graphics, not headings, descriptions or form fields.
Before prepare_card, check the smallest/largest inputs and empty selections: no zero divisor,
mismatched chart labels/values, stale totals or button without an effect. Initial values alone
are not enough. Do not claim visual or cross-model testing merely because compilation succeeds.

LEARN A WORKING PATTERN ONLY WHEN NEEDED
Call read_example(name="shopping_list",source_only=true) for a portion slider -> derived quantities
and independent checklist state; "trip_budget" for inputs -> total and per-person cost;
"filtered_chart" for a region selector -> chart labels, values and total derived from the same rows.
These are demonstration data, not researched prices or facts. Adapt the source and call prepare_card;
never present a learning example as the user's requested result. Read one relevant example, not all.
For an explicit demo request, omit source_only to prepare that example for display.

MINIMAL DISPLAY BLOCK (no state, no actions)
root = Flow(label="Three cooking stages",steps=[{title:"Prepare",caption:"2 min",icon:"list"},
 {title:"Cook separately",caption:"4 min",icon:"fire"},{title:"Combine",caption:"1 min",icon:"check",tone:"success"}])

WORKFLOW
Read this guide, then call prepare_card(source) once per block. Place each returned a2ui-ref fence
EXACTLY where that block belongs in your answer, with prose before/after as needed. The app renders
the compiled block at that position.
Do not repeat the source/NDJSON, reconstruct the output or revalidate a successful result.
References belong to this active turn; never reuse them in another turn/chat.
To revise a card from THIS turn, use update_card(reference,changes,remove?).
changes contains only named declarations to replace/add; remove lists existing declarations.
The complete merged graph is checked atomically. Inline components are changed via their named parent.
Display the NEW reference; it creates a separate revision, NOT a live edit of a displayed card.
Old cards and user inputs are preserved. New revisions start from their declared initial data.
Patch errors use merged source locations. For previous turns, prepare complete new source.
valid:false is a completed inspection: fix the reported line/column and field. Do not retry identical
input or infer unsupported nesting. Validation does not prove visual quality or arithmetic intent.
read_example prepares an unmodified demo by default; source_only=true only returns source for learning.
Custom cards are freely composed, not fixed templates.

EXPRESS (Operit named-argument subset, compiled locally to AndroidX A2UI v0.9)
One assignment per line; multiline constructors allowed. No enclosing tags or Markdown fences.
root is required. ALL component/function arguments are named: Text(text="Hello").
Inline components or reference declarations (forward references allowed):
root = Column(children=[title, Text(text="Details")])
title = Heading(text="Overview")
State bindings use absolute object paths: $/form/years. Initialize with literal assignments:
$/form = {years:4,discount:false}
No overlapping initializations, array-index paths, reserved keys, uninitialized bindings, or calls
inside initial data. Strings use JSON double quotes/escapes; numbers are decimal.
No positional args, scripts, operators, imports, lifecycle commands or remote catalogs.
All declarations must be reachable from root. No cycles. Max 128 components, 32 children/container,
depth 12; source 64K characters, compiled document 128K, initial data 32K, string 8192 characters.
Event(name="...", context={...}) describes an action, not a callback.
Local calculations: multiply(a=$/form/years,b=10), formatNumber(value=...,decimals=2,grouping=true).
Functions accept numeric strings. if evaluates eagerly; division by zero is unresolved. Use
Conditional hints and Button checks, e.g. checks=[{condition:greaterThan(a=$/form/years,b=0),
message:"Enter a positive value"}]. Bind controls' value/checked/number to initialized state.
Literal controls are read-only. Calls never write state; an explicit local_action does.

DYNAMIC LISTS
Repeat(items=$/issues,key="id",child=issueRow,maxItems=20) repeats one component template.
Initialize issues as object arrays with stable, unique nonempty string IDs (not array indexes).
Template fields use item(field="title") or item(field="author/name"); functions may consume item values.
Item data is read-only. Use item_state(field="done",default=false) for independent writable row fields.
Defaults must be scalar literals; declarations of the same field must agree. State follows Repeat ID +
stable item key across reorder/filter/reopen. Hidden rows retain state within the 32K quota.
Absolute $/ bindings still refer to shared card state. Never write the internal _operit_rows branch.
local_action {op:"row_set",repeat:"rows",key:"issue-id",field:"done",value:true} edits a declared field
of a currently present row. Removing a row rejects its old events; reusing its key restores its state.
Arrays may come from arrayFilter. Default maxItems=20, maximum32; overflow is rejected, never truncated.
Repeat can nest: inner items=item(field="children"). item/item_state always refer to the nearest row.
State includes every ancestor's stable key; the whole card shares the 512 rendered-node budget.
For nested row_set, pass parents:["outerKey",...] in outermost-first order plus the named inner Repeat ID.
Empty arrays render no rows; use Conditional for an empty-state explanation.

NATIVE INTERACTION
Pressable(label="View step",child=...,selected=booleanBinding,disabled=false,action=Event(...))
makes an entire display/layout node clickable with a 48dp minimum target and accessibility semantics.
Its descendants must be noninteractive; keep inputs, links and other buttons outside the node.
For interactive flow diagrams combine Pressable + Column/Row + Vector arrows + Conditional details.
Completed is different from disabled: completed steps remain clickable; Next depends only on position.
Input controls support onChange=Event(name="local_action",context={op:"set",path:"/selection",
value:event_value(field="value")}). event_value fields: value, previous, index (or -1 when not indexed).
Only actual changed user input fires onChange, after its bound value is saved. Initialization,
restore and programmatic state changes do not fire. onChange cannot submit to AI or call tools.
Vector shapes retain literal types/array structure; coordinates, tone, stroke, filled and text may bind
to state or calculations. arrow uses x1,y1,x2,y2 and draws its own arrowhead. All geometry remains
bounded to the viewport. Invalid runtime geometry shows a local error; no arbitrary SVG/JS.

LIVE TASK DATA
prepare_card(source,live=true) enables data-only updates during this active turn.
Initialize $/agent={...} as an object (maximum8K). Keep user inputs under other branches such as $/form.
After displaying the reference ONCE, patch_card_data(reference,expected_version=0,data={...}) replaces
the COMPLETE Agent object. Each success returns the next version; use it on the next patch.
Conflicts and invalid updates change nothing. The whole source is revalidated before publication.
User controls/local actions cannot write Agent data. Patches never execute actions or modify layout.
The final data is saved on this device, including while the card is off-screen. After this turn ends
the reference is no longer writable. update_card still creates a separate, non-live revision.
Live data and user input are included in app data backups; ordinary text exports carry initial data.
Cross-card sharing is not implicit: use multiple Panels in one card for shared controls and results.

FLOW (static steps and linear flowcharts)
Flow(label="Accessible summary",steps=[{title:"...",caption:"...",icon:"check",tone:"success"}],
direction="auto"|"horizontal"|"vertical"). 1..8 steps; title required (<=80 chars), caption <=160.
1535 Lucide v0.456.0 icons are built in. Search list_icons(query="orbit") or another English
keyword, then use the returned name, e.g. lucide:orbit, lucide:brain, lucide:music.
Prefer the lucide: prefix for an exact library match. Bare new names also work; legacy names retain
their original artwork. Do not list the whole catalog in the answer or request all pages unnecessarily.
Legacy icons: check,info,settings,person,home,star,warning,search,calendar,folder,edit,play,flag,time,list,
fire,food,code,upload,download,lock,chat,build,phone (alias smartphone),globe (alias language),
browser,computer,terminal,server,storage,cloud,extension,shield,mic,audio,image,video,document,link,
chart,science,lightbulb,robot,wifi,delete. All are native outlined vector icons; use phone for a
mobile device, globe for the internet, browser for a web page, and extension for a module.
Use these icons instead of emoji when a matching icon exists. The same names work in Icon,
Flow steps and inline :icon[...] / :badge[...]{icon=...}. calendarToday is an alias for calendar.
Example: Row(children=[Icon(name="phone"),Text(text="Operit Core")]); inline :icon[globe]{tone=info}.
Tones: neutral,accent,success,info,warning,error.
auto draws horizontal tiles with right arrows for <=4 steps when they fit, otherwise vertical tiles
with down arrows. horizontal is a preference: it still stacks vertically when tiles would not fit.
Combine several Flows in a Column/Grid for simple branches. No state or actions needed.

LAYOUT
One purpose per block. Titles are optional; prominent results, then details.
Header(text=title,label=optionalSubtitle,status=optionalBadge,tone=...) titles a self-contained block.
Heading is a smaller section heading. InfoRow(label=...,text=...) aligns each attribute/value;
long values automatically stack without truncation. Divider separates header and details.
Display(text=...,label=...,align="start") gives statistics a large value on an inset grey panel.
Default alignment is start; calculators use align="end". Badge and Text tone=
neutral, success, info, warning, error. Use colors for meaning, never as decoration.
Stack forms vertically. Table aligns tabular data; Grid suits short metrics or keypads, not prose.
Plan the whole component tree first: header (icon + title/description), body, related feature grid,
then optional detail/actions. Compose these freely; do not design a collection of fixed-pixel boxes.
Your layout values express intent; the client decides final sizes and reflow from available width,
font scale and content. Row wraps items by default instead of squeezing later text; wrap=false
prefers one line but the client still stacks the items if they do not fit.
For a logo beside growing text, use Row(weights=[0,1],children=[logo,titleColumn]).
weights is one literal integer0..12 per child: 0 uses natural width, positive values share the remaining
width. [1,1] gives equal regions; [1,2] gives the second twice the space. Weighted rows fill the width
and stack in source order when any flexible region would get less than minItemWidth (default120dp,
optional80..320, adjusted for font scale). This readability fallback also applies with wrap=false.
Panel directly inside a Row hugs its content. Use Grid for repeated feature tiles; omit columnCount
to adapt to screen width and large text. Let text determine height; never crop prose with Frame.
read_example(name="profile") demonstrates a composed header, responsive feature tiles and local
selection/details. It uses the same basic components, not a special product-card renderer.
Avoid decorative nesting and duplicating titles in the surrounding text.
gap=none|xs|sm|md|lg means 0/4/8/16/24 dp.
Row align=start|center|end is vertical; justify=start|center|end|between is horizontal.
Unweighted rows keep their content width by default; explicit justify uses the available full width.
Column align=start|center|end is horizontal. Both default to an 8 dp gap.
Panel(child=...,padding="md",radius="md",background="inset",border=false,maxWidth=600)
groups content with bounded styles. Background: transparent|surface|inset|accentMuted|light|dark|blue|green|orange|red;
info|success|warning|error are aliases for blue|green|orange|red. Colored fills follow the local light/dark
palette; light/dark explicitly choose a light-gray/deep-blue-gray panel. Text and icons inside adapt to that
panel (including nested panels), without changing the rest of the answer. radius:none|sm|md|lg.
Use read_example(name="backgrounds") for a four-color panel demo. No HEX/RGB or gradient backgrounds.
Text textAlign=start|center|end, tone=success|...; optional lineClamp=1..8 truncates nonessential text only.
Scroll(child=...,maxHeight=240) creates a bounded vertical region (80..480 dp). Avoid nested scrolling.
Blocks are frameless; see FRAMING. Keep commentary in Markdown outside the block.
Choose one primary action; quiet secondary actions. No arbitrary style/color overrides.
Grid columnCount=1..4; columnSpans needs one integer span per child, each 1..columnCount.
Without columnCount, Grid auto-fits 1..4 columns using minItemWidth (80..320 dp, default160)
and font scale. Prefer auto columns for metrics; explicit columns for keypads.
Items pack in order and incomplete rows keep empty cells. A zero key can span 2 columns.
Slider steps counts INTERMEDIATE stops: min=0,max=100,steps=99 gives integers; default continuous.
Retain decimal precision unless asked to round. Long Display values shrink to fit, then wrap; never truncated.
Text variants: h1/h2/h3/body/caption.
Icon(name="lucide:orbit",tone="accent",size=32) supports semantic tone=neutral|accent|success|info|warning|error
(can bind to local state); no tone follows surrounding content color. Literal size=12..96 dp, default24.
Size is a preference constrained by the available layout. Colors follow the theme and bubble contrast.
Bind Expandable.expanded and Tabs/Carousel.value to retain state.
Navigation value is a named child's ID; use named declarations for navigation children.
DatePicker uses yyyy-MM-dd and expands inline; minDate/maxDate bound 1900..2100.
clearable=true adds Clear (writes an empty string). Changing bounds does not rewrite existing data.
TextField/TextArea error="..." shows a field error. TextArea minLines/maxLines are 1..12 (default3/8).
TextField mask=none|password|digits: password masks DISPLAY ONLY (the value remains plain local state);
digits accepts ASCII digits only, without reformatting the stored value. No arbitrary mask patterns.
Button loading=true keeps its label and shows a spinner; clicks are blocked until loading=false.
Controls do not open Activity dialogs.
Tooltip(label="What is this?",text="Explanation") opens on tap/long-press, using the same closable
in-card overlay as Popover. Stepper(label="Steps",labels=["Start","Finish"],current=$/step)
shows connected numbered steps (zero-based integer); prior steps are shown as passed, not independent
completion flags. Past steps remain clickable and onChange is local.
Formula(label="Equation",text="\\frac{1}{2}") renders native bounded math, never arbitrary TeX.
Maximum1024 chars/64 commands/12 brace levels. Allowed: frac,sqrt,sum,prod,int,lim,infty,left,right,
alpha,beta,gamma,delta,theta,lambda,mu,pi,sigma,phi,omega,Delta,Sigma,Omega,times,cdot,div,pm,
le,leq,ge,geq,ne,neq,approx,sin,cos,tan,log,ln,exp,text,mathrm,mathbf,overline,vec,hat.
No macros, environments, external files or size commands. Express quoted strings escape backslashes.

DATA AND MEDIA
Chart type="line"|"bar"|"pie"|"scatter", labels=["A","B"], series=[{label:"Sales",values:[1,2]}].
Scatter: series=[{label:"Demo",points:[{x:1,y:2,label:"A"}]}].
Max 4 series/128 points, category lengths must agree. Pie: one nonnegative series.
Charts provide tap inspection and read their values to screen readers; do not repeat the data in a Table by default.
Table: string columns and array rows, max 8 columns/32 rows.
columnWidths=[120,180] sets one literal64..480dp width per column (default140).
maxHeight=80..480 bounds the vertically scrolling body; the header stays visible and aligns on horizontal scroll.
Rows may bind to state or array expressions; every row must match columns and contain scalar cells.
Pure collection functions use items (max128) and optional field="object/nestedField", never callbacks.
arraySort(items,field?,direction="asc"|"desc") is stable and requires same-type sortable values.
arrayFind(items,field,value,fallback) returns the first equal item or explicit fallback.
arrayUpdateByKey(items,key="id",value="a",patch={done:true}) returns a new array; keys must be unique
scalars and patch cannot change the key. No match leaves it unchanged. Write with a local_action set.
RichText runs may use strike=true, highlight=true, tabular=true along with bold/italic/underline.
arrayMap projects a required field; arrayFilter requires field,operator,value.
Filter operators: eq/ne/gt/gte/lt/lte/contains. arraySum/arrayAverage use optional field.
arrayJoin requires separator; arrayLength counts items. Missing fields or invalid numbers are unresolved;
empty sum=0, empty average is unresolved. Bound results max32K, strings8192, finite numbers <=1e12.
stringContains(text,query,ignoreCase?), stringReplace(text,search,replacement), stringTrim(text).
Dates are ISO yyyy-MM-dd, years1900..2100. dateAddDays(date,days), dateDiffDays(start,end),
formatDate(date,format="iso"|"yearMonth"|"monthDay"). All arguments are NAMED.
if supports bounded objects/arrays but is eager: both branches must be valid.
RichText runs=[{text:"...",bold:true,italic:false,underline:false,tone:"primary",size:"caption"}].
Run tone: primary|muted|success|info|warning|error; mix colors in one line with RichText runs.
Optional run url is http(s); RichText is not HTML. Put math/Mermaid in normal Markdown.
Image/Video/Audio use user/tool-provided http(s) URLs; load only on click, never autoplay.
Link/File open web URLs, not device paths. Media can fail independently.
Map markers=[{label:"A",latitude:30,longitude:120}], route=true connects points in order:
this is a LOCATION SKETCH, not roads/navigation. Draft saves/copies locally, never sends mail.
Popover(label="Details",child=...) opens an anchored panel inside the card, at most320x240 dp,
with scrolling, a close button and outside-tap dismissal. Short cards expand to fit the panel.
Do not nest Popovers or hide essential information in them. Opening never calls AI.
Frame(child=...,minWidth=0,maxWidth=600,minHeight=0,maxHeight=300,aspectRatio=1.5)
bounds one region; aspectRatio is width/height (0.25..4). Available screen constraints take priority.
Use ratios for graphics/media, not growing prose or forms. Width0..960dp, height0..480dp;
defaults are min0,maxWidth960,maxHeight480. Content is clipped to the requested region.
Vector(label="Accessible description",width=320,height=180,shapes=[...]) draws data-bound geometry.
width/height define the drawing coordinate viewport, not fixed screen pixels. In a Row (also through
a Panel), viewport width is the preferred display width in dp, bounded by available space; standalone
diagrams expand to available width. Frame supplies explicit bounds when needed.
Shape types and point topology stay literal; coordinates, text and style may use bindings/functions.
Viewport integer dimensions1..1000; coordinates stay inside it. Max64 shapes,128 points/shape.
Shapes: {type:"line",x1:0,y1:0,x2:100,y2:100}, {type:"arrow",x1:0,y1:0,x2:100,y2:100},
{type:"rect",x:10,y:10,width:80,height:50}, {type:"circle",cx:50,cy:50,r:20},
{type:"polyline",points:[[0,0],[100,50]]}, {type:"polygon",points:[[0,0],[100,0],[50,50]]},
{type:"text",x:10,y:10,text:"Label",size:16}. Text uses TOP-LEFT coordinates; allow space for it.
Optional tone=neutral|accent|success|info|warning|error, filled=true, stroke=0.5..12.
Curves: {type:"quadratic",x1:0,y1:80,cx:100,cy:0,x2:200,y2:80} or
{type:"cubic",x1:0,y1:80,cx1:60,cy1:0,cx2:140,cy2:160,x2:200,y2:80}.
All endpoints and controls must be within the viewport; curves are stroked, never filled.
No SVG markup, arbitrary paths, scripts, URLs or touch handlers. Use charts for data inspection.

ACTIONS
Button(child=Text(text="..."),variant="primary"|"default"|"borderless",action=Event(...)).
local_action context: {op:"set",path:"/count",value:5}, {op:"add",path:"/count",value:1},
{op:"save"}, {op:"edit"} or {op:"cancel"}. Local interactions never request AI.
ai_submit context: bound structured data; optional required:["/form/name"].
tool_action context: requested tool and args, NOT direct execution. Both preview and require
confirmation, preserve composer drafts and use normal authorization/review.
open_url context {url:"https://..."}; copy context {text:"..."}.
Label consequential actions honestly. Completed cards only; no actions on history replay.
State persists on this device by chat/message/revision/card. Changed source starts new state.
calculatorStep(state,key) is a pure reducer; initialize
{display:"0",stored:"",op:"",replace:false,error:false}; keys 0..9,".","+","-","*","/","=",
"AC","backspace","sign". Set the whole calculator state atomically with local_action.

MINIMAL INTERACTIVE DEMO (only because the user explores a value; simulated price)
$/form = {years:4}
root = Card(child=Column(children=[
 Display(label="Demo total",text=formatNumber(value=multiply(a=$/form/years,b=12.5))),
 Slider(label="Years",min=1,max=10,steps=8,number=$/form/years)
]))

CATALOG — generated from the compiler's contract; ? means optional
`;

function native() {
    if (typeof NativeInterface === "undefined" || typeof NativeInterface.prepareDynamicUi !== "function")
        throw new Error("This app version does not include the Express card compiler.");
    return NativeInterface;
}
function prepare(source, live = false) {
    const root = typeof globalThis !== "undefined" ? globalThis : this;
    const callId = String(root.__operitCurrentCallId || "");
    if (!callId) throw new Error("No active card tool invocation.");
    return {success:true, ...JSON.parse(String(native().prepareDynamicUi(callId, source, live === true)))};
}
exports.read_guide = async () => complete({success:true,message:guide + native().dynamicUiGuide()});
exports.prepare_html = async ({label, html, css = "", max_height = 720} = {}) => {
    if (typeof label !== "string" || !label.trim() || label.length > 200 ||
        typeof html !== "string" || !html.trim() || html.length > 8192 ||
        typeof css !== "string" || css.length > 8192 ||
        !Number.isInteger(max_height) || max_height < 120 || max_height > 900) {
        complete({success:true,valid:false,message:"Provide label (1..200 chars), html/css (up to 8192 each), and max_height (120..900)."});
        return;
    }
    complete(prepare(`root=Html(label=${JSON.stringify(label)},html=${JSON.stringify(html)},css=${JSON.stringify(css)},maxHeight=${max_height})`));
};
exports.list_icons = async ({query = "", limit = 30, offset = 0} = {}) => {
    if (typeof query !== "string" || query.length > 80 || !Number.isInteger(limit) ||
        limit < 1 || limit > 100 || !Number.isInteger(offset) || offset < 0 || offset > 10000) {
        complete({success:true,valid:false,message:"Use a query up to 80 characters, limit 1..100, and offset 0..10000."});
        return;
    }
    complete({success:true,...JSON.parse(String(native().dynamicUiIcons(query, limit, offset)))});
};
exports.prepare_card = async ({source, live = false}) => {
    if (typeof source !== "string" || source.length > 65536) {
        complete({success:true,valid:false,code:"invalid_source",message:"Pass Express source (maximum 65536 characters)."});
        return;
    }
    complete(prepare(source, live));
};
exports.patch_card_data = async ({reference, expected_version, data}) => {
    if (typeof reference !== "string" || reference.length > 80 || !Number.isInteger(expected_version) ||
        expected_version < 0 || !data || Array.isArray(data) || typeof data !== "object" || JSON.stringify(data).length > 8192) {
        complete({success:true,valid:false,message:"Pass a live reference, integer version and an Agent data object up to 8K characters."});
        return;
    }
    complete({success:true,...JSON.parse(String(native().patchDynamicUiData(
        String(globalThis.__operitCurrentCallId || ""), reference, expected_version, JSON.stringify(data))))});
};
exports.update_card = async ({reference, changes, remove = []}) => {
    if (typeof reference !== "string" || reference.length > 80 || typeof changes !== "string" ||
        changes.length > 65536 || !Array.isArray(remove) || remove.length > 128 ||
        remove.some(name => typeof name !== "string" || name.length > 256)) {
        complete({success:true,valid:false,code:"invalid_revision",message:"Use a current-turn reference, Express changes and an array of declaration names."});
        return;
    }
    const root = typeof globalThis !== "undefined" ? globalThis : this;
    complete({success:true,...JSON.parse(String(native().updateDynamicUi(
        String(root.__operitCurrentCallId || ""),reference,changes,JSON.stringify(remove))))});
};

const q = JSON.stringify;
function example(name) {
    if (name === "shopping_list") return String.raw`
$/form = {people:2}
$/ingredients = [{id:"rice",name:"大米",perPerson:150,unit:"克"},{id:"egg",name:"鸡蛋",perPerson:1,unit:"个"},{id:"greens",name:"青菜",perPerson:200,unit:"克"}]
root = Card(child=Column(children=[intro,people,portions,ingredients,note]))
intro = Header(text="备餐购物清单",label="演示份量 · 按人数调整")
people = Slider(label="用餐人数",min=1,max=8,steps=6,number=$/form/people)
portions = Text(text=concat(values=["当前准备 ",formatNumber(value=$/form/people,decimals=0)," 人份；勾选已备齐的食材。"]))
ingredients = Repeat(items=$/ingredients,key="id",child=ingredient)
ingredient = CheckBox(label=concat(values=[item(field="name")," · ",formatNumber(value=multiply(a=item(field="perPerson"),b=$/form/people),decimals=0)," ",item(field="unit")]),value=item_state(field="done",default=false))
note = Text(text="人数变化只调整数量，已勾选状态保留；增加人数后请重新核对是否备齐。",variant="caption")
`;
    if (name === "trip_budget") return String.raw`
$/form = {people:2,nights:2,rooms:1}
root = Card(child=Column(children=[intro,inputs,results,note]))
intro = Header(text="出行费用试算",label="演示价格 · 交通每人 ¥120，住宿每间每晚 ¥280")
inputs = Column(children=[people,nights,rooms])
people = Slider(label="出行人数",min=1,max=6,steps=4,number=$/form/people)
nights = Slider(label="住宿晚数",min=1,max=7,steps=5,number=$/form/nights)
rooms = Slider(label="房间数",min=1,max=3,steps=1,number=$/form/rooms)
results = Grid(minItemWidth=160,children=[total,perPerson])
total = Display(label="合计（元）",text=formatNumber(value=add(a=multiply(a=$/form/people,b=120),b=multiply(a=multiply(a=$/form/nights,b=$/form/rooms),b=280)),decimals=2))
perPerson = Display(label="人均（元）",text=formatNumber(value=divide(a=add(a=multiply(a=$/form/people,b=120),b=multiply(a=multiply(a=$/form/nights,b=$/form/rooms),b=280)),b=max(a=$/form/people,b=1)),decimals=2))
note = Text(text="只估算交通和住宿，不含餐饮等费用；这里不会预订或付款。",variant="caption")
`;
    if (name === "filtered_chart") return String.raw`
$/filter = {region:"北区"}
$/sales = [{region:"北区",month:"一月",amount:12},{region:"北区",month:"二月",amount:18},{region:"北区",month:"三月",amount:15},{region:"南区",month:"一月",amount:9},{region:"南区",month:"二月",amount:14},{region:"南区",month:"三月",amount:24}]
root = Card(child=Column(children=[intro,region,total,chart,note]))
intro = Header(text="地区销售趋势",label="演示数据 · 单位：万元")
region = Segmented(label="地区",options=["北区","南区"],value=$/filter/region)
total = Display(label=concat(values=[$/filter/region," · 三个月合计（万元）"]),text=formatNumber(value=arraySum(items=arrayFilter(items=$/sales,field="region",operator="eq",value=$/filter/region),field="amount"),decimals=0))
chart = Chart(label=concat(values=[$/filter/region,"月度销售额 · 演示数据"]),type="bar",labels=arrayMap(items=arrayFilter(items=$/sales,field="region",operator="eq",value=$/filter/region),field="month"),series=[{label:"销售额（万元）",values:arrayMap(items=arrayFilter(items=$/sales,field="region",operator="eq",value=$/filter/region),field="amount")}])
note = Text(text="切换地区会同步更新图表和合计，直接在本地完成。",variant="caption")
`;
    if (name === "surfaces") {
        const html = `<div class="demo">
<input class="choice" type="radio" name="scene" id="aurora" checked><input class="choice" type="radio" name="scene" id="nebula"><input class="choice" type="radio" name="scene" id="cyber"><input class="choice" type="radio" name="scene" id="soft">
<nav class="tabs"><label for="aurora">极光</label><label for="nebula">星云</label><label for="cyber">赛博</label><label for="soft">柔光</label></nav>
<section class="art"><div class="glow"></div><div class="texture"></div><div class="content">
<header><span class="dot"></span> OPERIT DESIGN LAB <span class="star">✦</span></header>
<p class="eyebrow">DYNAMIC SURFACES / 2026</p><h1>超越纯色的<br><span>动态背景艺术。</span></h1>
<p class="intro">光影、渐变与纹理交织，让每一张卡片拥有自己的空间感。</p>
<section class="glass"><div class="live">◇ LIVE PREVIEW <small>● ACTIVE</small></div><div class="stats"><div><strong>04</strong><span>背景风格</span></div><div><strong>CSS</strong><span>实时绘制</span></div><div><strong>0</strong><span>外部图片</span></div></div><div class="track"><i></i></div></section>
</div></section></div>`;
        const css = `.demo{width:100%}.choice{position:absolute;width:1px;height:1px;opacity:0}.tabs{display:flex;gap:6px;margin:0 0 14px}.tabs label{flex:1;text-align:center;padding:9px 2px;border-radius:24px;color:var(--operit-text);cursor:pointer}
#aurora:checked~.tabs [for=aurora],#nebula:checked~.tabs [for=nebula],#cyber:checked~.tabs [for=cyber],#soft:checked~.tabs [for=soft]{background:var(--operit-surface);box-shadow:inset 0 0 0 1px currentColor}
.choice:focus-visible~.tabs{outline:2px solid var(--operit-accent)}
.art{--fg:#f4f7ff;--muted:#d8e3f6;--accent:#aff8ef;--glass:#ffffff12;position:relative;overflow:hidden;isolation:isolate;border-radius:26px;background:radial-gradient(ellipse at 4% 0%,#2a838e,transparent 68%),radial-gradient(ellipse at 100% 15%,#4163d5,transparent 70%),linear-gradient(150deg,#12273e,#21173d 75%,#643f91);color:var(--fg)}
.content{position:relative;padding:28px;z-index:2}.glow{position:absolute;inset:-35%;background:conic-gradient(from 30deg,transparent,#44bccc45,transparent,#9e75ff55,transparent);filter:blur(28px);animation:drift 16s ease-in-out infinite alternate;pointer-events:none}.texture{position:absolute;inset:0;pointer-events:none}header{display:flex;align-items:center;gap:9px;font-size:11px;font-weight:700;letter-spacing:2px}.dot{width:7px;height:7px;border-radius:50%;background:var(--accent);box-shadow:0 0 18px var(--accent)}.star{margin-left:auto;font-size:28px;color:var(--accent)}
.eyebrow{margin:42px 0 16px;color:var(--muted);font-size:11px;letter-spacing:3px}h1{font-size:clamp(26px,7vw,40px);line-height:1.3;margin:0;font-weight:750}h1 span{color:var(--accent)}.intro{font-size:15px;color:var(--muted);margin:24px 0 30px;line-height:1.8}.glass{padding:20px;border-radius:22px;background:var(--glass);border:1px solid #ffffff40;backdrop-filter:blur(18px);box-shadow:0 10px 30px #00000010}.live{display:flex;justify-content:space-between;gap:8px;font-size:10px;letter-spacing:1.5px;color:var(--muted)}small{font-size:9px}.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin:22px 0}.stats strong{display:block;font-size:clamp(23px,6vw,33px);font-weight:650}.stats span{font-size:11px;color:var(--muted)}.track{height:4px;background:#ffffff24;border-radius:8px;overflow:hidden}.track i{display:block;width:78%;height:100%;background:linear-gradient(90deg,var(--accent),#af90ed);border-radius:8px}
#nebula:checked~.art{--accent:#f2c6f7;background:radial-gradient(ellipse at 15% 30%,#5d316f,transparent 70%),radial-gradient(ellipse at 100% 80%,#383fa0,transparent 65%),#111329}
#nebula:checked~.art .texture{background-image:radial-gradient(#ffffff70 .7px,transparent 1px);background-size:23px 29px}
#cyber:checked~.art{--accent:#6effd4;--muted:#c4e4e7;background:radial-gradient(ellipse at 80% 10%,#263c70,transparent 75%),#091c25}
#cyber:checked~.art .texture{background-image:linear-gradient(#5ef9d518 1px,transparent 1px),linear-gradient(90deg,#5ef9d518 1px,transparent 1px);background-size:27px 27px;transform:perspective(300px) rotateX(16deg) scale(1.15);transform-origin:bottom}
#soft:checked~.art{--fg:#252c40;--muted:#47536b;--accent:#624487;--glass:#ffffff66;background:radial-gradient(ellipse at 0 0,#f1d8e5,transparent 65%),radial-gradient(ellipse at 100% 0,#cbd9fa,transparent 68%),radial-gradient(ellipse at 50% 100%,#c6e9df,transparent 75%),#f6f5ee}
#soft:checked~.art .texture{background:radial-gradient(#55556612 .7px,transparent 1px);background-size:7px 7px}#soft:checked~.art .glow{opacity:.18}
@keyframes drift{to{transform:translate(6%,4%) rotate(25deg)}}@media(prefers-reduced-motion:reduce){.glow{animation:none}}@media(max-width:320px){.content{padding:20px}.glass{padding:15px}.live{letter-spacing:0}.eyebrow{margin-top:28px}}`;
        return `root=Html(label="可切换的四种动态背景",html=${JSON.stringify(html)},css=${JSON.stringify(css)},maxHeight=900)`;
    }
    if (name === "backgrounds") return String.raw`
root=Grid(minItemWidth=150,children=[light,blue,green,dark])
light=Panel(background="light",radius="lg",child=Column(gap="md",children=[Icon(name="lucide:layers",size=32),Heading(text="浅色背景"),RichText(runs=[{text:"柔和的次级表面",tone:"muted"}])]))
blue=Panel(background="blue",radius="lg",child=Column(gap="md",children=[Icon(name="lucide:droplets",tone="info",size=32),Heading(text="蓝色背景"),RichText(runs=[{text:"淡蓝色卡片",tone:"muted"}])]))
green=Panel(background="green",radius="lg",child=Column(gap="md",children=[Icon(name="lucide:leaf",tone="success",size=32),Heading(text="绿色背景"),RichText(runs=[{text:"柔和的绿色",tone:"muted"}])]))
dark=Panel(background="dark",radius="lg",child=Column(gap="md",children=[Icon(name="lucide:moon",tone="info",size=32),Heading(text="深色背景"),RichText(runs=[{text:"深蓝灰色卡片",tone:"muted"}])]))`;
    if (name === "profile") {
        const features = [
            ["role", "person", "自由选择角色", "任何角色卡都可以成为伴侣", "选择适合自己的角色与交流方式。"],
            ["memory", "storage", "长期记忆", "持续理解经历与偏好", "在本地展开这一项的说明，再次点击同一项即可收起。"],
            ["companion", "chat", "主动陪伴", "让交流自然地延续", "这里是交互演示，不会启动后台任务或发送模型请求。"],
            ["tasks", "calendar", "自主任务", "计划、提醒与检查", "需要真实执行时，再设置明确的操作入口与确认流程。"],
        ];
        return String.raw`
$/selected=""
root=Panel(border=true,radius="lg",child=Column(gap="md",children=[header,Divider(),Text(text="一个长期陪伴你、了解你、主动帮助你的 AI 助理。"),features,details]))
header=Row(weights=[0,1],gap="md",align="center",children=[logo,Column(gap="xs",children=[Text(text="Orbit",variant="h1"),RichText(runs=[{text:"Your companion, your choice.",tone:"muted"}])])])
logo=Panel(padding="sm",radius="lg",background="inset",child=Vector(label="Orbit 环形标志",width=40,height=40,shapes=[{type:"circle",cx:20,cy:20,r:13,stroke:3},{type:"circle",cx:10,cy:30,r:4,filled:true}]))
features=Grid(minItemWidth=140,children=[${features.map(([id]) => id).join(",")}])
details=Column(children=[${features.map(([id, , title, , detail]) =>
    `Conditional(visible=equals(a=$/selected,b=${q(id)}),children=[Panel(background="inset",child=Column(children=[Heading(text=${q(title)}),Text(text=${q(detail)})]))])`).join(",")}])
${features.map(([id, icon, title, description]) =>
    `${id}=Pressable(label=${q(title)},selected=equals(a=$/selected,b=${q(id)}),action=Event(name="local_action",context={op:"set",path:"/selected",value:if(condition=equals(a=$/selected,b=${q(id)}),then="",else=${q(id)})}),child=Panel(background="inset",child=Column(gap="sm",children=[Icon(name=${q(icon)}),Heading(text=${q(title)}),Text(text=${q(description)})])))`).join("\n")}
`;
    }
    if (name === "flow_steps") return String.raw`
root = Flow(label="番茄炒蛋三个阶段",steps=[{title:"准备食材",caption:"2 分钟",icon:"list"},{title:"分别炒制",caption:"4 分钟",icon:"fire"},{title:"混合出锅",caption:"1 分钟",icon:"check",tone:"success"}])`;
    if (name === "flowchart") return String.raw`
root = Column(gap="xs",children=[main,question,split,branches])
main = Flow(label="请求处理：收到请求后校验参数",direction="vertical",steps=[{title:"收到请求",icon:"download"},{title:"校验参数",icon:"search"}])
question = Row(justify="center",children=[Badge(text="参数是否有效？",tone="info")])
split = Vector(label="分成两个分支",width=320,height=36,shapes=[{type:"arrow",x1:160,y1:2,x2:80,y2:34},{type:"arrow",x1:160,y1:2,x2:240,y2:34}])
branches = Grid(columnCount=2,children=[
 Flow(label="有效分支",direction="vertical",steps=[{title:"有效",icon:"check",tone:"success"},{title:"执行业务",icon:"build"},{title:"返回结果",icon:"upload"}]),
 Flow(label="无效分支",direction="vertical",steps=[{title:"无效",icon:"warning",tone:"error"},{title:"返回错误",caption:"附带原因"}])
])`;
    if (name === "data_story") return String.raw`
root = Column(gap="md",children=[
 Grid(minItemWidth=100,children=[Display(label="本周总量",text="9.0M"),Display(label="缓存命中",text="94%"),Display(label="较上周",text="+12%")]),
 Chart(label="每日用量（百万 Token，演示数据）",type="bar",labels=["周一","周二","周三","周四","周五"],series=[{label:"用量",values:[1.2,1.8,1.5,2.1,2.4]}]),
 Table(columns=["模型","占比","平均耗时"],rows=[["主对话","72%","14 秒"],["子代理","21%","38 秒"],["预测","7%","1.2 秒"]])
])`;
    if (name === "mixed_branch") return String.raw`
$/release = {plan:"稳妥"}
root = Column(gap="md",children=[
 Flow(label="发布流程",steps=[{title:"开发",icon:"code"},{title:"审计",icon:"search"},{title:"发布",icon:"upload",tone:"success"}]),
 Segmented(label="选择发布方式",options=["快速","稳妥"],value=$/release/plan),
 Conditional(visible=equals(a=$/release/plan,b="快速"),children=[InfoRow(label="快速发布",text="跳过真机验证，适合文案修改")]),
 Conditional(visible=equals(a=$/release/plan,b="稳妥"),children=[InfoRow(label="稳妥发布",text="真机验证通过后再合并，适合功能改动",tone="success")])
])`;
    if (name === "interactive_flow") return String.raw`
$/flow = {step:0,flavor:"原味"}
$/steps = [
 {id:"step0",order:0,title:"开始",detail:"点击任意步骤查看说明。准备好后，勾选完成并前往下一步。"},
 {id:"step1",order:1,title:"准备食材",detail:"准备番茄、鸡蛋、葱、食用油和适量盐。清洗番茄并切块。"},
 {id:"step2",order:2,title:"打散鸡蛋",detail:"鸡蛋打入碗中，加少量盐搅匀。生熟食材分开放置。"},
 {id:"step3",order:3,title:"炒鸡蛋",detail:"锅热后加油，倒入蛋液，轻推至凝固后盛出备用。"},
 {id:"step4",order:4,title:"炒番茄",detail:"锅中补少量油，放入番茄炒软，按需加一点水。"},
 {id:"step5",order:5,title:"口味选择",detail:"选择甜口或原味。可以随时返回更改，不影响其他步骤记录。"},
 {id:"step6",order:6,title:"混合出锅",detail:"倒回鸡蛋轻轻翻匀，按口味调盐，确认鸡蛋熟透后出锅。"},
 {id:"step7",order:7,title:"结束",detail:"完成！已经完成的步骤仍可点开查看，也可以返回上一步。"}
]
root = Column(children=[Header(text="番茄炒蛋",label="点步骤查看说明，勾选记录进度",status="本地交互"),steps])
steps = Repeat(items=$/steps,key="id",maxItems=8,child=stepRow)
stepRow = Column(gap="xs",children=[node,details,connector])
node = Pressable(label=concat(values=[item(field="title"),if(condition=item_state(field="done",default=false),then="，已完成",else="，未完成")]),selected=equals(a=$/flow/step,b=item(field="order")),child=Header(text=item(field="title"),status=if(condition=item_state(field="done",default=false),then="已完成",else="待完成"),tone=if(condition=item_state(field="done",default=false),then="success",else="neutral")),action=Event(name="local_action",context={op:"set",path:"/flow/step",value:item(field="order")}))
details = Conditional(visible=equals(a=$/flow/step,b=item(field="order")),children=[Text(text=item(field="detail")),branch,CheckBox(label="本步骤已完成",value=item_state(field="done",default=false)),navigation])
branch = Conditional(visible=equals(a=item(field="order"),b=5),children=[Frame(maxWidth=120,child=Vector(label="判断节点：选择口味",width=120,height=64,shapes=[{type:"polygon",points:[[60,2],[118,32],[60,62],[2,32]],tone:"info",filled:true}])),Segmented(label="选择口味",options=["甜口","原味"],value=$/flow/flavor),Text(text=if(condition=equals(a=$/flow/flavor,b="甜口"),then="甜口分支：加入少量糖，先少放，再尝味调整。",else="原味分支：不加糖，保留番茄的酸甜。"))])
connector = Conditional(visible=lessThan(a=item(field="order"),b=7),children=[Vector(label="前往下一步骤",width=320,height=28,shapes=[{type:"arrow",x1:160,y1:2,x2:160,y2:26,tone:if(condition=item_state(field="done",default=false),then="success",else="neutral")}])])
navigation = Row(wrap=true,children=[Button(child=Text(text="上一步"),checks=[{condition:greaterThan(a=$/flow/step,b=0),message:"当前是第一步"}],action=Event(name="local_action",context={op:"add",path:"/flow/step",value:-1})),Button(child=Text(text="下一步"),variant="primary",checks=[{condition:lessThan(a=$/flow/step,b=7),message:"已到最后一步"}],action=Event(name="local_action",context={op:"add",path:"/flow/step",value:1}))])
`;
    if (name === "repeat") return [
        '$/issues = [{id:"login",title:"修复登录",status:"处理中"},{id:"layout",title:"调整排版",status:"待处理"}]',
        '$/query = ""',
        'root = Column(children=[Header(text="任务清单",status="演示数据"),TextField(label="筛选标题",value=$/query),rows,empty])',
        'rows = Repeat(items=arrayFilter(items=$/issues,field="title",operator="contains",value=$/query),key="id",maxItems=20,child=Panel(child=InfoRow(label=item(field="title"),text=item(field="status")),background="inset"))',
        'empty = Conditional(visible=equals(a=arrayLength(items=arrayFilter(items=$/issues,field="title",operator="contains",value=$/query)),b=0),children=[Text(text="没有匹配的任务")])',
    ].join("\n");
    if (name === "curves") return [
        'root = Column(children=[Frame(maxWidth=480,maxHeight=240,aspectRatio=2,child=Vector(label="两条不同形状的曲线",width=320,height=160,shapes=[{type:"quadratic",x1:10,y1:80,cx:160,cy:0,x2:310,y2:80,tone:"accent"},{type:"cubic",x1:10,y1:100,cx1:100,cy1:20,cx2:220,cy2:160,x2:310,y2:100,tone:"success"}]))])',
    ].join("\n");
    if (name === "calculator") {
        const keys = [["AC","AC"],["backspace","⌫"],["sign","±"],["/","÷"],
            ["7","7"],["8","8"],["9","9"],["*","×"],["4","4"],["5","5"],["6","6"],["-","−"],
            ["1","1"],["2","2"],["3","3"],["+","+"],["0","0"],[".","."],["=","="]];
        return [
            '$/calculator = {display:"0",stored:"",op:"",replace:false,error:false}',
            'root = Card(child=Column(children=[Header(text="本地计算器",status="本地计算"),pending,Display(label="结果",text=$/calculator/display,align="end"),error,keys,Text(text="支持小数、退格和连续运算。",variant="caption")]))',
            'pending = Conditional(visible=not(value=equals(a=$/calculator/op,b="")),children=[Text(text=concat(values=[$/calculator/stored," ",$/calculator/op]))])',
            'error = Conditional(visible=$/calculator/error,children=[Text(text="无法计算，请检查除数或清除后重试。")])',
            'keys = Grid(columnCount=4,columnSpans=[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,2,1,1],children=[' +
                keys.map(([key,label]) => `Button(child=Text(text=${q(label)}),variant=${q(key === "=" ? "primary" : "default")},action=Event(name="local_action",context={op:"set",path:"/calculator",value:calculatorStep(state=$/calculator,key=${q(key)})}))`).join(",") + "])",
        ].join("\n");
    }
    if (name === "cost_calculator") return String.raw`
$/form = {years:4,discount:false}
root = Card(child=Column(children=[
 Header(text="费用试算",status="演示数据"),
 Display(label="预计费用",text=formatNumber(value=multiply(a=multiply(a=$/form/years,b=11.622222),b=if(condition=$/form/discount,then=0.9,else=1)))),
 Slider(label="购买年限",min=1,max=10,steps=8,number=$/form/years),
 CheckBox(label="模拟九折优惠",value=$/form/discount),
 Text(text="拖动滑块或勾选优惠即可本地重新计算，不会下单或付款。",variant="caption")
]))`;
    if (name === "charts") return 'root = Column(children=[' +
        ["line","bar","pie","scatter"].map((type, i) => 'Chart(label=' + q(["折线图","柱状图","饼图","散点图"][i] + " · 演示数据") +
            ',type=' + q(type) + (type === "scatter"
            ? ',series=[{label:"演示数据",points:[{x:1,y:7},{x:2,y:12},{x:4,y:26}]}])'
            : ',labels=["A","B","C"],series=[{label:"演示数据",values:[32,56,42]}])')).join(",") + "])";
    if (name === "form") return String.raw`
$/form = {name:"",notes:""}
root = Card(child=Form(label="偏好表单 · 演示",children=[
 TextField(label="称呼",value=$/form/name),
 TextArea(label="补充说明",value=$/form/notes),
 Button(child=Text(text="提交给 AI"),variant="primary",action=Event(name="ai_submit",context={form:$/form,required:["/form/name"]}))
]))`;
    if (name === "info") return String.raw`
root = Card(child=Column(children=[
 Header(text="example.com",label="域名状态 · 演示数据",status="活动中",tone="success"),
 Divider(),
 InfoRow(label="到期日期",text="2028/09/29"),
 InfoRow(label="自动续费",text="已开启",tone="success")
]))`;
    if (name === "stats") return String.raw`
root = Column(children=[
 Grid(children=[Display(label="总 Token",text="9.0M"),Display(label="缓存命中率",text="94.2%")]),
 Chart(label="每日 Token 用量",type="bar",labels=["周一","周二","周三","周四","周五"],
 series=[{label:"Token",values:[1200000,1800000,1500000,2100000,2400000]}])
])`;
    if (name === "progress") return String.raw`
$/tasks = {analysis:true,review:true,test:false}
root = Card(child=Column(children=[
 Header(text="代码审查任务",label="演示清单，仅修改当前卡片",status=if(condition=and(a=and(a=$/tasks/analysis,b=$/tasks/review),b=$/tasks/test),then="已完成",else="进行中"),tone=if(condition=and(a=and(a=$/tasks/analysis,b=$/tasks/review),b=$/tasks/test),then="success",else="info")),
 Progress(label="任务进度",number=divide(a=add(a=add(a=if(condition=$/tasks/analysis,then=1,else=0),b=if(condition=$/tasks/review,then=1,else=0)),b=if(condition=$/tasks/test,then=1,else=0)),b=3)),
 CheckBox(label="分析代码",value=$/tasks/analysis),
 CheckBox(label="检查问题",value=$/tasks/review),
 CheckBox(label="运行测试",value=$/tasks/test)
]))`;
    if (name === "responsive") return String.raw`
root = Column(gap="md",children=[
 Row(wrap=true,gap="sm",align="center",children=[Badge(text="Android",tone="info"),Badge(text="本地交互",tone="success"),Badge(text="响应式布局")]),
 Grid(minItemWidth=120,children=[Display(label="已完成",text="12"),Display(label="待处理",text="3"),Display(label="完成率",text="80%")]),
 Panel(background="inset",padding="md",radius="lg",child=Text(text="屏幕变窄或字体变大时，数据面板自动换行。",textAlign="center")),
 Popover(label="这些数字是什么意思？",child=Column(children=[Heading(text="示例说明"),Text(text="这些是演示数据。可以把面板替换为真实任务统计。")]))
])`;
    if (name === "data") return String.raw`
$/items = [{name:"设计",hours:8,row:["设计",8]},{name:"开发",hours:20,row:["开发",20]},{name:"测试",hours:12,row:["测试",12]}]
$/filter = {minimum:10}
$/date = "2026-10-12"
root = Column(gap="md",children=[
 Header(text="工时筛选",status="演示数据"),
 Slider(label="最低工时",min=0,max=24,steps=23,number=$/filter/minimum),
 Table(columns=["任务","工时"],rows=arrayMap(items=arrayFilter(items=$/items,field="hours",operator="gte",value=$/filter/minimum),field="row")),
 Display(label="全部任务工时",text=formatNumber(value=arraySum(items=$/items,field="hours"),decimals=0)),
 InfoRow(label="一周后",text=dateAddDays(date=$/date,days=7)),
 Text(text=arrayJoin(items=arrayMap(items=$/items,field="name"),separator=" · "),variant="caption")
])`;
    if (name === "vector") return String.raw`
root = Column(children=[
 Vector(label="输入经过本地处理，产生结果",width=320,height=150,shapes=[
 {type:"rect",x:5,y:40,width:85,height:65,tone:"info",filled:true},
 {type:"rect",x:120,y:40,width:85,height:65,tone:"success"},
 {type:"circle",cx:275,cy:72,r:34,tone:"accent"},
 {type:"line",x1:90,y1:72,x2:120,y2:72},
 {type:"polyline",points:[[110,65],[120,72],[110,79]]},
 {type:"line",x1:205,y1:72,x2:240,y2:72},
 {type:"text",x:18,y:58,text:"输入",size:18},
 {type:"text",x:129,y:58,text:"处理",size:18},
 {type:"text",x:254,y:58,text:"结果",size:18}
 ]),
 Text(text="矢量图用于解释关系；真实统计数据请使用图表。",variant="caption")
])`;
    if (name === "p2_controls") return String.raw`
$/form={date:"2026-10-12",notes:"",code:"",busy:false,step:0,formula:"\\frac{1}{2}"}
root=Column(children=[
 Header(text="原生控件演示",label="所有输入与切换均在本地"),
 Stepper(label="准备步骤",labels=["填写","检查","完成"],current=$/form/step),
 DatePicker(label="日期",value=$/form/date,minDate="2026-10-01",maxDate="2026-10-31",clearable=true),
 TextField(label="数字编号",value=$/form/code,mask="digits",error=if(condition=equals(a=$/form/code,b=""),then="请输入数字编号",else="")),
 TextArea(label="备注",value=$/form/notes,minLines=2,maxLines=5),
 Switch(label="预览按钮忙碌状态",checked=$/form/busy),
 Button(child=Text(text="保存"),loading=$/form/busy,action=Event(name="local_action",context={op:"save"})),
 Tooltip(label="为什么按钮不能点？",text="忙碌时会显示进度圈并禁止点击。关闭上方开关后即可使用。"),
 RichText(runs=[{text:"旧值 ",strike:true},{text:"重要信息 ",highlight:true},{text:"1234.56",tabular:true}]),
 TextField(label="公式源码",value=$/form/formula),
 Formula(label="公式预览",text=$/form/formula),
 Table(columns=["项目","数量"],rows=[["甲",12],["乙",24],["丙",36],["丁",48],["戊",60],["己",72]],columnWidths=[160,100],maxHeight=160)
])`;
    if (name === "p2_lists") return String.raw`
$/groups=[{id:"a",title:"厨房",rank:2,children:[{id:"one",title:"准备食材"},{id:"two",title:"整理台面"}]},{id:"b",title:"客厅",rank:1,children:[{id:"one",title:"整理书架"}]}]
root=Column(children=[
 Header(text="分组任务",label="相同子任务 ID 在不同分组中独立保存"),
 Row(wrap=true,children=[
  Button(child=Text(text="按顺序排列"),action=Event(name="local_action",context={op:"set",path:"/groups",value:arraySort(items=$/groups,field="rank")})),
  Button(child=Text(text="重命名厨房"),action=Event(name="local_action",context={op:"set",path:"/groups",value:arrayUpdateByKey(items=$/groups,key="id",value="a",patch={title:"厨房与餐厅"})}))
 ]),
 groups
])
groups=Repeat(items=$/groups,key="id",child=Panel(child=Column(children=[
 Heading(text=item(field="title")),
 tasks
]),background="inset"))
tasks=Repeat(items=item(field="children"),key="id",child=CheckBox(label=item(field="title"),value=item_state(field="done",default=false)))
`;
    throw new Error("Choose shopping_list, trip_budget, filtered_chart, backgrounds, surfaces, profile, flow_steps, flowchart, data_story, mixed_branch, stats, charts, info, vector, curves, responsive, interactive_flow, calculator, cost_calculator, form, progress, data, repeat, p2_controls or p2_lists.");
}
exports.read_example = async ({name, source_only = false}) => {
    if (typeof source_only !== "boolean") {
        complete({success:true,valid:false,message:"source_only must be a boolean."});
        return;
    }
    const source = example(name);
    complete(source_only
        ? {success:true,source,message:"Example source only; no card was created. Adapt it to the user's data, then call prepare_card to validate and display."}
        : prepare(source));
};
