<div align="center">
  <img src="./public/image/lophine/lophine3.png" alt="Lophine Logo" width="300">
  
  # Lophine
  
  *Lophine is a Luminol fork with many useful optimizations and configurable vanilla features, aims to provide more function for survival-usable circuit on folia (Please note that Fabric should be used for complete survival-usable)*
  
  ![Created At](https://img.shields.io/github/created-at/LophineCraft/Lophine?style=for-the-badge&color=blue)
  [![License](https://img.shields.io/github/license/LophineCraft/Lophine?style=for-the-badge&color=green)](LICENSE.md)
  [![Issues](https://img.shields.io/github/issues/LophineCraft/Lophine?style=for-the-badge&color=orange)](https://github.com/LuminolMC/Lophine/issues)
  
  ![Commit Activity](https://img.shields.io/github/commit-activity/w/LophineCraft/Lophine?style=for-the-badge&color=purple)
  ![CodeFactor Grade](https://img.shields.io/codefactor/grade/github/LophineCraft/Lophine?style=for-the-badge&color=yellow)
  ![GitHub all releases](https://img.shields.io/github/downloads/LophineCraft/Lophine/total?style=for-the-badge&color=red)
  
  ![Repo contributors](https://img.shields.io/github/contributors/LophineCraft/Lophine?style=for-the-badge&color=brightgreen)
  
  **English** | [中文](./README.md)
</div>

---

## ✨ Core Features

- 🔧 **Configurable Vanilla Features** - Flexibly adjust game mechanics to suit different server needs
- 📊 **Tpsbar Support** - Real-time TPS status display
- 🐛 **Folia Bug Fixes** - Targeted fixes for known Folia issues
- 💾 **Multiple World Format Support** - Support for linear and b_linear (linear reimplementation) world formats
- 🔬 **Redstone Enhancement** - More redstone functionality on Folia (use Fabric for complete redstone features)
- 🛠️ **More Useful Functions** - Continuously adding useful server features

## 📥 Download

### Stable Releases
All release versions can be found on the [Releases](https://github.com/LuminolMC/Lophine/releases) page.

### Development Builds
If you want to experience the latest features, you can build it yourself following the steps below.

### Build Steps

```bash
# Clone the project
git clone https://github.com/LuminolMC/Lophine.git
cd Lophine

# Apply patches and build Paperclip JAR
./gradlew applyAllPatches && ./gradlew createPaperclipJar
```

After building, you can find the generated JAR file in the `lophine-server/build/libs` directory.

## 🔌 API Usage

### Gradle Configuration

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

### Maven Configuration

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
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## 💬 Community & Support

> If you're interested in this project or have any questions, feel free to ask us.

### Join Our Community

- **QQ Group**: [1015048616](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=QML5kIVsniPi1PlZvnjHQT_02EHsZ5Jc&authKey=%2FTCJsZC7JFQ9sxAroPCKuYnlV57Z5fyqp36ewXZk3Sn4iJ9p4MB1JKdc%2FFcX3HOM&noverify=0&group_code=1015048616)
- **QQ Channel**: [Join Here](https://pd.qq.com/s/eq9krf9j)
- **Telegram**: [Join Here](https://t.me/LuminolMinecraft)
- **Discord**: [Join Here](https://discord.gg/Qd7m3V6eDx)

### Get Help

- 📋 [Submit Issues](https://github.com/LuminolMC/Lophine/issues)
- 💬 [GitHub Discussions](https://github.com/LuminolMC/Lophine/discussions)
- 📖 [Project Documentation](./docs/)

## 🐛 Bug Reports

When you encounter any issues, please ask us and we'll do our best to resolve them. Please remember to:

- 📝 **Describe the problem clearly** - Provide detailed information about the specific issue
- 📋 **Provide complete logs** - Include error logs and relevant configuration information
- 🔍 **Environment details** - Specify server version, plugin list, and other environment details
- 🔄 **Reproduction steps** - If possible, provide specific steps to reproduce the issue

## 🤝 Contributing

We welcome community contributions! For detailed contribution guidelines, please see:

- 📖 [Contributing Guide (English)](./docs/CONTRIBUTING_EN.md)
- 📖 [贡献指南 (中文)](./docs/CONTRIBUTING.md)

## 📊 Project Statistics

### BStats Data

![bStats](https://bstats.org/signatures/server-implementation/Lophine.svg "bStats")

## ⭐ Give Us a Star!

> Every free ⭐Star you give is the motivation for our every step forward.
