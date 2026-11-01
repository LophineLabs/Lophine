# Instructions for Coding Agents

These instructions apply throughout the Lophine repository. Read the relevant
parts of [the contribution guide](docs/CONTRIBUTING_EN.md) before changing code.
The [Simplified Chinese guide](docs/CONTRIBUTING.md) is also available. Inspect
the current checkout and Gradle task definitions when a documented path or task
does not match the working tree.

## Project and toolchain

Lophine is a Folia-based Minecraft server fork with Paper API compatibility.
Use the checked-in Gradle wrapper and JDK 25. The Minecraft version and upstream
Folia revision are recorded in `gradle.properties`; use those revisions when
comparing upstream behavior. Do not update upstream references as incidental
cleanup.

On Windows, use `.\gradlew.bat`; on Linux and macOS, use `./gradlew`. Keep paths
and commands portable instead of embedding a contributor's local JDK location.

## Locate the persistent editing target

The build combines tracked Lophine sources with applied upstream sources:

| Area | Applied or direct source | Persistent changes |
| --- | --- | --- |
| Lophine implementation and resources | `lophine-server/src/main/` | Edit tracked files directly |
| Minecraft implementation | `lophine-server/src/minecraft/java/` | `lophine-server/minecraft-patches/` |
| Paper server implementation | `paper-server/` | `lophine-server/paper-patches/` |
| Folia server implementation | `folia-server/` | `lophine-server/folia-patches/` when configured |
| Paper API | `paper-api/` | `lophine-api/paper-patches/` |
| Folia API | `folia-api/` | `lophine-api/folia-patches/` when configured |
| Module build scripts | Applied `lophine-*/build.gradle.kts` | Corresponding `build.gradle.kts.patch` |

Confirm the actual path and owning patch before editing. Several applied trees
and module build scripts are ignored by Git and can be replaced by patch tasks.
A change made only in an ignored tree is not a persistent repository change.
Inspect nested Git repositories separately from the main repository.

## Patch workflow and ownership

1. Inspect the branch, HEAD, staged changes, unstaged changes, and untracked
   files. Read the relevant applied source, callers, and existing patches.
2. If applied sources are missing, prepare them with `applyAllPatches` when
   source preparation is within the task's scope. Preserve any existing applied
   source edits before running tasks that recreate those trees.
3. Implement the change in its owning source or patch. For an existing feature,
   update its existing patch rather than adding a later patch solely to undo or
   repair it. Keep unrelated formatting and patch metadata out of the diff.
4. Export changes from applied sources with the precise task for the affected
   layer. Minecraft feature changes use
   `:lophine-server:rebuildMinecraftFeaturePatches`; Paper server feature changes
   use `:lophine-server:rebuildPaperServerFeaturePatches`. Use `tasks --all` to
   find other layer-specific tasks. Avoid ambiguous abbreviated task names.
   For feature history edits, fold the change into its owning generated commit
   before export, preserving later feature commits. Use an isolated generated
   repository when this requires rebasing history that contains other work.
5. Review every exported file. Reapply changed patches in an isolated checkout
   or a verified clean generated tree, then validate the resulting source.

Patch tasks can update local Git history inside generated repositories. Keep
that work scoped to the patch being changed; use isolation when other edits
would be overwritten. Do not commit generated source files into the main
repository. If the user requests source-only work, report which changes still
need export instead of exporting them automatically.

Keep Carpet integrations grouped by origin: vanilla Carpet and Scarpet in the
Carpet feature patch, and AMS, ORG, and TIS changes in their respective feature
patches. Use configuration `section` annotations and the actual implementation
to establish ownership, including changes mixed within one Minecraft class.
Keep genuinely shared integration in the common Carpet patch. Inspect current
filenames instead of assuming patch numbers are stable.

When splitting or reordering patches, verify that the complete resulting source
tree matches the original tree unless a behavior change was requested. Preserve
subsequent patch contents and ordering, adjusting numbering only as needed.

## Validation

Choose checks that exercise the changed behavior. Typical PowerShell commands
from the repository root are:

```powershell
.\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
.\gradlew.bat :lophine-server:compileJava --console=plain --stacktrace --no-daemon
.\gradlew.bat :lophine-server:test --tests 'package.RelevantTest' --console=plain --no-daemon
.\gradlew.bat verifyLangFiles --console=plain --no-daemon
git diff --check
```

Use actual existing test names in place of the example filter. Add a focused
regression test when it can meaningfully reproduce a defect. Run broader checks
when shared behavior changes or focused results leave an unresolved concern.
For documentation-only changes, review links, paths, commands, and the diff;
server compilation is not required.

Use `createPaperclipJar` when a distributable server artifact is needed. Runtime
checks should use a separate test directory and explicit server configuration.
Do not change EULA acceptance or operate an existing server or world as an
incidental validation step. `runDevServer` uses source-set output, so recompile
and restart it after relevant Java changes.

Record the commands actually run and their outcomes. Compilation, unit tests,
patch application, and a real client/server interaction establish different
things. State any untested protocol, plugin, or runtime path explicitly.

## Configuration and localization

Configuration changes must cover loading, defaults, runtime edits, persistence,
and migration where applicable. Preserve existing values during comment-only
refreshes. Use the shared configuration path handling rather than duplicating
section construction in individual consumers.

Carpet configuration uses the `carpet` hierarchy with source-specific sections
such as `ams`, `org`, and `tis`. Keep rule bindings aligned with the corresponding
configuration annotations. Update names and descriptions in
`lophine-server/src/main/resources/assets/lophine/lang/`, including `en_us.json`,
`zh_cn.json`, and `zh_tw.json` for affected keys. Preserve translation placeholders
and use `verifyLangFiles` to check language assets. Run `sortLangKeys` only when
the change calls for rewriting their order.

## Java, threading, and compatibility

Follow surrounding Java style and retain annotations and contracts. For Folia
changes, identify which region owns each entity, chunk, and mutable object;
trace scheduling and completion through unload, retirement, and shutdown.
Preserve plugin events, native operation order, and compatibility contracts.

For hot paths, explain allocation and computational costs. Keep performance
predictions separate from measurements. Distinguish confirmed defects from
risks, and attribute findings to Lophine, upstream behavior, unsupported usage,
or unknown origin with source evidence.

## Git and communication

Preserve unrelated changes. In the main repository, stage, commit, or push only
when the user requests those actions. Publish or send project-facing messages
only when requested. Explain changes using verified behavior and evidence; do
not invent validation or speak for maintainers.

At completion, identify changed files, behavior, validation results, remaining
checks, and whether the main repository was committed or pushed. A suggested
commit message is not evidence that a commit was made.
