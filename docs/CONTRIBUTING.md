为Lophine贡献代码
===============

[English](./CONTRIBUTING_EN.md) | **中文**

我们很开心您想为我们的项目做出贡献！一般来说，我们对PR的审核是十分宽松的；
但是如果您可以遵守下列的规则，我们可以更快地完成审核。

## 使用个人账户进行 Fork

我们会定期尝试合并已有的 PR，如果有一些小问题，会尝试帮您解决这些问题。

但是如果您使用了组织账号进行 PR，我们就不能对您的 PR 进行修改了。因此我们只能关闭你的PR然后进行手动合并。

所以，请不要使用组织账号进行 Fork！

您可以看看 [这个 Issue](https://github.com/isaacs/github/issues/1681) 来了解一下我们为什么无法修改组织账号的 PR。

## 开发环境

在开始开发之前，您首先需要拥有以下软件作为开发环境：

- `git`
- `JDK 25 或更高版本`

特别提醒：在操作前，您需要启用系统和Git的长路径支持，以下为部分平台的相关描述。

[`Windows`](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)
[`Git for Windows`](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)

## 了解补丁（Patches）

Lophine 使用和 Folia 一样的补丁系统，并为了针对不同部分的修改分成了两个目录：

- `lophine-api` - 对 `Folia-API` / `Paper-API` / `Spigot-API` / `Bukkit-API` 进行的修改。
- `lophine-server` - 对 Minecraft 标准服务器原有逻辑进行的修改。

补丁系统是基于 git 的，你可以在这里了解 git 的基本内容: <https://git-scm.com/docs/gittutorial>

如果你已经 Fork 了主储存库，那么下面你应该这么做：

1. 将你的仓库 clone 到本地；
2. 在你的 IDE 或 终端 内执行 Gradle 的 `applyAllPatches` 任务，如果是在终端内，你可以执行 `./gradlew applyAllPatches`；
3. 在执行操作后，仓库根目录下应该存在以下目录对： `lophine-api` 和 `lophine-server` , `folia-api` 和 `folia-server` ， 以及 `paper-api` 和 `paper-server`（下文称作 `*-api` 和 `*-server` ）；
4. 进入 仓库根目录下的 `*-api` 和 `*-server` 文件夹进行修改。

以下为对上述各个文件夹的简单描述，详细描述可以参考[这里](https://github.com/Toffikk/paperweight-examples/blob/18241979c88068d5b061d95ad69c98ecb201c246/README.md)：

1. API部分

- `lophine-api` ：对新增API的修改
- `folia-api` ：对folia-API的修改应当在此文件夹下进行
- `paper-api` ：对paper-API/spigot-API/bukkit-API的修改应该在此文件夹下进行

2. Server部分

- `lophine-server` ：对Minecraft原版服务器的修改和新增文件应当在此文件夹下进行
- `folia-server` ：对folia-Server的修改应当在此文件夹下进行
- `paper-server` ：对于paper对服务器逻辑的修改应当在此文件夹下进行

顺便一提，仓库根目录下的 `*-api` 和 `*-server` 并不是正常的 git 仓库：

- 在应用补丁前，基点将会指向未被更改的源码
- 在基点后的每一个提交都是一个补丁
- 只有在 Folia 最后一个提交后的提交才会被视为 lophine 补丁

## 增加补丁

按照以下步骤增加一个补丁是非常简单的：

1. 对 `*-api` 和 `*-server` 进行修改；
2. 使用 git 添加你的修改，比如 `git add .`（不要提交新建的文件的修改）；
3. 使用 `git commit -m <提交信息>` 进行提交；
4. 运行 Gradle 任务 `fixupPaperApiFilePatches` 生成新建文件的补丁文件（注意不要提交）；
5. 运行 Gradle 任务 `rebuildAllServerPatches` 将你的提交转化为一个补丁；
6. 将你生成的补丁文件进行推送。

这样做以后，你就可以将你的补丁文件进行 PR 提交。

## 修改补丁

你可以使用以下方法来修改一个补丁的内容：

1. 在 HEAD 上直接进行修改；
2. 使用 `git commit -a --fixup <hash>` 来进行一个更正提交；（不要提交对在lophine新建文件的修改）
    - 如果你想要更改提交信息，你也可以用 `--squash` 来代替 `--fixup`。
3. 使用 `git rebase -i --autosquash base` 来进行自动变基，你只需要输入 `:q` 来关闭确认页面即可；
4. 运行 Gradle 任务 `fixupPaperApiFilePatches` 来修改已被修改的在lophine新建文件的补丁（注意不要提交）；
5. 运行 Gradle 任务 `rebuildAllServerPatches` 来修改已被修改的补丁；
6. 将修改后的补丁 PR 发回储存库。

## 为配置项提供本地化的注释支持

1. 在 `lophine-server/src/main/resources/assets/lophine/lang` 目录下创建或修改相应的语言文件，添加本地化的注释；
    - 文件的名字应当符合 `https://minecraft.wiki/w/Language` 页面下的格式，如 `en_us` `zh_cn` `zh_hk` `zh_tw`，文件以 `json` 为格式类型；
2. 运行 Gradle 任务 `sortLangKeys` 来对你的语言文件内容进行重排序；
3. 使用 `git commit -m <提交信息>` 进行提交；
4. 将你修改的文件进行推送。

这样做以后，你就可以将你的修改进行 PR 提交。
