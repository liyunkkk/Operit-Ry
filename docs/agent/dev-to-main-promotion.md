# dev → main 晋升操作手册

本手册供 Agent 在用户确认功能测试通过后，将 `personal/dev` 上的通用功能改动晋升到 `personal/main` 稳定分支。

## 前提

1. 功能已在 `personal/dev` 上构建并通过实际测试（可共存 debug APK）。
2. 用户已明确同意晋升。
3. `personal/dev` 工作区干净且与远端同步。

## 版本号规则

稳定版本号形如 `<基线版本>-ry.<序号>`，例如 `1.12.2-ry.6`。两段各自独立演进：

| 字段 | 何时变化 | 例子 |
|---|---|---|
| `versionName` 的 `ry.<序号>` | 每次稳定版发布加一 | `1.12.2-ry.6` → `1.12.2-ry.7` |
| `versionName` 的基线版本 | 跟随上游正式版本 | `1.12.1` → `1.12.2` |
| `versionCode` | 只随基线版本更新加一，`ry` 发布不动 | 基线变到 `1.12.3` 时才从 `100205` 变为 `100206` |

约束：

- `personal/dev` 与 `personal/main` 的 `versionCode` 必须一致；它按基线版本推进，dev 不得落后或领先稳定分支。
- 晋升分支只更新 `versionName` 的 `ry` 序号，不调整 `versionCode`；混入 `versionCode` 改动视为晋升失败。
- dev 的 `-dev.<build>` 后缀由 debug 变体的 `versionNameSuffix` 自动追加，不要在源码里手写。

## 晋升检查点

每次晋升完成后，将最新 `personal/main` 回合并到 `personal/dev`，并在专用检查点提交上创建不可复用的 annotated tag：

```bash
promotion-checkpoint/main-<YYYYMMDD>-<main-short-sha>
```

该标签同时表示：

- 标签所在提交已经包含当时的 `personal/main`；
- 标签之前的 dev 通用改动已经完成晋升审查；
- 下一次晋升默认只需要审查该标签之后的 dev 改动。

开始下一轮晋升时先定位最近检查点：

```bash
git fetch origin personal/main personal/dev --tags
CHECKPOINT=$(git describe \
  --tags \
  --match 'promotion-checkpoint/*' \
  --abbrev=0 \
  origin/personal/dev)

git log --oneline "$CHECKPOINT"..origin/personal/dev
git log --no-merges --oneline \
  "$CHECKPOINT"..origin/personal/dev \
  --not origin/personal/main
git diff --stat "$CHECKPOINT"..origin/personal/dev
git diff "$CHECKPOINT"..origin/personal/dev
```

第一条日志和 diff 用于查看检查点后的完整变化；第二条日志排除以同一 commit 对象从 `personal/main` 同步到 dev 的提交，用作待晋升候选列表。squash 或 rebase 后 SHA 和 patch 边界可能变化，仍须结合检查点、PR 记录和逐提交 diff 人工确认；若检查点后同步过 main，不得只根据原始 diff 判断晋升范围。

检查点只缩小审查范围，不替代以下检查：

- 确认标签可从当前 `personal/dev` 到达；
- 确认标签说明中记录的 main SHA 与上一轮实际晋升结果一致；
- 继续排除检查点之后新增或修改的开发版专属文件；
- 如果 `personal/main` 在检查点之后又有其他改动，先将最新 main 回合并到 dev；只有 dev 没有待晋升通用改动时才立即建立新检查点，否则保留旧检查点并使用上面的过滤日志。

检查点标签不得移动、覆盖或复用；每轮晋升创建一个新标签，以保留可审计历史。

## 变更通道

### Dev-first：功能与高风险改动

新功能、用户可见行为、持久化、共享业务代码、依赖、原生模块及需要真机验证的改动，必须先进入 `personal/dev`，完成构建和实际测试后再按本手册晋升。改动行数少不等于风险低。

### personal/main-first：小型维护

文档修正、低风险 CI 或仓库元数据、稳定版专属配置，以及范围明确且已有针对性验证的紧急修复，可以直接从 `personal/main` 创建独立分支并通过 PR 合入稳定分支：

```bash
git fetch origin personal/main
git switch -c fix/<short-name> origin/personal/main
# 修改并执行与范围匹配的最小充分验证
git push origin fix/<short-name>
gh pr create --base personal/main --head fix/<short-name>
```

这里的“main-first”只指 PR 目标为 `personal/main`；`main` 仍是自动同步的只读兼容镜像，不得直接提交或接收 PR。`personal/main` PR 合并后，将它回合并到 `personal/dev`：

```bash
git fetch origin personal/main personal/dev --tags
git switch personal/dev || exit 1
test "$(git branch --show-current)" = "personal/dev" || exit 1
git pull --ff-only origin personal/dev
git merge --no-ff origin/personal/main || {
  echo "Resolve and run git merge --continue before validation; or run git merge --abort." >&2
  exit 1
}
# 保留并验证 dev 专属身份、Nightly 和热更新配置，执行范围匹配的检查。
git push origin personal/dev
```

若过滤后的待晋升候选为空，可立即按第 10 节建立新检查点；若仍有待晋升功能，只同步并验证 main，不要仅为这次小型维护执行 suspend/replay 或移动检查点。保留原检查点，下一次晋升时排除已经属于 `personal/main` 的提交，待晋升完成后再建立新检查点。

## 步骤

### 1. 确认状态

```bash
git checkout personal/dev
git pull origin personal/dev
git log --oneline -20
```

逐条审视最近的提交，标记哪些是通用功能、哪些是开发版专属改动。

### 2. 更新 personal/main

```bash
git checkout personal/main
git pull origin personal/main
```

### 3. 创建晋升分支

一个发布轮次对应一个晋升分支，用本轮稳定版本号命名（如 `promote/ry.8`）：

```bash
git checkout -b promote/<release>   # 从 personal/main 创建；<release> 是本轮稳定版本号，如 ry.8
```

### 4. 挑选通用功能提交

从 `personal/dev` 中 cherry-pick 只包含通用功能的提交：

```bash
git cherry-pick <commit-sha>
# 或多个连续提交
git cherry-pick <oldest-sha>^..<newest-sha>
```

如果某次提交混入了开发版专属改动，需要手动修正后再继续。

### 5. 排除开发版专属文件

以下文件和改动**不得**出现在晋升 PR 中：

| 类型 | 文件 |
|---|---|
| 开发包名 | `app/build.gradle.kts` 中的 `applicationIdSuffix = ".dev"`、`versionNameSuffix`、`resValue("string", "app_name", "Operit Ry Dev")`、`buildConfigField("boolean", "PERSONAL_DEV_UPDATE_CHANNEL", "true")` |
| DEV 图标 | `app/src/debug/res/` 下的 `ic_launcher_dev_badge.xml`、`ic_launcher_simple_foreground_dev.xml` 等 |
| 开发版热更新 | `app/src/main/java/.../data/updates/UpdateManager.kt` 和 `PatchUpdateInstaller.kt` 中的 `personal-dev` channel 逻辑 |
| 差分更新工具 | `tools/hotbuild/build_patch.py`、`tools/hotbuild/publish_dev_update.py` |
| Nightly 子模块 | `tools/hotbuild/OperitNightlyRelease` 子模块指针 |
| ccache/CI 专属 | Nightly workflow 中的 ccache 配置 |

如果 cherry-pick 时这些文件被带入，使用不提交的 cherry-pick + 精确恢复：

```bash
git cherry-pick -n <commit-sha>
git restore --source=HEAD --staged --worktree -- <dev-only-file>
git commit
```

若 cherry-pick 已经提交完成，可从父提交恢复并 amend：

```bash
git restore --source=HEAD^ --staged --worktree -- <dev-only-file>
git commit --amend --no-edit
```

### 6. 验证

```bash
# === 拒绝所有 personal/dev 身份配置（无论是否在 debug 块中）===

# 以下任何匹配均应视为晋升失败，对应提交需重新 cherry-pick 并排除：
#   applicationIdSuffix = ".dev"
#   versionNameSuffix = "-dev"（或含 -dev 的任何变体）
#   resValue("string", "app_name", "Operit Ry Dev")
#   buildConfigField("boolean", "PERSONAL_DEV_UPDATE_CHANNEL", "true")
#   app/src/debug/res/ 下 *_dev_* 图标文件

grep -En 'applicationIdSuffix\s*=\s*"\.dev"|versionNameSuffix\s*=\s*".*-dev|app_name.*Operit Ry Dev|PERSONAL_DEV_UPDATE_CHANNEL.*true' \
  app/build.gradle.kts && echo "FAIL: dev 身份配置残留" && exit 1 || true

test -f app/src/debug/res/drawable/ic_launcher_dev_badge.xml \
  && echo "FAIL: DEV 图标残留" && exit 1 || true

# 注意：clone 配置（applicationIdSuffix = ".clone"、Operit Ry Clone）可以在
# 稳定分支中保留；上述检查只针对 dev 专用身份。

# 编译检查
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# release APK 的构建与发布见第 9 节；PR Check 只验证编译、测试与 lint
```

### 7. 推送并创建 PR

晋升说明按功能域分组，交代本轮带入了什么，并列出检查点 tag、本轮基线、提交数和排除掉的开发版专属内容：

```bash
git push origin promote/<release>
gh pr create --base personal/main --head promote/<release> \
  --title "<type>: promote <本轮的几组改动> for <release>" \
  --body-file <按功能域分组写好的晋升说明>
```

### 8. 等待 CI 并合并

`personal/main` 的 Ruleset 只把 `Fast checks` 列为必需检查。`Full Android checks` 要在 GitHub 托管 runner 上跑完整测试与 lint，经常因为 `The runner has received a shutdown signal`、Gradle daemon 消失，或与本次改动无关的既有 flaky 用例（如 `TokenTrackingAIServiceTest`、`CleanupReliabilityTest`）失败，不作为晋升门槛。

晋升的判定标准是两条同时满足：

1. `gh pr checks <pr>` 里除 `Full Android checks` 以外的检查全部通过；
2. 晋升分支上本地 `:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 通过。

满足后直接开启自动合并，让它随必需检查一起完成：

```bash
gh pr merge <pr> --repo CATMIAOZHI/Operit --auto --merge
```

`Full Android checks` 可以继续在后台跑完。只有它失败且失败原因指向本轮改动时，才需要修复后重跑。

合并后 `sync-main-mirror.yml` 会将同一 commit 自动快进到只读 `main` 镜像；也可将 `personal/main` 合并回 `personal/dev` 保持开发线同步。

### 9. 发布稳定版

晋升 PR 合并后发布稳定版。发布包只能来自 `workflow_dispatch` 手动触发的 `:app:assembleRelease`；本机不执行完整 `assembleRelease`：

- push 到 `personal/main` 触发的 Android Build 走 `--lane full`，只构建 `:app:assembleDebug`（另跑 `:app:testDebugUnitTest` 与 `:app:lintDebug`），产物 `app-debug.apk` 带 `application-debuggable`，不得作为发布包；
- `workflow_dispatch` 按输入的 `gradle_task` 构建，选 `:app:assembleRelease` 才产出 `app-release.apk`。

```bash
gh workflow run android-build.yml --repo CATMIAOZHI/Operit \
  --ref personal/main \
  -f gradle_task=:app:assembleRelease
# 可选：-f run_unit_tests=true -f run_android_lint=true（默认均为 false）

# 刚触发时 run 可能还没出现在列表里；取到空值就稍等重试，不要用旧的 run
RUN_ID=$(gh run list --repo CATMIAOZHI/Operit --workflow android-build.yml \
  --branch personal/main --event workflow_dispatch --limit 1 \
  --json databaseId -q '.[0].databaseId')
test -n "$RUN_ID" || { echo "run 尚不可见，稍后重试" >&2; exit 1; }
gh run watch "$RUN_ID" --repo CATMIAOZHI/Operit --exit-status --interval 60 >/dev/null 2>&1
```

下载产物。`-n` 只指定一个 artifact 时，内容直接解压到 `-D` 目录，不再多一层 artifact 同名目录；同一次 run 还有 `operit-android-reports-<n>`，只取 `operit-android-<n>`：

```bash
RUN_NUMBER=$(gh run view "$RUN_ID" --repo CATMIAOZHI/Operit --json number -q .number)
WORK=<临时目录>
gh run download "$RUN_ID" --repo CATMIAOZHI/Operit \
  -n "operit-android-$RUN_NUMBER" -D "$WORK"

# artifact 内部路径固定为 apk/release/app-release.apk；后续校验与打包都用这个 $APK
APK="$WORK/apk/release/app-release.apk"
```

校验该产物确为 release 且签名正确。`aapt`、`apksigner` 在 SDK 的 `build-tools/35.0.0/`（本机 `C:\AndroidSdk\build-tools\35.0.0`，CI 为 `$ANDROID_HOME/build-tools/35.0.0`）；不在 PATH 时用完整路径：

```bash
# 必须出现：package: name='com.rainy.operitry' versionCode='<本轮>' versionName='<本轮>'
#           application-label:'Operit Ry'、native-code: 'arm64-v8a'
# 不得出现：application-debuggable
aapt dump badging "$APK" | grep -E "^package:|application-label:|native-code:|application-debuggable"

# 证书 SHA-256 须为发布签名；CI 已按 OPERIT_RELEASE_CERT_SHA256 校验，这里是二次确认：
# 40:F8:7A:4D:66:EB:70:D0:E2:D1:37:9C:6A:97:DD:DC:0C:ED:D3:BA:87:2E:02:74:50:CA:77:AF:42:EC:5E:74
apksigner verify --print-certs "$APK"
```

整理发布资产。`SHA256SUMS.txt` 为单行、LF 结尾，格式 `<小写 sha256><两个空格>Operit-Ry-<version>-arm64-v8a.apk`：

```bash
cp "$APK" Operit-Ry-<version>-arm64-v8a.apk
sha256sum Operit-Ry-<version>-arm64-v8a.apk > SHA256SUMS.txt

# Windows PowerShell（必须显式写 LF，CRLF 会让 sha256sum -c 失败；必须用 -Path/-Value 命名参数，
# 否则 -NoNewline 加位置参数会把值当成路径）：
# $name = 'Operit-Ry-<version>-arm64-v8a.apk'
# $hash = (Get-FileHash ".\$name" -Algorithm SHA256).Hash.ToLower()
# Set-Content -Path SHA256SUMS.txt -Value ("{0}  {1}`n" -f $hash, $name) -NoNewline -Encoding ascii
```

创建 Release，`--target` 指向本轮 `personal/main` 合并后的 commit：

```bash
gh release create v<version> \
  --repo CATMIAOZHI/Operit \
  --target <本轮 main 合并 commit> \
  --title "Operit Ry <version>" \
  --notes-file <release-notes.md> \
  Operit-Ry-<version>-arm64-v8a.apk SHA256SUMS.txt
```

资产传错（例如误传 debug 包）时覆盖并复核：

```bash
gh release upload v<version> Operit-Ry-<version>-arm64-v8a.apk SHA256SUMS.txt \
  --repo CATMIAOZHI/Operit --clobber

# assets[].digest 的 sha256 须与本地一致；targetCommitish 须为本轮 main 合并 commit
gh release view v<version> --repo CATMIAOZHI/Operit --json assets,targetCommitish
```

release notes 首行为 `# Operit Ry <version>`，正文按功能域分组（模型与提供商 / 记忆与历史 / 对话与上下文 / 搜索与工具 / 安全边界 / 基础修复 等），末尾固定一行：

```text
稳定版包名为 `com.rainy.operitry`，可与开发版并存。支持从此前 Operit Ry 稳定版覆盖安装；请勿卸载旧版后再安装，以免丢失应用数据。
```

创建 Release 属于对外动作，必须先取得用户明确同意。

### 10. 回合并 main 并建立新检查点

晋升 PR 合并后，将稳定分支回合并到开发分支。由于晋升 PR 可能经过 squash，出现等价代码冲突时，通用代码以通过审查的 `personal/main` 为准，同时保留 dev 专属身份、Nightly 和热更新配置。

本节用于功能晋升完成后的检查点，或 main-first 维护后不存在待晋升通用改动的情况。main-first 维护后若仍有待晋升功能，只回合并 main 并保留旧检查点，不执行下面的 suspend/replay。

如果 dev 仍有未包含在本轮晋升 PR 中的通用改动，合并前必须按从旧到新的顺序列出对应提交。每个提交必须是单父提交，只能属于一个待晋升功能，且不得混入 dev 专属配置；merge commit 或混合提交必须先通过 revert 和分块重提拆成单一职责的单父替代提交，不能继续建立检查点。

检查点提交本身只允许包含“最新 main 通用代码 + dev 专属设施”。因此先按逆序暂时 revert 待晋升提交，再回合并 main；标签创建后，按原顺序逐提交 cherry-pick。这样即使通用改动和 dev 专属配置位于同一文件，也只移动待晋升提交实际修改的 hunk，并保留独立功能的提交边界。

```bash
git fetch origin personal/main personal/dev --tags
git checkout personal/dev || exit 1
test "$(git branch --show-current)" = "personal/dev" || {
  echo "Checkpoint preparation must run on personal/dev" >&2
  exit 1
}
git pull --ff-only origin personal/dev
MAIN_SHA=$(git rev-parse origin/personal/main)

# 仅在仍有待晋升通用改动时执行本段。
# 提交按从旧到新排列，并逐个检查不含 dev 专属配置或其他功能。
PENDING_COMMITS=(<oldest-commit> ... <newest-commit>)
for commit in "${PENDING_COMMITS[@]}"; do
  read -r -a COMMIT_AND_PARENTS <<<"$(git rev-list --parents -n 1 "$commit")"
  if ((${#COMMIT_AND_PARENTS[@]} != 2)); then
    echo "Pending commit must have exactly one parent: $commit" >&2
    exit 1
  fi
  git show --stat --patch "$commit"
done

# 逆序移除待晋升功能，使检查点树不包含它们。
REVERT_COMMITS=()
for ((i=${#PENDING_COMMITS[@]}-1; i>=0; i--)); do
  REVERT_COMMITS+=("${PENDING_COMMITS[$i]}")
done
git revert --no-edit "${REVERT_COMMITS[@]}" || {
  echo "Resolve and repeat git revert --continue until the entire sequence finishes, then reload the variables documented below; or run git revert --abort." >&2
  exit 1
}
test ! -d "$(git rev-parse --git-path sequencer)" || {
  echo "The revert sequence is still in progress" >&2
  exit 1
}
test -z "$(git diff --name-only --diff-filter=U)" || {
  echo "The revert sequence still has unresolved files" >&2
  exit 1
}

# main 已经集成时创建显式空提交；否则必须停在 merge 提交前。
CHECKPOINT_PARENT=$(git rev-parse HEAD)
if git merge-base --is-ancestor "$MAIN_SHA" HEAD; then
  git commit --allow-empty \
    -m "chore(dev): record personal/main checkpoint" || exit 1
else
  git merge --no-ff --no-commit origin/personal/main || {
    echo "Resolve and run git merge --continue, then run the self-contained recovery block below; or abort and restore pending features as documented below." >&2
    exit 1
  }

  # 解决冲突：通用代码采用 main，dev 专属设施继续保留。
  git diff --name-only --diff-filter=U  # 必须无输出
  git commit -m "chore(dev): sync personal/main checkpoint" || exit 1
fi

# 空提交、clean merge 提交或 merge --continue 后都必须从这里继续验证。
test "$(git rev-parse HEAD^1)" = "$CHECKPOINT_PARENT" &&
  git merge-base --is-ancestor "$MAIN_SHA" HEAD || {
  echo "HEAD is not a checkpoint commit for $MAIN_SHA" >&2
  exit 1
}
```

若 revert 冲突导致 shell 退出，重复解决冲突并执行 `git revert --continue`，直到 sequencer 完成，然后在继续 checkpoint 创建前重新声明原列表并重新读取 main：

```bash
PENDING_COMMITS=(<same-oldest-commit> ... <same-newest-commit>)
MAIN_SHA=$(git rev-parse origin/personal/main)
```

若 merge 冲突导致 shell 退出，解决后使用下面的自包含恢复块；它不依赖退出前的变量。存在待晋升提交时重新声明同一列表，然后从创建标签的代码块继续：

```bash
git merge --continue
MAIN_SHA=$(git rev-parse origin/personal/main)
test "$(git rev-parse HEAD^2)" = "$MAIN_SHA" &&
  git merge-base --is-ancestor "$MAIN_SHA" HEAD || {
  echo "Recovered merge does not contain the expected personal/main tip" >&2
  exit 1
}
PENDING_COMMITS=(<same-oldest-commit> ... <same-newest-commit>)
```

若执行 `git merge --abort`，已经创建的 revert commits 不会被撤销。此时不得推送 dev；必须重新执行一次 `git cherry-pick "${PENDING_COMMITS[@]}"`，处理完整序列并验证待晋升功能已经恢复。下次重试使用这次重放生成的新 commit SHA。

在检查点提交上创建 annotated tag；若暂时移除了待晋升提交，随后按原顺序逐个重放：

```bash
MAIN_SHORT=$(git rev-parse --short=8 origin/personal/main)
CHECKPOINT="promotion-checkpoint/main-$(date +%Y%m%d)-$MAIN_SHORT"
CHECKPOINT_SHA=$(git rev-parse HEAD)

git tag -a "$CHECKPOINT" \
  -m "Promotion checkpoint: personal/main $MAIN_SHA" || {
  echo "Checkpoint tag already exists or could not be created; inspect it and do not continue." >&2
  exit 1
}
test "$(git rev-parse "$CHECKPOINT^{}")" = "$CHECKPOINT_SHA" || {
  echo "Checkpoint tag does not point to this checkpoint commit" >&2
  exit 1
}

git cherry-pick "${PENDING_COMMITS[@]}" || {
  echo "Resolve and run git cherry-pick --continue until the sequence finishes; after --abort do not validate or push." >&2
  exit 1
}
```

若 cherry-pick 发生冲突，必须保留 main 已审查修复和 dev 专属配置，只重放该提交所属功能的 hunk，验证后执行 `git cherry-pick --continue`。若执行 `git cherry-pick --abort`，dev 会停在缺少待晋升功能的检查点提交；此时不得进入验证或推送，必须重新执行完整 cherry-pick 序列或放弃本轮检查点并先恢复功能。若没有待晋升通用改动，应省略 `PENDING_COMMITS`、revert 和 cherry-pick 段。确认 dev 专属配置仍然存在后，验证最终 dev 工作树：

```bash
test "$(git branch --show-current)" = "personal/dev" &&
  git merge-base --is-ancestor origin/personal/main HEAD || {
  echo "personal/dev does not contain the fetched personal/main tip" >&2
  exit 1
}
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

最后原子推送 dev 与检查点标签，避免只发布其中一个：

```bash
git fetch origin personal/main || exit 1
test "$(git rev-parse origin/personal/main)" = "$MAIN_SHA" || {
  echo "personal/main advanced; rebuild and retest the checkpoint from the new tip" >&2
  exit 1
}
git push --atomic origin personal/dev "$CHECKPOINT"
```

确认远端标签指向本次检查点提交，并且标签说明记录了准确的 main SHA；`git diff "$CHECKPOINT"..origin/personal/dev` 应只显示标签之后尚未晋升的改动。原子推送失败时不得把该轮视为已建立检查点，应排查分支并发更新或远端规则后整体重试。

## 注意事项

- **不要直接 merge `personal/dev` 到 `personal/main`**：dev 分支包含大量开发版专属提交，直接 merge 会全部带入。
- **不要省略测试**：晋升以「CI 必需检查（`Fast checks`）通过 + 本地 `:app:compileDebugKotlin` 与 `:app:testDebugUnitTest` 通过」为准，`Full Android checks` 的基础设施类失败不阻塞晋升，判定与自动合并见第 8 节。
- **上游更新方向相反**：上游更新走 `upstream/main → personal/dev`（先测试）→ `personal/main`，晋升走 `personal/dev → personal/main`，两条路径都经过 dev 验证，不要混用。
- **一个发布轮次一个晋升 PR**：检查点之后的所有通用改动一次性晋升，`personal/main` 每轮只推进一个稳定版本号。说明按功能域分组，每个功能仍保留自己的提交边界，需要回滚时只 revert 该功能的提交，不必放弃整轮。
- **发布资产必须来自 `:app:assembleRelease`**：`personal/main` 的 push 构建只构建 `:app:assembleDebug`（另跑单测与 lint），其 `app-debug.apk` 带 `application-debuggable`，不能当作发布包；发布步骤见第 9 节。
