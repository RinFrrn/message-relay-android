# 001 — 页面导航改为栈式 + 加入进出场转场

- **Status**: TODO
- **Commit**: `4ef8947`
- **Severity**: HIGH
- **Category**: Missed opportunities（完全无过渡）+ Interruptibility / 信息架构（返回栈缺失）
- **Estimated scope**: 1 文件（`app/src/main/java/io/github/messagerelay/MainActivity.kt`），约 90 行改动

## Problem

导航完全由一个**单槽** nullable 状态驱动，没有栈、没有转场。

`app/src/main/java/io/github/messagerelay/MainActivity.kt:172` — current:

```kotlin
var subPage by remember { mutableStateOf<SubPage?>(null) }
```

`app/src/main/java/io/github/messagerelay/MainActivity.kt:197` — current:

```kotlin
BackHandler(enabled = subPage != null) { subPage = null }
```

`app/src/main/java/io/github/messagerelay/MainActivity.kt:216-279` — current（节选，`when` 结构不变，此处为关键分支）:

```kotlin
            when (subPage) {
                SubPage.PushChannels -> PushChannelScreen(modifier, settings, repository, colors)
                SubPage.AppSelection -> SimpleAppSelectionScreen(modifier, settings, repository, colors) { app ->
                    editingApp = app
                    subPage = SubPage.AppRuleSettings
                }
                // ...
                null -> when (tab) {
                    MainTab.Home -> Home(/* ... */)
                    MainTab.Records -> Records(modifier, colors) { subPage = SubPage.AdvancedRules }
                    MainTab.Settings -> SettingsHub(/* ... */)
                }
            }
```

两个后果：

1. **进入和返回都是 0ms 硬切。** `when` 换分支 = 直接卸载旧页面、装载新页面，没有 `AnimatedContent` / `Crossfade` / 任何形式的过渡。用户报告的「页面进入返回没有过渡动画」即此。
2. **返回栈是假的。** `subPage` 只有一个槽位。从「软件选择」点进某个 App 的「设置」时（`:218-221`），`subPage` 被**覆盖**成 `SubPage.AppRuleSettings`，原来那个 `SubPage.AppSelection` 就没了。此时按返回键，`BackHandler` 执行 `subPage = null` → 回到**首页页签**，而不是回到「软件选择」。用户丢失上一级位置，只能重新点一遍入口。

顺带：`app/build.gradle.kts:43` 声明了 `androidx.navigation:navigation-compose:2.9.6`，但全仓库零调用点。本方案**不引入 NavHost**（为 14 个扁平子页引入 NavHost 收益低、改动面大），而是用一个显式栈 + `AnimatedContent` 解决，`navigation-compose` 的去留见 [006](006-dead-actions-and-polish.md)。

## Target

用 `ArrayDeque<SubPage>` 作为显式返回栈，`AnimatedContent` 驱动转场。推进（push）与弹出（pop）方向相反。

**动效参数（不得近似，照抄）：**

| 场景 | 时长 | 缓动 | 位移 |
| --- | --- | --- | --- |
| push（进入子页） | `280ms` | `CubicBezier(0.32f, 0.72f, 0f, 1f)` | 新页从右侧 `30%` 自身宽处滑入 + 淡入；旧页向左退 `15%` 自身宽 + 淡出 |
| pop（返回上一级） | `280ms` | `CubicBezier(0.32f, 0.72f, 0f, 1f)` | 与 push **严格镜像**（新页从左 `15%` 滑入，旧页向右 `30%` 滑出） |
| Tab 切换 | `180ms` | `CubicBezier(0.23f, 1f, 0.32f, 1f)` | **只淡入淡出，不位移**（页签是并列关系，没有空间方向） |

说明：
- `280ms` 落在 AUDIT.md「Modals, drawers 200–500ms」区间，且低于「UI 动画 < 300ms」上限。
- `CubicBezier(0.32f, 0.72f, 0f, 1f)` = AUDIT.md 的 `--ease-drawer`（iOS 式抽屉曲线），横向页面推进的标准手感。
- `CubicBezier(0.23f, 1f, 0.32f, 1f)` = AUDIT.md 的 `--ease-out`（强 ease-out），用于进场/出场。
- **位移一律用自身尺寸百分比换算，不写死 dp/px**（AUDIT.md §8：`translate` 百分比表达「元素自身高度/宽度」，禁止硬编码像素偏移）。`slideInHorizontally` 的 `initialOffsetX` lambda 收到的 `fullWidth` 就是元素自身宽度，`(fullWidth * 0.30f).toInt()` 即 30%。
- 位移与透明度**同时**进行，不要纯淡入淡出的进场（AUDIT.md §3：pure-fade entrances with no initial transform 是要抓的问题）。

**降级动效（系统开启「移除动画」时）：**

AUDIT.md §6：「Reduced motion means fewer and gentler animations, **not zero** —— keep transitions that aid comprehension, remove position changes.」

即：位移去掉，**保留 150ms 淡入淡出**。读取 `Settings.Global.ANIMATOR_DURATION_SCALE`，值为 `0f` 时走降级分支。

**目标代码骨架**（`MainActivity.kt`，替换 `:172` 与 `:216-279`）：

```kotlin
// 文件顶部新增 import
// import androidx.compose.animation.AnimatedContent
// import androidx.compose.animation.core.CubicBezier
// import androidx.compose.animation.core.tween
// import androidx.compose.animation.fadeIn
// import androidx.compose.animation.fadeOut
// import androidx.compose.animation.slideInHorizontally
// import androidx.compose.animation.slideOutHorizontally
// import androidx.compose.animation.togetherWith
// import androidx.compose.runtime.mutableStateListOf

// MainActivity.kt 新增：动效 token（放在 UiColors 定义之后，约 :119）
private object Motion {
    val EaseDrawer = CubicBezier(0.32f, 0.72f, 0f, 1f)   // --ease-drawer
    val EaseOut = CubicBezier(0.23f, 1f, 0.32f, 1f)      // --ease-out
    const val NAV_DURATION_MS = 280
    const val TAB_DURATION_MS = 180
    const val FADE_ONLY_DURATION_MS = 150
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
```

```kotlin
// :172 替换为
    var tab by remember { mutableStateOf(MainTab.Home) }
    val subStack = remember { mutableStateListOf<SubPage>() }
    val subPage = subStack.lastOrNull()
    var editingApp by remember { mutableStateOf<Pair<String, String>?>(null) }
    var navDirection by remember { mutableStateOf(1) }   // 1 = push, -1 = pop
    val reducedMotion = rememberReducedMotion()

    fun push(page: SubPage) { navDirection = 1; subStack.add(page) }
    fun pop() { navDirection = -1; if (subStack.isNotEmpty()) subStack.removeAt(subStack.lastIndex) }
```

```kotlin
// :197 替换为
        BackHandler(enabled = subStack.isNotEmpty()) { pop() }
```

```kotlin
// :216-279 的 when 包进 AnimatedContent；只列骨架，各分支内部 lambda 保持原样
        ) { padding ->
            val modifier = Modifier.padding(padding)
            val navSpec = if (reducedMotion) {
                fadeIn(tween(Motion.FADE_ONLY_DURATION_MS, easing = Motion.EaseOut)) togetherWith
                    fadeOut(tween(Motion.FADE_ONLY_DURATION_MS, easing = Motion.EaseOut))
            } else {
                val enterD = tween<Offset>(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)
                val exitD = tween<Offset>(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)
                if (navDirection >= 0) {
                    (slideInHorizontally(enterD) { (it * 0.30f).toInt() } + fadeIn(enterD)) togetherWith
                        (slideOutHorizontally(exitD) { (-it * 0.15f).toInt() } + fadeOut(exitD))
                } else {
                    (slideInHorizontally(enterD) { (-it * 0.15f).toInt() } + fadeIn(enterD)) togetherWith
                        (slideOutHorizontally(exitD) { (it * 0.30f).toInt() } + fadeOut(exitD))
                }
            }
            val tabSpec = fadeIn(tween(Motion.TAB_DURATION_MS, easing = Motion.EaseOut)) togetherWith
                fadeOut(tween(Motion.TAB_DURATION_MS, easing = Motion.EaseOut))

            AnimatedContent(
                targetState = subPage to tab,
                transitionSpec = { if (subPage != targetState.first) navSpec else tabSpec },
                label = "main-nav"
            ) { (page, activeTab) ->
                val modifier = Modifier.padding(padding)
                when (page) {
                    // ... 原有 14 个分支，内容 lambda 一字不改
                    // 唯一改动：原来写 subPage = SubPage.X 的地方改成 push(SubPage.X)
                }
            }
        }
```

> ⚠️ 上面 `transitionSpec` 里 `subPage != targetState.first` 的写法在 `AnimatedContent` 的 scope 内拿不到外层 `subPage`，实际实现请改用 `navDirection` 判定方向、用 `initialState.first == targetState.first` 判定「同为 null 且只是 Tab 变了」→ 走 `tabSpec`，否则走 `navSpec`。若一步判定不干净，可把 Tab 切换与子页切换拆成两层 `AnimatedContent`（外层 Tab、内层 subStack）—— 两层更清晰，优先采用两层。

**回填点清单**（原 `subPage = SubPage.X` → `push(SubPage.X)`）：

| 行号 | 原调用 | 所在位置 |
| --- | --- | --- |
| `:220` | `subPage = SubPage.AppRuleSettings` | `SimpleAppSelectionScreen` 的 `onOpenAppSettings` |
| `:232` | `subPage = SubPage.AdvancedRules` | `AdvancedSettingsScreen` 的 `onOpenRules` |
| `:233` | `subPage = SubPage.AdvancedTemplates` | `AdvancedSettingsScreen` 的 `onOpenTemplates` |
| `:240` | `subPage = SubPage.AppRuleSettings` | `SubPage.AppRuleSettings` 分支的兜底 |
| `:252`~`:257` | `subPage = SubPage.PushChannels` 等 6 处 | `Home` 的 `onOpenXxx` |
| `:260` | `subPage = SubPage.AdvancedRules` | `Records` 的 `onAdjustRules` |
| `:266`~`:276` | `subPage = SubPage.Manual` 等 7 处 | `SettingsHub` 的 `onOpenXxx` |

## Repo conventions to follow

- 全部 UI 都在 `app/src/main/java/io/github/messagerelay/MainActivity.kt` 单文件内，composable 用 `private fun` + `@Composable`，颜色走传参的 `colors: UiColors`，页面容器是 `PageScaffold(title, subtitle, modifier, colors) { ... }`（`:1364`）。新增的 `Motion` object 按同样风格放在文件级 `private object`（可参考 `TemplateCatalog` / `RecordRetentionPolicy` 的 object 写法）。
- 屏幕签名范式：`private fun XxxScreen(modifier: Modifier, settings: AppSettings, repository: AppSettingsRepository, colors: UiColors, onOpenYyy: () -> Unit)`（见 `:546` `PushChannelScreen`）。**保持这个签名不变**，只改调用侧的 lambda 体。
- 现有可参考的「正确的做法」：`ManualChapter`（`:1346-1361`）是全文件唯一带 `semantics { contentDescription = ... }` 的组件 —— 语义化写法参考它。

## Steps

1. 在 `MainActivity.kt` 顶部补齐 import（见 Target 骨架的 import 块）。`Settings` 已在 `:11` import。
2. 在 `:118`（`DarkUi` 定义之后）插入 `private object Motion { ... }` 与 `@Composable private fun rememberReducedMotion(): Boolean { ... }`，数值**照抄 Target，不得改动**。
3. 替换 `:171-173` 为栈版本：`subStack` / `subPage` / `navDirection` / `reducedMotion` / `push()` / `pop()`（见 Target）。`editingApp` 保留。
4. 替换 `:197` 的 `BackHandler` 为 `BackHandler(enabled = subStack.isNotEmpty()) { pop() }`。
5. 用两层 `AnimatedContent` 包住 `:216-279` 的 `when`（外层切 Tab 用 `tabSpec`，内层切 subStack 用 `navSpec`）。各分支内部 composable 调用**一字不改**。
6. 按「回填点清单」把 17 处 `subPage = SubPage.X` 改成 `push(SubPage.X)`。注意 `:240` 那处兜底分支里还有第二个 lambda（`SimpleAppSelectionScreen` 的 `onOpenAppSettings`），同样改。
7. 确认 `SubPage.AppRuleSettings` 分支（`:238-242`）的 `editingApp?.let { ... } ?: SimpleAppSelectionScreen(...)` 结构保持不变 —— 它处理的是 `editingApp` 为空的异常路径，本方案不动它。

## Boundaries

- **Do NOT** 改动任何 `XxxScreen` 内部实现（`PushChannelScreen` / `Home` / `Records` / `SettingsHub` 等 20 个页面的函数体）。
- **Do NOT** 引入 `androidx.navigation:navigation-compose` / NavHost。那是 006 的决定项。
- **Do NOT** 改 `PageScaffold` 的滚动行为（那是 [002](002-state-restoration.md) 的范围）。
- **Do NOT** 改任何 Room Entity / DataStore 字段 / `RelayEngine` 逻辑。本方案纯 UI 层。
- **Do NOT** 新增第三方依赖（`androidx.compose.animation` 随 compose-bom 已在 classpath 上，只需 import，不要改 `build.gradle.kts`）。
- 若 `AnimatedContent` 的 `transitionSpec` 方向判定在实际代码里无法一步写干净，采用 Target 里注明的**两层 AnimatedContent**方案，不要临时发明第三种结构。
- 若某一步与你找到的代码对不上（本方案基于 commit `4ef8947`），**停下来报告**，不要即兴发挥。
- 遵守 `AGENTS.md` 规则 8：所有用户可见文案保持简体中文。

## Verification

- **Mechanical**:

  ```bash
  ./gradlew :app:compileDebugKotlin --no-daemon --console=plain
  ./gradlew :app:lintDebug --no-daemon --console=plain
  ```

  预期：两条都 0 error。`lintDebug` 允许既有 warning，但不得新增 `NewApi`（`Settings.Global.ANIMATOR_DURATION_SCALE` 常量自 API 17，`minSdk 26` 无风险）。
  Windows 用 `.\gradlew.bat`（见 `AGENTS.md` 常用命令）。

- **Feel check**（真机或模拟器，必须做 —— 动效可能机械正确但手感不对）：

  1. 设置 → 「推送渠道」：确认新页**从右侧滑入**，且旧的设置页**同时向左轻微退让**（不是整块被推走、也不是纯淡入）。280ms 内完成，手感是"推进去"而不是"飞过去"。
  2. 在「推送渠道」按系统返回键：确认**严格镜像**回来 —— 从左滑入、右滑出。连续按 push/pop 交替 5 次，方向每次都要对，不能有一次反向。
  3. **连点验证**：快速连点同一个入口 5 次，确认不会压入 5 个相同页面（push 前判一下 `if (subStack.lastOrNull() != page)`）。
  4. **返回栈验证（本方案的核心修复）**：首页 → 「软件选择」→ 点任意 App 的「设置」→ 按返回键。**必须回到「软件选择」**，而不是回到首页页签。再按一次返回才回首页。
  5. 底部三个页签来回切：确认**只有淡入淡出、没有横向位移**（页签没有方向性）。
  6. 系统设置 → 开发者选项 → 把「过渡动画缩放」/「动画程序时长缩放」设为**关闭动画**，重做第 1、2 步：确认**位移消失、只保留淡入淡出**，页面内容仍然可见（不是瞬切、不是黑屏）。
  7. 逐帧：开发者选项把动画缩放设为 `0.5x`，观察 push 过程中新旧页面**是否同时在屏**（应该重叠过渡约 280ms，而不是"旧页先消失、新页再出现"的闪烁）。

- **Done when**：
  - 两条 gradle 命令 0 error
  - Feel check 1~6 全部通过
  - 第 4 条（返回栈）是验收硬门槛 —— 若它没通过，本方案视为未完成
