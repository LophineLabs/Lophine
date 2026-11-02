# Feature Patch Continue 工作流（以 Lophine 为模板）

本文用 Lophine 工作区说明 ComfreyWeight 的 feature-patch continue。路径、任务名和命令都以当前 Lophine 仓库为准。其它 `paperweight-core` / `paperweight-patcher` 工程按同样规则替换层名即可。

Feature patch 用 `git am` 顺序应用。某一张冲突时，apply 会回滚该 patch、留下可恢复状态，再由 `continue` 组任务接手。File patch（`sources/`、`files/`、`resources/`）不走这套流程。

命令均在 **Lophine 主仓库根目录** 执行。示例用 PowerShell；Linux/macOS 把 `.\gradlew.bat` 换成 `./gradlew`。

## Lophine 里哪些层有 continue

Lophine 是 Folia 之上的 active fork。可写 feature-patch 层如下。

| 层 | 编辑的生成仓库 | Patch 目录 | Apply | Continue | Drop 当前失败项 |
| --- | --- | --- | --- | --- | --- |
| Minecraft Java | `lophine-server/src/minecraft/java/` | `lophine-server/minecraft-patches/features/` | `:lophine-server:applyMinecraftFeaturePatches` | `:lophine-server:continueMinecraftFeaturePatches` | `:lophine-server:continueDropCurrentMinecraftFeaturePatches` |
| Paper 服务端 | `paper-server/` | `lophine-server/paper-patches/features/` | `:lophine-server:applyPaperServerFeaturePatches` | `:lophine-server:continuePaperServerFeaturePatches` | `:lophine-server:continueDropCurrentPaperServerFeaturePatches` |
| Folia 服务端 | `folia-server/` | `lophine-server/folia-patches/features/` | `:lophine-server:applyFoliaServerFeaturePatches` | `:lophine-server:continueFoliaServerFeaturePatches` | `:lophine-server:continueDropCurrentFoliaServerFeaturePatches` |
| Paper API | `paper-api/` | `lophine-api/paper-patches/features/` | `:applyPaperApiFeaturePatches` | `:continuePaperApiFeaturePatches` | `:continueDropCurrentPaperApiFeaturePatches` |
| Folia API | `folia-api/` | `lophine-api/folia-patches/features/` | `:applyFoliaApiFeaturePatches` | `:continueFoliaApiFeaturePatches` | `:continueDropCurrentFoliaApiFeaturePatches` |

服务端三层（Minecraft / Paper server / Folia server）注册在 `:lophine-server`（`paperweight-core`，`activeFork = lophine`）。API 两层注册在主仓库根项目（`paperweight-patcher`）。

当前工作区里 Folia 服务端 feature 目录可以为空；任务仍在，只是没有可应用的 `.patch`。Checkstyle 等 directory patch set 若带 `features/`，根项目也会有对应 `continuePaperCheckstyleFeaturePatches` 一类任务。模块 `build.gradle.kts.patch` 是单文件 patch，没有 continue。

只读上游（Folia checkout、Paper 工作副本）不注册 continue。`lophine-server/src/main/`、`lophine-api/src/main/` 是主仓库直接维护的文件，也不走这套流程。

## 一次失败在工作区里长什么样

以 Minecraft 层为例。`applyAllPatches` 或 `:lophine-server:applyMinecraftFeaturePatches` 在 `git am --3way` 失败后会：

1. 退出 `am`（必要时清掉 `.git/rebase-apply` / `.git/am`）。
2. `git reset --hard HEAD` 并 `git clean -fd`。`lophine-server/src/minecraft/java/` 回到上一张成功 feature 的干净提交。
3. 写入 continue 状态。
4. 打印失败文件名，以及：

   ```text
   ***     ./gradlew continueMinecraftFeaturePatches
   ***   Do not commit; the continue task commits the current working tree
   ***   using the original patch metadata.
   ```

5. 抛出 `PaperweightException`，整次 apply 停下。

之后在该生成仓库里手工把失败 patch 的改动写进去，**不要在生成仓库里自己 commit**。Continue 会用原 patch 的作者、日期和提交说明来提交当前工作树，再继续应用剩余 patch。

其它层同样：改 `paper-server/`、`folia-server/`、`paper-api/` 或 `folia-api/`，跑上表对应的 continue 任务。

## 状态文件

每个生成仓库各自保存一份状态：

| 层 | 状态文件 |
| --- | --- |
| Minecraft | `lophine-server/src/minecraft/java/.git/paperweight-feature-patch-continue.json` |
| Paper 服务端 | `paper-server/.git/paperweight-feature-patch-continue.json` |
| Folia 服务端 | `folia-server/.git/paperweight-feature-patch-continue.json` |
| Paper API | `paper-api/.git/paperweight-feature-patch-continue.json` |
| Folia API | `folia-api/.git/paperweight-feature-patch-continue.json` |

同目录还会写 `.git/patch-apply-failed`（内容为 `1`）。清理状态时两份一起删。有 JSON 才算“被中断”。

```json
{
  "failedPatch": "0002-Config-System-Framework.patch",
  "remainingPatches": ["0003-Correct-player-respawn-place.patch"]
}
```

- `failedPatch`：当前失败、需要人工落地的文件名（相对该层 `features/`）。
- `remainingPatches`：失败项之后尚未应用的文件名。

Apply 开始或成功结束会清掉它们。Continue 在提交（或丢弃）失败项、开始应用剩余 patch 之前也会先清。剩余 patch 再次失败会写入新状态。

`:lophine-server:listContinuable` 和 `:lophine-server:continueAll` 只认 JSON。仓库不存在或没有该文件时，该层视为空闲。这两项只扫描 **服务端** 三层，不含 API。

## 任务怎么用

Gradle group 均为 `continue`。任务不跟踪状态，每次请求都会跑。

### 单层

| 任务 | 行为 |
| --- | --- |
| `continue…FeaturePatches` | 工作区有改动则用失败 patch 的元数据 commit，再应用剩余 patch。工作区干净则跳过 commit。 |
| `continueDropCurrent…FeaturePatches` | 丢弃失败 patch：`reset --hard HEAD` + `clean -fd`，不提交，再应用剩余 patch。 |

Apply 失败日志里的任务名是 `continueTaskName`（例如 `continueMinecraftFeaturePatches`）。从主仓库根目录请用带模块前缀的名字，见上表。Drop 变体不会出现在失败提示里，需要显式调用。

该层没有中断状态时，单层 continue 会失败，提示先跑 apply。

### 服务端聚合

`:lophine-server` 额外注册：

| 任务 | 行为 |
| --- | --- |
| `:lophine-server:listContinuable` | 列出当前有中断的服务端 continue 任务：失败 patch、剩余数量、建议命令。没有中断时打印 `No interrupted feature patch applies`。 |
| `:lophine-server:continueAll` | 按 Minecraft → Paper server → Folia server 尝试。无中断则跳过，不执行其它 Gradle continue 任务。结束后 `finalizedBy listContinuable`。 |

`continueAll` 是“提交后继续”（`dropCurrent = false`）。要丢弃当前失败 patch，用对应的 `continueDropCurrent…`。某一层剩余 patch 再次失败时，`continueAll` 抛错并停下，后续层不再跑；`listContinuable` 仍会打印当时仍中断的集合。

根项目的 `paperweight-patcher` **不**注册 `continueAll` / `listContinuable`。API 层中断后跑 `:continuePaperApiFeaturePatches` 或 `:continueFoliaApiFeaturePatches`。

`applyAllPatches`（根项目）会先应用 Folia 上游（含 API），再嵌套执行 `:lophine-server:applyAllServerPatches`。服务端 feature 失败时，到 `:lophine-server` 的 continue / `continueAll` 接手。API feature 失败时，到根项目对应 continue 接手。

## 在 Lophine 里的操作步骤

```
applyAllPatches
  或 :lophine-server:applyMinecraftFeaturePatches 等
        │
        ├─ 全部成功 → 清状态，结束
        │
        └─ git am 失败（例：Minecraft 第 N 张）
              │
              ├─ 回滚失败 patch（生成仓库干净，HEAD 停在上一张成功 patch）
              ├─ 写入该仓库 .git/paperweight-feature-patch-continue.json
              └─ 停止
                    │
                    ├─ 在生成仓库里手工改源码（不要 commit）
                    │         │
                    │         └─ .\gradlew.bat :lophine-server:continueMinecraftFeaturePatches
                    │            或多层服务端中断时：
                    │            .\gradlew.bat :lophine-server:continueAll
                    │
                    └─ 确认这张 patch 不再需要
                              │
                              └─ .\gradlew.bat :lophine-server:continueDropCurrentMinecraftFeaturePatches
```

推荐步骤：

1. 从主仓库根目录跑 apply。常见入口：

   ```powershell
   .\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
   ```

   或只跑一层，例如：

   ```powershell
   .\gradlew.bat :lophine-server:applyMinecraftFeaturePatches --console=plain --stacktrace --no-daemon
   ```

2. 失败后看日志里的 patch 文件名和 continue 任务名。确认服务端有哪些层中断：

   ```powershell
   .\gradlew.bat :lophine-server:listContinuable --console=plain --no-daemon
   ```

3. 在对应生成仓库里手工解决失败 patch，保持改动未提交。先确认进的是生成仓库，而不是主仓库：

   ```powershell
   git -C lophine-server/src/minecraft/java rev-parse --show-toplevel
   git -C lophine-server/src/minecraft/java status --short
   ```

   Paper 服务端把路径换成 `paper-server`，API 换成 `paper-api`，以此类推。对照失败 patch（例如 `lophine-server/minecraft-patches/features/0002-….patch`）改源码。

4. 跑该层 continue。Minecraft 示例：

   ```powershell
   .\gradlew.bat :lophine-server:continueMinecraftFeaturePatches --console=plain --stacktrace --no-daemon
   ```

   服务端多层都中断、且都已手工改完时：

   ```powershell
   .\gradlew.bat :lophine-server:continueAll --console=plain --stacktrace --no-daemon
   ```

   Paper API 示例：

   ```powershell
   .\gradlew.bat :continuePaperApiFeaturePatches --console=plain --stacktrace --no-daemon
   ```

5. 这张 patch 打算整张丢掉时，改跑对应 `continueDropCurrent…`。丢弃后工作区回到失败前的提交，该 patch 不会进入当前这次 apply。需要永久去掉它时，另外从 patch 目录删除并 `rebuild…FeaturePatches`。

6. Continue 成功后该仓库的 JSON 消失。后面又有一张失败，重复 2–5。全部结束后再按贡献指南导出或编译。

## Continue 内部步骤

`FeaturePatchOperations.continueApplyingPatches`：

1. 检查 Git、仓库、patch 目录、JSON 状态。空闲且 `skipIfIdle` 时直接返回（仅 `continueAll`）。
2. `git am --quit`。
3. 校验 `failedPatch` 与每条 `remainingPatches` 在该层 `features/` 中都是普通文件。
4. `dropCurrent`：硬重置并清理工作区。否则：工作区 dirty 则 `git add --all`，用原 patch 的 `From` / `Date` / `Subject` / body 生成提交（author 与 committer 相同）；干净则跳过提交并打日志。
5. 删除 JSON 与 `patch-apply-failed`。
6. 没有剩余 patch 则结束。
7. 对剩余列表再次走 `git am --3way --ignore-whitespace --committer-date-is-author-date`。失败则回滚、写入新状态、提示对应 continue 任务。

提交说明来自 patch 头：去掉 `[PATCH…]` 前缀的 Subject，加上 `---` / `diff --git` 之前的 body。缺少 author email 或 subject 会直接失败。

## 约束

- Continue 只覆盖 feature patch。File patch 冲突走 rejects / fuzzy / `applyOrMove…`。
- 在生成仓库里手工 commit 会打乱“用原 patch 元数据提交”的约定。工作区应保持未提交改动。主仓库的 `lophine-server/src/main/` 等直接维护文件不受影响。
- `:lophine-server:continueAll` 不会调用各 `continue…` Gradle 任务，而是对每个服务端 target 直接跑同一套 continue 逻辑，因此不会触发那些任务的 Gradle 依赖图，也不处理 API 层。
- 状态绑在具体生成仓库路径上。`applyAllPatches` 若重建该树并清掉 `.git`，中断点消失，只能重新 apply。
- 只读上游和 `readOnly = true` 的 patch set 不注册 continue。

## 实现入口（ComfreyWeight）

| 职责 | 位置 |
| --- | --- |
| Apply / continue / 回滚 / 状态读写 | `paperweight-core/.../tasks/patching/ApplyFeaturePatches.kt`（`FeaturePatchOperations`） |
| `continueAll`、`listContinuable` | `paperweight-core/.../tasks/patching/ContinueAllPatches.kt` |
| 聚合注册与 target 收集 | `paperweight-core/.../taskcontainers/CoreTasks.kt` |
| Minecraft continue 任务名与仓库 | `paperweight-core/.../taskcontainers/MinecraftPatchingTasks.kt` |
| 上游 directory patch set continue 任务 | `paperweight-core/.../taskcontainers/PatchingTasks.kt` |
| 上游 continue target 转发 | `paperweight-core/.../taskcontainers/UpstreamConfigTasks.kt` |

Lophine 侧配置：

- 根项目上游（API 等）：`build.gradle.kts` 的 `paperweight { upstreams.register("folia") { … } }`
- 服务端 fork 与 Minecraft：`lophine-server/build.gradle.kts` 的 `forks.register("folia")` / `forks.register("lophine")`，`activeFork = lophine`

行为由 `ApplyFeaturePatchesTest` 与 `ContinueAllPatchesTest` 覆盖：失败回滚、用原元数据 continue、空闲 set 被 `continueAll` / `listContinuable` 跳过。
