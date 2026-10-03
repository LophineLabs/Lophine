# Carpet 规则

| 能力 | 代码状态与边界 |
| --- | --- |
| `/player` | 原版完整语法与动作机、异步生成、shadow、原生玩家保存统计、TIS prefix/suffix、remoteSpawn、after/perTick；Org 扩展节点各自受权限控制，启用扩展不会开启原版动作 |
| `/profile` | 实际 Folia timer、实体与方块实体调用次数与耗时；按全局刻窗口采集。独立采集器复用实际 timer 调用，已占用原生 profiler 的区域仍可统计；区分墙钟、全局工作与区域工作 |
| `/spawn` | 刷怪跟踪、范围过滤、最近生成、mocking、rates、动态 cap、test、跨维度 mobcaps、各区域实体列表；各区域周期线程隔离，汇总实际区域上限。点位探针使用独立临时 FULL 票据，取得完整区域所有权后执行；未进行真实世界生成概率验证 |
| `/info`、`carpets` | 方块属性、光照、供电、原版随机徘徊采样；六色地毯动作接实际放置状态入口。探针先加载连续范围并等待真实 Folia 所有权合并，再运行原版探针；超时会释放票据并报告 |
| `/draw` | 原版 sphere/ball/diamond/pyramid/cone/cylinder/cuboid 几何与 replace 过滤、NBT 与容器处理、fillUpdates；生成坐标在解释工作线程，实际块写入逐区块 owner。结果异步报告，原生 command callback 在实际放置完成后给出最终改动数量 |
| TIS `/speedtest` | 官方下载/上传/ping/abort 服务端协议；16KiB、三并发、确认节流、精确接收计数、单帧跳过压缩；未进行实际带客户端的测速 |
| TIS `/manipulate` | block/entity/server/container/chunk 五树均有实际实现；所属区域列表重排与真正 erase/relight/inhabitedTime，未执行真实世界调试命令 |
| `persistentLoggerSubscription` | UUID 保存、显式空订阅、首次加入恢复与权限过滤；单写入器原子替换并刷新磁盘，损坏文件保留。文件往返与损坏处理测试通过 |
| `entityIdCounterLoggerSamplingDuration`、`lightQueueLoggerSamplingDuration`、`loggerMovement` | 实际 Entity AtomicInteger、Moonrise 光照任务、每次 Entity.move；跨 owner 使用不可变消息和位置快照。移动日志距离参照可延迟一全局刻 |
| Org 在线背包与物品扩展拾取 | 普通 43 槽与 GCA 54 槽、动作按钮、末影箱及 `/orange ruleself` 玩家规则开关；非阻塞跨 owner escrow 和共享物品别名身份已接入；未知存档读回窗口会暂存所有受影响物品；扩展拾取先取得覆盖区域的所有权，再调用原生 `Player.touch`。对应 coordinator 测试通过，服务端编译通过；游戏内存档往返及真实跨区域行为未验证 |
| Org 经验转账 | 精确整数 XP、双端 owner、持久 ledger 与 debit/credit marker、读取存档核验；损坏 ledger 不逃出 player tick，保留原文件并限频重试。新增四项实际 coordinator 保存/读取故障注入测试通过，款项先进入已核验托管再释放；未进行真实世界断电或磁盘故障验证 |
| AMS 新服务端规则 | 真正 superLeash、redstoneComponentSound、renewableNetheriteScrap、preventAdministratorCheat、customBlockUpdateSuppressor 与 forceMode；AMS crashFix 使用 false/true/silence，上游 testRule 无运行逻辑 |
| `creativeFlySpeed`、`creativeFlyDrag`、`cleanLogs` | 确认并接入服务端混入，编译通过；CLIENT 分类不代表仅客户端。死亡日志遵循源实际判断，默认 false 不输出该 named-death 日志 |
| `/perimeterinfo`、`commandTrackAI` | 原版球形 24–128 格扫描与特定生物探针、村民繁殖与铁傀儡跟踪、床与路径粒子；区域票据保证扫描所有权。26.3 WeightedList 检查改为比较加权项 value，修正上游直接 contains(SpawnerData) 的类型失配 |

### 规则摘要

| 规则 | 说明 |
| --- | --- |
| `commandLog`、`defaultLoggers` | `/log` 命令树、列表按钮、订阅/取消反馈、管理员反馈与审计日志门控及新玩家默认订阅按 Carpet `f358000` 对照；UUID 持久订阅是 TIS 扩展。已接入 tps、mobcaps、counter、packets、tnt、projectiles、fallingBlocks、raid、xcounter、entityIdCounter、lightQueue、movement、lifetime、microTiming、explosions、pathfinding |
| `opPlayerNoCheat`、`commandRemoveEntity`、`commandSleep` | 阻止玩家来源使用上游指定的作弊命令与模式切换数据包；清洁移除实体；服务端睡眠命令保持上游 s/ms/us 参数树 |
| `applyToolEffectsImmediately`、`recordPlayerCommand` | 挖掘前完成真实装备属性更新；记录实际执行的玩家命令 |
| `autoSyncPlayerStatus` | 每 30 刻玩家信息与上游完整周边方块范围同步；真实 FULL 票据取得连续矩形区域所有权后捕获方块状态，不以只同步当前区域代替 |
| `fakePlayerAutoRestock`、`fakePlayerShulkerBoxItemHandling`  | 按上游逐个接入使用、完成食用、工具损坏与图腾消费的补货；主副手、库存和潜影盒搬移沿用 Org 库存语义，动作目标区域使用 Folia 所有权票据；尚无实际世界运行验证 |
| `fakePlayerKeepInventory`、`fakePlayerKeepInventoryCondition` | 实际 Bot 死亡路径默认保存库存与经验，避免 dropAll；死亡存档恢复满血。保留死亡与移除事件的取消、保存选项 |
| `perfPermissionLevel` | `/perf` 权限动态采用 2 或 4，规则修改刷新命令树 |
| `superSecretSetting`  | 关闭已连接 Carpet 客户端的规则变更广播，保留首次握手全量数据；命令异常打印调试栈 |
| `updateSuppressionBlock`  | 屏障上熄灭的激活铁轨消耗邻居更新栈，下一刻恢复供电；临时信号使用线程作用域避免 Folia 共享状态 |
| `flippinCactusExtras`、`flippinCactusSoundEffect`  | 木桶、合成器、雕纹书架和架子的仙人掌翻转与五种上游音效；原版每个 setValue 前发声、楼梯半块路径和 extras 自身尾部顺序用实际方法验证 |
| `maxPlayerBlockInteractionRange`、`maxPlayerEntityInteractionRange` 及两个 `Scope`  | 范围 -1 或 0–512；server 模式修改服务端距离校验，global 同步属性；动态切换失效属性缓存。超过客户端上限仍需对应客户端支持 |
| `enhancedWorldEater`、`fertilizableSmallFlower`  | 非排除方块高爆炸抗性可设为 0–16；普通小花施骨粉生成同类花，遵循上游 FlowerBlock 实现 |
| `fakePlayerNoScoreboardCounter`、`fakePlayerPickUpController` | 单参数统计入口关闭；拾取可禁用或主手非空时跳过，遵循上游实际范围 |
| `sendPlayerDeathLocation`、`phantomSpawnAlert`  | 死亡坐标与幻翼生成提醒，复制坐标按钮，逐玩家所属线程发送 |
| `maxChainUpdateDepth` | 覆盖收集式邻居更新上限，-1 使用原值，其他负数取消上限 |
| `experimentalMinecartEnabled`、`experimentalMinecartSpeed`  | 真实 feature flag、移动器、速度及原版游戏规则适配；启动开关保存后需重启 |
| `naturalSpawningUse13Heightmap`、`naturalSpawningUse13HeightmapExtra`  | 自然生成按旧版光遮挡高度，extra 排除指定方块 |
| `preciseEntityPlacement`  | 盔甲架、水晶与刷怪蛋按点击位置生成，在 finalizeSpawn 与插件生成事件前定位 |
| `dispensersFireDragonBreath`  | 发射器消耗龙息生成上游参数的效果云，保留取消与换物品事件，跨区域调度目标生成 |
| `redstoneDustRandomUpdateOrder`、`redstoneDustRepeaterComparatorIgnoreUpwardsStateUpdate`  | 随机线更新顺序，不消耗世界随机源；红石线忽略中继器、比较器上方状态更新 |
| `noToolBreak`、`betterTotemOfUndying`、`knockbackStick`  | 修补工具最后一点耐久时停用工具与属性；图腾可查背包和潜影盒；木棍通过铁砧接受击退 |
| `disableCreativeContainerDrops`、`blockDropsDirectlyEnterInventory`  | 创造破坏容器不掉内容；破坏战利品直接进入背包，custom 使用随玩家数据保存的开关 |
| `itemPickupRangeExpand`、`itemPickupRangeExpandPlayerControl` | 扩大物品拾取范围，支持玩家持久开关；Folia 先取得覆盖区域的完整所有权，再沿原生触碰入口执行，以保留 Carpet 碰撞事件 |
| `quickShulker`、`shulkerBoxStackable`、`openVillagerInventory`  | 手持或槽位快捷打开潜影盒、空盒堆叠与完整库存保护、村民八槽背包；自动化库存与比较器仍采用原堆叠尺寸 |
| `creativeHitRemoveEntity`  | 创造攻击清洁移除非玩家实体与剑横扫，屏蔽容器掉落和立方生物分裂，保留攻击取消事件 |
| `largeBarrel`  | 相邻反向两只 27 格桶合并为 54 格；门锁、战利品、名称、漏斗和比较器共用合并容器；规则切换按所属区块唤醒睡眠漏斗并失效缓存 |
| `lightUpdates`、`synchronizedLightThread`  | 接入 Moonrise 实际光照队列，on/suppressed/ignored/off；同步等待任务快照，并拒绝与 suppressed/off 同开 |
| `endPortalChunkLoadDisabled`、`preventServerPause`  | 禁用末地门额外 PLACE_PORTAL_TICKET 回调；Folia 本身保持空服活动，必要传送加载票据保留 |
| `customizedNetherPortal`、`stringDupeReintroduced`、`largeBundle`  | 无框门与门硬度；线恢复类型验证回退；收纳袋使用 3/6 排原生容器菜单，保留源组件与隐藏槽数据 |
| 音符盒、活塞、钟的 `blockChunkLoader` 及范围、时长、世界刻开关  | 注册持久原生票据，大范围分块票据保持完整、方块刻、实体刻边界；时长动态读取，范围 1–300 |
| `commandPlayerChunkLoadController`、`creativePlayersLoadChunks`  | 玩家加载开关撤销 Moonrise 真实加载、生成与模拟票据，已有其他票据支持的可见区块仍可发送 |
| `instantCommandBlock`、`overspawningReintroduced`、`zombifiedPiglinDropLootIfAngryReintroduced`、`entityPathNavigationStuckDetectionUseRealTimeReintroduced` | 红石矿石上普通命令方块立即执行；超量生成不受每轮硬上限；愤怒僵尸猪灵保留玩家掉落判定；寻路卡住检测使用单调时钟 |
| `chunkTickSpeed`  | 每个 Folia 区域内重复区块随机刻与冰雪刻，零值跳过；默认一次 |
| `elytraFireworkKeepLeashConnection` | 烟花加速鞘翅时保留拴绳连接 |
| `sandDupingFix`  | 已删除下落方块停止落块流程，开启后阻断已有强制传送复制选项 |
| `oakBalloonPercent`  | 无花普通橡树按百分比改用大橡树；适配 26.3 WeightedList，上游两份版本谓词在恰好 26.3 留有空档 |
| `violentNetherPortalCreation`  | 可替换方块或任意非框架方块可作为门内部 |
| `renewableDragonEgg`  | 龙息云内龙蛋随机复制，复制成功将云半径缩至五分之一；跨区域按目标区块和云的所属线程调度。关闭时不会进入随机刻名单；动态开关在实际 section 计数、Moonrise 坐标名单和块修改前重建，两项实际 section 测试通过 |
| `beaconRangeExpand`、`beaconWorldHeight`  | 调整信标水平范围或覆盖世界高度；效果逐玩家所属线程应用并保留插件事件 |
| `limitPhantomSpawn`  | 幻翼生成受全局与局部怪物上限限制，达到上限结束本次幻翼生成刻 |
| `commandHere`、`commandWhere`、`commandGoto`  | 坐标发送、查询玩家与维度传送；按玩家所属线程处理实体、异步传送；支持 true/false/ops/0–4 权限 |
| `commandGetPlayerSkull`、`commandGetHeldItemID`、`commandGetSaveSize`、`commandGetSystemInfo` | 玩家头颅、手持物 ID、保存后统计存档大小、真实系统与 JVM 信息；所有权限动态读取规则 |
| Org `/navigate` (`commandNavigate`)  | 实体、玩家、UUID、路径点、方块坐标、重生点、死亡点和停止分支；路径点子树受 `commandLocations` 控制，死亡导航另受 `navigate.death` 控制；跟踪状态与原版服务端线包顺序已对照 |
| `keepMobInLazyChunks`  | 官方两个 1.14.4 版本混入在 >=1.15 指向 DummyClass；保留规则入口。Folia 原生仅对 entity-ticking 实体检查消失，已移除额外的 AI 阶段搬移与消失保护 |
| `chunkUpdatePacketThreshold`  | 上游版本类明确限制在 1.16 之前；保留审计结果，无行为占位 |
| `canMineSpawner`  | 精准采集刷怪笼时保留生成逻辑 NBT，重新放置恢复逻辑，采集时不掉经验 |
| `tooledTNT`  | 爆炸方块战利品及经验使用造成爆炸生物主手工具；跨 Folia 区域使用其最近一次刻的工具副本，避免直接读另一地区的物品栏 |
| `HUDLoggerUpdateInterval` | HUD 刷新间隔为 1–1000 刻，默认 20，运行时读取新间隔 |
| `chatMessageLengthLimitUnlocked`  | 入站聊天与出站签名消息正文上限按上游设为 32000 字符；有签名与无签名命令解除 Paper 的 256 字符限制。客户端需具备相应长消息支持 |
| `tileTickLimit` | 方块与流体的计划刻数量上限，适配 Folia 的每区域调度；包括默认 65536 在内均使用规则实际值 |
| `failSoftBlockStateParsing` | 服务端方块参数忽略未知属性和无效属性值；结构性语法错误及重复有效属性仍拒绝 |
| `craftableEnchantedGoldenApples`、`craftableElytra`、`betterCraftablePolishedBlackStoneButton`、`rottenFleshBurnedIntoLeather`、`craftableCarvedPumpkin`  | 使用 AMS 上游材料、数量及烧炼参数；雕刻南瓜保留剪刀并按耐久附魔损耗。七个配方规则切换后批量刷新自身注入配方，保留插件替换及其他配方 |
| `stackableDiscounts` | 次要正面声望上限 200，主要正面上限 100、传播衰减 100；关闭后恢复 25/20/20 |
| `commandDistance` | `/distance from`、`to` 与直接两点测距，球面、水平与曼哈顿距离；支持 true/false/ops/0–4 权限 |
| `channelingIgnoreConditions` | 引雷三叉戟击中实体或方块时可忽略天气，或同时忽略天气与天空；回调之外保持普通天气判定 |
| `gazeDisguiseEquipmentExtended` | 头部装备头颅物品时可避开末影人凝视判定 |
| `renewableDragonHead` | 末影龙因苦力怕伤害进入死亡阶段时使用其头颅掉落额度，死亡结束额外生成龙头；与上游一样不另加充能检查 |
| `easyRefreshTrades` | 零经验一级村民可通过反复打开交易刷新交易，主手绿宝石块保留当前交易；原交易生成与插件事件路径保留 |
| `flippinCactus`、`rotatorBlock` | 主手仙人掌翻转方块，副手仙人掌反向放置，发射器仙人掌旋转邻接方块；包含台阶、楼梯、柱形方块及活塞保护 |
| `movableBlockEntities`  | 活塞携带原方块实体与库存，落位重新注册；保存使用上游 carriedTileEntityCM 格式；规则关闭后仍读取已存携带数据，避免丢失库存。保留光照、POI 与插件事件路径 |
| `strongLeash` | 生物与快乐恶魂拴绳不因距离断开，烟花加速保留拴绳；同样覆盖 Paper 的最大距离入口 |
| `regeneratingDragonEgg` | 再次击杀末影龙仍形成龙蛋，保留 DragonEggFormEvent |
| `cryingObsidianNetherPortal` | 哭泣黑曜石可构成下界传送门框架 |
| `creativeNetherWaterPlacement` | 创造玩家可在会蒸发水的环境倒水，保留玩家倒水事件 |
| `netherPortalMaxSize` | 门框检测、完整性检查与搜索宽高上限可配置为 2–384；大型门的区域边界行为仍需运行验证 |
| `sharedVillagerDiscounts` | 村民治疗的主要正面声望向所有玩家共享，并保留原声望上限 |
| `debugNbtQueryNoPermission` | 服务端接受非管理员的方块实体与实体 NBT 查询；客户端快捷键修改不在范围内 |
| `flattenTriangularDistribution` | 双精度与单精度三角分布改为均匀分布，保持原有两次随机数消耗 |
| `leaderZombieSpawnWithMaxHealthDisabled` | 领头僵尸生成时保留原生命值，与现有旧版生命值配置共同生效 |
| `moveableReinforcedDeepslate` | 活塞可推动强化深板岩，使用上游 26.3 推动反应入口 |
| `vaultBlacklistDisabled` | 宝库不再将领取玩家写入黑名单，已有黑名单不限制激活；保留插件直接维护黑名单的 API |
| `blowUpEverything` | 方块爆炸抗性返回零，流体抗性保持上游行为 |
| `blueSkullController` | vanilla/surely/never 控制凋灵蓝骷髅；never 与上游一致关闭普通及困难下的闲置头蓝骷髅路径 |
| `itemAntiExplosion` | true/no_blast_wave 将掉落物所有伤害值置零；no_blast_wave 另禁爆炸推动，遵循上游实际实现 |
| `superZombieDoctor` | 转化中的僵尸村民下一刻完成治疗 |
| `easyCompost` | 使用上游 26.3 addLayer 实现，每次合格堆肥物贡献一层，插件事件仍可修改或取消 |
| `setAnvilExperienceConsumptionLimit` | 修改服务端铁砧过于昂贵阈值，保留重命名原阈值；客户端显示修改不在服务端范围内 |
| `language`  | 原版六语言服务端规则名称、描述、附加说明、分类与选项，TIS/Org/AMS 服务端翻译；按上游严格语言集合验证，规则查询与搜索使用当前 Carpet 语言 |
| `commandTick`、`tickCommandPermission` | String 权限，动态原生命令树；完整 TIS tick 树、深冻结、sprint 状态与 profile 别名，按 Folia 区域时钟适配 |
| `creativeNoClip`  | 转发到 Lophine 的创造飞行穿墙实现 |
| `ctrlQCraftingFix`  | 当前的合成与切石机菜单已经自带修复后的 Ctrl+Q 结果槽丢弃行为 |
| `placementRotanFix` | 放置朝向判断可使用玩家身体朝向而不是头部旋转 |
| `tntDoNotUpdate` | 新放置的 TNT 不会再因放置时更新而自动点燃 |
| `explosionNoBlockDamage` | 爆炸仍会伤害实体，但不会破坏方块 |
| `interactionUpdates` | 玩家交互和破坏方块时可以在不触发邻居更新与形状更新的情况下运行 |
| `xpNoCooldown` | 经验球可以在同一 tick 内被连续吸收，不再有拾取冷却 |
| `persistentParrots` | 玩家受伤时鹦鹉按伤害量概率离肩，普通跌落不使其离肩 |
| `maxEntityCollisions` | 非零值覆盖生物推挤实体数量限制，零值沿用原有限制；遵循上游非负验证器 |
| `lightningKillsDropsFix` | 闪电不会伤害生成后不超过八刻的掉落物 |
| `xpTrackingDistance` | 经验球跟随玩家的距离可配置，零值关闭跟随 |
| `witherSpawnedSoundDisabled` | 关闭凋灵生成时的全局音效包 |
| `snowMeltMinLightLevel` | 雪层融化的最低方块亮度可配置 |
| `turtleEggTrampledDisabled` | 玩家与生物踩踏、坠落和生物挖掘不会破坏海龟蛋 |
| `voidRelatedAltitude` | 负数非默认值可覆盖虚空伤害的绝对高度阈值 |
| `voidDamageAmount` | 非默认值可覆盖每次虚空伤害值 |
| `voidDamageIgnorePlayer` | 可指定所有玩家或按游戏模式免受虚空伤害 |
| `undeadDontBurnInSunlight` | 僵尸、骷髅及幻翼在阳光下不再被点燃 |
| `disableDamageImmunity`  | 上游固定源码标记为 removed；本地历史配置仍可关闭受伤冷却伤害免疫，不计入当前上游活动规则数 |
| `notDamageEnderPearl` | 末影珍珠传送的两条玩家路径都跳过自身伤害 |
| `reusableSmithingTemplate` | 可选择复用全部模板或只复用下界合金升级模板 |
| `disableFurnaceDropExperience` | 熔炉配方不会产生经验球 |
| `hopperSuctionDisabled` | 漏斗的物品移动入口直接返回未移动 |
| `safePointedDripstone` | 玩家落在滴水石锥上仅承受普通摔落伤害 |
| `pointedDripstoneCollisionBoxDisabled` | 滴水石锥的服务端碰撞形状为空 |
| `sneakToEditSign` | 玩家需潜行才能交互编辑告示牌文字 |
| `meekEnderman` | 玩家注视末影人时不触发注视判定 |
| `furnaceSmeltingTimeController` | 可覆盖熔炉配方的烧炼时间 |
| `fasterMovement`、`fasterMovementController` | 按指定维度覆盖玩家移动速度 |
| `easyWitherSkeletonSkullDrop` | 凋灵骷髅死亡时额外掉落一个头颅 |
| `witchRedstoneDustDropController`、`witchGlowstoneDustDropController` | 女巫死亡时额外掉落指定数量的红石粉、荧石粉 |
| `jebSheepDropRandomColorWool` | 名为 `jeb_` 的羊被剪毛时掉落随机颜色羊毛 |
| `cakeBlockDropOnBreak` | 生存玩家挖掉完整蛋糕时掉落蛋糕物品 |
| `easyGetPitcherPod` | 生存玩家挖掘瓶子草作物时额外掉落瓶子草荚果 |
| `creativeShulkerBoxDropsDisabled` | 创造玩家破坏潜影盒时不生成装有物品的潜影盒掉落物 |
| `mitePearl` | 末影珍珠符合怪物生成条件时跳过末影螨概率限制 |
| `kirinArm` | 玩家挖掘速度返回最大浮点值 |
| `superBow` | 无限与经验修补附魔可同时存在 |
| `tntPowerController` | 新生成的 TNT 实体可指定爆炸威力 |
| `truePeacefulMode` | 生物不将玩家判为有效攻击目标 |
| `disableMobPeacefulDespawn` | 持久化生物在和平模式下不因默认清除规则消失 |
| `disableWindChargeEffect` | 玩家发射的风弹爆炸不触发方块 |
| `itemEntitySkipMovementDisabled` | 掉落物每刻执行移动逻辑 |
| `toughWitherRose` | 凋灵玫瑰可放在任意方块上 |
| `structureBlockDoNotPreserveFluid` | 结构方块加载结构时忽略含水状态 |
| `renewableElytra` | 被潜影贝杀死的幻翼可按指定概率掉落鞘翅 |
| `entityTrackerDistance`、`entityTrackerInterval` | 可覆盖可追踪实体的追踪距离与更新间隔 |
| `repeaterHalfDelay` | 红石矿石上方的中继器计划刻延迟减半，最少一刻 |
| `breedableParrots` | 可指定鹦鹉繁殖食物并开启鹦鹉繁殖 |
| `quasiConnectivity` | 活塞、投掷器和发射器向上检查可配置范围的准连接信号 |
| `huskSpawningInTemples`、`shulkerSpawningInEndCities`、`piglinsSpawningInBastions` | 在相应结构的生成列表中覆盖怪物种类与权重 |
| `antiCheatDisabled` | 放宽飞行、交互距离与移动偏差检查 |
| `stackableShulkerBoxes` | 空潜影盒可按配置数量堆叠，装有物品的潜影盒保持原限制 |
| `hardcodeTNTangle` | 新点燃的 TNT 可使用固定的水平初始运动方向 |
| `mergeTNT` | 同位置、同引信且静止的 TNT 合并为一个实体，爆炸时补足次数 |
| `creativeInstantTame` | 创造玩家驯服狼、猫、鹦鹉及骑乘马时跳过随机失败 |
| `openSeedPermission`、`openTpPermission`、`openGameRulePermission` | 动态开放 `/seed`、`/tp`、`/teleport`、`/gamerule` 使用权限，修改后刷新玩家命令树 |
| `forceOpenContainer` | 可按规则打开受阻的潜影盒，或同时打开受阻的普通箱与末影箱 |
| `villagerHeal` | 村民每 80 个游戏刻回复一点生命值 |
| `fakePlayerHeal` | 假玩家每 40 个游戏刻回复一点生命值和一点饥饿值 |
| `playerDropsNotDespawning` | 玩家死亡时从物品栏掉出的物品不自动消失 |
| `totemOfUndyingInvincibleTime` | 不死图腾触发后给予 40 刻五级抗性效果 |
| `superChargedCreeper` | 高压苦力怕每次击杀都会重置头颅掉落限制 |
| `playerDropHead` | 玩家被高压苦力怕击杀时掉落带身份的玩家头颅 |
| `villagerVoidTrading` | 村民交易界面不再检查村民存活与交互距离 |
| `forceRestock` | 每刻从背包中向相同物品的快捷栏堆叠补货 |
| `fakePlayerSpawnNoKnockback` | 假玩家加入世界时清除速度、着火、摔落高度及负面状态效果 |
| `perfectInvisibility`、`sneakInvisibility` | 隐身或潜行时服务端生物可见度可分别降为零 |
| `onlyPlayerCanCreateNetherPortal`、`itemEntityCreateNetherPortalDisabled` | 限制谁能在跨维度传送时新建目标下界传送门 |
| `foliageGenerateDisabled` | 禁止树叶、巨型菌类菌盖和垂泪藤生成 |
| `headHunter` | 玩家死亡掉落物品时额外掉落对应玩家头颅 |
| `setBedrockHardness`、`pickaxeMinedBedrock`  | 上游固定源码标记为 removed；本地历史配置仍可调整基岩硬度及镐的采掘速度，不计入当前上游活动规则数 |
| `softDeepslate`、`softObsidian`、`softOres`、`softNetherite` | 对应方块使用上游定义的较低挖掘硬度 |
| `riptideIgnoreConditions` | 激流三叉戟使用时可忽略下雨或浸水条件 |
| `protectionEnchantmentCompatible`、`damageEnchantmentCompatible` | 指定保护类与伤害类魔咒可与其他不同魔咒共存 |
| `maxBlockPlaceDistance`、`maxBlockPlaceDistanceReferToEntity` | 服务端方块交互距离可覆盖，并可用于实体交互距离；超出原版客户端可指向的范围仍需客户端支持 |
| `canActivatesObserver` | 玩家用打火石或火焰弹右键侦测器，可启动一次信号 |
| `customPiglinBarteringTime` | 可覆盖猪灵拿到金锭后的欣赏时间 |
| `climbingBoat` | 玩家驾驶的船最大上台阶高度提高到一格 |
| `experienceOrbMerge` | 周期性交替启用不同经验值的经验球合并，保持总经验不超过 32767 |
| `spawnBabyProbably`、`spawnJockeyProbably`、`spawnLeaderZombieProbably` | 可覆盖对应生物的幼年、骑乘和僵尸首领生成概率；炽足兽骑乘者维持上游的 1:3 比例 |
| `entityPlacementIgnoreCollision` | 放置船、盔甲架、末地水晶及地面矿车时可忽略碰撞检查 |
| `minecartPlaceableOnGround`、`minecartTakePassengerMinVelocity` | 矿车可放在普通地面，并可覆盖旧矿车拾取乘客的速度比较阈值 |
| `chainStone` | 链条沿轴线粘连相同链条、末地烛与支撑面；`stick_to_all` 模式沿轴线粘连任意相邻方块 |
| `structureBlockLimit`、`structureBlockIgnored` | 结构方块可读取更大尺寸的 NBT 与 Carpet 扩展数据包，保存时可忽略指定方块；大尺寸客户端编辑需客户端支持 |
| `summonNaturalLightning` | 指令生成闪电时按局部难度与生物生成规则尝试生成骷髅马陷阱 |
| `fillUpdates` | 指令与结构方块加载时可关闭邻居更新和形状后处理；Folia 延后执行的填充与单方块任务也保持该状态 |
| `renewableCoral` | 骨粉可按上游概率将含水珊瑚长成同色珊瑚结构，`expanded` 也支持珊瑚扇，并保留湿海绵生成概率 |
| `thickFungusGrowth` | 骨粉种植的巨型菌类可全部或以 6% 概率形成粗树干；按规则描述补全上游 26.3 留在注释中的随机分支 |
| `optimizedTNT`、`tntRandomRange` | 使用 Carpet 的双精度射线顺序、首个采样点阻挡优化及固定随机范围，缓存沿用每次爆炸独立的 Paper 实现 |
| `largeEnderChest` | 新建玩家末影箱库存使用 54 格，打开界面按实际容量选择行数；切换规则后需重新登录 |
| `largeShulkerBox` | 启动时可启用 54 格潜影盒，扩展保存、界面与漏斗可访问槽位；运行时不重载此规则 |
| `carpetCommandPermissionLevel` | `/carpet` 支持 `ops`、`2` 与 `4` 权限级别，修改此规则需拥有者权限 |
| `liquidDamageDisabled` | 保留可容纳流体的方块行为，流体不再冲走其他普通方块 |
| `silverFishDropGravel` | 蠹虫破块出现时掉落砂砾 |
| `moreBlueSkulls` | 凋灵危险头颅的生成概率提高 |
| `sculkSensorRange` | 幽匿感测体监听半径可配置，保留默认值时仍使用 Paper 覆盖值 |
| `missingTools` | 用镐开采玻璃音效类方块时，借用石头方块的工具开采速度 |
| `pingPlayerListLimit` | 可覆盖服务器列表的玩家样本数量；包括默认值在内均使用规则实际值 |
| `customMOTD` | 服务器状态描述可改为自定义文本 |
| `xpFromExplosions` | 爆炸破坏方块时可额外掉落方块经验 |
| `movableAmethyst` | 紫水晶母岩可被活塞移动，精准采集镐挖掘时掉落自身 |
| `endPortalOpenedSoundDisabled` | 末地传送门开启时不发送全局音效 |
| `fluidDestructionDisabled` | 流体不会冲毁阻挡其蔓延的方块 |
| `poiUpdates` | 可关闭 POI 方块状态更新 |
| `enchantCommandNoRestriction` | `/enchant` 可跳过等级、适用物品和已有附魔兼容性限制 |
| `entityMomentumLoss` | 可关闭实体加载时超过 10 的运动分量清零逻辑 |
| `dispenserNoItemCost` | 发射器及向外抛出的投掷器物品不会被消耗，投掷器向容器传输仍正常消耗 |
| `explosionNoEntityInfluence` | 爆炸不对实体造成伤害、击退或其他命中效果 |
| `scheduledRandomTickCactus`、`scheduledRandomTickBamboo`、`scheduledRandomTickChorusFlower`、`scheduledRandomTickSugarCane`、`scheduledRandomTickStem`、`scheduledRandomTickAllPlants` | 对应植物收到计划刻时也执行一次随机生长刻 |
| `netherWaterPlacement` | 玩家可在水会蒸发的环境中放置水桶内容物 |
| `bambooCollisionBoxDisabled` | 竹子的实体碰撞形状为空 |
| `useItemCooldownDisabled` | 物品冷却状态检查始终返回未冷却 |
| `enderDragonNoDestroyBlock` | 末影龙穿过普通方块时不会破坏方块 |
| `viewDistance` | 设置为至少 2 时覆盖专用服务器启动视距；0 或 1 使用服务器设置 |
| `simulationDistance` | 设置为至少 2 时覆盖专用服务器启动模拟距离；0 或 1 使用服务器设置 |
| `fastRedstoneDust` | 规则启用时，红石粉更新会统一走 Alternate Current 快速更新后端 |
| `lagFreeSpawning` | 自然生成会使用轻量碰撞检查与预构造实体路径 |
| `defaultLoggers` | 按 Carpet 默认订阅语义为玩家启用 HUD logger，当前支持 `tps`、`mobcaps`、`counter` |
| `commandPlayer`  | 当前由同一套 `/bot` 假人能力承接 |
| `hopperCounters`  | 转发到 Lophine 的羊毛漏斗计数器 |
| `yeetUpdateSuppressionCrash`  | 转发到同一套 Lophine 崩溃修复逻辑 |
| `dustTrapdoorReintroduced`  | 转发到 `lophine.experiment.redstone.redstone-ignore-upwards-update` |
| `shulkerBoxCCEReintroduced`  | 转发到 `lophine.experiment.redstone.cce-update-suppression` |
| `instantBlockUpdaterReintroduced`  | 转发到 `lophine.experiment.redstone.instant-block-updater` |
| `optimizedDragonRespawn`  | 转发到 Luminol 的末影龙重生优化 |
| `antiSpamDisabled` | 在 `ServerGamePacketListenerImpl` 中关闭聊天和创造丢物防刷屏限制 |
| `blockPlacementIgnoreEntity` | 创造模式放置方块时忽略实体碰撞检查 |
| `breedingCooldownDisabled` | 成年生物不会进入繁殖冷却 |
| `blockEventPacketRange` | 方块事件数据包广播距离可配置，最小值限制为零 |
| `creativeOpenContainerForcibly` | 创造玩家可以强制打开被阻挡的箱子、末影箱和潜影盒 |
| `observerNoDetection` | 观察者的检测触发会在发出脉冲前被取消 |
| `creativeNoItemCooldown` | 创造玩家不会应用物品冷却 |
| `totallyNoBlockUpdate` | 邻居更新和形状更新会在 `NeighborUpdater` 中被统一短路 |
| `tiscmNetworkProtocol` | 原生 `tiscm:network/v1` 握手与数据包协商已经通过专用 Leaves 协议接入 |
| `optimizedTNTHighPriority`  | 当前 `ServerExplosion` 路径已经运行在优化过的服务端爆炸实现上 |
| `tntIgnoreRedstoneSignal` | TNT 在自动点燃判断时忽略红石信号 |
| `tntDupingFix` | 活塞复制 TNT 的路径现在由 `PistonBaseBlock` 中的兼容规则直接控制 |
| `clientSettingsLostOnRespawnFix` | 玩家上一次的客户端设置会在 `ServerPlayer.restoreFrom` 中恢复 |
| `entityInstantDeathRemoval` | 已死亡生物不会再保留原版的 20gt 延迟，而是立即移除 |
| `farmlandTrampledDisabled` | 耕地不会再因实体踩踏而变回泥土 |
| `yeetOutOfOrderChatKick` | 乱序的签名聊天不会再破坏安全聊天链 |
| `tickCommandPermission` | `/tick` 的权限等级可通过兼容配置调整 |
| `tickFreezeCommandToggleable` | 已冻结时再次执行 `/tick freeze` 会切换回运行状态 |
| `syncServerMsptMetricsData` | 实时 MSPT 样本会通过原生 TISCM 协议通道广播 |
| `microTiming` 与 dyeMarker/target/tickDivision  | 原生更新、调度、阶段、嵌套日志和染料标记；每个真实 Folia 区域独立记录顺序，区域合并拆分时清理旧帧；通过 Carpet scShapes 发送方框与文字；不是性能计时替代 |
| `optimizedFastEntityMovement`  | Moonrise/Paper 的快速实体移动碰撞优化已经是基础运行时的一部分 |
| `optimizedHardHitBoxEntityCollision`  | Moonrise/Paper 的硬碰撞箱实体碰撞优化已经是基础运行时的一部分 |
| `cauldronBlockItemInteractFix`、`entityBrainMemoryUnfreedFix`  | TIS 固定源码分别限制在 `<1.17` 与 `<=1.19.4-pre3`，当前目标版本不启用这两处旧版修复混入 |
| `minecartFullDropBackport`  | TIS 固定源码只在 `<1.19` 安装回移混入；当前目标的原版矿车销毁路径提供完整矿车掉落 |
| `yeetAsyncTaskExecutionDelay`  | 原版通过 `shouldRun(TickTask)` 推迟服务器任务；Folia 不保留该队列，改由全局与区域队列唤醒调度器，并在相应 owner 的任务轮次中排空队列，因此没有额外的 tick-time 检查可关闭 |
| `tntFuseDuration` | 可配置的 TNT 引信时长已接入 NMS TNT 逻辑 |
| `fakePlayerTicksLikeRealPlayer`  | 转发到 Leaves 的假人网络阶段 tick 行为 |
| `hopperCountersUnlimitedSpeed`  | 转发到不限速计数器模式 |
| `hopperNoItemCost` | 漏斗上方放置羊毛时，可在传输后恢复被推出的物品堆，实现零消耗供给线 |
| `simpleInGameCalculator` | 以 `=` 开头的聊天内容会作为简单计算表达式私聊回复 |
| `disableOpenOrWaterDetection` | 钓鱼不再检查开放水域 |
| `creativeImmuneKill` | `/kill` 跳过创造和旁观玩家 |
| `disableBatCanSpawn` | 禁止蝙蝠自然生成 |
| `disableWaterFreezes` | 水不会自然结冰 |
| `turtleEggFastHatch` | 海龟蛋在适用随机刻里无需等待孵化概率条件 |
| `farmlandPreventStepping` | 保留摔落伤害，但踩踏不触发耕地交互和踩坏逻辑 |
| `bindingCurseInvalidation` | 装备更换检查忽略绑定诅咒效果 |
| `peacefulCreeper` | 苦力怕不会选择新的攻击目标 |
| `staringEndermanNotAngry` | 玩家直视末影人时不会由注视行为触发仇恨 |
| `healthNotFullCanEat` | 饱和度较低且生命值未满时可进食 |
| `turtleEggFastMine` | 一次挖掘可采集整组海龟蛋 |
| `disableRespawnBlocksExplode` | 禁止床和重生锚因环境限制而爆炸 |
| `fireworkRocketUseCooldown` | 真实玩家成功消耗烟花火箭后添加短暂冷却 |
| `noCakeEating` | 玩家不能食用蛋糕 |
| `sneakToEatCake` | 玩家必须潜行才能食用蛋糕 |
| `shulkerHitLevitationDisabled` | 潜影贝子弹造成的漂浮效果时长设为零 |
| `immuneShulkerBullet` | 潜影贝子弹命中实体时不造成伤害或漂浮 |
| `noEnchantedGoldenAppleEating` | 禁止食用附魔金苹果 |
| `easyMineDragonEgg` | 龙蛋交互或攻击时不再成功随机传送 |
| `endermanPickUpDisabled` | 末影人不会启动拾取方块目标 |
| `endermanTeleportRandomlyDisabled` | 关闭末影人的随机传送路径 |
| `renewableBlackstone` | 熔岩接触周围蓝冰可生成黑石 |
| `renewableDeepslate` | 主世界零高度以下流体凝固可生成深板岩或深板岩圆石 |
| `explosionPacketRange` | 爆炸数据包广播距离可配置，跨区域玩家在自己的区域线程接收 |
| `renewableSponges` | 普通守卫者被闪电击中时转换成远古守卫者 |
| `desertShrubs` | 干燥沙漠中的树苗可变成枯萎的灌木 |
| `pushLimit` | 活塞推动方块上限可配置 |
| `railPowerLimit` | 动力铁轨的传导距离可配置 |
| `forceloadLimit` | `/forceload` 的单次区块数量上限可配置 |
| `infiniteTrades` | 交易次数在使用后归零，不会耗尽交易 |
| `infiniteDurability` | 物品耐久变化量在服务端处理时归零 |
| `noFamilyPlanning` | 成年生物读取繁殖年龄时返回零 |
| `undyingCoral` | 珊瑚不再安排死亡刻，珊瑚方块视为始终有水 |
| `villagerInfiniteTrade` | 村民交易使用次数不再增加 |
| `amsUpdateSuppressionCrashFix`  | 转发到 Lophine 现有的更新抑制崩溃修复 |
| `creativeOneHitKill` | 创造玩家可通过 `Player.attack` 瞬间击杀附近符合条件的目标 |
| `extinguishedCampfire` | 新放置的篝火默认不点燃 |
| `safeFlight` | 玩家免疫鞘翅飞行撞墙伤害 |
| `invulnerable` | 玩家免疫虚空坠落以外的伤害 |
| `quickVillagerLevelUp` | 与未满级的村民交互时，村民立即提升一级 |
| `fullMoonEveryDay` | 月相索引始终按满月处理 |
| `fakePeace` | 允许按维度 ID 关闭怪物自然生成 |
| `ironGolemNoDropFlower` | 铁傀儡掉落逻辑不生成虞美人 |
| `easyMaxLevelBeacon` | 信标下方有有效基座方块时按四级基座处理 |
| `bambooModelNoOffset` | 竹子和竹笋始终返回零模型偏移 |
| `carpetAlwaysSetDefault` | 普通规则修改的真实反馈完成后读取此开关，再发默认值提示并等待实际配置保存；关闭此开关的那次修改不会被自动保存 |
| `powerfulExpMending` | 使用原背包槽位和 MENDING 键筛选受损物品，按独立 shuffle 顺序修补；不添加背包修补事件或消耗玩家随机序列 |
| `sensibleEnderman` | 末影人仅会在规则开启时拾取南瓜和西瓜 |
| `shulkerGolem` | 潜影盒上方放置雕刻南瓜时可以召唤潜影贝 |
| `preventEndSpikeRespawn` | 末影龙重生时会跳过黑曜石柱重建和柱顶水晶重建 |
| `betterCraftableBoneBlock` | 向配方管理器注入 AMS 的替代骨块配方 |
| `betterCraftableDispenser` | 向配方管理器注入 AMS 的替代发射器配方 |
| `fakePlayerDefaultSurvivalMode` | 新创建的假人可以被强制设为生存模式，而不是沿用服务器默认游戏模式 |
| `fakePlayerInteractLikeClient` | 假人与盔甲架交互时会先按真实客户端方式回退，再进入普通实体交互 |
| `fakePlayerAutoReplenishmentFormShulkerBox` | 假人自动补货现在可以从背包里的潜影盒中抽取匹配物品 |

### Leaves 扩展承接

| 规则 | 状态 | 说明                               |
| --- | --- |----------------------------------|
| `commandBot`  | 启用 Leaves `/bot`                |
| `fakePlayerResident`  | 转发到 Leaves 的假人常驻模式              |
| `openFakePlayerInventory`  | 转发到 Leaves 的假人背包打开功能            |
| `fakePlayerAutoReplaceTool`  | 为现有自动换工具逻辑增加了新开关                 |
| `fakePlayerAutoReplenishment`  | 为现有自动补货逻辑增加了新开关                  |
| `fakePlayerReloadAction`  | 为假人动作持久化增加了新开关                   |
| `fakePlayerAutoFish` | 手持鱼竿的假人会自动抛竿和收杆，除非显式执行 `fish` 动作 |
