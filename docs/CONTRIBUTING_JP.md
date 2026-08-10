Lophine への貢献
===============

[English](./CONTRIBUTING_EN.md) | [中文（簡體）](./CONTRIBUTING.md) | [中文（繁體）](./CONTRIBUTING_ZH_TW.md) | **日本語**

Lophine プロジェクトに貢献していただき、ありがとうございます！一般的に、PR（プルリクエスト）のレビュー基準は比較的緩やかです。
以下のルールに従っていただければ、よりスムーズにレビューを進めることができます。

## 個人アカウントでフォークしてください

定期的に既存の PR をマージしています。軽微な問題であれば、こちらで直接修正したうえでマージする場合があります。

しかし、組織（Organization）アカウントでフォークしたリポジトリから作成された PR の場合、直接変更を加えることができないため、一度クローズしてから手動で再マージする必要が生じます。

そのため、**組織アカウントを使用してこのプロジェクトをフォークしないでください**。

組織アカウントの PR に直接変更を加えられない理由については、[こちらの Issue](https://github.com/isaacs/github/issues/1681) をご参照ください。

## 開発環境

コードを書き始める前に、以下の開発ツールと環境を準備する必要があります。

- `git`
- `JDK 25` またはそれ以降のバージョン

注意： 作業を開始する前に、OS および Git で長いパス（Long Path）のサポートを有効にしてください。一部のプラットフォームの設定手順は以下をご参照ください。

[`Windows`](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)
[`Git for Windows`](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)

## パッチ（Patch）システムについて

Lophine は Folia と同じパッチシステムを採用しており、修正箇所に応じて以下の 2 つのディレクトリに分かれています。

- `lophine-api` - `Folia-API` / `Paper-API` / `Spigot-API` / `Bukkit-API` をベースにした修正。
- `lophine-server` - Minecraft のバニラサーバーの既存ロジックに対する修正。

このパッチシステムは Git をベースに動作します。Git の基本操作についてはこちらをご覧ください：<https://git-scm.com/docs/gittutorial>

メインのリポジトリをフォークしたら、以下の手順に従って操作してください：

1. フォークしたリポジトリをローカルにクローンします。
2. お使いの IDE またはターミナルで Gradle の `applyAllPatches` タスクを実行します。ターミナルで行う場合は、直接 `./gradlew applyAllPatches` を実行できます。
3. 実行が完了すると、リポジトリのルートディレクトリに `lophine-api` と `lophine-server`、`folia-api` と `folia-server`、`paper-api` と `paper-server` の各ディレクトリが生成されます。
4. リポジトリのルートディレクトリ下にある対応する `*-api` と `*-server` フォルダに移動し、そこで修正を行います。

上記各フォルダの簡単な説明は以下の通りです。より詳細な説明は[こちら](https://github.com/Toffikk/paperweight-examples/blob/18241979c88068d5b061d95ad69c98ecb201c246/README.md)をご参照ください：

1. API 部分

- `lophine-api`：新規 API の追加や変更はここで行ってください。
- `folia-api`：Folia API に対する修正はここで行ってください。
- `paper-api`：Paper API / Spigot API / Bukkit API に対する修正はここで行ってください。

2. Server 部分

- `lophine-server`：Minecraft のバニラサーバーに対する修正、および新規作成したファイルはすべてこのフォルダに配置してください。
- `folia-server`：Folia Server に対する修正はここで行ってください。
- `paper-server`：Paper サーバーのロジックに基づく修正はここで行ってください。

なお、リポジトリのルートディレクトリにある `*-api` と `*-server` は通常の Git リポジトリではありません：

- パッチを適用する前は、ベース（Base）は未修正のソースコードを指しています。
- ベース以降のすべてのコミットが、1 つのパッチとして扱われます。
- Folia の最後のコミット以降に追加されたコミットのみが、Lophine のパッチとして扱われます。

## 新規パッチの追加

新しいパッチを追加するのは非常に簡単です。以下の手順に従ってください：

1. `*-api` と `*-server` 内でコードを変更します。
2. Git を使用して変更をステージングエリアに追加します（例：`git add .`。新しく作成したファイルを同時に追加しないでください）。
3. `git commit -m <コミットメッセージ>` を実行してコミットします。
4. Gradle タスク `fixupPaperApiFilePatches` を実行し、新しく作成されたファイルに対応するパッチファイルを生成します（注意：実行が完了する前にコミットしないでください）。
5. Gradle タスク `rebuildAllServerPatches` を実行し、コミットをパッチに変換します。
6. 生成されたパッチファイルを Git にコミットし、プッシュします。

上記の手順が完了したら、これらのパッチファイルを元に PR を作成して提出できます。

## パッチの修正

既存のパッチを修正する場合は、以下の手順に従ってください：

1. HEAD で直接修正を行います。
2. `git commit -a --fixup <hash>` を実行して、fixup コミットを作成します（Lophine で新しく作成したファイルの変更を一緒にコミットしないでください）。
    - コミットメッセージも同時に変更したい場合は、`--fixup` の代わりに `--squash` を使用してください。
3. `git rebase -i --autosquash base`  を実行して、fixup コミットを自動的にまとめます。エディタが開いたら、`:q` と入力して終了します。
4. Gradle タスク `fixupPaperApiFilePatches` を実行し、Lophine で新しく作成したファイルのパッチを再生成します（注意：実行が完了する前にコミットしないでください）。
5. Gradle タスク `rebuildAllServerPatches` を実行し、修正を既存のパッチに反映させます。
6. 修正したパッチを再度プッシュし、PR を更新します。

## 設定項目に対するローカライズされたコメントサポートの提供

1. `lophine-server/src/main/resources/assets/lophine/lang` ディレクトリ下に、対応する言語のファイルを作成または修正し、各言語に対応したコメントを追加します。
    - ファイル名は <https://minecraft.wiki/w/Language> ページに記載されている言語コード（例：`en_us`、`zh_cn`、`zh_hk`、`zh_tw`、`ja_jp` など）に従って命名してください。ファイル形式は `json` です。
2. Gradle タスク `sortLangKeys` を実行し、修正した言語ファイルのキーの内容を並べ替えます。
3. `git commit -m <コミットメッセージ>` を実行してコミットします。
4. 修正したファイルをプッシュします。

上記の手順が完了したら、修正した内容を元に PR を作成して提出できます。
