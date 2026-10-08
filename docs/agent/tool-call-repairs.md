# 工具调用修复路由

聊天工具批次在权限检查之前应用确定的修复规则：

- `read_file` 出现 `start_line` 或 `end_line` 时，改为 `read_file_part`。
- `super_admin` 命名空间下重复冒号的 `terminal`（如 `super_admin::terminal`、
  `super_admin:::terminal`）精确修正为 `super_admin:terminal`；其他名字不动。
- 仅对 `super_admin:terminal`，将有效整数毫秒 `timeout` 改名为 `timeoutMs`。
  兼容约定：3～300 的整数按秒乘以 1000；3000 到 Int.MAX_VALUE 按毫秒保留；
  其余值不猜测。与 timeoutMs 换算后相等时移除别名，冲突时不改。正式参数仍为 timeoutMs。
- `proxy` / `package_proxy` 外层的 `package_name` 与目标包名前缀完全相同时移除重复字段。
- 代理调用没有声明 `tool_name`（缺失或空白）时，如果 `params` 末尾并入了目标名，
  且只可能是 `<对象>, {"tool_name":"x"}` 或 `<对象>, "tool_name":"x"}` 这两种形态之一，
  则还原出工具名与参数对象。参数对象的边界按字符串感知的花括号配对判定（命令里含 `{`、`}`
  不影响），并且必须是一个不含同名键的合法对象，否则不改。
- `memory_learning_action` / `learning_manage` 的 `arguments` 里出现 `new_text`、没有 `content`，且 action
  属于读取 `content` 的 `memory_change` / `skill_create` / `skill_write` / `skill_patch` /
  `skill_remove_file` 时，将 `new_text` 改名为 `content`。
- 支持直接调用和代理调用，保留代理外壳；`package_proxy` 仍只接受带包名的工具。
- 直接调用 `memory_learning_action` 时，精确的 `action=memory_learning_finish`，且
  `arguments` 缺失或为严格空 JSON 对象、无额外参数，改为独立的 `memory_learning_finish`。
  终止批次识别也使用该结果；非空参数、代理调用不做此修复，仍需能力鉴权。
- 除上述明确的秒转毫秒规则，保留参数值；保留调用 ID、原始响应与调用位置，不修正路径。
  代理参数需要改名时重新编码 JSON，保留值及类型；只改工具名时保留原始 JSON 文本。
- 格式无效、重复的外层参数或 JSON 任意层级重复键不自动修复；嵌套的 `arguments`
  文本与代理 `params` 共用同一套严格解析，重复键会拒绝修复而不是静默折叠。

新增规则在 `ToolCallRepairRules.ordered` 注册唯一 ID 和纯转换函数，未命中返回 null。
规则按顺序只执行一遍，可组合（例如先修正工具名，再改超时参数名），不循环重试。
规则不得执行 IO、请求模型、调整权限或修改输入对象。新增规则应覆盖命中、不命中、
冲突、重复应用，以及代理外壳与参数值保留的测试；日志面板为规则 ID 增加中英文说明。

转换后的工具经过原有工具可用性、角色卡和权限检查。原始调用继续用于重复调用检测。

`terminal_wait` 如收到 `command` 会明确拒绝并告知命令未执行，不会静默忽略或自动执行。
记忆读取返回当前字符数、上限和剩余量；超限错误包含本次拟写入的大小和超出量。
连续三次完全相同的记忆调用失败后，拦截相同输入并如实返回失败；修正输入仍可调用，
成功读取或其他操作会重置计数，以便处理容量、版本变化后重试。

每次转换独立记录到应用私有目录 `files/tool_call_repairs.jsonl`，每行一个 JSON 对象：
时间戳、调用 ID、调用序号、原工具名、目标工具名、全部命中规则和转换前后参数名称。
保留旧 `rule` 字段（第一条命中规则）；新日志使用 `rules` 数组，查看器也兼容旧日志。
不记录参数值或原始响应内容。记录表示路由发生，不代表执行成功或通过权限检查；
执行失败仍使用原有工具错误记录。日志异步写入，写入失败会报告到应用日志。

在工具箱的日志查看器中选择“工具调用修复”可分页查看，最新写入的记录在前，
点击记录可复制完整 JSON。打开页面时会自动读取，新增记录会自动刷新。
“工具调用问题”支持全部、近 24 小时、7 天、14 天及 30 天筛选。
