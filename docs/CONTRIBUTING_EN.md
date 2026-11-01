# Contributing to Lophine

**English** | [中文（简体）](./CONTRIBUTING.md) | [中文（繁體）](./CONTRIBUTING_ZH_TW.md) | [日本語](./CONTRIBUTING_JP.md)

Thank you for contributing to Lophine. Explain the problem, implementation, and validation in your PR so reviewers can assess the change and its maintenance cost. Coding agents must also follow [AGENTS.md](../AGENTS.md) in the repository root.

## Environment and first build

- Install Git and JDK 25, and use the checked-in Gradle wrapper. The compilation toolchain and CI currently use Java 25.
- Read `gradle.properties`, `settings.gradle.kts`, and module build configuration for the Minecraft version, Folia baseline, and plugin versions.
- On Windows, enable long paths in the OS and Git; see the [Windows documentation](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation) and [Git for Windows FAQ](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names).
- Prefer a personal fork and a working branch based on the intended target. Inspect `git status` and preserve existing work.

Run commands from the main repository root. Examples use PowerShell; on Linux/macOS replace `.\gradlew.bat` with `./gradlew`.

```powershell
java -version
git --version
.\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
.\gradlew.bat :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat createPaperclipJar --console=plain --no-daemon
```

The Paperclip JAR is written to `lophine-server/build/libs/`. `applyAllPatches` prepares upstream sources and applies all layers; the first run downloads dependencies. Save or export applied-source work first, as patch application can recreate those directories.

## Find the correct editing target

Lophine uses ComfreyWeight for layered patching on top of Folia, Paper, and Minecraft. Directly maintained files and applied upstream files require different workflows.

| Change | Editing location | Persistent location in the main repository |
| --- | --- | --- |
| Lophine implementation, configuration, resources, and tests | `lophine-server/src/main/`, `lophine-server/src/test/` | Commit files directly |
| New Lophine API and tests | `lophine-api/src/main/`, `lophine-api/src/test/` | Commit files directly |
| Minecraft Java | `lophine-server/src/minecraft/java/` | `lophine-server/minecraft-patches/sources/` or `features/` |
| Vanilla Minecraft resources | `lophine-server/src/minecraft/resources/` | `lophine-server/minecraft-patches/resources/` |
| Paper server sources, resources, and tests | `paper-server/` | `lophine-server/paper-patches/files/` or `features/` |
| Existing Paper/Bukkit API | `paper-api/` | `lophine-api/paper-patches/files/` or `features/` |
| Folia-owned server or API files | `folia-server/`, `folia-api/` | The module's `folia-patches/`, as configured |
| Module build scripts | `lophine-*/build.gradle.kts` | The corresponding `build.gradle.kts.patch` |

The main repository normally ignores `paper-*`, `folia-*`, Minecraft sources, and applied module build scripts. Changes made only there must be exported to their owning patches to persist. Put new Lophine-owned classes and resources in directly maintained directories; they do not need patch generation.

Applied sources have independent Git histories. Run `git rev-parse --show-toplevel`, `git status`, and `git log --oneline` in the target directory before editing history. References such as `base` and `file` belong to that layer: per-file changes are collected in a File Patches commit, followed by individually exported feature commits. Not every commit after `base` is a separate feature patch.

## Choose a patch type and task

Maintain the owning patch when changing an existing feature. Small upstream edits can use per-file patches; a complete feature that needs separate explanation and maintenance can use a feature patch. Vanilla Minecraft resources use resource patches. Feature rebuild tasks may also run per-file dependencies, so review every exported change.

Invoke these fully qualified tasks from the main repository root:

| Layer | Collect per-file changes | Export per-file patches | Export feature patches |
| --- | --- | --- | --- |
| Minecraft Java | `:lophine-server:fixupMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftFeaturePatches` |
| Minecraft resources | `:lophine-server:fixupMinecraftResourcePatches` | `:lophine-server:rebuildMinecraftResourcePatches` | — |
| Paper server | `:lophine-server:fixupPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFeaturePatches` |
| Paper API | `:fixupPaperApiFilePatches` | `:rebuildPaperApiFilePatches` | `:rebuildPaperApiFeaturePatches` |
| Folia server | `:lophine-server:fixupFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFeaturePatches` |
| Folia API | `:fixupFoliaApiFilePatches` | `:rebuildFoliaApiFilePatches` | `:rebuildFoliaApiFeaturePatches` |

Export module build script edits with `:rebuildFoliaSingleFilePatches`. Its name comes from the Folia upstream registered in the root build; do not infer `rebuildLophineSingleFilePatches` from the module name. Inspect additional tasks with:

```powershell
.\gradlew.bat tasks --all :lophine-server:tasks --all --console=plain
```

### Directly maintained Lophine files

Edit, validate, and commit selected files in the main repository. Add new files to Git tracking normally. The old instruction to exclude new files does not apply to these directories, and API `fixup` tasks are not part of this workflow.

### Add a feature patch

For Minecraft, implement and validate the change in applied sources, create a feature commit in that generated repository, and export from the main repository:

```powershell
git -C lophine-server/src/minecraft/java status --short
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit -m "Describe the feature"
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

Replace the example path with actual paths relative to the generated repository, listing each affected file. Create Paper server or API feature commits in the corresponding generated repository and use its matching task. Finally, review and commit the exported patches and directly maintained files in the main repository.

### Modify an existing feature patch

Develop and validate at the fully applied source HEAD. Locate the owning feature commit with `git log` / `git blame`, create a fixup in that generated repository, and fold it into the original commit. Replace `<target-hash>` with a verified Lophine feature commit:

```powershell
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit --fixup=<target-hash>
git -C lophine-server/src/minecraft/java rebase -i --autosquash '<target-hash>^'
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

Inspect and save the rebase plan. Resolve conflicts according to patch ownership before continuing. Use an isolated copy when other work or complex dependencies are present. Repair the owning patch instead of appending another patch to cancel an earlier mistake. Do not run this generated-history rebase in the main repository.

### Modify per-file patches

Edit existing upstream files in the target generated repository without first creating a new feature commit. Run the matching `fixup...` task followed by `rebuild...`. For Minecraft Java:

```powershell
.\gradlew.bat :lophine-server:fixupMinecraftSourcePatches --console=plain
.\gradlew.bat :lophine-server:rebuildMinecraftSourcePatches --console=plain
```

`fixup` collects changes to tracked files in that generated repository and updates the File Patches commit. It does not save new main-repository files for you. Isolate unrelated applied-source changes first; later feature commits can cause rebase conflicts during this operation.

## Ownership, style, and compatibility

- Keep Carpet, Scarpet, and shared integration in the Carpet patch. Group AMS, ORG, and TIS changes by origin, using configuration `section` annotations and implementation evidence even within one class. Inspect current filenames rather than relying on fixed patch numbers.
- A split or reorder should reproduce the original complete source tree unless behavior changes are intended. Preserve subsequent patch contents and necessary application order.
- Follow surrounding style. Mark upstream edits with meaningful `// Lophine - ...` or `// Lophine start/end - ...` comments and retain upstream comments, authorship, and licenses. Explain complex features and maintenance constraints in the patch commit body.
- For Folia code, verify ownership of entities, chunks, and mutable state through region scheduling, unloading, removal, and shutdown. Running asynchronously is not a thread-safety argument.
- Preserve plugin events, API contracts, persisted formats, and configuration defaults. Explain performance mechanisms, scenarios, and measurements; do not report predictions as measured gains.

## Configuration and localization

Use the existing `@ConfigClassInfo`, `@ConfigInfo`, and shared `ConfigPaths`. Check loading, defaults, runtime edits, persistence, and legacy path migration. Comment refreshes must preserve user values. Carpet extensions retain the `carpet` hierarchy and their source-specific sections.

Update affected configuration names and descriptions in `lophine-server/src/main/resources/assets/lophine/lang/`, including `en_us.json`, `zh_cn.json`, `zh_tw.json`, and other affected languages. Preserve formatting placeholders:

```powershell
.\gradlew.bat verifyLangFiles --console=plain
```

This task validates JSON, string values, and keys unknown to the English reference. Missing translations are reported and fall back to English; success does not establish complete translation coverage. `sortLangKeys` rewrites language file ordering. Run it when sorting is needed and review the full diff.

## Validation and pull requests

Choose checks appropriate to the change. Documentation-only changes do not require server compilation:

```powershell
git diff --check
.\gradlew.bat :lophine-api:compileJava :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat :lophine-server:test --tests 'package.RelevantTest' --console=plain --no-daemon
```

Replace the test filter with an existing test. For patch changes, rerun `applyAllPatches` in an isolated copy or an applied tree verified to have no unsaved changes. Build `createPaperclipJar` when delivering a server. Check API implementations and callers together; protocol or plugin compatibility needs interactions with the affected clients or plugins.

`runDevServer` uses source-set output, so Java changes require recompilation and restart. Use a separate test directory and explicit configuration, not production worlds. The operator must read the EULA and decide whether to accept it. Enable the optional test plugin through `test-plugin.settings.gradle.kts`.

Before opening a PR, review main and generated repository diffs. Export required source changes and exclude caches, build output, runtime worlds, and unrelated edits. Describe the problem and trigger, resulting behavior, compatibility or threading effects, actual checks, and unverified scope. Compilation does not replace real client testing. Contributors must understand and own their code and explanations; disclose meaningful AI assistance honestly and review its output.
