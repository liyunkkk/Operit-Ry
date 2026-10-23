# 构建手册

本手册供 Agent 在本地或 CI 上构建 Operit 时参考。完整的开发者环境配置（JDK、SDK、NDK 安装）见 `docs/doc-src/dev-core/BUILDING.md`；此处只记录 Agent 构建时需要知道的分支选择、构建变体、前置步骤和验证命令。

## 分支与构建变体

| 分支 | 用途 | 主要构建变体 | 包名 |
|---|---|---|---|
| `personal/dev` | 功能集成与测试 | `debug` | `com.rainy.operitry.dev` |
| `personal/dev` | 共存测试 | `clone` | `com.rainy.operitry.clone` |
| `personal/main` | 稳定发行 | `release` | `com.rainy.operitry` |
| `personal/main` | Nightly | `nightly` | `com.rainy.operitry` |

构建变体定义在 `app/build.gradle.kts` 的 `buildTypes` 块中。`debug` 变体自动添加 `.dev` 包名后缀和 `-dev` 版本后缀，`clone` 变体添加 `.clone` 后缀。

> **注意**：`debug` 变体的 `.dev` 包名后缀、应用名 `Operit Ry Dev` 和 `PERSONAL_DEV_UPDATE_CHANNEL=true` 仅在 `personal/dev` 分支存在。在 `personal/main` 上构建 `assembleDebug` 使用稳定的 `com.rainy.operitry` 包名，**不具备**与开发版共存的能力，会覆盖已安装的 Operit Ry 稳定版（官方 Operit `com.ai.assistance.operit` 包名不同，可共存）。

## 前置步骤

### 当前工具链

使用 JDK 21、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.20 和 Android SDK
37.1（`platforms;android-37.1`）。根构建通过 `finalizeDsl` 为所有 Android
模块统一编译 SDK，并固定 NDK 27.0.12077973；CMake 仍为 3.31.6。
`minSdk` 与 `targetSdk` 不因编译 SDK 升级而改变。

AGP 9 迁移期间保留外置 Kotlin 插件与旧 DSL：
`android.builtInKotlin=false`、`android.newDsl=false`。这是为了兼容当前
KAPT、ObjectBox 和 terminal 子模块，并非永久方案；升级到 AGP 10 前需要完成迁移。
本地与 CI 均须安装 Build Tools 37.0.0。

### 1. 初始化子模块

```bash
git submodule update --init --recursive terminal
```

`terminal` 是 Android 构建唯一需要初始化的子模块。仓库另有一个 `tools/hotbuild/OperitNightlyRelease` 子模块，仅由 Nightly 发布流程使用，本地构建无需关注。

### 2. 准备本地依赖

完整构建需要三个手动下载的依赖包，放置位置如下：

| 压缩包 | 解压目标 |
|---|---|
| `libs.zip` | `app/libs` |
| `models.zip` | `app/src/main/assets/models` |
| `jniLibs.zip` | `app/src/main/jniLibs` |

导出模板已改为运行时按需下载，不再需要 `subpack.zip`。

这些文件不得提交到 Git。下载地址见 `docs/doc-src/dev-core/BUILDING.md` 和 `README.md`。

CI 通过 `ci/script/download_android_dependencies.sh` 和 `ci/script/prepare_android_dependencies.py` 自动下载和解压。本地构建时需手动放置。

### 3. 配置 local.properties

```properties
sdk.dir=<Android SDK 路径>
GITHUB_CLIENT_ID=
GITHUB_OAUTH_BROKER_BASE_URL=https://<个人控制的 OAuth broker>
```

GitHub OAuth 的 `client_secret` 只能配置在 broker 的服务端 secret store 中，不得进入
`local.properties`、Gradle BuildConfig 或 APK。broker 协议与安全边界见
`docs/agent/github-oauth-broker.md`。

稳定版签名需要额外配置（仅 `personal/main` release 构建）：

```properties
RELEASE_STORE_FILE=<keystore 路径>
RELEASE_STORE_PASSWORD=<密码>
RELEASE_KEY_ALIAS=<别名>
RELEASE_KEY_PASSWORD=<密码>
```

签名材料不得写入代码或日志。

### 4. Windows 长路径与 Ninja

项目原生 Android 模块统一固定使用 SDK Manager 提供的 CMake 3.31.6；该软件包自带支持 Windows 长路径的 Ninja 1.12.1。先安装固定版本：

```powershell
sdkmanager --install "cmake;3.31.6"
```

Windows 本身也必须启用 Win32 长路径。使用 PowerShell 检查：

```powershell
Get-ItemPropertyValue `
  -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' `
  -Name LongPathsEnabled
```

结果应为 `1`。系统设置只为支持长路径的应用放开限制，因此仍需核对实际工具版本：

```powershell
& "$env:ANDROID_HOME\cmake\3.31.6\bin\cmake.exe" --version
& "$env:ANDROID_HOME\cmake\3.31.6\bin\ninja.exe" --version
```

`local.properties` 只需配置 Android SDK。若此前为旧版长路径问题配置过 `cmake.dir`，应移除该行，避免它覆盖模块固定的 CMake 3.31.6：

```properties
sdk.dir=C\:\\AndroidSdk
```

首次构建后不能只检查安装目录，还要确认 AGP 生成的 `build_command_*.bat` 或 `CMakeCache.txt` 实际指向固定工具链：

```powershell
rg -n 'CMAKE_MAKE_PROGRAM|cmake[\\/]3\.31\.6' `
  app/.cxx quickjs/.cxx `
  app/build/intermediates/cxx quickjs/build/intermediates/cxx
```

从仓库原始长路径执行以下验证：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

CMake 3.31 可能针对锁定第三方依赖中的旧 `cmake_minimum_required()` 输出兼容性弃用提示，也可能输出 `CMAKE_OBJECT_PATH_MAX` 警告；只要配置和编译成功，这些警告不应被单独报告为失败。不要为了消除警告批量修改第三方依赖。

如果系统长路径未启用，或 CMake 3.31.6 / Ninja 1.12.1 仍然失败，使用短盘符作为回退：

```powershell
subst R: '<Operit 仓库绝对路径>'
Set-Location R:\
.\gradlew.bat :app:lintDebug
Set-Location '<原工作目录>'
subst R: /d
```

使用前确认盘符未被占用；构建命令必须从短盘符根目录执行。不要把原生工具链路径失败误报为 Kotlin 或 Lint 代码失败，也不能因此跳过验证。

### 5. 构建 WebChat 和 ToolPkg

```bash
npm install
npm --prefix web-chat install
npm run build:webchat
npm run build:examples:github
python3 ./tools/example_packages/sync_example_packages.py --no-hot-reload
```

修改 `web-chat/src` 或 `examples/` 下的脚本包后，需要重新执行对应步骤再构建 APK。

## 构建命令

### 开发版（personal/dev）

```bash
# 完整 APK：先同步内置 ToolPkg，再构建 Debug APK
./gradlew assembleDebugWithToolPkg

# 快速 Android 构建：沿用现有 assets，不同步 ToolPkg
./gradlew :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
# 包名：com.rainy.operitry.dev
# 版本后缀：-dev.<build>（CI 中由 OPERIT_DEV_BUILD_NUMBER 环境变量提供）
```

需要安装到真机或验证最终 APK 内容时使用 `assembleDebugWithToolPkg`。该任务按 CI
顺序执行 `build:examples:github`、`sync_example_packages.py --no-hot-reload` 和
`:app:assembleDebug`；只验证 Android 代码且确认内置包产物已是最新时，可以继续使用
`:app:assembleDebug`。

### 共存版（clone）

```bash
./gradlew :app:assembleClone
# 输出：app/build/outputs/apk/clone/app-clone.apk
# 包名：com.rainy.operitry.clone
```

### 稳定版（personal/main）

```bash
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
# 包名：com.rainy.operitry
# 需要 local.properties 中配置签名
```

发布稳定版的完整流程（触发 CI release 构建、校验产物非 debuggable、打包资产与创建 Release）见 [dev → main 晋升操作手册](./dev-to-main-promotion.md) 第 9 节。注意 `personal/main` 的 push 构建只产出 `:app:assembleDebug`，不能作为发布包。

## 最小验证命令

根据改动范围选择，不要求每次都跑完整构建：

```bash
# Kotlin 编译
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# WebChat 类型检查
npm --prefix web-chat run typecheck

# ToolPkg 构建一致性
npm run build:examples:github
git diff --exit-code -- examples/github.js
```

## CI 构建（Nightly）

Nightly 构建由 `OperitNightlyRelease` 仓库的 `personal-dev-update.yml` workflow 自动执行，每 5 分钟检查 `personal/dev` 是否有新提交。

关键配置：

- **NDK 版本**：`27.0.12077973`
- **CMake 版本**：`3.31.6`
- **JDK**：21
- **ccache**：`CCACHE_COMPILERCHECK=content`、2G 滚动缓存，加速原生编译
- **签名**：使用 Actions Secrets 中配置的 Operit Ry 发布签名，构建前和构建后双重校验证书 SHA-256

Agent 等待 Actions 时使用至少 60 秒轮询间隔：

```bash
gh run watch <run-id> --exit-status --interval 60 >/dev/null 2>&1
# Windows PowerShell: gh run watch <run-id> --exit-status --interval 60 *> $null
```

## 原生依赖

原生第三方依赖已固定到具体 commit SHA，锁定清单见 `cmake/NATIVE_DEPENDENCY_LOCK.md`。CMake 配置时自动下载对应 GitHub archive，不需要手动获取。

升级依赖时：

1. 用 `git ls-remote` 解析新 SHA。
2. 更新对应 `CMakeLists.txt` 中的 SHA。
3. 更新 `cmake/NATIVE_DEPENDENCY_LOCK.md`。
4. 一次只升级一个依赖，构建验证后再升级下一个。

`OPERIT_*_GIT_REF` 命令行参数覆盖已不再生效（`operit_git_source.cmake` 使用 `CACHE ... FORCE`）。

## 常见问题

| 问题 | 解决方法 |
|---|---|
| `NDK not found` | 确认安装了 `ndk;27.0.12077973` |
| `Missing web-chat/dist` | 先执行 `npm run build:webchat` |
| `ERROR: prebuild step failed` | 先在项目根目录执行 `npm install`，确认 `pnpm` 可用 |
| Ninja `Filename longer than 260 characters` | 确认 Windows `LongPathsEnabled=1`，安装 SDK Manager 的 CMake 3.31.6，并核对实际使用 Ninja 1.12.1；`subst` 仅作为回退 |
| CMake `Unable to resolve ref` | 确认 `operit_git_source.cmake` 的 SHA 检测逻辑未被改动；CMake regex 不支持 `{n}` 量词 |
| 构建产物体积异常 | 检查是否意外打包了多个 ABI；当前只支持 `arm64-v8a` |
| ObjectBox 模型文件被修改 | 提交前用 `git status` 检查，恢复无关生成物 |
