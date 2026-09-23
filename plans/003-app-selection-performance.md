# 003 — 应用选择页与列表性能

- **Status**: TODO
- **Commit**: `4ef8947`
- **Severity**: HIGH
- **Category**: Performance
- **Estimated scope**: 1 文件（`app/src/main/java/io/github/messagerelay/MainActivity.kt`），约 70 行改动

> **依赖**：与 [001](001-navigation-stack-and-transitions.md)、[002](002-state-restoration.md) 无硬依赖，可并行。但若 002 已做，`search` 等状态已换成 `rememberSaveable`，本方案的 `remember(search, apps)` 写法不受影响。

## Problem

用户反馈：「app 选择页面卡顿」。四个叠加根因，按严重度排：

**根因 A —— 主线程上逐 App 做 Binder IPC。**
`MainActivity.kt:1521-1530` — current:

```kotlin
private fun loadInstalledApps(context: Context): List<Pair<String, String>> =
    runCatching {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
            .map { appInfo -> appInfo.loadLabel(packageManager).toString() to appInfo.packageName }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase() }.thenBy { it.second })
    }.getOrDefault(emptyList())
```

`appInfo.loadLabel(packageManager)` 对**每一个**已安装 App 都会解析它的资源表去取应用名 —— 这是一次跨进程调用。200~400 个 App = 200~400 次 Binder IPC + 资源解析 + Unicode 排序，全部在**主线程**上同步完成。

调用点（三处，全在 composition 中执行）：
- `MainActivity.kt:289` — `Onboarding`：`val apps = remember { loadInstalledApps(context) }`
- `MainActivity.kt:695` — `SimpleAppSelectionScreen`：`val installedApps = remember { loadInstalledApps(context) }`
- `MainActivity.kt:898` — `Rules`：`val apps = remember { loadInstalledApps(context) }`

**根因 B —— `remember` 挂在页面内，每次进页重跑。**
`remember` 的生命周期跟 composition 绑定，而这三个页面在 `when(subPage)` 切走时就被卸载（见 [002](002-state-restoration.md) 根因 A）。所以**每进一次「软件选择」就重新做一遍数百次 IPC**。这就是用户体感的"卡一下"。

**根因 C —— 非虚拟化列表 + 每个按键全量重算。**
`MainActivity.kt:707-714` — current:

```kotlin
        SectionCard("其他应用", "简单模式不显示复杂包名说明，需要细节可到高级设置。", Icons.Outlined.List, colors) {
            OutlinedTextField(search, { search = it }, label = { Text("搜索应用") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            installedApps.filter { search.isBlank() || it.first.contains(search, true) || it.second.contains(search, true) }.take(20).forEach { (name, pkg) ->
                SimpleAppRow(name, pkg, rules.firstOrNull { it.packageName == pkg }, settings.selectedTemplatePreset, colors, onOpenAppSettings) { rule ->
                    scope.launch { dao.saveRule(rule) }
                }
            }
        }
```

三个问题叠一起：
1. 外层是 `Column + verticalScroll`（`PageScaffold:1365`），**没有虚拟化**，20 行全部一次性 compose。
2. 过滤表达式在**每次重组**时重算，输入每个字符都全量跑一遍 `contains`（O(n×m)）。
3. 行内 `rules.firstOrNull { it.packageName == pkg }` 是 O(n×m) —— 20 行 × 规则数，且每行各查一次。

同样的模式还在 `Rules`（`:909`）和 `Onboarding`（`:1183`）里重复了一遍。

**根因 D —— Keystore 解密在重组路径上。**
`MainActivity.kt:379`（`Home`）和 `:564`（`PushChannelScreen`）— current:

```kotlin
    val channels = ChannelSelection.normalized(storedChannels(context))
```

配合 `MainActivity.kt:1498-1499` — current:

```kotlin
private fun storedChannels(context: Context): List<ChannelConfig> =
    SecureStore(context).get("channels")?.let { runCatching { ChannelSender.parse(it) }.getOrDefault(emptyList()) }.orEmpty()
```

`SecureStore.get` 走 Android Keystore 解密，单次 10~50ms。这行**在 composable 体内、没有 `remember`**，所以**每次重组都解密一次**。`Home` 上任何状态变化（今日计数、待发送数、规则列表）都会触发重组 → 触发解密。

`PushChannelScreen` 尤其荒谬：`:552` 用 `remember { ... }` 存了 `existing`，`:564` 却又**不 remember** 地算了一遍 `channels`，两份数据来源还不一致。旁边还有一个为了强制刷新而存在的假状态（`:551-563`）:

```kotlin
    var refreshKey by remember { mutableIntStateOf(0) }
    val existing = remember { ChannelSelection.normalized(storedChannels(context)) }
    ...
    @Suppress("UNUSED_VARIABLE")
    val currentRefresh = refreshKey
    val channels = ChannelSelection.normalized(storedChannels(context))
```

## Target

### 1. 应用列表改成进程级缓存 + 后台加载

新增一个文件 `app/src/main/io/github/messagerelay/InstalledApps.kt`（**按 `AGENTS.md` 规则 1「不要把全部代码继续堆进 `MainActivity.kt`」，这段逻辑放独立文件**）：

```kotlin
package io.github.messagerelay

import android.content.Context
import android.content.pm.ApplicationInfo

/** 已安装应用列表的进程级缓存。loadLabel 是逐 App 的 Binder IPC，绝不能在 composition 里同步跑。 */
object InstalledAppsCache {
    @Volatile
    private var cached: List<Pair<String, String>>? = null

    fun cachedOrNull(): List<Pair<String, String>>? = cached

    suspend fun load(context: Context): List<Pair<String, String>> =
        cached ?: withContext(Dispatchers.IO) { loadBlocking(context.applicationContext) }.also { cached = it }

    private fun loadBlocking(context: Context): List<Pair<String, String>> =
        runCatching {
            val packageManager = context.packageManager
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
                .map { appInfo: ApplicationInfo -> appInfo.loadLabel(packageManager).toString() to appInfo.packageName }
                .filter { it.second.isNotBlank() }
                .distinctBy { it.second }
                .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase() }.thenBy { it.second })
        }.getOrDefault(emptyList())
}
```

需要 import `kotlinx.coroutines.Dispatchers`、`kotlinx.coroutines.withContext`（`MainActivity.kt:85-86` 已 import，新文件自己带）。

`MainActivity.kt:1521-1530` 的 `loadInstalledApps` **删除**，三个调用点统一改用下面这个 composable：

```kotlin
/* MainActivity.kt 新增 */
@Composable
private fun rememberInstalledApps(): List<Pair<String, String>> {
    val context = LocalContext.current
    val initial = remember { InstalledAppsCache.cachedOrNull() ?: emptyList() }
    val apps by remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) {
        val loaded = InstalledAppsCache.load(context)
        (apps as? androidx.compose.runtime.MutableState)?.value   // ← 见下方说明
    }
    return apps
}
```

> ⚠️ 上面是**意图示意**。可直接粘贴的实现用 `produceState`：
>
> ```kotlin
> @Composable
> private fun rememberInstalledApps(): List<Pair<String, String>> {
>     val context = LocalContext.current
>     val initial = remember { InstalledAppsCache.cachedOrNull() ?: emptyList() }
>     val apps by produceState(initialValue = initial, context) {
>         value = InstalledAppsCache.load(context)
>     }
>     return apps
> }
> ```
>
> 需要 import `androidx.compose.runtime.produceState`。**用 `produceState` 这个版本。**

效果：首次进入显示空列表（或上次缓存），加载完成后自动填入；第二次起**零延迟**（走 `cached`）。主线程不再被 IPC 阻塞。

### 2. 列表改成 `LazyColumn` + 记忆化过滤

`SimpleAppSelectionScreen`（`:697-715`）的「其他应用」段 — target 结构：

```kotlin
        SectionCard("其他应用", "简单模式不显示复杂包名说明，需要细节可到高级设置。", Icons.Outlined.List, colors) {
            OutlinedTextField(search, { search = it }, label = { Text("搜索应用") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val filtered by remember(installedApps, search) {
                derivedStateOf {
                    installedApps.filter {
                        search.isBlank() || it.first.contains(search, true) || it.second.contains(search, true)
                    }.take(20)
                }
            }
            val rulesByPackage = remember(rules) { rules.associateBy(RuleEntity::packageName) }
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                state = rememberLazyListState()
            ) {
                items(filtered, key = { it.second }) { (name, pkg) ->
                    SimpleAppRow(
                        name, pkg, rulesByPackage[pkg], settings.selectedTemplatePreset, colors, onOpenAppSettings
                    ) { rule -> scope.launch { dao.saveRule(rule) } }
                }
            }
        }
```

新增 import：`androidx.compose.foundation.lazy.LazyColumn`、`androidx.compose.foundation.lazy.items`、`androidx.compose.foundation.lazy.rememberLazyListState`、`androidx.compose.foundation.layout.heightIn`、`androidx.compose.runtime.derivedStateOf`。

三个要点：
1. **`remember(rules) { rules.associateBy(...) }`** 把 O(n×m) 的 `firstOrNull` 降到 O(n+m)，且只在 `rules` 变化时重建。
2. **`key = { it.second }`** 用包名做稳定 key —— 让 Compose 在过滤结果变化时复用节点、不整列重建。
3. **`heightIn(max = 480.dp)`** —— `LazyColumn` 必须有确定高度，否则在 `Column + verticalScroll` 里会塌成 0 或无限高。480dp 大约能显示 8 行，配合滚动足够，且不会把整页撑得过长。

「推荐应用」段（`:698-705`）只有 3 项，**保持 `forEach` 不动**，但把 `recommendedApps(installedApps)` 提到一次 `remember`（现在 `:699` 和 `:704` 各调了一次）：

```kotlin
            val recommended = remember(installedApps) { recommendedApps(installedApps) }
            recommended.forEach { (name, pkg) -> ... }
            if (recommended.isEmpty()) EmptyText("暂未识别到短信、电话、微信，可在其他应用里搜索。", colors)
```

`Rules`（`:909-917`）和 `Onboarding`（`:1183-1197`）的列表用**同样的模式**改（都只有 `.take(12)` / `.take(20)`，量小，但 `rulesByPackage` / `associateBy` 的记忆化必须做，因为 `rules` 变化会触发全列重算）。

### 3. `storedChannels` 记忆化

`MainActivity.kt:1498-1499` 保持不变（它是纯函数），但两个调用点必须包 `remember`：

```kotlin
/* :379 — Home 内，target */
    val channels = remember { ChannelSelection.normalized(storedChannels(context)) }

/* :564 — PushChannelScreen 内，target：直接删掉这一行，统一用 :552 的 existing */
```

并把 `PushChannelScreen` 的假刷新状态（`:551-563`）换成一个真状态：

```kotlin
/* :551-564 — target */
    var channels by remember { mutableStateOf(ChannelSelection.normalized(storedChannels(context))) }
    var type by rememberSaveable { mutableStateOf(channels.firstOrNull()?.type ?: "bark") }
    var name by rememberSaveable { mutableStateOf(channels.firstOrNull()?.name ?: "Bark 1") }
    var url by rememberSaveable { mutableStateOf(channels.firstOrNull()?.url.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(channels.firstOrNull()?.secret.orEmpty()) }
    var sound by rememberSaveable { mutableStateOf(channels.firstOrNull()?.sound.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(channels.firstOrNull()?.icon.orEmpty()) }
    var status by remember { mutableStateOf("") }
    val secureStore = remember { SecureStore(context) }
    val unreadableChannels = remember { secureStore.hasUnreadableValue("channels") }
```

原来所有 `refreshKey++` 的地方（`:617`、`:638`）改成**重新读一次并赋值**：

```kotlin
                    channels = ChannelSelection.normalized(storedChannels(context))
```

这样 `@Suppress("UNUSED_VARIABLE") val currentRefresh = refreshKey` 这个 hack（`:562-563`）可以整体删除。

> 注意：`channels` 现在是 `var by remember { mutableStateOf(...) }`，`existing` 变量可以删掉（它的值就是 `channels` 的初值）。`unreadableChannels` 同理包上 `remember` —— 它也走了一次 Keystore。

### 4. `Home` 的记录查询只取 5 条

`MainActivity.kt:377` — current:

```kotlin
    val records by dao.records().collectAsState(initial = emptyList())
```

`:416` 只用 `records.take(5)`。全表拉进内存只为 5 条。

在 `RelayDao`（`Database.kt:54` 附近）加一条带 LIMIT 的查询：

```kotlin
    @Query("SELECT * FROM DeliveryRecord ORDER BY createdAt DESC LIMIT 5") fun recentRecords(): Flow<List<DeliveryRecord>>
```

（请对齐 `records()` 既有的 `ORDER BY` 方向 —— 以 `Database.kt` 里 `records()` 的实际写法为准，**不要改变排序方向**。）

`Home` 内改成：

```kotlin
    val records by dao.recentRecords().collectAsState(initial = emptyList())
```

`:416` 的 `records.take(5)` 改成直接用 `records`。

> 这是 `RelayDao` 的**新增查询**，不是 Entity 改动，**不需要 Migration**（`AGENTS.md` 规则 3 只约束 Entity 变更）。

## Repo conventions to follow

- 业务对象独立成文件的范式见 `TemplateCatalog.kt`、`PerAppRouting.kt`、`SmsDuplicateGuard.kt` —— 都是 `object` + 纯函数，无 Android 依赖（除 Context 参数）。新增的 `InstalledAppsCache` 照这个风格。
- Room 查询范式见 `Database.kt:52-60`（`@Query` + `Flow`）。新增查询照抄格式。
- Composable 里取 Context 用 `LocalContext.current`（`:156`、`:286` 等）。**注意 `AGENTS.md` 规则 2：UI 不应直接执行数据库和网络操作** —— 本方案里 `dao.saveRule` 的既有写法不动（它已走 `scope.launch`），但不要新增同步 DB 调用。

## Steps

1. 新建 `app/src/main/java/io/github/messagerelay/InstalledApps.kt`，内容照抄 Target 第 1 节。
2. `MainActivity.kt` 删除 `loadInstalledApps`（`:1521-1530`），新增 `rememberInstalledApps()`（**用 `produceState` 版本**），import `androidx.compose.runtime.produceState`。
3. `:289` / `:695` / `:898` 三处 `remember { loadInstalledApps(context) }` 改成 `rememberInstalledApps()`。
4. `SimpleAppSelectionScreen` 的「其他应用」段按 Target 第 2 节改成 `LazyColumn` + `derivedStateOf` 过滤 + `rulesByPackage` 记忆化；「推荐应用」段把 `recommendedApps(installedApps)` 提成一次 `remember`。
5. `Rules`（`:909`）和 `Onboarding`（`:1183`）的列表按同样模式加 `associateBy` 记忆化。
6. `PushChannelScreen` 按 Target 第 3 节重写状态块，删除 `refreshKey` / `currentRefresh` / `existing`，把两处 `refreshKey++` 改成重读 `channels`。
7. `Home`（`:379`）的 `storedChannels` 包 `remember`。
8. `Database.kt` 加 `recentRecords()` 查询；`Home`（`:377`、`:416`）改用它。
9. 跑 Verification。

## Boundaries

- **Do NOT** 改 `loadInstalledApps` 的排序规则、`distinctBy`、`filter` 语义 —— 只换执行线程和缓存位置。排序变化会让用户"找不到刚才那个 App"。
- **Do NOT** 改 `SimpleAppRow` / `recommendedApps` 的内部实现。
- **Do NOT** 加 Room Migration —— 本方案只新增 `@Query`，不动 Entity（`AGENTS.md` 规则 3）。
- **Do NOT** 改 `Home` / `Records` 的展示结构（虚拟化记录页属于本方案的可选项，见下）。
- **Do NOT** 引入新的第三方依赖（`kotlinx-coroutines`、`androidx.compose.foundation` 已在 classpath）。
- 若某一步与你找到的代码对不上（本方案基于 commit `4ef8947`），**停下来报告**，不要即兴发挥。

**可选扩展（超出本次反馈范围，未列入 Steps）**：`Records`（`:447`）同样是一次性 compose 全部记录，记录量大时会卡。若要一并改，用同样的 `LazyColumn + items(key = { it.id })` 模式。但它需要同时改 `visible` 的过滤写法（改成 `derivedStateOf`），改动面比本次反馈大，**默认不做**，需要时另起一份方案。

## Verification

- **Mechanical**:

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon --console=plain
  ./gradlew :app:lintDebug --no-daemon --console=plain
  ```

  预期：两条 0 error。`lintDebug` 不得新增 `NewApi`。

- **Feel check**（中低端真机优先 —— 高端机上卡顿不明显，验不出来）：

  1. **进页不卡**：首页 → 「软件选择」。**首次**进入可有短暂空列表后填入（这是预期的异步加载），但**页面必须立刻可交互**（标题、开关、滚动都不该卡住）。**第二次**起进入必须是瞬时的（走缓存）。
  2. **搜索不掉帧**：在搜索框里**连续快速输入** 10 个字符。输入必须跟手，不能有明显顿挫。
  3. **滚动流畅**：「其他应用」列表上下滚动，无明显丢帧。
  4. **返回不重载**：进「软件选择」→ 等列表加载完 → 进某 App 设置 → 返回。**列表必须立刻显示**（不重新加载）。
  5. **首页不卡**：首页上反复切开关（转发开关），确认没有顿挫 —— 这条验证的是 `storedChannels` 记忆化（原先每次重组都解密 Keystore）。
  6. **渠道页不卡**：推送渠道页上反复修改「渠道名称」输入框，确认输入跟手。
  7. **刷新仍有效**：推送渠道页保存一个渠道 → 确认「主推送渠道」列表**立即出现**新渠道（验证 `refreshKey` 删除后刷新逻辑仍通）。
  8. **首页记录正确**：首页「最近记录」最多 5 条，且与记录页前 5 条一致（验证 `recentRecords()` 的 `ORDER BY` 与 `records()` 一致）。

- **Feel check（性能量化，可选但推荐）**：

  ```bash
  adb shell am start -W io.github.messagerelay/.MainActivity
  ```

  冷启动 + 点进「软件选择」，用 Android Studio Profiler 看主线程。**目标**：点进「软件选择」时不出现持续 > 100ms 的主线程块（原先会出现 300~800ms）。

- **Done when**：
  - 两条 gradle 命令通过
  - Feel check 1~8 全部通过
  - 第 1、2 条是验收硬门槛（直接对应用户反馈的"卡顿"）
