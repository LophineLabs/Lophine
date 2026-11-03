# Carpet 插件 API

插件只需依赖 `lophine-api` 编译，无需依赖 Minecraft 服务端实现。
通过 `CarpetApi.get()` 或 `Server.getCarpet()` 获取当前服务器的 API 实例。

## 规则查询与修改

```java
import fun.bm.lophine.api.carpet.CarpetApi;

CarpetApi carpet = CarpetApi.get();
carpet.rule("chunkTickSpeed").thenAccept(rule -> {
    rule.ifPresent(snapshot -> getLogger().info(snapshot.name() + " = " + snapshot.value()));
});
carpet.setRule(this, "chunkTickSpeed", "2", false).whenComplete((result, failure) -> {
    if (failure != null) getLogger().warning("规则修改失败：" + failure);
    else getLogger().info("规则修改结果：" + result.status());
});
```

以上示例在插件类中运行。查询结果是不可变快照。`rules()` 包含隐藏规则；
当前无法访问的规则会标记为 `available=false`。查询未知规则时返回
`Optional.empty()`。规则名称区分大小写。快照中的默认值是内置默认值，
不代表配置文件中保存的启动覆盖值。

规则查询和修改都会排入 Folia 的全局区域（global region）执行。
不要在 tick 线程上调用返回 future 的 `join()` 或 `get()` 等待结果。
完成回调不保证拥有任何区域或实体的访问权限；即使回调紧接着规则操作执行，
访问实体或世界时仍需调度到对应的 Folia 所属线程。

`setRule` 与原有规则命令使用相同的解析、可用性检查、校验、重启限制、
Scarpet 通知、客户端规则同步和命令刷新流程。`persist` 明确指定是否将请求值
保存为启动覆盖值。需要重启才能生效的规则不允许临时修改；保存成功后返回
`SAVED_FOR_RESTART`，当前运行值保持原值。其他结果为 `UPDATED`（运行值已改变）
和 `UNCHANGED`（运行值未改变）。即使运行值未改变，只要请求了持久化，仍会执行保存。

校验失败或受到协议限制的修改会以异常完成 future。规则应用后，如果原有观察者
执行失败，future 也会以异常完成，已经改变的运行值不会回滚。
取消返回的 future 不会撤销已经接受的操作。服务器关闭时，排队中和尚未完成的
操作 future 会以异常完成，已经应用的写入不会撤销。

通过普通 Bukkit 事件注册方式监听
`fun.bm.lophine.api.carpet.CarpetRuleChangeEvent`。事件携带修改前后的不可变规则
快照、变化来源；对于 API 发起的修改，还会携带请求插件的名称。
事件在全局区域派发，不可取消。规则命令、API 调用、单项配置应用和已完成的
配置文件重载产生实际运行值变化时，都会触发事件。启动加载、值未改变和仅保存
待重启值的操作不会触发事件。直接写入内部静态字段会绕过这套事件机制。

## Scarpet 函数扩展

```java
import fun.bm.lophine.api.carpet.*;
import java.util.concurrent.CompletableFuture;

ScarpetRegistration greeting = CarpetApi.get().registerScarpetFunction(
    this, "greeting", arguments ->
        CompletableFuture.completedFuture(new ScarpetValue.Text("你好"))
);
getLogger().info("Scarpet 函数名称：" + greeting.functionName());
// 名为 Demo 的插件会注册 plugin_demo__greeting()。

// 需要移除某项可选功能时，可以主动注销：
greeting.close();
```

函数名称由 `plugin_` 前缀、规范化后的插件名称、`__` 和局部函数名组成。
规范化会将插件名称转为小写，并将不属于标识符的字符替换为下划线。
局部函数名必须匹配 `[a-z][a-z0-9_]*`。重复的最终函数名会被拒绝，
包括不同插件名称经过规范化后产生的冲突。

注册只对新构建的表达式生效。已经加载的 Scarpet 应用需要重载才能使用新增函数。
关闭注册句柄后，已有表达式中的对应绑定也会立即失效；重新注册同名函数不会
恢复旧表达式的绑定。`unregisterScarpetFunctions(plugin)` 可以注销指定插件的
全部函数。插件禁用时，会在禁用事件监听器和 `onDisable` 执行前撤销注册，
并拒绝重新注册，直到该插件下一次启用。服务器关闭时会撤销所有注册。

这些注册和注销操作不阻塞，并且支持并发调用。已经接受的调用可能正在执行插件
代码；注销会使其待完成的结果 future 以异常完成，并丢弃迟到结果，但无法取消
插件自己已经调度的世界操作。

函数回调在 Scarpet 解释器线程上执行，不拥有实体或区域的访问权限，并且应尽快
返回。需要操作世界时，应向对应的实体或区域调度器提交任务，返回一个在操作
完成后才结束的 `CompletionStage<ScarpetValue>`。实体退役、调度被拒绝或插件
禁用时，应使该 stage 以异常完成。不要在回调中阻塞等待调度任务。
解释器等待返回的 stage 时会释放其执行锁，Folia tick 线程不会等待插件回调。

`ScarpetValue` 支持不可变的空值、布尔值、整数、浮点数、字符串、列表和 map
快照。整数保留完整的 64 位精度，map 支持不同类型的键。如果两个不同的 API 键
在 Scarpet 中相等，例如整数 `1` 和浮点数 `1.0`，返回该 map 时会报错，
避免静默覆盖数据。

实体、方块、函数值和惰性序列不能直接传递。请传递标识符或坐标，再在对应的
所属线程上解析和访问。每组参数或返回结果的转换最多允许 64 层容器深度，
并最多访问 16,384 个值。返回 Java `null` stage 或 `null` 结果，以及传入
包含循环引用的原生数据，都会报错。扩展函数按非纯函数注册，避免在脚本解析时
被常量折叠。
