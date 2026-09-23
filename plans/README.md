# 消息接力 · UX/动效/逻辑整体整改方案

来源：`improve-animations` skill 全量审计（2026-09-23）。
审计底稿见 `000-ux-motion-audit.md`（18 项发现 F1–F18 + 用户反馈↔发现映射）。
本目录为整改方案；全部 6 个计划已实施完毕（004 → 003 → 002 → 001 → 005 → 006，编译与单测通过，真机 feel check 待验）。

## 用户反馈 → 计划对照

| 用户原话 | 根因发现 | 落在哪个计划 |
| --- | --- | --- |
| 页面进入返回没有过渡动画 | F3 `when(subPage)` 硬切、单槽丢父页 | 001 |
| 页面 pop 后上一页面列表位置回到顶部 | F5 bare `remember` 卸载即丢 + F12 非虚拟列表 | 002（+003 的 LazyColumn） |
| 功能入口重复（如消息模板） | F9 三套规则 UI + F10 模板 6 入口 3 数据源 | 005 |
| app 选择页面卡顿 | F4 主线程逐应用 Binder IPC + F12 非虚拟化 | 003 |
| 高级设置自定义模板无效 | F2 自定义模板不可引用 + F1 10/12 预设雷同 + F7 `require()` 崩溃 | 004 |
| （整体体感）点了没反应、设置找不到 | F6 死按钮 + F11–F18 细节 | 006 |
| 设置页逻辑不直观、功能找不到状态 | P1–P7（静态副标题、图标重复、模板/规则双入口跨层级） | 007 |

## 执行顺序与依赖

```
001 导航栈与转场 ──┐
002 状态恢复     ──┼─→ 003 选择页性能 ─→ 004 模板系统 ─→ 005 入口整合
                   │                              ↘
006 死动作与打磨（可随时并行，仅 004 step 6 有微弱交集）
```

推荐顺序：**001 → 002 → 003 → 004 → 005**，006 穿插任意位置。
理由：001/002 是导航与状态地基（003–005 的手验都依赖「pop 不丢状态」）；003 先解决卡顿再动模板（模板页重构会用到 003 的缓存模式）；005 必须在 004 之后（入口整合的前提是自定义模板已可被引用）。

## 索引

| # | 计划 | 覆盖发现 | 严重度 | 规模 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 000 | [UX/动效/逻辑审计底稿](000-ux-motion-audit.md) | F1–F18 | — | 审计 | ✅ 完成 |
| 001 | [导航栈与转场动画](001-navigation-stack-and-transitions.md) | F3 | HIGH | 2 文件 ~120 行 | ✅ implemented（编译+单测+lint 通过，feel check 待真机） |
| 002 | [状态恢复（pop/旋转不丢）](002-state-restoration.md) | F5 | HIGH | 2 文件 + 4 Saver ~160 行 | ✅ implemented（机械验证通过，feel check 待真机） |
| 003 | [App 选择页性能](003-app-selection-performance.md) | F4、F12 | HIGH | 3 文件 + 1 新文件 ~200 行 | ✅ implemented（机械验证通过，feel check 待真机） |
| 004 | [模板系统重构](004-template-system.md) | F1、F2、F7、F8 | HIGH | 3 文件 ~130 行 + 1 测试 | ✅ implemented（机械验证通过，feel check 待真机） |
| 005 | [入口整合](005-entry-consolidation.md) | F9、F10 | HIGH | 3 文件 ~220 行 −90 行 | ✅ implemented（编译+单测+lint 通过，feel check 待真机） |
| 006 | [死动作清理与细节打磨](006-dead-actions-and-polish.md) | F6、F11、F13–F18 | MED | 5 文件 ~150 行 −60 行 | ✅ implemented（编译+单测+lint 通过，feel check 待真机） |
| 007 | [设置页直观化](007-settings-clarity.md) | 用户直提：设置不直观 | MED | 1 文件 ~200 行 | ✅ implemented（编译+单测+lint 通过，feel check 待真机） |

> **006 落地与原方案的差异/裁决**：
> - F6a（模板库「发送测试」死按钮）已由 004 删除、F16（Onboarding 返回上一步）已由 002 完成，均无需重复处理。
> - F6b 选择「补真」：`RelayEngine.enqueue` 复用既有 `RelayWorker` 发送路径（模板沿用规则当前模板），弹窗保持打开并提示「已重新发送」。
> - F6c「一键诊断」没有真实诊断链路，按计划「补不了的删」删除（复制按钮本就存在且有效）。
> - F6d `LinkRow` 增加 url 参数；两个参考链接指向飞书官方自定义机器人文档与 Bark 官网（原代码只有文案没有 URL）。
> - F11 现状：三处解密分属不同 composable（Home 曾每次重组解一次；PushChannelScreen 已 remember；reconcileUpgradeState 本就一次），改为各处单次读取，不引入跨页面缓存。
> - F13 只做计划限定的基线（48dp 触摸目标 / 开关中文语义 / 状态非纯色），未做完整无障碍审计。
> - F14 隐私三档语义：`full` 完整显示、`masked` 详情隐藏正文、`hidden` 7 位以上数字串保留后 4 位（列表+详情）；打码后的原值不写日志。
> - F15 可实测项 = 通知监听 / 通知权限 / 电池优化白名单 / 最近一次转发；厂商自启锁后台一律「检测受限」灰，不写结论。
> - F18 色板合并实现为 `darkColorScheme` 直接引用 `DarkUi` 字段（色值零变化）。

> **005 落地与原方案的差异**（原方案基于 `4ef8947`，实际落地时 004/003/002/001 已先行完成）：
> - 规则 UI 三个 composable（`AppRuleSettingsScreen` / `Rules` / `SimpleAppRow`）连同 `TemplateSelector`、`CallTypeSelector` 全部迁入新文件 `RulesScreens.kt`（原方案只点名搬 `AppRuleSettingsScreen`）。
> - 导航仍走 `editingApp` Pair + `push(SubPage.AppRuleSettings)`，未改成带参 `SubPage` 枚举（不动 001 的导航栈接线）。
> - `Rules` 页的「新增规则」保留应用搜索列表，但点击改为进编辑页（原内联字段编辑全部删除）；批量启停为「全部启用 / 全部停用」。
> - `SimpleAppRow` 副标题为「当前模板名 · 近期命中 N 次」（记录受保留策略裁剪，故用「近期」而非「已命中」）。
> - `TemplateLibrary` 的自定义模板列表只列自定义（管理面语义不变）；「可选集合」统一由 `TemplateCatalog.allTemplates()` 提供。
> - `SimpleTemplatePresetScreen` 保留，它就是「全局默认模板」选择行（DataStore 配置，不是模板管理入口）。

## 跨计划共用的动效参数（源自 AUDIT.md）

| 参数 | 值 | 用途 |
| --- | --- | --- |
| `EaseDrawer` | `CubicBezier(0.32f, 0.72f, 0f, 1f)` | 页面 push/pop 位移 |
| `EaseOut` | `CubicBezier(0.23f, 1f, 0.32f, 1f)` | 元素入场 |
| NAV 时长 | 280ms | 页面转场 |
| TAB 时长 | 180ms | 底部导航切换（纯淡入淡出） |
| reduced-motion | 150ms 纯淡入淡出 | `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` 时 |

UI 动画一律 < 300ms；modal/drawer 200–500ms；reduced-motion 是「减量」不是「归零」。

## 全局约定（每个计划都必须遵守）

- `AGENTS.md` 十条全部生效，重点：不堆 MainActivity、UI 不直连 DB/网络、Room 改动必须 Migration、不清用户配置、日志不出现 Token/号码/验证码、未经真机验证不写稳定、简体中文提示、README/CHANGELOG 事实一致。
- 每个计划落地后跑：`compileDebugKotlin` → `testDebugUnitTest` → `lintDebug` → 真机手验。
- 手验顺序建议：001/002 的「导航 + 旋转 + pop」作为回归基线，之后每个计划的真机步骤都顺带回归一遍。
