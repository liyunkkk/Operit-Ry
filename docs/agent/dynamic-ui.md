# 聊天动态界面（Dynamic UI）

## 使用

定位：聊天回答里的原生富内容，交互只是可选增强。每个 `a2ui-ref` 渲染成一个无框块，
出现在模型放置它的位置，可与 Markdown 段落任意穿插，一条回答可以有多个块。
指南要求模型按「文字 → 纯展示 UI → 需要操作才加交互」选择最轻的形式；
纯展示块不需要 `$/` 状态、`Event` 或按钮。

模型在调用 `read_guide` 之前只能看到系统提示「可用包」里的一行包描述。描述要求：凡是讲解、
介绍、分析或对比内容，就先加载本包并读取 `read_guide`；用不用界面块由模型读完后自己决定
（简单定义、观点、简短回答、代码、普通列表不用）。`read_guide` 开头的
DECIDE FIRST 说明读取指南不等于必须生成界面；VISUAL RULES 约束标题层级、同类项一致、
徽章只表达含义、结论不放彩色面板、按钮必须有实际作用。

段落内的状态不需要块：模型直接在 Markdown 正文写 `:badge[已完成]{tone=success icon=check}`
或 `:icon[time]`，无需 `prepare_card`。包描述只写触发条件（汇报有依据的状态或结果），
语法与使用规则只在指南 INLINE IN PARAGRAPHS 里，所以模型要先激活包并读取 `read_guide`。
解析在 `util/markdown/InlineDirectives.kt`，只扫描纯文本节点，
行内代码里的同样写法保持原样（复制时也是）；格式不对时原样显示文字，未知图标名不显示。
徽章不会折行，标签最多 16 个字符；引用块按纯文本绘制，其中的徽章只显示标签文字。
`accent` 的墨色把主题主色向正文一侧（浅色向黑、深色向白）拉近 30%，保证小字对比度。
渲染在 `MarkdownInlineElements.kt`：徽章是画在标签文字上的圆角 `ReplacementSpan`
（复用 `Flow` 的语义色和线框图标表），图标是与字号等高的 `ReplacementSpan`，
二者都按文字视觉中心对齐，与行内公式一致；表格单元格同样生效。
解析器可能把 `[已完成]{…}` 拆成多个纯文本节点，渲染和复制前会先合并相邻纯文本。
复制全文和朗读保留徽章文字、去掉图标。

卡片 `Icon`、`Flow`、行内图标和徽章共用 `CardIcons`。内置 Lucide v0.456.0 的 1535 个图标，
以 `lucide:orbit` 等明确名称访问；无冲突时也接受裸名称。`list_icons(query,limit,offset)`
按英文名称和关键词查找，返回名称、总数和下一页起点，不向全局提示词注入完整清单。
`cardFlowIcons` 继续保存旧 Material 名称，包括 `phone` / `smartphone`（手机）、
`globe` / `language`（地球）、`browser`（网页）以及电脑、终端、服务器、模块、语音等图标。
保留原 AndroidX 内置图标名称（包括 `calendarToday`），旧卡片可继续显示。
例如 `Row(children=[Icon(name="phone"),Text(text="Operit Core")])`；
普通正文写 `:icon[globe]{tone=info}`。只有行内 Badge 接受 `icon` 属性，
卡片 Badge 仍只支持文字和语义色。

卡片写 `Icon(name="lucide:orbit",tone="accent",size=32)`，`tone` 支持
neutral/accent/success/info/warning/error（可绑定状态），不指定则继承内容颜色；
`size` 为 12～96 的整数字面量，单位 dp、默认 24，最终受父布局限制。
正文写 `:icon[lucide:orbit]{tone=info size=lg}`，尺寸 sm/md/lg 分别为正文的
0.85/1/1.25 倍，默认 md；徽章内图标继续随标签字号。图标名上限 64 字符，
徽章标签仍为 16 字符。行内绘制保留填充、描边、圆头和透明度，继续支持 Plain/Dot/Halo。

图标数据由 `tools/icons/generate_lucide.py` 从固定 SHA-512 的官方 npm 压缩包生成，
不在运行时加载 SVG/脚本或联网；Compose 图标与 Android Path 各有 128 项 LRU 缓存。
许可证随 APK 放在 `assets/licenses/lucide-0.456.0.txt` 和 `feather-4.29.2.txt`。
生成与升级方式见 `tools/icons/README.md`。

工具包描述和 `read_guide` 会引导 AI 主动选择有助于理解的可视化，无需用户明确要求：
线性步骤和流程用 `Flow`，趋势用折线图，数量对比用柱状图，占比用饼图，数值关系用散点图；
参数试算用本地控件和计算，收集信息用表单。简单回答仍用文字或 Markdown 表格。
事实图表必须使用用户或工具提供的数据，演示数据与假设需明确标注；不为配图编造数据。
这是给模型的使用指引，不会强制每条回答生成卡片。

宿主不再绘制外框、背景或内边距，块上下各留 8 dp，与正文对齐；源码按钮只在出错或
图标缺失时出现。需要视觉边界的独立工具（计算器、表单、状态记录）由模型显式使用
`root = Card(...)`，`Card` 自己画白底（深色为深灰）、淡边框和 16 dp 内边距。
无框块中的 inset 底色（`Display`、`Panel(background="inset")`、`Flow` 中性步骤）是半透明墨色，
在任何气泡颜色上都可见；图表悬浮提示保持不透明。发送/工具确认面板自带边框。
`Flow(label, steps, direction?)` 绘制静态步骤图：1..8 步，每步 `{title, caption?, icon?, tone?}`，
`auto` 在步骤不超过 4 个且放得下时横排并用 → 连接，否则竖排并用 ↓ 连接；
多个 `Flow` 放进 `Column`/`Grid` 可组成简单分支。`steps` 可绑定状态，编译、展开和渲染三层校验一致。
`Header` 组合标题、说明和状态徽章；`InfoRow` 左右排列标签与数值，长内容改上下排列。
`Badge`、`Header` 和 `InfoRow` 支持 neutral/success/info/warning/error 语义色，含深色模式。
`Display(text,label?,align?)` 提供突出结果区，默认左对齐；计算器显式使用 `align="end"`。
长结果先缩小字号（最小到 50%），仍放不下再均衡换行，不省略数字，也不需要横向拖动。同一行网格里的 Display 和 Panel 高度对齐。
窄网格单元格（同行多格且宽度不足 240dp）里，`Text` 的 h1–h4 统一缩为 18sp，短标题保持一行，同行各格字号一致。
`Grid` 内按钮铺满各自单元格，未满行不会重新均分。显式 `columnCount` 时可传
`columnSpans` 整数数组，与 `children` 一一对应，每项为 `1..columnCount`；
跨列不足时另起一行。计算器的零键使用两列，费用试算也复用 `Display`。
启用「动态界面」工具包。AI 先调用 `dynamic_ui.read_guide`，再调用
`prepare_card(source)` 编译 Express 源码。成功后只需在回答中放返回的 `a2ui-ref`
短围栏。应用在模型文本进入显示、历史和持久化之前，将引用展开为完整 `a2ui`。
不再让模型二次抄写 JSON；工具不回传整份编译结果。`valid:false` 保留行、列和原因。
新编写入口只有 Express，移除 `prepare_card(components,data)` 和 `validate_card`。

`read_example` 的纯展示示例有 flow_steps（横向步骤）、flowchart（带分支的流程图）、
data_story（指标 + 图表 + 表格）、stats、charts、info、vector、curves；
mixed_branch 只在分支选择处交互；交互示例有 calculator、cost_calculator、form、progress 等。
同样编译后返回短引用，不修改真实应用配置。
每张卡片独立初始化，不能跨卡片更新状态。

需要学习后自定义时，用 `read_example(name="...", source_only=true)` 只取 Express
源码，不创建卡片或展示引用。按需读一个相关示例，替换演示数据后调用 `prepare_card`；
默认 `source_only=false` 仍直接编译并展示原示例。新增三个完整本地交互示例：

- `shopping_list`：人数滑块同步调整食材数量，每行勾选状态独立保存。
- `trip_budget`：人数、晚数、房间数同步计算合计和人均，均为明确标注的演示价格。
- `filtered_chart`：地区选择同步改变图表标签、数值与汇总，全部取自同一组原始记录。

指南先引导组织整块内容的阅读顺序和控件依赖，再选择组件；宽度与字号由客户端
响应式布局决定。公式直接写在组件属性中，普通命名声明只用于组件，不能声明公式别名。
示例沿用 Express → A2UI → Compose，无新增网页引擎、脚本权限或 HTML 使用条件变化。

## Express 编写与交付

参考 [A2UI Express 规范](https://a2ui.org/specification/proposals/express/a2ui_express/)及
[`Express.g4`](https://github.com/a2ui-project/a2ui/blob/ce03d005c0575ff6708da583d9e7658a34df2d5d/specification/inference_formats/express/Express.g4)，
参考版本 `ce03d005c0575ff6708da583d9e7658a34df2d5d`。
这是 Operit 的 **Kotlin 受限子集实现**，不是完整官方 Express SDK，也不宣称通过其全套 conformance。
不引入 TypeScript/ANTLR 运行时。

```text
$/form = {years:4}
root = Column(children=[
  Heading(text="费用试算"),
  Display(label="演示金额",text=formatNumber(value=multiply(a=$/form/years,b=12.5))),
  Slider(label="年数",min=1,max=10,steps=8,number=$/form/years)
])
```

仅命名参数；一行一项赋值，可多行构造。组件内联或用变量引用，编译器生成 ID；
使用 `root` 入口。状态仅绝对对象路径、字面量初始化；纯函数使用现有允许列表，
动作以 `Event(name=...,context={...})` 声明。不支持位置参数、标签、脚本、import、
生命周期命令、远端目录、模板或跨卡片更新。组件说明和签名来自同一作者目录。
解析前限制 64K 字符，解析中限制深度、节点、数组和字段数量，编译后再过原生协议校验。

注册只能通过当前 `dynamic_ui` 工具调用的真实执行会话；引用归属稳定的 run ID，
不受 steering 引起的消息计时 scope 切换影响。仅模型正文中的独立完整引用围栏可展开，
思考、工具内容、普通代码示例不展开；SAVEPOINT/ROLLBACK 同时恢复解码状态。
未闭合引用保持代码，丢失或跨轮引用显示明确提示。单轮最多 16 张/512K 编译文本，
临时全局存储最多 4M 字符；单次采样展开最多 512K，重复引用也计入。
整轮结束清理临时内容。最终聊天记录为自包含卡片，不需要临时存储进行导出和恢复。
这节省二次生成成本，不代表后续历史中的卡片源码不占输入 token。

卡片原文随聊天消息保存，本地字段值以原子文件保存在应用私有 `dynamic_ui/`。
按聊天、消息时间戳、回答版本、围栏位置、源内容和 surface ID 隔离。
重开恢复字段；编辑或重新生成卡片后使用新版本。删除聊天会清理其卡片状态。
此轮未将侧边状态加入聊天导出/跨设备同步，导入聊天会使用协议中的初始值。
Expandable 可将 `expanded` 绑定数据路径，跨页面恢复展开状态。

输入不请求模型。只有明确点击 `ai_submit` / `tool_action` 并确认，才生成
当前聊天的一条用户消息；聊天忙碌、切换聊天、聊天只读或消息已被编辑时拒绝提交。
输入框的文本、附件和回复草稿不被卡片消耗。`tool_action` 是正常工具操作请求，
不会直接运行工具，也不绕过自动审核和权限检查。

## 官方集成

固定 AndroidX A2UI alpha01，使用其 parser、processor、数据绑定和 Compose surface。
协议固定 **v0.9**；自有目录 ID 为 `https://operit.ai/a2ui/catalog/v1`。
这是标识符，不是网络加载地址。原生组件继续使用官方 Compose；
仅用户点开高级 `Html` 视觉时创建独立离线 WebView，不引入第三方 React。

- `DynamicUiProtocol`：围栏事件校验、静态图和资源边界。
- `DynamicUiCatalog`：官方基础组件，以及原生 Form/Table/Grid 等目录扩展。
- `DynamicUiCard`：按序增量入队、恢复屏障、动作预览、提交和局部错误。
- `DynamicUiStateStore`：原子写入、合并待写状态和聊天删除清理。
- `CanvasMarkdownNodeRenderer`：仅识别明确的 `a2ui` 语言围栏。
- `ChatViewModel.submitDynamicUi`：再次核对会话、消息和版本，复用原聊天发送链路。

P0 支持 Card、Text、Heading、Display、Badge、Icon、Row、Column、Grid、Divider、
Button、Progress、List、Table、Code。
P1 支持 TextField、TextArea、CheckBox、RadioGroup、Select、Slider、Switch、
DatePicker、Form、Expandable。Select 和 DatePicker 在卡片内展开，不创建 Activity
弹窗；悬浮窗不能自行提交到某个猜测的会话。

扩展目录支持：

| 能力 | 实现和边界 |
| --- | --- |
| 排版与导航 | 响应式 Grid、分段选择、Tabs、可滑动 Carousel、Conditional 条件显示 |
| 富文本 | RichText 的结构化字重、斜体、下划线、逐段语义色（primary/muted/success/info/warning/error）、字号及安全链接；不是 HTML |
| 图表 | 原生 Canvas 折线、柱状、饼图（环形）、散点；点击查看数值，读屏朗读数值（共最多 64 个，各系列均分，并说明省略数量）；最多 4 系列、每系列 128 点 |
| 本地计算 | 官方动态函数绑定，支持四则运算、比较、布尔、条件值、格式化、字符串组合；不请求模型 |
| 媒体 | 图片、音频、视频，用户点击后才加载；不自动播放；离开可见区域或应用后台暂停播放器 |
| 数据卡片 | 商品、地点、网页、文件链接；数据由用户/工具提供，不保证实时；文件入口仅 http(s) |
| 地图 | 地点坐标示意、按顺序连线、外部地图入口；不含道路底图、路线规划或导航 |
| 草稿 | Draft 本地编辑、保存、复制；需要发送给 AI 时另放明确提交按钮，不会发送邮件或发布文章 |

公式、Mermaid、Markdown 表格继续使用既有普通消息渲染；不在 A2UI 内嵌套
Markdown 围栏或工具标记。高级 Html 仅接受白名单 HTML/CSS，不开放作者脚本、设备权限或隐式请求。
以上是 Operit 自有目录能力，不声称使用了 ChatGPT 私有 UI SDK。

按键计算器仍由 Form/Grid/Button 等普通组件组合，不新增固定 Calculator 界面组件。
`calculatorStep(state,key)` 是有界纯函数；按键通过已有 `local_action/set` 原子替换
该计算器状态。支持小数、退格、清除、正负切换、连续运算、除零提示和错误后重输。
结果保留 12 位有效数字，绝对值不超过 1e12，普通十进制最多 24 位小数；
超范围显式提示错误。模型生成的科学计数法状态不会进入巨量字符串格式化。

`Grid.columnCount` 可显式指定 1～4 列，窄屏保留触摸宽度并允许横滚；
`Slider.steps` 是中间停靠点数量，0 表示连续，例如 0～100 整数用 99。
TextField 的数字字符串可以直接交给算术函数，不必为了计算改成滑块。

动作限定 `local_action`、`ai_submit`、`tool_action`、`open_url`、`copy`。
本地操作只允许 set/add/save/edit/cancel；不支持任意代码、网络函数、正则、递归模板、
远端目录或隐式操作。open_url 限 http/https，不能含 URL 凭据。
未完成事件不进入官方 parser；完整事件可显示，围栏结束且恢复完成后启用交互。
重放历史不会执行动作。独立 surface 顺序消费；尾部保留标记用于确认恢复已完成。

最大 128K 源字符、256 事件、4 surface、每 surface 128 组件、
32 个静态子节点、12 层组件、512 个展开实例；表格 8 列/32 行，
数据状态 32K 字符，单字段 8192 字符。超过限制显示错误，不请求 AI 自动修复。
状态写入失败明确提示，表单过大阻止保存/提交。

纯函数白名单：add/subtract/multiply/divide/min/max、equals/greaterThan/lessThan、
and/or/not、if、formatNumber、concat、calculatorStep；
另有 arrayLength/arrayMap/arrayFilter/arraySum/arrayAverage/arrayJoin、
stringContains/stringReplace/stringTrim、dateAddDays/dateDiffDays/formatDate。最多 6 层嵌套、128 次调用声明，
只在动态属性/检查/动作上下文使用；初始数据及组件结构不含函数。数值必须有限且
绝对值不超过 1e12，除零/无效数字得到未解析值，修正输入后可恢复。if 参数是急切求值。
表单检查使用 Button.checks；不会执行正则。所有函数无 I/O、无工具访问、无状态写入。
动态卡片保存后禁用字段修改，但仍可切换浏览区、查看图表、复制或打开链接。
提交预览按字段标签展示实际发送的值，确认后仍走正常聊天、工具审核流程。

未开放任意画布代码、文件上传、内部文件 URI、内嵌网页和模态窗口。
新增能力通过目录组件和安全边界扩展，不把任意 JSON 当界面。
包指南按需读取，不在系统前缀塞入完整 schema。

## 布局、表达式与局部修订

- Row 支持 wrap、gap、align、justify；默认保持内容宽度，显式 justify 才铺满可用宽度；
  Column 支持 gap、align。Grid 未指定列数时按
  实际可用宽度、minItemWidth 和字体缩放选择 1–4 列；键盘仍使用明确列数及跨度。
- Panel 集中提供有限间距、圆角、背景、边框和最大宽度；Text 支持对齐、1–8 行截断和语义色 `tone`（与 Badge 同一组，不开放任意色值；自定义气泡上自动校正到 4.5:1）。
- `Panel.background`：原有 `transparent/surface/inset/accentMuted`，另支持 `light/dark/blue/green/orange/red`。
  `info/success/warning/error` 分别是蓝/绿/橙/红的别名；彩色底跟随当前局部明暗配色，
  `light/dark` 则明确指定浅灰/深蓝灰底。面板内文字、图标和控件使用该面板的局部配色，
  嵌套透明面板继承；不会改变面板外的气泡或其他内容。不接受 HEX/RGB、渐变或背景图。
  `read_example(name="backgrounds")` 提供浅色、蓝色、绿色、深色四格示例。
- 复杂视觉可用 `prepare_html(label, html, css, max_height?)`，仍返回正文短引用，也可在 Express 里组合
  `Html(label=...,html=...,css=...,maxHeight=720)`。支持自定义 CSS 颜色、渐变、纹理、动画、
  filter/backdrop-filter（实际效果依赖系统 WebView），以及 radio/checkbox + label、details 的本地交互。
  HTML 是 body 片段，HTML/CSS 各限 8192 字符、最多 256 元素，每卡最多 4 个 Html；
  高度按内容适配至 120～900dp 上限（默认720），超出滚动。宽度由容器决定。
  `--operit-text/surface/accent` 提供当前主题色；自行指定背景时同时指定清晰的前景色。
  作者不可执行 JS；仅应用内置的随机 nonce 授权脚本测量内容高度，CSP 拒绝其他脚本。
  页面无原生桥、网络、文件/Content 访问、导航、表单提交或存储能力。
  CSS 交互状态只在当前视图存活期间保留，持久状态、工具/AI 操作和输入表单继续使用原生组件。
  `read_example(name="surfaces")` 演示极光、星云、赛博、柔光四种背景与本地切换。
  **使用条件**：仅用户明确要求高级视觉效果或 HTML/CSS 卡片时允许模型使用；普通卡片、
  图标和排版需求仍用原生组件，禁止为装饰或绕过原生错误而主动使用。
  **运行门槛**：默认只显示“这是一个 HTML 卡片，点击进行加载。”，点击前不创建 WebView。
  完全离屏或应用进入后台后销毁网页实例，回到可见前台时重新加载；保留占位高度防止聊天跳动，
  HTML 内部临时选择状态会重置。加载许可按该组件的 HTML/CSS 内容保留，源码改变需要重新点击。
  正文默认不截断。Scroll 必须给出 80–480 dp 的最大高度，避免聊天无限高度约束。
- 数组运算限制 128 项、32K 结果；字段投影/筛选使用相对对象路径，不能执行回调。
  Table 可使用派生行，仍要求 8 列/32 行及标量单元格。空数组平均值、缺失字段、
  无效数字返回未解析值；空数组求和为 0。字符串最多 8192 字符。
- 日期是 1900–2100 年的 ISO 日期；日期运算不受设备时区影响。
  if 可返回有限对象/数组，但仍急切求值，不能用它保护无效运算。
- Popover 是卡片内锚定浮层，有关闭按钮、外部点按关闭和有限滚动区域，
  不创建 Android 弹窗。最多 320×240 dp；短卡片展开到足够容纳浮层。
- Vector 提供 line/rect/circle/polyline/polygon/text，最多 64 图元、每条 128 点，
  语义色、有限描边、完整可访问描述；无任意 SVG 路径、脚本或网络资源。
- update_card(reference, changes, remove?) 只修改当前运行中卡片的命名 Express 声明。
  先合并 AST，再完整编译校验，成功注册**新引用**；失败不发布。
  它减少模型重写源码的量，不是已显示卡片的实时热更新。旧引用及用户填写状态保留；
  新卡片按新源码初始化。不同运行不可读彼此引用，内存配额同时计算源码和编译文档。
  旧历史卡片如需修改，应重新 prepare_card，不从历史恢复临时能力。

模型指南与示例覆盖 responsive、data、vector；示例通过同一原生编译器验证，
不建立单独绕过校验的演示通道。真实地图、任意代码以及历史卡片热更新未实现。

## 真机反馈修复

2026-10-11 用户测试发现 `Icon(name:"dashboard")` 报错时宿主隐藏整张卡片；
另一次生成含错拼的 `updataDataModelPlaceholder`，且把初始化分散在两个围栏。
后者是无效协议，不应把缺失的初始化猜成有效卡片。

- 非内置图标局部占位，不使无关字段失效；其他组件错误保留可显示内容，
  只锁定出错 surface 的动作。
- 协议错误保留行号供展开诊断，运行日志只记录阶段/标识/错误类型，不记录表单内容。
- 指南明确单围栏完整初始化、已知图标名称和组件属性。
- 去掉重复卡片标题/边框和大号源码按钮；表单单列、选项整行可点、
  按钮有主次、保存后不显示原始 JSON；源码入口缩为小图标。
- 图表按实际柱形位置命中，坐标间距按屏幕密度计算；无效数据有局部反馈。

第二次真机计算器反馈：首次生成的 NDJSON 第 2 行括号不配对；重写后计算正常，
但模型把连续滑块的小数结果格式化为整数，造成 `3.219 + 3.814 → 7` 的误解。
不是响应式引擎失效，也没有达到函数嵌套深度上限。
当时增加了原生校验工具；现已由 Express 编译入口统一校验并交付，
不再公开手写 NDJSON 的生成流程。检查仍不保证视觉布局或业务计算意图正确。

第三次真机反馈：模型反复抄写示例，22 次校验有 15 次失败，多数是在
`updateDataModel` 的嵌套初始值末尾漏掉闭合括号。嵌套对象本身受支持。
原先 `success:false` 会让工具执行器只保留 message/data，丢失 line/code。
校验现在将文档不合法作为 `success:true, valid:false` 的完整检查结果返回；
包含安全错误码、行号、可用时的列号和原因，文字消息也包含位置。
解析器原始片段不会进入诊断；工具不可用仍属于执行失败。

这次重构将生成流程统一为 Express 编译与短引用交付，消除校验后再次抄写的环节。
渲染入口继续独立校验；未引入自动补括号或静默放宽协议。

本地状态动作在宿主消费事件时重新解析原始绑定，随后无挂起地写回，避免排队按键
基于同一旧快照计算；发送给 AI/工具请求继续使用点击时快照，确认预览不变。

## 工具链与验证范围

AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.20 / compileSdk 37.1，
Compose BOM alpha 2026.09.00（UI 1.13.0-alpha03、Material3 1.5.0-alpha28）。
targetSdk、minSdk、版本、签名均不改变。官方 AAR 最低 AGP 9.1，
但 AGP9.1 文档测试上限是 API37.0，本地构建仍会保留该警告。

升级对既有下拉菜单、思考滑块、过渡动画及本地 LazyLayout 预取做了 API 迁移，
不能把改动范围描述为「仅影响新卡片」。工具链编译通过与真机体验通过是两回事。
主仓库 CI 已同步 SDK/Build Tools/NDK；外部 Nightly 发布仓库仍需在发布前同步，
本任务不修改公开子模块、不推送。

调研基线：AndroidX A2UI、a2ui-project/a2ui、uny/a2ui-compose、
JAICHANGPARK/droidkaigi2026-a2ui、Vercel json-render。依赖采用官方 AndroidX；
没有复制其他项目的渲染引擎。实际发布源码与 Maven metadata 优先于示例旧 README。

2026-10-11 初版验证：工具链单独编译通过，接入后编译、8 项定向 JVM 测试及
`assembleDebug` 通过。官方引擎测试使用发布版 parser/processor，JVM 中仅将
Android JsonReader 替换为 Gson token reader。APK 内指南与源码一致；
包名仍为 `com.rainy.operitry.dev`，versionCode 100205、minSdk 26、targetSdk 34。
初版独立只读代码审计结论 CLEAN，之后按用户要求覆盖安装并取得上述真机反馈。
扩展版编译、14 项定向 JVM 测试通过；代码安全与 UIUX 独立只读复审均为 CLEAN。
测试包括官方引擎计算器 41.84 → 37.66 → 94.14 的状态重算且不产生动作、
四类图表的官方引擎回放、协议及资源边界、无效数字恢复、提交预览标签/数值。
`node examples/tests/dynamic_ui_examples.cjs` 核对包输出、JVM 示例和打包资产一致。
这些是协议/逻辑验证，不是截图和触摸测试。扩展版已于 2026-10-11 17:26 覆盖安装，
保留应用数据，之后收到上述计算器反馈。
后续计算器修复：编译及 19 项定向测试通过，包含官方 action 队列连续输入
`12 × 34 = 408`、`0.1 + 0.2 = 0.3`、除零恢复、极端指数防护及真机坏 JSON。
本批独立只读审计 CLEAN；这批修复尚未再次安装真机。
未执行完整 Lint；
Compose/A2UI 使用 alpha 依赖，发布前仍需检查既有输入栏、下拉菜单和长聊天滚动体验。
