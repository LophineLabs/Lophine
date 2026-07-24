Contributing to Lophine
=======================

**English** | [中文](./CONTRIBUTING.md)

We're glad that you want to contribute to our project!  
In general, our review of pull requests is very lenient.  
And if you can follow the rules below, we can complete the review faster.

## Please fork using your personal account

We regularly merge existing PRs.  
If there are some small problems, we'll help you solve them by editing your PR.

But, if your PR is from an organization, we can NOT edit your PR, so we must merge your PR manually.

So, don't use organization accounts for fork!

See also [This issue](https://github.com/isaacs/github/issues/1681), and then you'll know why we can't edit PRs from organizations.

## Development Environment

Before coding, you need these pieces of software / tools as Dev Environment.

- `git`
- `JDK 25 or higher`

PS: You need to enable long path support in your System and Git before start, some of the platform's resolution here.

[`Windows`](https://learn.microsoft.com/windows/win32/fileio/maximum-file-path-limitation)
[`Git for Windows`](https://gitforwindows.org/faq.html#i-get-errors-trying-to-check-out-files-with-long-path-names)

## Understanding "Patches"

Lophine uses as the same patching system as Paper,  
and has been divided into two directories for the purpose of modifying different parts of it:

- `lophine-api` - Modifications to `Folia-API` / `Paper-API` / `Spigot-API` / `Bukkit-API`.
- `lophine-server` - Modifications to Minecraft Vanilla Server's source logic.

The patching system is based on git, and you can learn about it at here: <https://git-scm.com/docs/gittutorial>

If you have forked the main repository, then you should follow the steps below:

1. Clone your repository to local
2. Run Gradle's `applyAllPatches` task in your IDE or terminal (You can run `./gradlew applyAllPatches` directly in terminal.)
3. After performing the operation, the following directory pairs should exist in the root directory of the warehouse: `lophine-api` and `lophine-server` , `folia-api` and `folia-server` , `paper-api` and `paper-server` (Referred to `*-api` and `*-server` as below)
4. Enter `*-api` and `*-server` directory to carry out modifications.

The following is the simple description of the aforementioned folders, detailed description can be referred to [here](https://github.com/Toffikk/paperweight-examples/blob/18241979c88068d5b061d95ad69c98ecb201c246/README.md):

1. API part

- `lophine-api` : Modifications to the new API
- `folia-api` : Modifications to Folia API should be carried out in this folder
- `paper-api` : Modifications to Paper API/Spigot API/Bukkit API should be carried out in this folder

2. Server part

- `lophine-server` : Changes and new files to the Minecraft vanilla server should be made in this folder
- `folia-server` : Changes to folia-server should be made in this folder
- `paper-server` : Modifications to the server logic for paper should be made in this folder

BTW, `*-api` and `*-server` and are not normal git repositories.

- Before applying patches, the base will point to unmodified source code.
- Every commit after the base is a patch.
- Only commits after the last commit of Folia will be considered as Lophine patches.

## Adding new patches

It's very easy to add patches by following the steps below:

1. Modify the code of `*-api` and `*-server`
2. Add these changes to the local git repository (For example, `git add .`)
3. Commit these changes using `git commit -m <Commit Message>` (PS: do not commit new-created files)
4. Run Gradle's task `fixupPaperApiFilePatches` to generate newly created files to new patches (PS: do not commit again before you run this task)
5. Run Gradle's task `rebuildAllServerPatches` to convert your commits to a new patch
6. Push your patches to your repository

After pushing, you can open a PR to submit your patches.

## Modifying patches

You can modify an existing patch by following the steps below:

1. Modify code at HEAD
2. Run `git commit -a --fixup <hash>` in your terminal to make a fix-up commit (PS: do not commit changes of lophine-created files)
    - If you want to edit the commit message, replace `--fixup` with `--squash`
3. Run `git rebase -i --autosquash base` to rebase automatically, then just type `:q` to close the confirm page
4. Run Gradle's task `fixupPaperApiFilePatches` to regenerate lophine-created files to patches (PS: do not commit again before you run this task)
5. Run Gradle's task `rebuildAllServerPatches` to modify existing patches
6. Push and PR again

## Providing localized comment support for configuration entries

1. Create or modify the corresponding language file under the `lophine-server/src/main/resources/assets/lophine/lang` directory to add localized comments;
    - The file name should follow the format listed on the `https://minecraft.wiki/w/Language` page, such as `en_us`, `zh_cn`, `zh_hk`, `zh_tw`. The file format is `json`;
2. Run the Gradle task `sortLangKeys` to re-sort the keys in your language file;
3. Commit your changes using `git commit -m <Commit Message>`;
4. Push your generated patch files to your repository.

After pushing, you can open a PR to submit your patches.
