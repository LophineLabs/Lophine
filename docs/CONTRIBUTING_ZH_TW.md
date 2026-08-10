為 Lophine 貢獻程式碼
===============

[English](./CONTRIBUTING_EN.md) | [中文（簡體）](./CONTRIBUTING.md) | **中文（繁體）** | [日本語](./CONTRIBUTING_JP.md)

很高興你願意為這個專案貢獻！一般來說，我們對 PR 的審核標準相當寬鬆；
如果你可以照著下面的規則來，我們就能更快完成審核。

## 請使用個人帳號進行 Fork

我們會定期嘗試合併現有的 PR，如果內容有一些小問題，我們會直接幫你修改後合併。

但如果你的 PR 是用組織（organization）帳號 fork 出來的，我們就沒辦法直接修改你的 PR，只能先關閉、再手動合併一次。

所以，請不要用組織帳號來 fork 這個專案！

你可以參考[這個 Issue](https://github.com/isaacs/github/issues/1681)，了解為什麼我們沒辦法直接修改組織帳號的 PR。

## 開發環境

在開始寫程式之前，你需要先準備好以下開發工具與環境：

- `git`
- `JDK 25` 或更新版本

特別提醒：動手之前，請先在你的作業系統與 Git 裡啟用長路徑（long path）支援，以下是部分平台的設定說明。

[`Windows`](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)
[`Git for Windows`](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)

## 認識 Patch（補丁）系統

Lophine 沿用了和 Folia 相同的 patch 系統，並依照修改的部分分成了兩個目錄：

- `lophine-api` - 基於 `Folia-API` / `Paper-API` / `Spigot-API` / `Bukkit-API` 所做出的修改。
- `lophine-server` - 針對 Minecraft 原版伺服器既有邏輯所做的修改。

這套 patch 系統是以 git 為基礎運作的，你可以在這裡了解 git 的基本操作：<https://git-scm.com/docs/gittutorial>

如果你已經 fork 了主要的 repository，接下來請照以下步驟操作：

1. 把你 fork 出來的 repository clone 到本機；
2. 在你的 IDE 或終端機（terminal）裡執行 Gradle 的 `applyAllPatches` 任務；如果是在終端機裡，可以直接執行 `./gradlew applyAllPatches`；
3. 執行完成後，repository  的根目錄下應該會出現以下幾組目錄： `lophine-api` 與 `lophine-server`、`folia-api` 與 `folia-server` 以及 `paper-api` 與 `paper-server`（以下統稱為 `*-api` 與 `*-server`）；
4. 進入 repository 根目錄下對應的 `*-api` 與 `*-server` 資料夾，在裡面進行修改。

以下是對上述各個資料夾的簡單說明，更詳細的說明可以參考[這裡](https://github.com/Toffikk/paperweight-examples/blob/18241979c88068d5b061d95ad69c98ecb201c246/README.md)：

1. API 部分

- `lophine-api`：新增 API 的修改請放在這裡
- `folia-api`：針對 Folia API 的修改請放在這個資料夾
- `paper-api`：對於 Paper API / Spigot API / Bukkit API 的修改請放在這個資料夾

2. Server 部分

- `lophine-server`：針對 Minecraft 原版伺服器的修改、以及新增的檔案，都請放在這個資料夾
- `folia-server`：針對 folia-server 的修改請放在這個資料夾
- `paper-server`：根據 Paper 伺服器邏輯的修改請放在這個資料夾

順帶一提，repository 根目錄下的 `*-api` 與 `*-server` 並不是一般的 git repository：

- 在套用 patch 之前，base（基準點）指向的是未經修改的原始程式碼；
- base 之後的每一個 commit，都會被視為一個 patch；
- 只有在 Folia 最後一個 commit 之後的 commit，才會被視為是 Lophine 的 patch。

## 新增 Patch

依照以下步驟新增一個 patch 非常簡單：

1. 在 `*-api` 與 `*-server` 裡進行修改；
2. 用 git 把你的修改加入暫存區，例如執行 `git add .`（請不要把新建立的檔案一併加入）；
3. 執行 `git commit -m <提交訊息>` 進行 commit；
4. 執行 Gradle 任務 `fixupPaperApiFilePatches`，用於新建立的檔案產生對應的 patch 檔（注意：執行完之前先不要 commit）；
5. 執行 Gradle 任務 `rebuildAllServerPatches`，用於把你的 commit 轉換成一個 patch；
6. 把產生出來的 patch 檔 push 上去。

完成以上步驟後，你就可以拿這些 patch 檔開 PR 提交了。

## 修改 Patch

你可以用以下方法來修改一個既有 patch 的內容：

1. 直接在 HEAD 上進行修改；
2. 執行 `git commit -a --fixup <hash>` ，做一個 fixup commit（請不要把 Lophine 新建立的檔案的修改一併 commit 進去）；
   - 如果你想順便修改 commit 訊息，可以用 `--squash` 取代 `--fixup`。
3. 執行 `git rebase -i --autosquash base` ，進行自動 rebase，接著只要輸入 `:q` 關掉確認頁面即可；
4. 執行 Gradle 任務 `fixupPaperApiFilePatches`，用於重新產生 Lophine 新建立檔案的 patch（注意：執行完之前先不要 commit）；
5. 執行 Gradle 任務 `rebuildAllServerPatches`，把修改套用到既有的 patch 上；
6. 把修改後的 patch 重新 push 回去，再開一次 PR。

## 為設定項目提供本地化的注解支援

1. 在 `lophine-server/src/main/resources/assets/lophine/lang` 目錄下新增或修改對應語言的檔案，加入本地化的注解；
   - 檔名請依照 <https://minecraft.wiki/w/Language> 頁面上列出的語言代碼來命名，例如 `en_us`、`zh_cn`、`zh_hk`、`zh_tw`，檔案格式為 `json`；
2. 執行 Gradle 任務 `sortLangKeys`，把你修改的語言檔內容重新排序；
3. 執行 `git commit -m <提交訊息>` 進行 commit；
4. 把你修改的檔案 push 上去。

完成以上步驟後，你就可以拿修改後的內容開 PR 提交了。
