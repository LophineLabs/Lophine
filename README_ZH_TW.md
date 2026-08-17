<div align="center">
  <img src="./public/image/lophine/lophine3.png" alt="Lophine Logo" width="300">

# Lophine

*Lophine 是一個基於 Folia 的 fork，具備許多好用的最佳化與可設定的原版特性，目標是在 Folia 上實現更多生電向的內容（完整生電支援請使用 Fabric）*

![Created At](https://img.shields.io/github/created-at/LophineLabs/Lophine?style=for-the-badge&color=blue)
[![License](https://img.shields.io/github/license/LophineLabs/Lophine?style=for-the-badge&color=green)](LICENSE.md)
[![Issues](https://img.shields.io/github/issues/LophineLabs/Lophine?style=for-the-badge&color=orange)](https://github.com/LophineLabs/Lophine/issues)

![Commit Activity](https://img.shields.io/github/commit-activity/w/LophineLabs/Lophine?style=for-the-badge&color=purple)
![CodeFactor Grade](https://img.shields.io/codefactor/grade/github/LophineLabs/Lophine?style=for-the-badge&color=yellow)
![GitHub all releases](https://img.shields.io/github/downloads/LophineLabs/Lophine/total?style=for-the-badge&color=red)

![Repo contributors](https://img.shields.io/github/contributors/LophineLabs/Lophine?style=for-the-badge&color=brightgreen)

[English](./README_EN.md) | [中文（簡體）](./README.md) | **中文（繁體）**
</div>

---

> **原始來源與鏡像說明：** GitHub 是 Lophine 的官方上游與唯一權威來源。[CNB](https://cnb.cool/LophineLabs/Lophine) 會從 GitHub 自動同步，並針對對應提交獨立建置及發布 CNB Releases，服務中國大陸使用者。Issue、Pull Request 與 Discussions 統一在 GitHub 進行。

## ✨ 核心特性

- **可設定的原版特性** - 彈性調整遊戲機制，以符合不同伺服器的需求
- **Tpsbar 支援** - 即時顯示伺服器 TPS 狀態
- **Folia Bug 修復** - 針對 Folia 已知問題的專項修復
- **多種存檔格式支援** - 支援 b_linear（linear 重新實作）存檔格式
- **生電功能強化** - 在 Folia 上實現更多生電內容（完整生電請使用 Fabric）
- 🛠**更多實用功能** - 持續新增有用的伺服器功能

### 額外啟動參數

- morninggloryclip.useMojangSource 強制伺服器端使用 Mojang 官方來源下載檔案
- morninggloryclip.enable.mixin 為伺服器插件啟用 mixin 支援

## 📥 下載

### 穩定版本

- 中國大陸（CNB 鏡像）：[CNB Releases](https://cnb.cool/LophineLabs/Lophine/-/releases) · [CNB 鏡像倉庫](https://cnb.cool/LophineLabs/Lophine)
- 全球 / 官方（GitHub）：[GitHub Releases](https://github.com/LophineLabs/Lophine/releases) · [GitHub 官方倉庫](https://github.com/LophineLabs/Lophine)

CNB 與 GitHub 的對應版本基於相同的 Git Commit。中國大陸使用者可優先透過 CNB 下載或 Clone；程式碼貢獻與問題回報請前往 GitHub。

### 開發版本
如果你想搶先體驗最新功能，可以照下面的步驟自行建置。

### 建置步驟

```bash
# Clone 這個專案
git clone https://github.com/LophineLabs/Lophine.git
cd Lophine

# 套用 patch 並建置 Paperclip JAR
./gradlew applyAllPatches && ./gradlew createPaperclipJar
```

建置完成後，你可以在 `lophine-server/build/libs` 目錄中找到產生的 JAR 檔案。

## 🔌 API 使用

### Gradle 設定

```kotlin
repositories {
    maven {
        url = "https://repo.bacteriawa.com/repository/maven-public/"
    }
}

dependencies {
    compileOnly("fun.bm.lophine:lophine-api:26.2.build.+")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
```

### Maven 設定

```xml
<repositories>
    <repository>
        <id>repository</id>
        <url>https://repo.bacteriawa.com/repository/maven-public/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>fun.bm.lophine</groupId>
        <artifactId>lophine-api</artifactId>
        <version>[26.2.build,)</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## 💬 社群與支援

> 如果你對這個專案有興趣，或有任何問題，歡迎隨時來問我們。

### 加入我們的社群

- **QQ 群**: [1020403749](https://qm.qq.com/cgi-bin/qm/qr?k=y_MA9UaN7PM9e9J1LIs9Eea3LK8C0h6J&jump_from=webapi&authKey=ap5f8MlbeezXYtnmpnT5ZOFljDuOyV6OAb2PIcViQ+Ilr60Ycq63FDDTsJOZDYtj)
- **Discord**: [點我加入](https://discord.gg/UXSgPZczcy)

### 取得協助

- [提交 Issue](https://github.com/LophineLabs/Lophine/issues)
- [GitHub Discussions](https://github.com/LophineLabs/Lophine/discussions)
- [專案文件](./docs/)

## 🐛 問題回報

當你遇到任何問題時，歡迎來問我們，我們會盡力協助解決。請記得：

- **清楚描述問題** - 詳細說明問題的具體狀況
- **提供完整記錄檔** - 附上錯誤記錄與相關設定資訊
- **環境資訊** - 說明伺服器版本、插件清單等環境細節
- **重現步驟** - 如果可以，請提供重現問題的具體步驟

## 🤝 貢獻程式碼

我們歡迎社群貢獻！詳細的貢獻指南請參考：

- [貢獻指南（繁體中文）](./docs/CONTRIBUTING_ZH_TW.md)
- [Contributing Guide (English)](./docs/CONTRIBUTING_EN.md)
- [贡献指南 (简体中文)](./docs/CONTRIBUTING.md)
- [貢献ガイド（日本語）](./docs/CONTRIBUTING_JP.md)

## 📊 專案統計

### BStats 資料

![bStats](https://bstats.org/signatures/server-implementation/Lophine.svg "bStats")

## ⭐ 給我們一個 Star 吧！

> 你給的每一個免費 ⭐Star，都是我們前進的動力。
