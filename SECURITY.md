# Security Policy / 安全政策

[简体中文](#简体中文) | [English](#english)

## 简体中文

### 支持的版本

安全问题的维护范围遵循 [#33 的维护准则](https://github.com/LophineLabs/Lophine/issues/33)及[版本维护计划 #107](https://github.com/LophineLabs/Lophine/issues/107)：

| 版本 | 安全修复支持 |
| --- | --- |
| 当前最新维护版本的最新构建，包括项目维护中的正式版或预发布版 | 支持 |
| 维护计划中明确列出且尚未到期的延长支持版本（LST） | 按维护计划处理 |
| 已过期、未列入维护计划的旧版或停止维护的分支 | 不提供维护 |

请提供完整构建号或 Git 提交，不要仅填写 Minecraft 版本。存在旧分支、旧构建下载或上游修复，不代表 Lophine 承诺为该版本回移补丁。最新维护范围以项目发布及维护公告为准。

### 私下报告安全问题

远程代码执行、权限或认证绕过、敏感信息泄露、可被利用的服务端/客户端崩溃、拒绝服务及非预期物品复制等问题，请先私下报告。不要在公开 Issue、PR、Discussions 或社区频道发布利用代码、攻击数据包、敏感日志或完整复现方法。

加入 README 公布的 [LophineLabs Discord](https://discord.gg/UXSgPZczcy)，联系项目维护者，请其提供私密报告方式。在取得私密渠道前，仅说明希望报告安全问题，不要在公共频道披露技术细节。本项目使用自己的报告渠道；Paper 的漏洞频道不是 Lophine 的报告入口。

私密报告请尽量包括：

- 完整 `/version` 输出、构建号或提交，以及是否为自定义构建。
- Java、操作系统、插件和数据包版本，以及相关配置；涉及 Carpet/AMS/ORG/TIS 时注明规则、权限和启用条件。
- 触发条件、最低所需权限、预期行为、实际影响，以及在独立测试环境中的最小复现方法。
- 已确认受影响的版本、脱敏日志及已知的临时缓解方式；如已测试上游 Folia/Paper，请附上对应版本和结果。

不要提交密码、令牌、玩家个人信息或未经脱敏的生产数据。只在自己拥有或获准使用的测试环境中复现。

### 普通问题与处理范围

一般功能错误、插件兼容性或性能问题可使用 [公开 Issue](https://github.com/LophineLabs/Lophine/issues/new/choose)。无法判断是否涉及可利用漏洞时，先私下联系维护者。有意启用的原版兼容、复制规则或管理功能本身不自动构成安全漏洞；权限绕过或超出设计范围的影响仍应私下报告。

即使无法确认问题来自 Lophine、Folia、Paper、Minecraft 还是第三方插件，也可以先联系维护者，并说明已掌握的证据。若问题属于上游，按其安全政策协调报告；不要公开利用细节来确认归属。

请与维护者协调修复验证和公开披露时间。报告的接收不代表承诺固定修复期限或为停止维护的版本回移补丁。

## English

### Supported versions

Security maintenance follows the [policy in #33](https://github.com/LophineLabs/Lophine/issues/33) and the [version maintenance plan in #107](https://github.com/LophineLabs/Lophine/issues/107):

| Version | Security maintenance |
| --- | --- |
| Latest build of the current maintained version, including a maintained release or pre-release | Supported |
| An explicitly listed extended-support version (LST) whose maintenance period has not expired | As specified in the maintenance plan |
| Expired versions, older versions outside the plan, or discontinued branches | Not maintained |

Provide the complete build number or Git commit, not just the Minecraft version. An existing branch, an available old download, or an upstream fix does not establish a Lophine backport commitment. Refer to current release and maintenance announcements for support status.

### Report security issues privately

Report remote code execution, permission or authentication bypasses, sensitive information disclosure, exploitable server/client crashes, denial of service, and unintended item duplication privately first. Do not publish exploit code, attack packets, sensitive logs, or complete reproduction instructions in public issues, PRs, Discussions, or community channels.

Join the [LophineLabs Discord](https://discord.gg/UXSgPZczcy) listed in the README and contact a project maintainer to arrange a private reporting channel. Until a private channel is established, say only that you want to report a security issue. Lophine uses its own reporting route; Paper's exploit channel is not the reporting destination for Lophine.

Include, where available:

- Complete `/version` output, build number or commit, and whether the build is customized.
- Java, OS, plugin and datapack versions, and relevant configuration. For Carpet/AMS/ORG/TIS, identify the rules, permissions, and enabling conditions.
- Trigger, minimum required privileges, expected behavior, impact, and a minimal reproduction in an isolated test environment.
- Confirmed affected versions, sanitized logs, and known temporary mitigations. If tested on upstream Folia/Paper, provide the exact versions and results.

Exclude passwords, tokens, player personal information, and unsanitized production data. Reproduce only in environments you own or have permission to test.

### Ordinary bugs and scope

Use [public issues](https://github.com/LophineLabs/Lophine/issues/new/choose) for ordinary functional, plugin compatibility, or performance problems. Contact maintainers privately first if exploitability is uncertain. Intentionally enabled vanilla compatibility, duplication rules, or administrative features do not automatically constitute vulnerabilities; permission bypasses or effects beyond their intended scope should still be reported privately.

You can contact maintainers before determining whether a problem originates in Lophine, Folia, Paper, Minecraft, or a third-party plugin. Include the evidence available. Coordinate upstream reports under the relevant project's security policy rather than publishing exploit details to establish attribution.

Coordinate fix verification and public disclosure with maintainers. Receiving a report does not guarantee a fixed resolution deadline or backports to discontinued versions.
