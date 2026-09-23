# 005 · 入口整合：消息模板入口 6→2、规则 UI 3→1

- Status: planned
- Depends on: 004（模板系统必须先修好，本计划的模板入口整合建立在「自定义模板可被引用」之上）
- Related findings: F9（三套并行规则 UI）、F10（消息模板 6 个入口 3 个数据源）
- Estimated scope: 3 files（MainActivity.kt / AdvancedSettingsScreen 所在文件 / 独立 RulesScreen.kt 新建），约 220 行改动 + 删除约 90 行
- Severity: HIGH（用户明确点名「功能入口重复（如消息模板）」）

---

## Problem

### F10 · 消息模板有 6 个入口、3 个数据源（MED→实为体验 HIGH）

用户说「功能入口重复（如消息模板）」。全仓库通读后确认，**“消息模板”这件事有 6 个入口**：

| # | 入口 | 位置 | 能做什么 | 数据源 |
| --- | --- | --- | --- | --- |
| 1 | 高级设置 → 消息模板 | `AdvancedSettingsScreen:880` `SettingNavRow` | 进模板库 | Room `templates` |
| 2 | 模板库本体 | `TemplateLibrary`（MainActivity.kt:1000-1230） | 增删改自定义模板 | Room `templates` + `TemplateCatalog.builtIns` |
| 3 | 每应用规则里的模板单选 | `TemplateSelector`（1333-1343） | 规则选模板 | 只认 4 个硬编码 id |
| 4 | 规则编辑页的「简单模板」区 | `AppRuleSettingsScreen` 内嵌 | 选预设 + 看变量 | 同上 |
| 5 | 高级设置 → 全局默认模板 | `AdvancedSettingsScreen`（新增于 004 step 5） | 新规则默认值 | DataStore |
| 6 | 模板推荐/预览 | `recommend()`（1570-1575）+ 渲染预览 | 推荐预设 | 只认 4 个 id |

**3 个数据源**：`TemplateCatalog.builtIns`（静态）、Room `templates`（自定义）、`AppSettingsRepository`（全局默认，004 新增）。
用户在不同入口看到的选项集合**不一致**（入口 3/4/6 只认 4 个 id，入口 2 看得到全部自定义）——这就是「重复但又对不上」的割裂感来源。

### F9 · 三套并行的规则 UI（MED）

同一件事「管理某 App 的转发规则」有 3 套 UI，能力集互不一致：

| UI | 位置 | 能力 | 缺什么 |
| --- | --- | --- | --- |
| `SimpleAppRow` | 主页规则列表行内 | 开关 + 简单模板 | 无关键词/正则/免打扰/多渠道 |
| `AppRuleSettingsScreen` | 规则行点入 | 全量规则字段 | 无批量操作 |
| `Rules` 页 | 底部导航「规则」 | 列表 + 批量启停 | 编辑要跳别处 |

结果：用户在主页改了模板，进规则页看到的字段集合不一样；同一份 `AppRule` 三处写、三处保存，行为漂移已经发生（如 `SimpleAppRow` 不写 `advanced` 字段）。

---

## Target

### 1. 消息模板入口 6→2

**保留 2 个入口**：

- **入口 A · 模板库**（管理面）：`TemplateLibrary` 是唯一能增删改模板的地方。高级设置 → 消息模板 是它的唯一入口，其余 4 处不再有独立「模板管理」能力。
- **入口 B · 规则里的选择器**（使用面）：`TemplateSelector` 是唯一「选模板」的地方，被 3 套规则 UI 共用同一个 composable。

**收敛动作**：

1. `AppRuleSettingsScreen` 内嵌的「简单模板」区 → 直接换成 `TemplateSelector`（同一个 composable，不复制代码）。
2. `recommend()` 保留，但它只返回**预设 id**，用于「新建规则时的初始值」，不再是第 3 套展示面。
3. 004 新增的「全局默认模板」选择行保留（它是 DataStore 配置不是模板管理），但副标题明确写「仅作用于新建规则」。
4. 三处数据源在 `TemplateCatalog` 里加一个 **single source of truth** 函数：

```kotlin
// TemplateCatalog.kt
fun allTemplates(custom: List<CustomTemplate>): List<TemplateInfo> =
    builtIns + custom.map { TemplateInfo(id = it.id, name = it.name, body = it.body, isCustom = true) }
```

所有入口（TemplateLibrary / TemplateSelector / recommend / 全局默认选择行）都从这一个函数取集合，**选项不一致问题从根上消失**。

### 2. 规则 UI 3→1

**保留 1 套**：以 `AppRuleSettingsScreen` 为唯一规则编辑面（字段最全），派生两个轻量视图：

- `SimpleAppRow`（主页行内）→ 降级为**纯展示 + 开关**：显示 App 名、当前模板名、命中计数；开关切换 `rule.enabled`；**点整行 push 进 `AppRuleSettingsScreen`**。行内不再放模板单选（消除和入口 B 的重复）。
- `Rules` 页 → 保留列表 + 批量启停（它有独立价值），「编辑」统一 `push(SubPage.AppRuleSettings(packageName))`。

字段所有权归 `AppRuleSettingsScreen` 一处，`SimpleAppRow` 的写操作只剩 `enabled` 一个字段 —— 三处保存逻辑漂移的根因被消除。

---

## Repo conventions to follow

- `AGENTS.md` 第 1 条：合并后的单一规则编辑面抽成独立文件 `RulesScreens.kt`（新文件），`MainActivity.kt` 只保留 push 接线；**不许把三套 UI 的代码揉进 MainActivity**。
- `AGENTS.md` 第 2 条：批量应用模板走 Repository/DAO 一次性 update，UI 不直接跑循环 update。
- `AGENTS.md` 第 5 条：**合并 UI 不动数据**。`AppRule` 表结构不变、不写 Migration；三套 UI 各自存过的字段（含 `SimpleAppRow` 没写的 `advanced` 等）一律以 `AppRuleSettingsScreen` 的全量字段为准读写，旧规则读出来缺字段用默认值补，不丢数据。
- `AGENTS.md` 第 8 条：所有合并后的提示文案简体中文；被删除入口不留死链。
- `AGENTS.md` 第 9 条：README 里「三处可配置规则」之类的描述同步改成「规则统一在规则编辑页配置」。

## Steps

1. **`TemplateCatalog.kt` 加 `allTemplates(custom)`** + `TemplateInfo.isCustom` 标记；把 `TemplateLibrary`、`TemplateSelector`、`recommend()`、全局默认选择行四处取集合的代码全部换成它。
2. **新建 `RulesScreens.kt`**：把 `AppRuleSettingsScreen` 从 `MainActivity.kt` 整体搬出（只搬 UI composable，ViewModel/DAO 引用不变）。
3. **`SimpleAppRow` 降级**：删行内模板单选与任何字段编辑；整行 `clickable { onOpenRule(pkg) }` + `Switch(enabled)`；副标题显示 `当前模板名 · 已命中 N 次`。
4. **`Rules` 页编辑入口**：所有「编辑」按钮统一 `push(SubPage.AppRuleSettings(packageName))`；删掉 Rules 页内的任何内联字段编辑。
5. **`AppRuleSettingsScreen` 内嵌「简单模板」区**替换为 `TemplateSelector(...)`（复用，不复制）。
6. **死链清理**：被删的入口对应的 `SubPage`/导航分支若不再被引用，一并删掉；`SettingNavRow` 只留「消息模板 → 模板库」一条。
7. **README / CHANGELOG** 同步。

## Boundaries

- 不改 `AppRule` Entity、不写 Migration。
- 不合并 Room 里三个存储源（静态预设 / DataStore / Room 各司其职，只是取数走同一函数）。
- 不做规则导入导出（无此需求）。
- 不动 004 已定的模板内容与渲染逻辑。
- `Rules` 页的批量启停**保留**（它是批量视图的独立价值，不算重复编辑面）。

## Verification

1. `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain` 通过。
2. `.\gradlew.bat :app:lintDebug --no-daemon --console=plain` 无新增告警。
3. 真机手验：
   - 全局搜索「消息模板」相关 UI，**只有 2 个入口**（高级设置→模板库；规则编辑页里的选择器）；
   - 模板库新建自定义模板 → 规则编辑页选择器里**立即可见**（004 的能力不回退）；
   - 主页规则行：点整行进规则编辑页；点开关只切 enabled，**不弹模板选择**；
   - 三处（主页开关 / 规则页批量启停 / 规则编辑页）改同一规则，字段不串、不丢；
   - 老规则（`SimpleAppRow` 时代存的、缺 `advanced` 字段的）打开规则编辑页 → 字段显示默认值，保存后完整。
4. 旋转 + pop 返回（依赖 001/002）后，规则编辑页未保存的输入不丢。

## Done when

- 消息模板入口 = 2，规则编辑面 = 1（+2 个轻量派生视图）。
- 任一入口看到的模板选项集合**完全一致**（`allTemplates()` 单一来源）。
- `SimpleAppRow` 写操作只剩 `enabled`。
- 无死链、无残留导航分支；README/CHANGELOG 一致。
