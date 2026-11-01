# Lophine への貢献

[English](./CONTRIBUTING_EN.md) | [中文（简体）](./CONTRIBUTING.md) | [中文（繁體）](./CONTRIBUTING_ZH_TW.md) | **日本語**

Lophine への貢献をありがとうございます。PR には問題、実装方法、検証結果を記載し、変更内容と保守コストをレビューできるようにしてください。コーディングエージェントを使用する場合は、ルートの [AGENTS.md](../AGENTS.md) にも従ってください。

## 開発環境と最初のビルド

- Git と JDK 25 を用意し、リポジトリ付属の Gradle Wrapper を使用します。現在のコンパイル用ツールチェーンと CI は Java 25 を使用します。
- Minecraft のバージョン、Folia の基準コミット、プラグインのバージョンは `gradle.properties`、`settings.gradle.kts`、各モジュールのビルド設定で確認してください。
- Windows では OS と Git の長いパス対応を有効にしてください。[Windows の説明](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)と [Git for Windows FAQ](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)を参照できます。
- 個人アカウントの Fork と、対象ブランチを基にした作業ブランチを推奨します。最初に `git status` を確認し、既存の変更を保持してください。

コマンドはメインリポジトリのルートで実行します。例は PowerShell 用です。Linux/macOS では `.\gradlew.bat` を `./gradlew` に置き換えてください。

```powershell
java -version
git --version
.\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
.\gradlew.bat :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat createPaperclipJar --console=plain --no-daemon
```

Paperclip JAR は `lophine-server/build/libs/` に出力されます。`applyAllPatches` は上流ソースを準備し、各層のパッチを適用します。初回は依存関係をダウンロードします。適用により生成ディレクトリが再作成される場合があるため、既存の生成ソース変更は先に保存またはパッチへ書き出してください。

## 正しい編集対象を選ぶ

Lophine は ComfreyWeight による多層パッチ構成で、Folia、Paper、Minecraft のソースを使用します。直接管理するファイルと、パッチ適用で生成するファイルでは手順が異なります。

| 変更対象 | 編集場所 | メインリポジトリの保存先 |
| --- | --- | --- |
| Lophine 独自の実装、設定、リソース、テスト | `lophine-server/src/main/`、`lophine-server/src/test/` | ファイルを直接コミット |
| Lophine の新規 API とテスト | `lophine-api/src/main/`、`lophine-api/src/test/` | ファイルを直接コミット |
| Minecraft Java | `lophine-server/src/minecraft/java/` | `lophine-server/minecraft-patches/sources/` または `features/` |
| Minecraft 標準リソース | `lophine-server/src/minecraft/resources/` | `lophine-server/minecraft-patches/resources/` |
| Paper サーバーのソース、リソース、テスト | `paper-server/` | `lophine-server/paper-patches/files/` または `features/` |
| 既存の Paper/Bukkit API | `paper-api/` | `lophine-api/paper-patches/files/` または `features/` |
| Folia 独自のサーバーまたは API ファイル | `folia-server/`、`folia-api/` | 対応モジュールの `folia-patches/`（現在の設定による） |
| モジュールビルドスクリプト | `lophine-*/build.gradle.kts` | 対応する `build.gradle.kts.patch` |

`paper-*`、`folia-*`、Minecraft ソース、適用後のモジュールビルドスクリプトは通常メインリポジトリの Git 管理対象外です。変更を残すには対応するパッチへ書き出してください。Lophine 独自の新しいクラスやリソースは直接管理するディレクトリに置き、パッチ生成は行いません。

生成ソースは独立した Git 履歴を持ちます。対象ディレクトリで `git rev-parse --show-toplevel`、`git status`、`git log --oneline` を実行し、操作先を確認してください。`base`、`file` などの参照はその層のものです。ファイル単位の変更は File Patches コミットにまとめられ、後続の機能コミットは個別に書き出されます。`base` より後のコミットがすべて独立した機能パッチとは限りません。

## パッチの種類とタスク

既存機能の変更は、その機能を所有するパッチで保守します。小規模な上流変更にはファイル単位のパッチ、独立した説明や保守が必要な機能には feature patch を使えます。Minecraft 標準リソースにはリソースパッチを使います。機能パッチの再生成タスクがファイル単位の依存タスクも実行する場合があるため、出力全体を確認してください。

次の完全なタスク名をメインリポジトリのルートから呼び出します。

| 変更する層 | ファイル単位の変更を収集 | ファイル単位のパッチを書き出す | 機能パッチを書き出す |
| --- | --- | --- | --- |
| Minecraft Java | `:lophine-server:fixupMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftFeaturePatches` |
| Minecraft リソース | `:lophine-server:fixupMinecraftResourcePatches` | `:lophine-server:rebuildMinecraftResourcePatches` | — |
| Paper サーバー | `:lophine-server:fixupPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFeaturePatches` |
| Paper API | `:fixupPaperApiFilePatches` | `:rebuildPaperApiFilePatches` | `:rebuildPaperApiFeaturePatches` |
| Folia サーバー | `:lophine-server:fixupFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFeaturePatches` |
| Folia API | `:fixupFoliaApiFilePatches` | `:rebuildFoliaApiFilePatches` | `:rebuildFoliaApiFeaturePatches` |

モジュールビルドスクリプトは `:rebuildFoliaSingleFilePatches` で書き出します。名前はルートで登録された Folia 上流に由来します。モジュール名から `rebuildLophineSingleFilePatches` と推測しないでください。追加のタスクは次で確認できます。

```powershell
.\gradlew.bat tasks --all :lophine-server:tasks --all --console=plain
```

### 直接管理する Lophine ファイル

編集と検証の後、選択したファイルをメインリポジトリでコミットします。新規ファイルも通常どおり Git に追加してください。旧ガイドの「新規ファイルをコミットしない」という説明はこれらのディレクトリには適用されず、API の `fixup` タスクも不要です。

### 機能パッチを追加する

Minecraft の例では、適用後のソースで実装と検証を行い、その生成リポジトリで機能コミットを作成してから、メインリポジトリで書き出します。

```powershell
git -C lophine-server/src/minecraft/java status --short
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit -m "Describe the feature"
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

例のパスを生成リポジトリからの実際の相対パスに置き換え、対象ファイルを列挙してください。Paper サーバーや API の機能コミットは対応する生成リポジトリで作り、表の対応タスクを使用します。最後に、出力したパッチと直接管理する変更をメインリポジトリで確認し、コミットします。

### 既存の機能パッチを変更する

すべて適用済みのソース HEAD で開発と検証を行います。`git log` / `git blame` で対象機能のコミットを確認し、その生成リポジトリで fixup を作って元のコミットにまとめます。`<target-hash>` は Lophine の機能コミットであると確認したハッシュに置き換えてください。

```powershell
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit --fixup=<target-hash>
git -C lophine-server/src/minecraft/java rebase -i --autosquash '<target-hash>^'
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

rebase の計画を確認して保存します。競合があれば各層の変更元を確認し、解決してから続行してください。他の作業や複雑な依存関係がある場合は隔離コピーを使用します。前の誤りを打ち消すパッチを追加せず、所有元のパッチを修正してください。この rebase は生成リポジトリ用であり、メインリポジトリで直接行うものではありません。

### ファイル単位のパッチを変更する

対象生成リポジトリ内の既存上流ファイルを編集し、新しい機能コミットは先に作りません。表の対応する `fixup...`、続いて `rebuild...` を実行します。Minecraft Java の例です。

```powershell
.\gradlew.bat :lophine-server:fixupMinecraftSourcePatches --console=plain
.\gradlew.bat :lophine-server:rebuildMinecraftSourcePatches --console=plain
```

`fixup` は生成リポジトリの追跡済みファイルの変更を集め、File Patches コミットを更新します。メインリポジトリの新規ファイルを保存するタスクではありません。無関係な生成ソースの変更は先に分離してください。後続の機能コミットにより rebase 競合が発生する場合があります。

## 所有元、スタイル、互換性

- Carpet、Scarpet、共通統合は Carpet パッチに置きます。AMS、ORG、TIS は出典ごとのパッチに分け、同一クラス内も設定の `section` と実装を基に分類します。固定番号ではなく現在のファイル名を確認してください。
- パッチの分割や並べ替えでは、意図した動作変更がない限り最終ソースツリーを元と一致させます。後続パッチの内容と必要な適用順序を保持してください。
- 周囲のコードスタイルに従います。上流変更には意味のある `// Lophine - ...` または `// Lophine start/end - ...` を付け、上流コメント、著者、ライセンス情報を保持します。複雑な機能はパッチコミット本文で理由と保守上の要点を説明してください。
- Folia ではエンティティ、チャンク、可変状態のスレッド所有権を確認し、リージョン間スケジュール、アンロード、削除、停止処理を追ってください。非同期実行だけではスレッド安全性を説明できません。
- プラグインイベント、API 契約、永続化形式、既定設定の互換性を保ちます。性能変更では仕組み、シナリオ、測定結果を示し、予測を実測値として扱わないでください。

## 設定とローカライズ

既存の `@ConfigClassInfo`、`@ConfigInfo`、共通の `ConfigPaths` を使用し、読み込み、既定値、実行時変更、保存、旧パス移行を確認します。コメント更新ではユーザー値を保持してください。Carpet 拡張は `carpet` 階層と出典別の section を使います。

設定名や説明を変更したら、`lophine-server/src/main/resources/assets/lophine/lang/` の `en_us.json`、`zh_cn.json`、`zh_tw.json` と影響する言語を更新し、書式プレースホルダーを保持してください。

```powershell
.\gradlew.bat verifyLangFiles --console=plain
```

このタスクは JSON、文字列値、英語に存在しないキーを確認します。欠落翻訳は報告されて英語にフォールバックするため、成功は翻訳の完全性を意味しません。`sortLangKeys` は言語ファイルの順序を書き換えます。整列が必要なときに実行し、差分全体を確認してください。

## 検証と PR

変更範囲に合った検証を選びます。ドキュメントだけの変更にサーバーのコンパイルは不要です。

```powershell
git diff --check
.\gradlew.bat :lophine-api:compileJava :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat :lophine-server:test --tests 'package.RelevantTest' --console=plain --no-daemon
```

テストフィルターは実在するテストに置き換えます。パッチ変更では隔離コピー、または未保存の変更がないことを確認した生成ツリーで `applyAllPatches` を再実行してください。サーバーを配布する場合は `createPaperclipJar` をビルドします。API 変更は実装と呼び出し元を併せて確認し、プロトコルやプラグイン互換性は対応するクライアント／プラグインとの実際の通信で検証します。

`runDevServer` はソースセット出力を使用するため、Java 変更後は再コンパイルと再起動が必要です。独立したテストディレクトリと明示的な設定を使い、本番ワールドは使用しないでください。EULA は実行者自身が読み、同意するか判断します。任意のテストプラグインは `test-plugin.settings.gradle.kts` で有効にできます。

PR 前にメインと生成リポジトリの差分を確認し、必要な変更をパッチに書き出して、キャッシュ、ビルド成果物、実行ワールド、無関係な変更を除外してください。問題と発生条件、最終動作、互換性やスレッドへの影響、実施した検証、未検証範囲を説明します。コンパイル成功は実クライアント検証の代わりにはなりません。貢献者はコードと説明を理解し、責任を持ってください。意味のある AI 支援を受けた場合は正確に説明し、出力を確認してください。
