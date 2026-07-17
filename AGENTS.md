# AGENTS.md

Compact instructions for AI agents working in this repo.

## What is this

Lophine is a Minecraft server fork: Lophine → Luminol → Folia → Paper → Spigot → CraftBukkit → NMS. It adds configurable vanilla features, redstone/survival-circuit enhancements, and Carpet-compatible rules on top of Folia's multi-threaded region system.

Group ID: `fun.bm.lophine`, MC version: 1.21.11, Java 21 required.

## Build

```bash
./gradlew applyAllPatches   # generates upstream source dirs (paper-server/, luminol-server/, etc.)
./gradlew createMojmapPaperclipJar  # builds the server JAR
```

Output: `lophine-server/build/libs/lophine-*-paperclip.jar`

## Patch system (critical)

This repo uses a git-based patch system. Understanding where to edit is the #1 source of confusion:

### New Lophine code (preferred)
- `lophine-server/src/main/java/fun/bm/lophine/` — main package: carpet/, command/, config/, enums/, protocol/, utils/
- `lophine-api/src/main/java/` — Lophine API additions

### Modifying upstream code
Edit in the **generated** upstream directories (created by `applyAllPatches`), then regenerate patches:

```bash
# 1. Make changes in generated dirs (paper-server/, luminol-server/, folia-server/)
# 2. Stage and commit (do NOT commit new files this way)
git add .
git commit -m "your message"
# 3. For new files in lophine-api/paper-patches or lophine-server/paper-patches:
./gradlew fixupPaperApiFilePatches
# 4. Regenerate all patches:
./gradlew rebuildAllServerPatches
```

### Patch locations
- `lophine-server/minecraft-patches/features/` — vanilla Minecraft modifications (44 patches)
- `lophine-server/luminol-patches/features/` — Luminol-layer modifications (6 patches)
- `lophine-server/paper-patches/features/` — Paper-layer modifications (7 patches)
- `lophine-api/paper-patches/features/` — API-layer modifications (3 patches)

### Generated dirs (gitignored — do not edit directly for lasting changes)
`paper-server/`, `paper-api/`, `luminol-server/src/minecraft`, `lophine-server/build.gradle.kts`, `lophine-api/build.gradle.kts`

## Code style

- 4-space indent, LF line endings (`.editorconfig`)
- 2-space indent for YAML and JSON files
- Java 21, UTF-8 encoding
- No trailing whitespace, final newline required

## Key config files

- `gradle.properties` — MC version, upstream ref (`luminolRef`), Gradle flags
- `build.gradle.kts` — root build: patcher plugin, Java 21 toolchain, Maven repos
- `settings.gradle.kts` — subprojects: `lophine-api`, `lophine-server`
- `qodana.yaml` — static analysis config
- `docs/carpet-compat-status.md` — tracks which Carpet rules are ported

## Common gotchas

- After `applyAllPatches`, the upstream dirs are real git repos. Run `rebuildAllServerPatches` from the **root** repo, not from inside the upstream dirs.
- `lophine-server/build.gradle.kts` and `lophine-api/build.gradle.kts` are generated patches — they get overwritten by `applyAllPatches`.
- Windows: enable long path support in both OS and Git before building (deep nested paths).
- The `luminolRef` in `gradle.properties` pins the upstream Luminol commit. Update it to track upstream.
