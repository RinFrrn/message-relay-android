# 002 — 页面返回后保留列表位置与表单内容

- **Status**: TODO
- **Commit**: `4ef8947`
- **Severity**: HIGH
- **Category**: Interruptibility / Physicality（状态突变，位置"teleport"回顶部）
- **Estimated scope**: 1 文件（`app/src/main/java/io/github/messagerelay/MainActivity.kt`），约 60 处机械替换 + 4 个 Saver

> **依赖**：建议在 [001](001-navigation-stack-and-transitions.md) 之后执行（001 改了导航容器与 `subPage` 变量名）。若 001 未做，本方案仍可独立完成。

## Problem

用户反馈：「页面 pop 后上一页面列表位置回到顶部」。四个叠加根因：

**根因 A —— 页面卸载即丢弃全部 `remember` 状态。**
`MainActivity.kt:216-279` 的 `when (subPage)` 会把离开的页面**整体移出 composition**。`remember { mutableStateOf(...) }` 的生命周期绑定 composition，页面一卸载就清零。重新进入 = 全新状态。

**根因 B —— 滚动状态不保存。**
`MainActivity.kt:1364-1371` — current:

```kotlin
@Composable
private fun PageScaffold(title: String, subtitle: String? = null, modifier: Modifier = Modifier, colors: UiColors, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
```

`rememberScrollState()` 同样是裸 `remember`，随页面卸载一起消失。全应用 20 个页面共用这一个容器 → **每一个页面**都有这个问题。

**根因 C —— 旋转屏幕连输入内容都丢。**
`app/src/main/AndroidManifest.xml:12` — current:

```xml
        <activity android:name=".MainActivity" android:exported="true">
```

没有 `android:configChanges`。旋转 → Activity 销毁重建 → 所有 `remember` 状态归零。**用户填到一半的 Bark Webhook、加签密钥、自定义模板正文会全部清空**，这比"列表回顶部"严重得多。

**根因 D ——「软件选择」→「App 设置」后返回键回的是首页。**
`MainActivity.kt:218-221` 把 `subPage` 从 `AppSelection` 覆盖成 `AppRuleSettings`，`:197` 的 `BackHandler { subPage = null }` 直接跳回首页面签。由 [001](001-navigation-stack-and-transitions.md) 的栈式导航解决，本方案不重复处理。

顺带修掉的同族问题（`MainActivity.kt:290`）：

```kotlin
    var step by remember { mutableIntStateOf(0) }
```

Onboarding 的步骤号不保存，且 `:197` 的 `BackHandler(enabled = subPage != null)` 在 onboarding 期间恒为 false —— **配置到一半按返回直接退出应用**，回来从第 1 步重填。

## Target

所有「用户输入的内容」和「用户做出的选择」用 `rememberSaveable`；滚动位置由 `SaveableStateHolder` 托管，跨页面卸载存活。瞬时网络结果保留 `remember`。

### 1. 用 `SaveableStateHolder` 让整个子页状态跨卸载存活（核心做法）

```kotlin
/* MainActivity.kt 顶部新增 import */
// import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/* MessageRelayApp 内，AnimatedContent 之前 */
    val stateHolder = rememberSaveableStateHolder()

/* AnimatedContent 的 content lambda 内，包住整个 when(...) */
    stateHolder.SaveableStateProvider(key = page to activeTab) {
        when (page) {
            /* 原有 14 个分支 + null 分支，内容一字不改 */
        }
    }
```

关键点：`SaveableStateProvider(key)` 会把**作用域内所有 `rememberSaveable`** 存进 SavedStateRegistry，页面卸载后 key 对应的条目保留，重新进入同一 key 时全部恢复。key 必须含 `activeTab`，否则三个页签会互相覆盖保存值。

这是本方案的核心 —— 它一次性覆盖 20 个页面的滚动位置 + 25 处表单状态，不需要逐个提升变量。

### 2. `PageScaffold` 的滚动状态换成可保存版本

```kotlin
/* MainActivity.kt:1365 — target（只改这一行） */
    Column(modifier.fillMaxSize().verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }).padding(18.dp)) {
```

需要 import `androidx.compose.foundation.ScrollState`；现有 import `androidx.compose.foundation.rememberScrollState`（`:31`）替换后可删。

`ScrollState.Saver` 是 `androidx.compose.foundation.ScrollState.Companion.Saver`。若在 BOM `2025.12.01` 下不可见，改用 `rememberSaveable { mutableIntStateOf(0) }` 记 `value` 再喂给 `ScrollState(initial)`。**不要退回 `rememberScrollState()`** —— 它在第 1 步的 holder 里不会被保存。

`PageScaffold` 签名**保持不变**（不加 `scrollState` 参数）—— holder 已覆盖保存需求，加参数只扩大改动面。


### 3. 复杂类型的 Saver

```kotlin
/* MainActivity.kt 顶部新增 import */
// import androidx.compose.runtime.saveable.Saver
// import androidx.compose.runtime.saveable.listSaver
// import androidx.compose.runtime.saveable.rememberSaveable

/* 新增，放在 Motion object（001 插入的那个）之后 */
private const val PAIR_SEP = "

"   // 源码里必须写成 "\u0001"（ASCII SOH）

private val SourceSelectionListSaver: Saver<List<SourceSelection>, List<String>> = listSaver(
    save = { list -> list.flatMap { listOf(it.appName, it.packageName, it.templateId) } },
    restore = { flat -> flat.chunked(3).filter { it.size == 3 }.map { SourceSelection(it[0], it[1], it[2]) } }
)

private val AppPairSaver: Saver<Pair<String, String>?, String> = Saver(
    save = { pair -> pair?.let { it.first + PAIR_SEP + it.second } ?: "" },
    restore = { value ->
        if (value.isEmpty()) null
        else value.split(PAIR_SEP, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    }
)

private val CallTypesSaver: Saver<Set<CallEventType>, String> = Saver(
    save = { CallEventTypes.serialize(it) },   // 复用 CallRelay.kt:40 的既有实现
    restore = { CallEventTypes.parse(it) }     // 复用 CallRelay.kt:42 的既有实现
)

private val PackageSetSaver: Saver<Set<String>, List<String>> = listSaver(
    save = { it.toList() },
    restore = { it.toSet() }
)
```

三条硬性要求：

1. **`CallTypesSaver` 必须复用 `CallEventTypes.serialize` / `parse`**，不要自己写枚举序列化 —— 那是仓库里已有的、单测覆盖过的往返实现（`app/src/test/java/io/github/messagerelay/CoreLogicTest.kt`）。
2. **`PAIR_SEP` 在源码里必须写成 `"\u0001"`**（ASCII SOH，不可见控制字符；上面代码块里显示为空白是正常的）。App 名可能含空格或标点，**不要用竖线、空格或逗号**做分隔符。
3. `String` / `Boolean` / `Int` / 枚举 → **不写 Saver**，直接换关键字即可（Bundle 原生支持）。

### 4. Onboarding 允许回退 + 保留步骤

```kotlin
/* MainActivity.kt:290 — target */
    var step by rememberSaveable { mutableIntStateOf(0) }
```

```kotlin
/* Onboarding 内，step 声明之后新增 */
    BackHandler(enabled = step > 0) { step-- }
```

（`BackHandler` 已在 `:15` import。）这样第 2/3/4 步按返回退回上一步，第 1 步按返回才退出应用。

---

## 状态清单（逐处替换表）

### 必须换成 `rememberSaveable`（用户输入 / 用户选择）

| 行号 | 所在 composable | 变量 | 类型 | 处理 |
| --- | --- | --- | --- | --- |
| `:171` | `MessageRelayApp` | `tab` | `MainTab` 枚举 | `rememberSaveable` |
| `:173` | `MessageRelayApp` | `editingApp` | `Pair<String,String>?` | `rememberSaveable(saver = AppPairSaver)` |
| `:290` | `Onboarding` | `step` | `Int` | `rememberSaveable` |
| `:291-292` | `Onboarding` | `search` `manualPackage` | `String` | `rememberSaveable` |
| `:293` | `Onboarding` | `selectedSources` | `List<SourceSelection>` | `rememberSaveable(saver = SourceSelectionListSaver)` |
| `:294-299` | `Onboarding` | `selectedChannel` `dingtalk` `dingSecret` `feishu` `feiSecret` `bark` | `String` | `rememberSaveable` |
| `:428` | `Records` | `tab` | `String` | `rememberSaveable` |
| `:553-558` | `PushChannelScreen` | `type` `name` `url` `secret` `sound` `icon` | `String` | `rememberSaveable`（**`url`/`secret` 尤其重要 —— 旋转丢密钥是数据损失**） |
| `:654` | `BarkBindingCard` | `selected` | `Set<String>` | `rememberSaveable(saver = PackageSetSaver)` |
| `:696` | `SimpleAppSelectionScreen` | `search` | `String` | `rememberSaveable` |
| `:726-730` | `AppRuleSettingsScreen` | `enabled` `screenOffOnly` `includes` `excludes` `templateId` | `Boolean`/`String` | `rememberSaveable` |
| `:731-733` | `AppRuleSettingsScreen` | `callTypes` | `Set<CallEventType>` | `rememberSaveable(saver = CallTypesSaver)` |
| `:816-818` | `QuietHoursScreen` | `start` `end` `urgent` | `String` | `rememberSaveable` |
| `:845` | `BackupRestoreScreen` | `backupText` | `String` | `rememberSaveable`（**粘贴进来的备份 JSON 不能因为旋转而消失**） |
| `:874` | `AdvancedSettingsScreen` | `retryCount` | `String` | `rememberSaveable` |
| `:900-902` | `Rules` | `search` `include` `exclude` | `String` | `rememberSaveable` |
| `:901` | `Rules` | `selectedApp` | `Pair<String,String>?` | `rememberSaveable(saver = AppPairSaver)` |
| `:903` | `Rules` | `screenOffOnly` | `Boolean` | `rememberSaveable` |
| `:904` | `Rules` | `callTypes` | `Set<CallEventType>` | `rememberSaveable(saver = CallTypesSaver)` |
| `:1114-1116` | `TemplateLibrary` | `name` `title` `body` | `String` | `rememberSaveable`（**自定义模板正文不能因为旋转而消失**） |
| `:1347` | `ManualChapter` | `expanded` | `Boolean` | `rememberSaveable` |

### 保持 `remember` 不变（瞬时结果，重建后重取更合理）

| 行号 | 变量 | 理由 |
| --- | --- | --- |
| `:300-301` | `testPassed` `status` | **故意不保存 `testPassed`** —— 保存它会让用户旋转屏幕后绕过"至少一个渠道测试成功"的门槛（`:347`）直接完成配置，与 `AGENTS.md` 规则 5 的精神相悖。**照做，不要"顺手优化"。** |
| `:429` | `selected: DeliveryRecord?` | 弹窗对象，关掉即弃 |
| `:495` | `showAdvancedDialog` | 弹窗可见性 |
| `:551` | `refreshKey` | 强制刷新用的 hack |
| `:552` | `existing` | 派生值，见 [003](003-app-selection-performance.md) |
| `:559` `:734` `:846` `:905` | `status` `backupStatus` `ruleError` | 反馈文案，瞬时 |
| `:786` `:1117-1118` | `preview` | 瞬时 |
| `:1030` | `result: UpdateCheckResult?` | 网络结果对象，非 Bundle 类型 |
| `:1031-1032` | `checking` `detailsExpanded` | 瞬时 |
| `:374` | `since` | 每次启动重新取当天零点（保存反而会跨天出错） |

---

## Repo conventions to follow

- 状态声明范式：`var xxx by remember { mutableStateOf(initial) }` + `import androidx.compose.runtime.getValue / setValue`（`:68, 73`）。换 `rememberSaveable` 时**保持 `by` 委托写法不变**，只换关键字。
- 自定义序列化参照仓库既有做法：`ChannelSender.serialize/parse`（`ChannelSender.kt:80, 102`）、`CallEventTypes.serialize/parse`（`CallRelay.kt:40, 42`）都是 `object` 里的静态往返方法。新增的 Saver 放文件级 `private val`。
- 页面容器范式见 `PageScaffold`（`:1364`）；语义化写法参考 `ManualChapter`（`:1346-1361`）。

## Steps

1. 补 import：`androidx.compose.runtime.saveable.rememberSaveable`、`androidx.compose.runtime.saveable.Saver`、`androidx.compose.runtime.saveable.listSaver`、`androidx.compose.runtime.saveable.rememberSaveableStateHolder`、`androidx.compose.foundation.ScrollState`。
2. 在 `Motion` object（001 插入的那个）之后插入 4 个 Saver + `PAIR_SEP` 常量，**照抄 Target 第 3 节**。
3. 采用 Target 第 1 节：`MessageRelayApp` 内加 `val stateHolder = rememberSaveableStateHolder()`，用 `stateHolder.SaveableStateProvider(key = page to activeTab) { ... }` 包住整个页面 `when`。key 必须含 `activeTab`。
4. 把 `PageScaffold`（`:1365`）的 `rememberScrollState()` 换成 `rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }`。签名不变。
5. 按「状态清单」把 21 处 `remember` 换成 `rememberSaveable`（含 4 处带 `saver =`）。「保持 remember 不变」那一栏的 11 组**一个都不要动**。
6. 在 `Onboarding` 的 `step` 声明之后加 `BackHandler(enabled = step > 0) { step-- }`。
7. 跑 Verification 的机械部分。

## Boundaries

- **Do NOT** 改 `app/src/main/AndroidManifest.xml`。加 `android:configChanges` 是另一种思路（拦掉重建），但它会绕过 Activity 重建导致 `Configuration` 更新不完整、`LocalConfiguration` 不刷新，是已知的坑。本方案用 `rememberSaveable` 正面解决。
- **Do NOT** 改任何 Room Entity / Migration / DataStore 字段（`AGENTS.md` 规则 3、4）。
- **Do NOT** 清空或迁移任何用户已保存配置（`AGENTS.md` 规则 5）。
- **Do NOT** 把 `testPassed`、`result`、`selected` 等瞬时状态改成 saveable（见上表理由）。
- **Do NOT** 改任何 `XxxScreen` 的业务逻辑、文案或布局结构 —— 本方案**只换状态声明关键字** + 加一层 `SaveableStateProvider` 包装。
- **Do NOT** 引入新的第三方依赖（`androidx.compose.runtime.saveable` 随 compose-bom 已在 classpath）。
- 若某一步与你找到的代码对不上（本方案基于 commit `4ef8947`），**停下来报告**，不要即兴发挥。

## Verification

- **Mechanical**:

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon --console=plain
  ./gradlew :app:testDebugUnitTest --no-daemon --console=plain
  ```

  预期：编译 0 error；单测全绿（不得破坏 `CoreLogicTest` 里对 `CallEventTypes` 的覆盖）。Windows 用 `.\gradlew.bat`（见 `AGENTS.md` 常用命令）。

- **Feel check**（真机，必须做 —— 状态保存问题在模拟器旋转上常表现不一致）：

  1. **列表位置**：首页向下滚到「最近记录」→ 点「软件选择」→ 滚到列表中部 → 点某 App 的「设置」→ 按返回。**必须回到「软件选择」且停在刚才的滚动位置**（不回顶部）。→ 这条同时依赖 [001](001-navigation-stack-and-transitions.md)。
  2. **表单不丢**：设置 → 推送渠道 → 在「渠道地址」填一串字符 → **旋转屏幕** → 确认输入框里那串字符**还在**。
  3. **模板不丢**：高级设置 → 自定义消息模板 → 在「正文样式」里写一段带换行的自定义模板 → 旋转屏幕 → 确认内容完整保留（含换行）。
  4. **备份不丢**：备份与恢复 → 粘贴一段长文本 → 旋转屏幕 → 确认还在。
  5. **搜索词不丢**：软件选择 → 搜索框输入「微信」→ 进某个 App 设置 → 返回 → 确认搜索框仍是「微信」且列表仍是过滤结果。
  6. **Onboarding 回退**：清除应用数据进入首次配置 → 走到第 3 步 → 按返回键两次。**必须退回到第 1 步**，而不是退出应用。走到第 1 步再按返回才退出。
  7. **Onboarding 进程存活**：走到第 3 步 → 切到别的应用 → 系统开发者选项「不保留活动」打开 → 切回来。确认**仍在第 3 步**，且第 2 步选的来源 App 还在。

- **Done when**：
  - 两条 gradle 命令通过
  - Feel check 1~7 全部通过
  - 第 2、3 条是验收硬门槛（旋转丢输入 = 数据损失，不能接受）
