<div align="center">
  <img src="./public/image/lophine/lophine3.png" alt="Lophine Logo" width="300">
  
  # Lophine
  
  *Lophine 是一个基于Luminol的分支，具有许多有用的优化和可配置的原版特性，目标是在Folia上实现更多生电的内容（请注意，完整生电请使用Fabric）*
  
  ![Created At](https://img.shields.io/github/created-at/LophineLabs/Lophine?style=for-the-badge&color=blue)
  [![License](https://img.shields.io/github/license/LophineLabs/Lophine?style=for-the-badge&color=green)](LICENSE.md)
  [![Issues](https://img.shields.io/github/issues/LophineLabs/Lophine?style=for-the-badge&color=orange)](https://github.com/LophineLabs/Lophine/issues)
  
  ![Commit Activity](https://img.shields.io/github/commit-activity/w/LophineLabs/Lophine?style=for-the-badge&color=purple)
  ![CodeFactor Grade](https://img.shields.io/codefactor/grade/github/LophineLabs/Lophine?style=for-the-badge&color=yellow)
  ![GitHub all releases](https://img.shields.io/github/downloads/LophineLabs/Lophine/total?style=for-the-badge&color=red)
  
  ![Repo contributors](https://img.shields.io/github/contributors/LophineLabs/Lophine?style=for-the-badge&color=brightgreen)
  
  [English](./README_EN.md) | **中文**
</div>

---

## ✨ 核心特性

- 🔧 **可配置的原版特性** - 灵活调整游戏机制以适应不同服务器需求
- 📊 **Tpsbar 支持** - 实时显示服务器 TPS 状态
- 🐛 **Folia Bug 修复** - 针对 Folia 已知问题的专项修复
- 💾 **多存档格式支持** - 支持 linear 和 b_linear（linear 重新实现）存档格式
- 🔬 **生电功能增强** - 在 Folia 上实现更多生电内容（完整生电请使用 Fabric）
- 🛠️ **更多实用功能** - 持续添加有用的服务器功能

## 📥 下载

### 稳定版本
所有发布版本都可以在 [Releases](https://github.com/LophineLabs/Lophine/releases) 页面找到。

### 开发版本
如果您想体验最新功能，可以通过以下步骤自行构建。

### 构建步骤

```bash
# 克隆项目
git clone https://github.com/LophineLabs/Lophine.git
cd Lophine

# 应用补丁并构建 Paperclip JAR
./gradlew applyAllPatches && ./gradlew createPaperclipJar
```

构建完成后，您可以在 `lophine-server/build/libs` 目录中找到生成的 JAR 文件。

## 🔌 API 使用

### Gradle 配置

```kotlin
repositories {
    maven {
        url = "https://repo.bacteriawa.com/repository/maven-public/"
    }
}

dependencies {
    compileOnly("fun.bm.lophine:lophine-api:$VERSION")
}
```

### Maven 配置

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
        <artifactId>luminol-api</artifactId>
        <version>$VERSION</version>
    </dependency>
</dependencies>
```

## 💬 社区与支持

> 如果您对这个项目感兴趣或有任何问题，请随时向我们提问。

### 加入我们的社区

- **QQ群**: [1020403749](https://qm.qq.com/cgi-bin/qm/qr?k=y_MA9UaN7PM9e9J1LIs9Eea3LK8C0h6J&jump_from=webapi&authKey=ap5f8MlbeezXYtnmpnT5ZOFljDuOyV6OAb2PIcViQ+Ilr60Ycq63FDDTsJOZDYtj)
- **Discord**: [点击加入](https://discord.gg/UXSgPZczcy)

### 获取帮助

- 📋 [提交 Issue](https://github.com/LophineLabs/Lophine/issues)
- 💬 [GitHub Discussions](https://github.com/LophineLabs/Lophine/discussions)
- 📖 [项目文档](./docs/)

## 🐛 问题反馈

当您遇到任何问题时，请向我们提问，我们将尽力解决。请记得：

- 📝 **清楚描述问题** - 详细说明问题的具体表现
- 📋 **提供完整日志** - 包含错误日志和相关配置信息
- 🔍 **环境信息** - 说明服务器版本、插件列表等环境详情
- 🔄 **复现步骤** - 如果可能，请提供问题复现的具体步骤

## 🤝 贡献代码

我们欢迎社区贡献！详细的贡献指南请查看：

- 📖 [贡献指南 (中文)](./docs/CONTRIBUTING.md)
- 📖 [Contributing Guide (English)](./docs/CONTRIBUTING_EN.md)

## 📊 项目统计

### BStats 数据

![bStats](https://bstats.org/signatures/server-implementation/Lophine.svg "bStats")

## ⭐ 请给我们一个 Star！

> 你的每一个免费的 ⭐Star 就是我们每一个前进的动力。
