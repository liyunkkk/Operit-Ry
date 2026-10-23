# 应用体积精简

## 手机本地大模型

MNN 和 llama.cpp 的运行、模型下载及设置入口已移除。Ollama、LM Studio、
OpenAI 兼容远程服务不受影响，OCR、语音识别及 ONNX 语音功能保留。

旧配置和备份仍可读取，但这两个已退役的提供商不能执行请求或用于新配置。
保留序列化枚举和旧参数，避免删除功能同时破坏用户配置。
用户下载的 `Download/Operit/models` 文件不自动删除；旧配置页提供路径说明，
用户可通过文件管理器清理。

## 依赖、模板和缓存

移除没有业务调用的 TensorFlow Lite、MediaPipe Text、Devanagari OCR。
保留中文、拉丁文、日文、韩文 OCR，以及语音实际使用的 ONNX Runtime。
Android / Flutter 项目共用一份 aapt2，创建项目时仍复制到各自原有工具路径。

技能仓库 ZIP 缓存同时限制为 6 份和 128 MiB。正在导入的 ZIP 持有租约，
不参与并发淘汰；超大 ZIP 可用于当前导入，结束后不缓存。中断下载的临时
文件在下次访问缓存时清理，下载失败或取消也立即清理临时文件。

## 按需资源

离线 NCNN 语音模型、Ubuntu 安装包和 Android / Windows 导出模板不再打进 APK。
需要某个资源时先弹确认框（写明资源和大小），用户同意后联网下载；已安装的 Ubuntu
和经过校验的本地资源可离线复用，不再询问。确认框不看网络类型，Wi-Fi 和移动网络一致。
拒绝、取消或长时间未回答不会启动下载；拒绝后 30 秒内的自动重试沿用同一结果，
避免功能自身的重试循环反复弹窗，之后再次使用该功能会重新询问。
Silero VAD 仍内置，云端语音和唤醒检测不需要下载 NCNN 模型。

设置 →「组件与下载」集中管理 8 项可恢复资源：Ubuntu 安装包缓存、NCNN 模型、
SenseVoice / Paraformer 识别模型、AISHELL3 / Melo 合成模型，以及两种导出模板。
显示完整下载大小和本地占用（包括未完成的分片），可下载、取消、重试和删除。
点击下载即表示同意，离开页面后下载继续；下载模型不会改变当前语音配置。
当前选中的语音模型不能删除，先在语音服务设置中切换；模板正在被导出读取时不能删除。

Ubuntu 缓存位于 `files/downloaded_resources/terminal/`。终端准备阶段复制到原安装器路径，
复制与缓存删除互斥，安装器只读取自己的副本。管理页不删除安装副本、rootfs、
用户软件或文件，也不会关闭终端会话。旧版安装副本经过校验可直接复用。
原始快照不再收录 `local_voice_models` 和 `downloaded_resources`：恢复时保留设备上
已有资源，缺少的组件在使用时重新下载，避免备份重复携带数百 MiB 模型。

下载使用固定版本 URL、文件大小和 SHA-256，流式写入同目录临时文件，校验通过
后原子替换。中断或取消会保留已下载分片，下次下载用 `Range` 续传，进度从断点继续；
只有校验失败、分片超出预期大小，或服务器不接受续传（回了整份文件）时才丢弃分片。
失败或取消后进度卡片会留下「重试」按钮，点击即为确认并续传；确认框 3 分钟无人回答会
留下「未确认下载」卡片而不是无限等待。

模板发布于 `https://github.com/CATMIAOZHI/OperitResources/releases/tag/templates-v1`。
更新资源时发布新 tag，不覆盖旧版本，再更新 `OnDemandResources` 中的地址、
大小和校验值。NCNN 固定 Hugging Face 模型提交，Ubuntu 固定个人终端仓库提交。
CI 不再下载旧 `subpack.zip`；模型依赖压缩包仍为内置 VAD 提供文件，但 NCNN
目录由 APK 的精确资源过滤规则排除。

个人终端子模块新增可选的 Ubuntu 资源准备接口，独立使用终端库时未注册接口
仍使用原内置资源。资源准备发生在 shell 的 30 秒启动计时之前，环境重置与准备
使用同一把锁，避免下载与清理交错。
子模块来源为 `CATMIAOZHI/OperitTerminalCore`，基于 `cd5d53c` 更新至 `52afb8c`。
后续推送时先推送终端的 `codex/on-demand-resources`，再推送引用它的主仓库提交，
否则 CI 无法取到新的子模块版本。

## 终端项目地址

终端设置页的「项目地址」卡片和更新检查原本在子模块里写死 `AAswordman/OperitTerminal`，
主仓库无法覆盖。现在由终端库的 `TerminalProjectInfo` 承接，主应用启动时用
`DistributionConfig.TERMINAL_OWNER` / `TERMINAL_REPOSITORY` 注入；卡片文字、「访问」
链接、更新检查和 tags 接口都跟随发行版，独立使用终端库时仍默认指向上游项目。
嵌入运行时自己的版本号是三位（例如 `1.12.2-ry.3-dev`），更新检查因此直接判定
「已是最新」，不会把用户带到目前还没有 release 的页面；独立应用仍按 `vX.Y` 标签比较。

子模块来源为 `CATMIAOZHI/OperitTerminalCore`，基于 `52afb8c` 更新至 `995b7d5`（分支
`codex/on-demand-resources`）。推送时同样先推送终端分支，再推送引用它的主仓库提交。

## 后续模块化候选

2026-10-13 对本地已有 debug APK 的 ZIP 压缩条目盘点（175.66 MiB，非 release 大小）：

| 候选 | APK 内压缩占用约 | 拆分条件 |
| --- | ---: | --- |
| 桌面扩展 `desktop.apk` | 5.81 MiB | 沿用固定版本、校验和与按需下载；桌面功能入口等待资源就绪 |
| `shizuku.apk` | 1.71 MiB | 安装器资源可按需下载；保留已安装 Shizuku 的检测和授权流程 |
| 视频处理 FFmpeg 的 avcodec / avformat / avfilter | 13.69 MiB | 先梳理 native 加载和调用边界，再评估独立功能包；不能直接删 so |
| OCR 与语音 native 运行库 | OCR 4.07 MiB、sherpa JNI 9.29 MiB、ORT 5.76 MiB | OCR 保留离线能力；语音引擎还服务于内置 VAD，需验证运行库共享可行性 |

先拆纯数据和独立安装包，再考虑 native 功能边界。当前浏览器使用系统 WebView，
没有可直接移除的内置 Chromium 安装包。这些候选尚未实施，不把 debug 盘点等同于
最终 release 节省量。
