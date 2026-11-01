# 为 Lophine 贡献代码

[English](./CONTRIBUTING_EN.md) | **中文（简体）** | [中文（繁體）](./CONTRIBUTING_ZH_TW.md) | [日本語](./CONTRIBUTING_JP.md)

感谢你参与 Lophine。请在 PR 中说明问题、实现方式和验证结果，让维护者能够理解变更及其维护成本。使用编码代理时，还需遵循根目录的 [AGENTS.md](../AGENTS.md)。

## 开发环境与首次构建

- 安装 Git 和 JDK 25，使用仓库自带的 Gradle Wrapper。当前编译工具链和 CI 都使用 Java 25。
- Minecraft 版本、Folia 基准及插件版本以 `gradle.properties`、`settings.gradle.kts` 和模块构建配置为准。
- Windows 下请启用系统和 Git 的长路径支持，参见 [Windows 文档](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)与 [Git for Windows FAQ](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)。
- 建议使用个人账户 Fork，在与目标分支对应的工作分支上开发。先检查 `git status`，保留已有修改。

以下命令均在主仓库根目录执行。示例使用 PowerShell；Linux/macOS 请将 `.\gradlew.bat` 替换为 `./gradlew`。

```powershell
java -version
git --version
.\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
.\gradlew.bat :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat createPaperclipJar --console=plain --no-daemon
```

Paperclip JAR 输出到 `lophine-server/build/libs/`。`applyAllPatches` 准备上游源码并应用各层补丁；首次运行需要下载依赖。运行前保存或导出已有生成源码修改，应用补丁可能重新创建这些目录。

## 找到正确的修改位置

Lophine 使用 ComfreyWeight 的多层补丁流程，基于 Folia，并继承 Paper 和 Minecraft 源码。直接维护的文件与应用补丁生成的文件需要分别处理。

| 修改范围 | 编辑位置 | 主仓库保存位置 |
| --- | --- | --- |
| Lophine 自有实现、配置、资源及测试 | `lophine-server/src/main/`、`lophine-server/src/test/` | 直接提交文件 |
| Lophine 新增 API 及测试 | `lophine-api/src/main/`、`lophine-api/src/test/` | 直接提交文件 |
| Minecraft Java | `lophine-server/src/minecraft/java/` | `lophine-server/minecraft-patches/sources/` 或 `features/` |
| Minecraft 原版资源 | `lophine-server/src/minecraft/resources/` | `lophine-server/minecraft-patches/resources/` |
| Paper 服务端源码、资源及测试 | `paper-server/` | `lophine-server/paper-patches/files/` 或 `features/` |
| 现有 Paper/Bukkit API | `paper-api/` | `lophine-api/paper-patches/files/` 或 `features/` |
| Folia 自有服务端或 API 文件 | `folia-server/`、`folia-api/` | 对应模块的 `folia-patches/`，由当前配置决定 |
| 模块构建脚本 | `lophine-*/build.gradle.kts` | 对应的 `build.gradle.kts.patch` |

`paper-*`、`folia-*`、Minecraft 源码和应用后的模块构建脚本通常被主仓库忽略，只修改这些文件不会形成持久变更，必须导出补丁。Lophine 自有新类和资源应放在直接维护的目录，无须生成补丁。

生成源码有独立 Git 历史。在目标目录运行 `git rev-parse --show-toplevel`、`git status` 和 `git log --oneline`，确认操作的是哪个仓库。`base`、`file` 等引用属于该补丁层：逐文件修改汇入 File Patches 提交，后续功能提交分别导出。不能把 `base` 后的所有提交都当作独立功能补丁。

## 选择补丁类型与任务

修改现有功能时维护所属补丁。小范围上游文件修改可用逐文件补丁；需要独立说明和维护的完整功能可用 feature patch。Minecraft 原版资源使用资源补丁。功能重建任务也可能执行逐文件补丁依赖，请检查全部导出结果。

以下完整任务名从主仓库根目录调用：

| 修改层 | 收集逐文件修改 | 导出逐文件补丁 | 导出功能补丁 |
| --- | --- | --- | --- |
| Minecraft Java | `:lophine-server:fixupMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftFeaturePatches` |
| Minecraft 资源 | `:lophine-server:fixupMinecraftResourcePatches` | `:lophine-server:rebuildMinecraftResourcePatches` | — |
| Paper 服务端 | `:lophine-server:fixupPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFeaturePatches` |
| Paper API | `:fixupPaperApiFilePatches` | `:rebuildPaperApiFilePatches` | `:rebuildPaperApiFeaturePatches` |
| Folia 服务端 | `:lophine-server:fixupFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFeaturePatches` |
| Folia API | `:fixupFoliaApiFilePatches` | `:rebuildFoliaApiFilePatches` | `:rebuildFoliaApiFeaturePatches` |

模块构建脚本使用 `:rebuildFoliaSingleFilePatches` 导出。该名称对应根构建注册的 Folia 上游，不能按模块名推导成 `rebuildLophineSingleFilePatches`。更多任务请查看：

```powershell
.\gradlew.bat tasks --all :lophine-server:tasks --all --console=plain
```

### 直接维护的 Lophine 文件

编辑、验证后，在主仓库提交选定文件即可。新增文件需要正常加入 Git 跟踪。旧指南“不要提交新增文件”的说明不适用于这些目录，也不需要运行 API 的 `fixup` 任务。

### 新增功能补丁

以 Minecraft 为例，在应用后的源码实现并验证，再在该生成仓库创建功能提交，然后从主仓库导出：

```powershell
git -C lophine-server/src/minecraft/java status --short
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit -m "Describe the feature"
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

替换示例路径，按相对于生成仓库的路径逐项列出文件。Paper 服务端或 API 的功能提交在对应生成仓库创建，使用任务表中的对应任务。最后在主仓库检查并提交导出的补丁及本次直接维护的文件。

### 修改现有功能补丁

在完整应用的源码 HEAD 上开发并验证，用 `git log` / `git blame` 找到所属功能提交。在该生成仓库创建 fixup，并合入原提交。将 `<target-hash>` 替换为已确认属于 Lophine 的功能提交：

```powershell
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit --fixup=<target-hash>
git -C lophine-server/src/minecraft/java rebase -i --autosquash '<target-hash>^'
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

检查变基计划并保存；遇到冲突时确认各层改动归属，再解决冲突并继续。有其他人的修改或复杂依赖时，使用隔离副本。修复所属补丁，避免追加补丁抵消前面的错误。上述变基操作针对生成仓库，不要直接用于主仓库。

### 修改逐文件补丁

编辑目标生成仓库中的现有上游文件，不先创建新的功能提交；按任务表先执行对应 `fixup...`，再执行 `rebuild...`。例如 Minecraft Java：

```powershell
.\gradlew.bat :lophine-server:fixupMinecraftSourcePatches --console=plain
.\gradlew.bat :lophine-server:rebuildMinecraftSourcePatches --console=plain
```

`fixup` 收集该生成仓库已跟踪文件的修改并整理 File Patches 提交，不会替你保存主仓库新增文件。先隔离不相关的生成源码修改；存在后续功能提交时，过程可能需要处理变基冲突。

## 归属、代码风格与兼容性

- Carpet、Scarpet 和公共适配归入 Carpet 补丁；AMS、ORG、TIS 按来源放入各自补丁。同一类中的混合改动也按配置 `section` 和实际实现划分。先检查当前文件名，不依赖固定补丁编号。
- 拆分或重排补丁后，完整生成源码应与原版本一致，除非本次明确修改行为。保留后续补丁内容和必要应用顺序。
- 遵循相邻代码风格。上游代码修改使用有意义的 `// Lophine - ...` 或 `// Lophine start/end - ...` 标记，保留上游注释、作者与许可证。复杂功能在补丁提交正文解释原因和维护要点。
- Folia 代码需确认实体、区块和可变状态的线程归属，检查跨区域调度、卸载、移除及停服过程，不能仅以“异步执行”作为线程安全依据。
- 保持插件事件、API 契约、持久化格式和默认配置兼容。性能修改应说明机制、场景和测量结果，不能将推测写成已测收益。

## 配置与本地化

使用现有 `@ConfigClassInfo`、`@ConfigInfo` 和共享 `ConfigPaths`，检查读取、默认值、运行时修改、保存及旧路径迁移。注释刷新应保留用户值。Carpet 扩展沿用 `carpet` 层级和对应来源的 section。

配置名称或说明变化时，同步更新 `lophine-server/src/main/resources/assets/lophine/lang/` 的 `en_us.json`、`zh_cn.json`、`zh_tw.json` 及受影响语言，保留格式占位符：

```powershell
.\gradlew.bat verifyLangFiles --console=plain
```

该任务检查 JSON、字符串值和相对于英文的未知键；缺失译文会报告并回退到英文，成功不代表翻译完整。`sortLangKeys` 会重写语言文件顺序，仅在需要排序时执行，并检查全部改动。

## 验证与提交 PR

按修改范围选择检查，纯文档变更不必编译服务端：

```powershell
git diff --check
.\gradlew.bat :lophine-api:compileJava :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat :lophine-server:test --tests 'package.RelevantTest' --console=plain --no-daemon
```

测试过滤器需替换为实际测试。补丁变更应在隔离副本或确认无未保存修改的生成树中重新运行 `applyAllPatches`；交付服务端时再构建 `createPaperclipJar`。API 变更同时检查实现与调用方，协议或插件兼容性需验证相应客户端/插件的实际交互。

`runDevServer` 使用源码集输出，Java 修改后需重新编译并重启。用独立测试目录和明确配置验证，不使用生产世界；运行者自行阅读 EULA 并决定是否接受。可选测试插件通过 `test-plugin.settings.gradle.kts` 启用。

PR 前检查主仓库与生成仓库的差异，确认源码已导出，不夹带缓存、构建产物、运行世界或无关修改。描述问题及触发条件、最终行为、兼容性或线程影响、实际检查和未验证范围。编译通过不能替代真实客户端验证。贡献者应理解并负责代码与说明；如使用 AI 辅助，请如实说明有意义的参与方式，并复核输出。
