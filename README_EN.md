<div align="center">
  <img src="./public/image/lophine/lophine3.png" alt="Lophine Logo" width="300">
  
  # Lophine
  
  *Lophine is a Folia fork with many useful optimizations and configurable vanilla features, aims to provide more function for survival-usable circuit on folia (Please note that Fabric should be used for complete survival-usable)*
  
  ![Created At](https://img.shields.io/github/created-at/LophineLabs/Lophine?style=for-the-badge&color=blue)
  [![License](https://img.shields.io/github/license/LophineLabs/Lophine?style=for-the-badge&color=green)](LICENSE.md)
  [![Issues](https://img.shields.io/github/issues/LophineLabs/Lophine?style=for-the-badge&color=orange)](https://github.com/LophineLabs/Lophine/issues)
  
  ![Commit Activity](https://img.shields.io/github/commit-activity/w/LophineLabs/Lophine?style=for-the-badge&color=purple)
  ![CodeFactor Grade](https://img.shields.io/codefactor/grade/github/LophineLabs/Lophine?style=for-the-badge&color=yellow)
  ![GitHub all releases](https://img.shields.io/github/downloads/LophineLabs/Lophine/total?style=for-the-badge&color=red)
  
  ![Repo contributors](https://img.shields.io/github/contributors/LophineLabs/Lophine?style=for-the-badge&color=brightgreen)
  
  **English** | [中文（简体）](./README.md) | [中文（繁體）](./README_ZH_TW.md)
</div>

---

## ✨ Core Features

- 🔧 **Configurable Vanilla Features** - Flexibly adjust game mechanics to suit different server needs
- 📊 **Tpsbar Support** - Real-time TPS status display
- 🐛 **Folia Bug Fixes** - Targeted fixes for known Folia issues
- 💾 **Multiple World Format Support** - Support for linear and b_linear (linear reimplementation) world formats
- 🔬 **Redstone Enhancement** - More redstone functionality on Folia (use Fabric for complete redstone features)
- 🛠️ **More Useful Functions** - Continuously adding useful server features

### Additional Launch Parameters

 - morninggloryclip.useMojangSource - Use Mojang's source for Minecraft Server
 - morninggloryclip.enable.mixin - Enable mixin support for Leaves Plugin

## 📥 Download

### Stable Releases
All release versions can be found on the [Releases](https://github.com/LophineLabs/Lophine/releases) page.

### Development Builds
If you want to experience the latest features, you can build it yourself following the steps below.

### Build Steps

```bash
# Clone the project
git clone https://github.com/LophineLabs/Lophine.git
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
    compileOnly("fun.bm.lophine:lophine-api:26.2.build.+")
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(25))
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
        <artifactId>lophine-api</artifactId>
        <version>[26.2.build,)</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## 💬 Community & Support

> If you're interested in this project or have any questions, feel free to ask us.

### Join Our Community

- **QQ Group**: [1020403749](https://qm.qq.com/cgi-bin/qm/qr?k=y_MA9UaN7PM9e9J1LIs9Eea3LK8C0h6J&jump_from=webapi&authKey=ap5f8MlbeezXYtnmpnT5ZOFljDuOyV6OAb2PIcViQ+Ilr60Ycq63FDDTsJOZDYtj)
- **Discord**: [Join Here](https://discord.gg/UXSgPZczcy)

### Get Help

- 📋 [Submit Issues](https://github.com/LophineLabs/Lophine/issues)
- 💬 [GitHub Discussions](https://github.com/LophineLabs/Lophine/discussions)
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
