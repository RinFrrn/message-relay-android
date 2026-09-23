# 000 — 交互 / 动效整体审计报告

- **Commit**: `4ef8947`
- **Date**: 2026-09-23
- **Scope**: 全部用户可见 UI（`MainActivity.kt` 1612 行内 20 个 Screen）+ 模板链路（`TemplateCatalog` / `CoreLogic` / `RelayEngine` / `Database`）
- **Method**: 逐行阅读全部 UI 与模板渲染链路，逐条在 file:line 上复核；未在代码中确认的结论不写入本表

## 0. 侦察结论（Phase 1）

| 项 | 结论 |
| --- | --- |
| 框架 | Kotlin + Jetpack Compose（BOM `2025.12.01`）+ Material 3，`minSdk 26` |
| 动效库 | **无**。全仓库 0 处 `animate*AsState` / `AnimatedContent` / `Crossfade` / `AnimatedVisibility`。导入语句里没有任何 `androidx.compose.animation.*` |
| 导航 | **无 NavHost**。`navigation-compose:2.9.6` 已声明为依赖但零调用点（`app/build.gradle.kts:43`）。实际导航是 `var subPage: SubPage?` 单槽 + `when` 硬切（`MainActivity.kt:172, 216-279`） |
| 列表 | **无 LazyColumn / LazyRow**。全部 `Column + verticalScroll(rememberScrollState())`（`PageScaffold`，`MainActivity.kt:1365`） |
| 状态保存 | **无 `rememberSaveable`**。全仓库 0 处；所有表单状态用裸 `remember { mutableStateOf(...) }`。Manifest 未声明 `android:configChanges`（`app/src/main/AndroidManifest.xml:12`）→ 旋转即重建 Activity，全部输入丢失 |
| 动效 token | **无**。`UiColors`（`MainActivity.kt:96-118`）只管颜色，无 duration / easing 常量 |
| 产品性格 | 工具型 / 效率型。规则来自 `AGENTS.md`：简单模式优先、错误配置会漏发消息。→ 目标是**干脆、可预期**，不是活泼。**不做弹跳** |
| 频次图 | 100+/天：底部 Tab 切换、返回键、软件选择搜索框。<br>10+/天：进入「软件选择 / 推送渠道 / 记录」、切换单个 App 的 Switch。<br>偶尔：高级设置、备份恢复、版本更新。<br>一次：Onboarding |

**动作基线**：当前**所有**页面切换、Tab 切换、状态翻转都是 0ms 硬切。这既不是「刻意不做动效」（没有文档记录这个取舍），也不是「快速」——因为状态与滚动位置同时丢失，用户感觉是「跳走了 / 丢了」而不是「快」。

---

## 1. 已核实发现（Phase 3）

按杠杆（影响 ÷ 成本）排序。

严重度定义（沿用 AUDIT.md，外加一类）：
**HIGH** = 破坏体验 / 功能实际不可用；**MEDIUM** = 明显不对；**LOW** = 打磨。
`Logic` 类标 = 功能性缺陷，不是动效问题，但属于本次「整体检查」范围，一并列出。

| # | 严重度 | 分类 | 位置 | 发现 | 修复方向 |
| --- | --- | --- | --- | --- | --- |
| **F1** | **HIGH** | Logic | `TemplateCatalog.kt:24-37` | **12 个内置模板里 10 个内容逐字节相同**。`general` / `simple` / `standard` / `privacy` / `raw` / `wechat` / `work_wechat` / `qq` / `dingtalk` / `feishu` 全部传的是同一个 `APP_TITLE` + `APP_BODY`。仅 `phone`、`sms` 不同 →「隐私模板」不隐藏任何内容、「原始通知模板」不是原始内容、切换模板**看不到任何变化** | 差异化四套预设 |
| **F2** | **HIGH** | Logic | `MainActivity.kt:1110-1141`、`Database.kt:54-60`、`RelayEngine.kt:176` | **自定义模板永久失效**。`TemplateLibrary` 把模板存进 Room（id `custom_<ts>`，`:1134`），但 `TemplateSelector`（`:1333-1343`）只列 `simpleTemplatePresets()` = 4 个内置 id，`custom_*` **永远无法被任何规则引用**；`templatesFlow()` / `deleteCustomTemplate()` / `fallbackTemplate()` 三个 DAO 方法**零调用点**（自定义模板连查看/删除入口都没有）→ `RelayWorker:176` 的 `dao.template(requestedTemplate)` 永远查不到，回落到标准模板 | 让自定义模板可选可管 |
| **F3** | **HIGH** | 动效 + Logic | `MainActivity.kt:216-279, 197` | 页面切换是 `when(subPage)` 硬切，**进入/返回均无过渡**。且 `subPage` 是单槽不是栈：`软件选择 → App 设置`（`:218-221`）会覆盖 `subPage`，`BackHandler { subPage = null }`（`:197`）把用户直接丢回首页面签，**丢失上一级** | 栈式导航 + `AnimatedContent` |
| **F4** | **HIGH** | Performance | `MainActivity.kt:1521-1530`，调用点 `:289 / :695 / :898` | `loadInstalledApps()` 在**主线程 composition 中**对每个已装 App 调 `loadLabel()` —— 这是**逐 App 的 Binder IPC**。200~400 个 App = 数百 ms 主线程阻塞。且用 `remember {}` 挂在页面内，**每次进页重跑** | IO 线程 + 进程级缓存 |
| **F5** | **HIGH** | 动效 + Logic | `MainActivity.kt:1364-1371`、全文件 | **所有**滚动与表单状态用裸 `remember`，页面/页签一切换即销毁 → 返回后列表回顶部、搜索词清空。叠加 Manifest 无 `configChanges`（`AndroidManifest.xml:12`），**旋转屏幕会清空已填的 Webhook / 密钥 / 模板文本** | `rememberSaveable` + `LazyListState` |
| **F6** | **HIGH** | Logic | `MainActivity.kt:1128-1131`、`:467`、`:1259`、`:1478` | **四个「看起来能用、点了没反应」的入口**：①「发送预览到渠道」只把 status 文案设成"发送预览到渠道"，从不发送（`:1128-1131`）；②「重新发送 / 立即重试 / 仍然发送」全部只关弹窗 `onRetry = { selected = null }`（`:467`）；③「一键诊断」`onClick = {}` 空实现（`:1259`）；④ `LinkRow` 渲染"打开"却**无 onClick**（定义 `:1478`，调用 `:1323` `:1327`） | 接通或删除 |
| **F7** | **MEDIUM** | Logic | `CoreLogic.kt:41-44` | 模板渲染用 `require(key in data) { "不支持的模板变量：$key" }` —— 未知变量**直接抛异常**。自定义模板里手滑写 `{{appname}}`（大小写）→ 点「本地预览」崩 UI，发送时崩 `RelayWorker`。而 UI 只文档了 15 个变量里的 4 个（`:1120`） | 校验 + 全量变量说明 |
| **F8** | **MEDIUM** | Logic | `MainActivity.kt:1333-1343, 1570-1575` | `TemplateSelector` 只给 4 个选项（simple/standard/privacy/raw），但 `TemplateCatalog.recommend()` 返回的是 `phone`/`sms`/`wechat`/`qq`/`dingtalk`/`feishu`/`work_wechat` —— **不在选项里**，于是单选按钮一个都不选中；`simpleTemplateName()` 把这些统统显示成"标准模板"（`:1574` 的 `else` 分支），用户看到的名字是错的 | 选项取并集 + 名称映射 |
| **F9** | **MEDIUM** | Logic | `MainActivity.kt:684-716` / `:719-781` / `:893-961` | **三套并行的「按 App 配规则」UI**：软件选择行内 Switch（`SimpleAppRow`）、App 设置页（`AppRuleSettingsScreen`）、高级设置→应用独立规则（`Rules`）。三者语义还不一致 —— `Rules` 保存时用 `TemplateCatalog.recommend()` **覆盖**用户已选的模板（`:932`） | 收敛成一套 |
| **F10** | **MEDIUM** | Logic | `MainActivity.kt:405`、`:397`、`:508`、`:744`、`:945`、`:880` | 「消息模板」**6 个入口、3 套互不相通的存储**：全局 `selectedTemplatePreset`（DataStore）、每规则 `RuleEntity.templateId`（Room）、自定义 `TemplateEntity`（Room）。全局预设只在**新建规则时**作为默认值生效（`:1220, 725`），改了不回溯已有规则，但界面写的是"选择模板即可使用"（`:787`）—— 与事实不符 | 单一事实来源 |
| **F11** | **MEDIUM** | Performance | `MainActivity.kt:379, 564, 1498` | `storedChannels(context)` 每次 recomposition **同步解密 Android Keystore**（`:1498` 里 `SecureStore.get`），且 `:564` 没有 `remember`。首页和推送渠道页每次状态变动都重解密一次（10~50ms） | 记忆化 |
| **F12** | **MEDIUM** | Performance | `MainActivity.kt:423-473, 377, 416` | 记录页 / 首页用 `Column + verticalScroll` 一次性 compose **全部**记录（`:447 visible.forEach`）；首页只要 5 条却 `dao.records()` 拉全表再 `take(5)`（`:377, 416`） | `LazyColumn` + `LIMIT` 查询 |
| **F13** | **MEDIUM** | Accessibility | `MainActivity.kt:1392-1428` | `FeatureCard` / `SettingNavRow` 可点击但无 `semantics`；`Icon(contentDescription = null)` 满地；`SettingSwitchRow` / `CallTypeSelector` / `ChannelChoice` 的文字**不可点**，只能点到小控件本身（触控目标 < 48dp） | 语义化 + 可点区域外扩 |
| **F14** | **LOW** | Logic | `AppSettingsRepository.kt:33-34, 125-126` | `historyRetention` / `privacyDisplayMode` **没有任何设置入口**（只能靠导入备份改，`ConfigBackup.kt:155-156`）。首页却展示"记录保存 30 天"（`MainActivity.kt:411`），点它跳到记录页 —— 文不对题 | 补入口或删设置 |
| **F15** | **LOW** | Logic | `MainActivity.kt:831-840` | 「后台运行」三项全是硬编码 `Warning` + "到系统设置中确认"。而「通知访问是否已开启」完全可以程序内查询（`NotificationManagerCompat.getEnabledListenerPackages`），现在等于一个纯静态说明页 | 真实检查 |
| **F16** | **LOW** | Logic | `MainActivity.kt:197, 290-301` | Onboarding 的 `step` 只增不减，且 `BackHandler(enabled = subPage != null)` 在 onboarding 下恒为 false → **配置到一半按返回直接退出 App**，回来还要从头填。`step` 用 `mutableIntStateOf`，进程被杀也归零 | 允许回退 + saveable |
| **F17** | **LOW** | Cohesion | `MainActivity.kt:1162-1166`、`app/build.gradle.kts:43` | 死代码：`AboutLinksSection` 无任何调用点；`navigation-compose:2.9.6` 声明但零调用点 | 删除 |
| **F18** | **LOW** | Cohesion | `MainActivity.kt:91-118, 165-170` | 两套色板并存：手写 `LightUi`/`DarkUi`（喂给 `UiColors`）+ `lightColorScheme`/`darkColorScheme`（喂给 `MaterialTheme`）。`SectionCard` 取 `colors.card` 而 M3 组件取 `scheme.surface`，主题切换时可能不同步 | 统一到 `MaterialTheme.colorScheme` |

### 关于严重度的一点说明

F1/F2/F6 是**功能实际不可用**，比「动效破坏体验」更重，但对用户体感而言它们表现出来就是"这些地方设计不合理"——与本次反馈完全对得上。

---

## 2. 用户反馈 ↔ 发现 对照

| 反馈 | 对应发现 | 是否已定位根因 |
| --- | --- | --- |
| 页面进入返回没有过渡动画 | **F3**（+ F5 让"返回"还伴随状态丢失，加重了突兀感） | ✅ `when(subPage)` 硬切，无 `AnimatedContent` |
| 页面 pop 后上一页面列表位置回到顶部 | **F5**（+ F3 的栈丢失、F12 的非虚拟化列表） | ✅ 裸 `remember` + 页面卸载 + 无 `configChanges` |
| 功能入口重复（如消息模板） | **F10**（6 入口 3 存储）+ **F9**（3 套规则 UI） | ✅ 逐入口清点完毕 |
| App 选择页面卡顿 | **F4**（主线程 `loadLabel` 逐 App IPC）+ **F12**、**F11** | ✅ `loadInstalledApps` 调用链已定位 |
| 高级设置自定义模板无效 | **F2**（存进去选不了）+ **F1**（选了也看不出区别）+ **F7**（写错变量还会崩） | ✅ `TemplateEntity` 从保存到渲染的断链已定位 |

---

## 3. 错过的动效机会（Additive，非纠错）

1. **记录状态翻转无过渡**（`MainActivity.kt:454` `Text("${record.app} · ${record.status}")`）。发送结果从「发送中」→「成功 / 发送失败」是瞬切。这是**状态指示**类动效，属于该加的一类 —— 让用户看清"刚刚变了什么"。
2. **首页全局开关无反馈**（`:390` `Switch(!settings.paused)`）。这是全局停用所有转发的破坏性开关，翻转后整条链路行为改变，但屏幕上只有 Switch 位移。值得一次明确的、可撤销感知的状态反馈。
3. **模板选择与预览之间无空间关联**（`:788-809`）。点卡片 → 底部凭空出现一段预览文本。预览内容与被点卡片之间没有任何过渡连接，用户不确定"这段预览是哪个模板的"。
4. **保存动作的反馈是文字突变**（`StatusBadge` 各处）。"保存渠道 / 保存规则 / 保存免打扰" 点完只有 badge 文案变字。轻量的成功反馈属于「偶尔」频次，可给一点动效预算。

（按 AUDIT.md §8 要求控制在少量、且基于真实 UX 断点，不做清单式许愿。）

---

## 4. 方案总览

6 份实施方案，覆盖上述 5 条用户反馈 + 崩溃风险。全部写在 `plans/`，未改动任何源码。

| 编号 | 标题 | 覆盖 | 严重度 |
| --- | --- | --- | --- |
| [001](001-navigation-stack-and-transitions.md) | 栈式导航 + 页面转场 | F3 | HIGH |
| [002](002-state-restoration.md) | 状态与滚动位置保存 | F5、F16 | HIGH |
| [003](003-app-selection-performance.md) | 应用选择页性能 | F4、F11、F12 | HIGH |
| [004](004-template-system.md) | 模板系统打通（自定义模板可用 + 预设差异化 + 变量校验） | F1、F2、F7、F8 | HIGH |
| [005](005-entry-consolidation.md) | 入口收敛（消息模板 6→2、规则 UI 3→1） | F9、F10 | MEDIUM |
| [006](006-dead-actions-and-polish.md) | 死按钮 / 死代码 / 无入口设置 / 可访问性 | F6、F13~F18 | MEDIUM |

执行顺序与依赖见 [`README.md`](README.md)。

> 提醒：`AGENTS.md` 规则 8「所有用户提示使用简体中文」、规则 5「不得清空用户现有配置」在 004 / 005 里有直接约束 —— 模板重构必须迁移既有 `RuleEntity.templateId` 与 `TemplateEntity`，不得丢弃用户已保存的自定义模板。
