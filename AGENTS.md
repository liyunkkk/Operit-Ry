# AGENTS.md

本文件适用于 Operit 仓库。代码、Gradle 配置和 CI 脚本是实现事实来源；开发与贡献流程以 `docs/doc-src/dev-core/CONTRIBUTING.md` 为准。

## 项目结构

- `app/`：Android 主应用、业务逻辑、资源和 JVM 测试
- `terminal/`：公开 Git 子模块，构建前需初始化
- `quickjs/`：native 脚本模块
- `cmake/`：共享 CMake 工具和原生依赖锁定清单（`operit_git_source.cmake`、`NATIVE_DEPENDENCY_LOCK.md`）
- `web-chat/`：React/Vite Web Chat
- `examples/`、`tools/`：ToolPkg、示例和仓库工具
- `docs/`：用户与开发文档；Agent 操作手册位于 `docs/agent/`（构建手册、晋升流程、提交检查清单）
- `ci/`、`.github/`：检查、构建和发布自动化

## 构建与验证

- Android 构建使用仓库自带的 `gradlew` / `gradlew.bat`，JDK 21 和 Android SDK 37.1。构建手册见 `docs/agent/build-guide.md`。
- Windows 上固定使用 `.\gradlew.bat <任务> --no-daemon --console=plain` 执行 Gradle。禁止通过 `| Select-Object -Last`、`Tee-Object` 等 PowerShell 实时管道运行 Gradle；需要保留或截取输出时，先将完整输出重定向到日志文件，命令结束后再读取日志末尾。
- 所有可能长期运行的命令必须设置硬超时。达到超时后立即终止本次命令的整个进程树并输出已保存的日志，禁止无限等待。
- 首次构建先执行 `git submodule update --init --recursive terminal`。
- 完整 Android 构建需要 `models.zip`、`jniLibs.zip` 和 `libs.zip` 内容，下载及校验入口见 `ci/README.md`；导出模板已改为运行时按需下载，不再需要 `subpack.zip`。这些本地依赖不得提交。
- 隔离 worktree 不会继承被忽略的本机构建输入（如 `local.properties`、`app/libs` 本地依赖）或子模块状态。构建前先补齐并初始化，且不得提交；若仅 worktree 报缺依赖，先检查这些输入，不要改源码。
- 原生第三方依赖（ncnn、sherpa-ncnn、WAMR、QuickJS）已固定到具体 commit SHA，锁定清单见 `cmake/NATIVE_DEPENDENCY_LOCK.md`；升级时更新该清单和对应 `CMakeLists.txt`，不要通过 `OPERIT_*_GIT_REF` 命令行参数覆盖（已不再生效）。
- 根据改动范围运行最小充分验证：默认优先编译，只运行与本次修改直接相关的测试；没有直接相关测试时只做编译检查，小改动无明显回归风险时不额外补测试，已通过的测试不重复运行。不得仅为提高信心主动运行完整 `testDebugUnitTest`；全量测试和耗时的集成/UI 测试仅在用户明确要求、合并或 PR 最终验证、CI，或核心基础设施改动确有必要时运行。测试超时应覆盖 Gradle 冷启动和增量编译：聚焦 JVM 单测默认设置 10 分钟硬超时，其他任务按预计耗时设置明确上限；若连续 5 分钟没有新日志、CPU/进程活动或输出文件变化，应终止整个进程树并分析原因，不得仅因运行超过 60 秒判定失败。
- 原生 Android 模块统一固定使用 SDK Manager 的 CMake 3.31.6（自带 Ninja 1.12.1）。Windows 必须启用 Win32 长路径，并移除指向旧版自定义 CMake 3.22.1 的 `local.properties` `cmake.dir`；构建后从 `build_command_*.bat` 或 `CMakeCache.txt` 核对 Gradle 实际使用 `cmake/3.31.6`。若支持长路径的工具链仍失败，再用 `subst` 将仓库根目录映射为短盘符并重试。第三方依赖产生的 CMake 兼容性弃用提示或 `CMAKE_OBJECT_PATH_MAX` 警告本身不等于构建失败。完整步骤见 `docs/agent/build-guide.md`。
- 完整 APK 构建、release 构建和原生依赖升级后的验证优先使用 GitHub Actions（Nightly workflow 或手动触发），不要在本地执行完整 `assembleRelease`。本地构建仅用于快速迭代测试（`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`assembleDebug` 等），验证通过且用户授权推送后，由 CI 做完整构建。
- 原生依赖 SHA 升级必须先推送 `personal/dev`，由 Nightly 构建验证 ccache 命中率和编译成功后，再考虑合并到 `personal/main`。
- Web Chat、ToolPkg 和仓库检查命令以 `docs/doc-src/dev-core/CONTRIBUTING.md` 与 `ci/README.md` 为准。
- 等待 GitHub Actions workflow 时使用至少 60 秒的轮询间隔并默认静默输出（PowerShell 示例：`gh run watch <run-id> --exit-status --interval 60 *> $null`）；结束后用一次 `gh run view` 查询最终结果，仅在诊断失败时读取详细 job 日志。
- 构建可能触碰 ObjectBox 模型或占位文件。提交前检查 `git status` 和 diff，不提交无内容的行尾变化或无关生成物。
- 本地提交不要求运行完整 Lint。推送前对最终版本运行一次完整 `:app:lintDebug`；已通过后不重复运行，只有可能影响检查结果的修改才需要重新验证。开发阶段先做必要编译和相关测试，集中处理静态检查问题，小调整优先做定向验证。编译、单测通过不代表 Lint 通过；分别说明本次实际验证范围。
- 组合函数内优先用 `stringResource` 等 Compose 资源 API，事件回调引用已取得的值；不为规避新增错误更新 baseline 或禁用检查。
- 无法运行验证时，说明缺失的 SDK、依赖或环境条件，并分别报告编译、测试、Lint 和真机验证的实际状态，不得把“未运行”描述为“通过”。

## 分支与发布

- `origin` 指向个人 fork，默认分支为 `personal/main`；`upstream` 指向官方仓库，默认分支为 `main`。
- 除非用户明确要求同步上游、分析上游或向上游贡献，否则所有规划、开发、审查和验证只以个人版当前目标分支为基线，不主动拉取、对比、合并或兼容 `upstream/main`。
- `personal/main` 更新后由 `.github/workflows/sync-main-mirror.yml` 单向快进到 `main`，供仅支持 `main` 的安全工具读取。镜像使用仅授权本仓库 **Contents: read and write** 与 **Workflows: read and write** 的 `MAIN_MIRROR_TOKEN`，因为内置 `GITHUB_TOKEN` 不能推进包含 workflow 变更的提交。`main` 是只读兼容镜像，不得直接提交、合并 PR、强推或用作上游贡献分支；同步失败时先排查分叉，不得覆盖历史。
- 上游贡献以最新 `upstream/dev` 为基线，PR 显式目标官方 `dev`；官方 `main` 用于稳定发布和维护者执行的发布同步。新建贡献分支按上游当前 `docs/doc-src/dev-core/CONTRIBUTING.md` 使用 `feat/`、`fix/`、`docs/`、`ci/`、`refactor/` 或 `test/` 前缀加简短描述，不使用 `contrib/`、代理、模型或个人身份前缀；已有分支维持原名。不得混入 Operit Ry 的品牌、服务路由或发布配置。
- `personal/main` 是 Operit Ry 稳定发行分支，受规则保护；改动必须通过 PR 和必需检查，稳定 APK 与 `v*` Release tag 只从该分支发布。
- `personal/dev` 是所有新功能的集成与测试分支。新功能必须先进入该分支，构建并实际测试可共存的 debug APK；测试通过后，经用户同意再以只包含通用功能提交的 PR 晋升到 `personal/main`。晋升操作手册见 `docs/agent/dev-to-main-promotion.md`。不得绕过开发版验证直接向稳定分支加入新功能，也不得把开发版专属配置带入晋升 PR。
- 上游更新先整合进 `personal/dev` 构建并测试，确认不破坏现有功能后再合并到 `personal/main`；不要用上游分支重置或覆盖任一个人分支。
- 上游正式版本按 GitHub Release 的实际 APK 构建提交分批审查，流程与持久台账格式见 `docs/agent/upstream-release-review.md`；不得把 Release 之后尚未正式发布的 `upstream/main` 提交混入当前批次。
- `personal/dev` 的 `debug` 变体使用包名 `com.rainy.operitry.dev`、应用名 `Operit Ry Dev` 和 `-dev` 版本后缀，可与官方 Operit 及稳定版同时安装；`app/src/debug/res/` 维护 DEV 角标图标和指向开发包名的快捷方式，修改应用身份时必须同步核对这些资源。
- `OperitNightlyRelease` 仓库定时拉取 `personal/dev`，使用递增的 `-dev.<build>` 版本和自身 `GITHUB_TOKEN` 发布 `personal-dev` 差分更新；发布链中的 APK 必须保持 `com.rainy.operitry.dev` 包名及与现有开发版相同的固定签名，首次或补丁链不匹配时保留完整 debug APK 回退。签名 Secret 只配置在 Actions 中，不得写入代码或日志。Nightly 构建使用 ccache（`CCACHE_COMPILERCHECK=content`、2G 滚动缓存）加速原生编译，修改 `cmake/operit_git_source.cmake` 或原生依赖 SHA 时需注意对缓存命中率的影响。

## 修改原则

- 修改前阅读相关实现、调用方和现有测试；优先小而完整的修复。
- 修复 PR review 后，必须由未参与实现的独立 Agent 对最终差异做只读审计；若审计发现可操作问题，继续修复并重新独立审计，直到结论为 CLEAN。完成必要验证且独立审计 CLEAN 后，自动在 GitHub 将本轮已实际修复的对应 review 线程标记为 resolved；不得解决新增、未修复、存在歧义或审计未通过的线程。此规则不自动授权提交、推送或合并。
- 持久化数据、公开接口、配置格式或已发布行为发生变化时，评估兼容性和迁移影响。未发布的内部方案无需保留无用途的旧路径。
- 不以静默降级掩盖确定错误；是否需要兼容或恢复路径应由真实用户数据、外部调用方和产品契约决定。
- 新增用户可见文字使用资源字符串并提供简体中文；`values/`（默认简体中文）和 `values-en/`（英语）为唯一需要维护的文案来源，不新增或同步其他语言。例外：同步上游官方 i18n PR 中已发布的非默认语言翻译时，允许带入并在 PR 说明中标注上游 PR 编号。`MissingTranslation` 检查已在 `personal/main` 的 `app/build.gradle.kts` 中禁用，其他语言的现有翻译由社区自行维护，不作为本仓库门槛。文档只在行为、接口、构建或使用方式变化时更新。
- 注释解释非显而易见的约束和原因，不记录调试流水账。
- 不修改第三方子模块来绕过主仓库问题；确需更新子模块时单独说明来源和版本。

## 安全与 Git

- 凭据与未提交改动保护遵守工作区 `AGENTS.md`；此外，不提交 `local.properties`、本机私有路径或个人信息，不使用 `git reset --hard`、`git checkout --` 清理工作区。
- 提交前检查状态、完整差异和近期历史，只暂存本次任务文件。提交前检查清单见 `docs/agent/commit-checklist.md`。
- 提交、推送、关闭 PR、发布的授权要求遵守工作区 `AGENTS.md`；构建与验证指令本身不授予这些权限。
