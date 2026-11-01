# 為 Lophine 貢獻程式碼

[English](./CONTRIBUTING_EN.md) | [中文（简体）](./CONTRIBUTING.md) | **中文（繁體）** | [日本語](./CONTRIBUTING_JP.md)

感謝你參與 Lophine。請在 PR 說明問題、實作方式與驗證結果，讓維護者能理解變更及其維護成本。使用程式碼代理時，也需遵循根目錄的 [AGENTS.md](../AGENTS.md)。

## 開發環境與首次建置

- 安裝 Git 與 JDK 25，使用儲存庫內的 Gradle Wrapper。目前編譯工具鏈與 CI 都使用 Java 25。
- Minecraft 版本、Folia 基準與外掛版本以 `gradle.properties`、`settings.gradle.kts` 和模組建置設定為準。
- Windows 下請啟用系統與 Git 的長路徑支援，參見 [Windows 文件](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)與 [Git for Windows FAQ](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)。
- 建議使用個人帳號 Fork，在以目標分支為基礎的工作分支上開發。先檢查 `git status`，保留既有修改。

以下指令都在主儲存庫根目錄執行。範例使用 PowerShell；Linux/macOS 請將 `.\gradlew.bat` 換成 `./gradlew`。

```powershell
java -version
git --version
.\gradlew.bat applyAllPatches --console=plain --stacktrace --no-daemon
.\gradlew.bat :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat createPaperclipJar --console=plain --no-daemon
```

Paperclip JAR 輸出至 `lophine-server/build/libs/`。`applyAllPatches` 準備上游原始碼並套用各層修補檔；首次執行需要下載相依套件。執行前先儲存或匯出既有的產生原始碼修改，套用修補檔可能重新建立這些目錄。

## 找到正確的修改位置

Lophine 使用 ComfreyWeight 的多層修補流程，以 Folia 為基礎，並繼承 Paper 與 Minecraft 原始碼。直接維護的檔案和套用修補檔後產生的檔案需要分別處理。

| 修改範圍 | 編輯位置 | 主儲存庫儲存位置 |
| --- | --- | --- |
| Lophine 自有實作、設定、資源及測試 | `lophine-server/src/main/`、`lophine-server/src/test/` | 直接提交檔案 |
| Lophine 新增 API 及測試 | `lophine-api/src/main/`、`lophine-api/src/test/` | 直接提交檔案 |
| Minecraft Java | `lophine-server/src/minecraft/java/` | `lophine-server/minecraft-patches/sources/` 或 `features/` |
| Minecraft 原版資源 | `lophine-server/src/minecraft/resources/` | `lophine-server/minecraft-patches/resources/` |
| Paper 伺服器原始碼、資源及測試 | `paper-server/` | `lophine-server/paper-patches/files/` 或 `features/` |
| 既有 Paper/Bukkit API | `paper-api/` | `lophine-api/paper-patches/files/` 或 `features/` |
| Folia 自有伺服器或 API 檔案 | `folia-server/`、`folia-api/` | 對應模組的 `folia-patches/`，依目前設定決定 |
| 模組建置腳本 | `lophine-*/build.gradle.kts` | 對應的 `build.gradle.kts.patch` |

主儲存庫通常忽略 `paper-*`、`folia-*`、Minecraft 原始碼和套用後的模組建置腳本。只修改這些檔案不會形成持久變更，必須匯出所屬修補檔。Lophine 自有的新類別與資源應放在直接維護的目錄，不需要產生修補檔。

產生的原始碼有獨立 Git 歷史。在目標目錄執行 `git rev-parse --show-toplevel`、`git status` 和 `git log --oneline`，確認操作的是哪個儲存庫。`base`、`file` 等參照屬於該修補層：逐檔修改彙入 File Patches 提交，後續功能提交分別匯出。不能將 `base` 後的所有提交都視為獨立功能修補檔。

## 選擇修補類型與任務

修改既有功能時維護所屬修補檔。小範圍的上游修改可用逐檔修補；需要獨立說明和維護的完整功能可用 feature patch。Minecraft 原版資源使用資源修補。功能重建任務也可能執行逐檔修補相依任務，請檢查所有匯出結果。

以下完整任務名稱從主儲存庫根目錄呼叫：

| 修改層 | 收集逐檔修改 | 匯出逐檔修補檔 | 匯出功能修補檔 |
| --- | --- | --- | --- |
| Minecraft Java | `:lophine-server:fixupMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftSourcePatches` | `:lophine-server:rebuildMinecraftFeaturePatches` |
| Minecraft 資源 | `:lophine-server:fixupMinecraftResourcePatches` | `:lophine-server:rebuildMinecraftResourcePatches` | — |
| Paper 伺服器 | `:lophine-server:fixupPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFilePatches` | `:lophine-server:rebuildPaperServerFeaturePatches` |
| Paper API | `:fixupPaperApiFilePatches` | `:rebuildPaperApiFilePatches` | `:rebuildPaperApiFeaturePatches` |
| Folia 伺服器 | `:lophine-server:fixupFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFilePatches` | `:lophine-server:rebuildFoliaServerFeaturePatches` |
| Folia API | `:fixupFoliaApiFilePatches` | `:rebuildFoliaApiFilePatches` | `:rebuildFoliaApiFeaturePatches` |

模組建置腳本以 `:rebuildFoliaSingleFilePatches` 匯出。名稱對應根建置註冊的 Folia 上游，不能依模組名推導成 `rebuildLophineSingleFilePatches`。更多任務請查看：

```powershell
.\gradlew.bat tasks --all :lophine-server:tasks --all --console=plain
```

### 直接維護的 Lophine 檔案

編輯與驗證後，在主儲存庫提交選定檔案即可。新增檔案需正常加入 Git 追蹤。舊指南「不要提交新增檔案」的說明不適用於這些目錄，也不需要執行 API 的 `fixup` 任務。

### 新增功能修補檔

以 Minecraft 為例，在套用後的原始碼實作並驗證，再於該產生儲存庫建立功能提交，然後從主儲存庫匯出：

```powershell
git -C lophine-server/src/minecraft/java status --short
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit -m "Describe the feature"
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

替換範例路徑，以相對於產生儲存庫的路徑逐項列出檔案。Paper 伺服器或 API 的功能提交在對應產生儲存庫建立，使用任務表中的對應任務。最後在主儲存庫檢查並提交匯出的修補檔及本次直接維護的檔案。

### 修改既有功能修補檔

在完整套用的原始碼 HEAD 上開發與驗證，用 `git log` / `git blame` 找出所屬功能提交。在該產生儲存庫建立 fixup 並合入原提交。將 `<target-hash>` 替換為已確認屬於 Lophine 的功能提交：

```powershell
git -C lophine-server/src/minecraft/java add -- path/to/ChangedClass.java
git -C lophine-server/src/minecraft/java commit --fixup=<target-hash>
git -C lophine-server/src/minecraft/java rebase -i --autosquash '<target-hash>^'
.\gradlew.bat :lophine-server:rebuildMinecraftFeaturePatches --console=plain
```

檢查並儲存 rebase 計畫。遇到衝突時先確認各層修改歸屬，再解決衝突並繼續。有他人的修改或複雜相依關係時，使用隔離副本。修復所屬修補檔，避免追加修補檔來抵銷前面的錯誤。上述 rebase 針對產生儲存庫，不要直接用於主儲存庫。

### 修改逐檔修補檔

編輯目標產生儲存庫中的既有上游檔案，不先建立新的功能提交；依任務表先執行對應 `fixup...`，再執行 `rebuild...`。例如 Minecraft Java：

```powershell
.\gradlew.bat :lophine-server:fixupMinecraftSourcePatches --console=plain
.\gradlew.bat :lophine-server:rebuildMinecraftSourcePatches --console=plain
```

`fixup` 收集該產生儲存庫已追蹤檔案的修改並整理 File Patches 提交，不會代為儲存主儲存庫新增檔案。先隔離不相關的產生原始碼修改；存在後續功能提交時，過程可能需要處理 rebase 衝突。

## 歸屬、程式碼風格與相容性

- Carpet、Scarpet 與公共適配歸入 Carpet 修補檔；AMS、ORG、TIS 依來源放入各自修補檔。同一類別的混合修改也依設定 `section` 和實際實作劃分。先檢查目前檔名，不依賴固定編號。
- 拆分或重排修補檔後，完整產生原始碼應與原版本一致，除非本次明確修改行為。保留後續修補檔內容與必要套用順序。
- 遵循相鄰程式碼風格。上游修改使用有意義的 `// Lophine - ...` 或 `// Lophine start/end - ...` 標記，保留上游註解、作者與授權資訊。複雜功能在修補提交正文解釋原因與維護要點。
- Folia 程式碼需確認實體、區塊和可變狀態的執行緒歸屬，檢查跨區域排程、卸載、移除及停服過程，不能僅以「非同步執行」作為執行緒安全依據。
- 保持外掛事件、API 契約、持久化格式和預設設定相容。效能修改應說明機制、情境與測量結果，不能將推測寫成已測收益。

## 設定與本地化

使用現有 `@ConfigClassInfo`、`@ConfigInfo` 和共享 `ConfigPaths`，檢查讀取、預設值、執行階段修改、儲存及舊路徑遷移。註解重新整理應保留使用者值。Carpet 擴充沿用 `carpet` 層級與對應來源的 section。

設定名稱或說明變更時，同步更新 `lophine-server/src/main/resources/assets/lophine/lang/` 的 `en_us.json`、`zh_cn.json`、`zh_tw.json` 和受影響語言，保留格式預留位置：

```powershell
.\gradlew.bat verifyLangFiles --console=plain
```

該任務檢查 JSON、字串值和相對於英文的未知鍵；缺少譯文會報告並回退至英文，成功不代表翻譯完整。`sortLangKeys` 會重寫語言檔案順序，僅於需要排序時執行，並檢查所有修改。

## 驗證與提交 PR

依修改範圍選擇檢查，純文件變更不必編譯伺服器：

```powershell
git diff --check
.\gradlew.bat :lophine-api:compileJava :lophine-server:compileJava --console=plain --no-daemon
.\gradlew.bat :lophine-server:test --tests 'package.RelevantTest' --console=plain --no-daemon
```

測試篩選器需替換為實際測試。修補變更應在隔離副本或確認無未儲存修改的產生樹中重新執行 `applyAllPatches`；交付伺服器時再建置 `createPaperclipJar`。API 變更同時檢查實作與呼叫端，協定或外掛相容性需驗證對應客戶端／外掛的實際互動。

`runDevServer` 使用原始碼集輸出，Java 修改後需重新編譯與啟動。使用獨立測試目錄和明確設定，不使用正式世界；執行者自行閱讀 EULA 並決定是否接受。選用測試外掛透過 `test-plugin.settings.gradle.kts` 啟用。

PR 前檢查主儲存庫及產生儲存庫差異，確認原始碼已匯出，不夾帶快取、建置產物、執行世界或無關修改。描述問題及觸發條件、最終行為、相容性或執行緒影響、實際檢查和未驗證範圍。編譯通過不能取代真實客戶端驗證。貢獻者應理解並負責程式碼與說明；如使用 AI 輔助，請如實說明有意義的參與方式，並複核輸出。
