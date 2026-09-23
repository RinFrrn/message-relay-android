# 消息接力 · UX/动效/逻辑整体整改方案

来源：`improve-animations` skill 全量审计（2026-09-23）。
审计底稿见 `000-ux-motion-audit.md`（18 项发现 F1–F18 + 用户反馈↔发现映射）。
本目录只写方案，**未改动任何源码**。

## 用户反馈 → 计划对照

| 用户原话 | 根因发现 | 落在哪个计划 |
| --- | --- | --- |
| 页面进入返回没有过渡动画 | F3 `when(subPage)` 硬切、单槽丢父页 | 001 |
| 页面 pop 后上一页面列表位置回到顶部 | F5 bare `remember` 卸载即丢 + F12 非虚拟列表 | 002（+003 的 LazyColumn） |
| 功能入口重复（如消息模板） | F9 三套规则 UI + F10 模板 6 入口 3 数据源 | 005 |
| app 选择页面卡顿 | F4 主线程逐应用 Binder IPC + F12 非虚拟化 | 003 |
| 高级设置自定义模板无效 | F2 自定义模板不可引用 + F1 10/12 预设雷同 + F7 `require()` 崩溃 | 004 |
| （整体体感）点了没反应、设置找不到 | F6 死按钮 + F11–F18 细节 | 006 |

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
| 001 | [导航栈与转场动画](001-navigation-stack-and-transitions.md) | F3 | HIGH | 2 文件 ~120 行 | 📋 planned |
| 002 | [状态恢复（pop/旋转不丢）](002-state-restoration.md) | F5 | HIGH | 2 文件 + 4 Saver ~160 行 | 📋 planned |
| 003 | [App 选择页性能](003-app-selection-performance.md) | F4、F12 | HIGH | 3 文件 + 1 新文件 ~200 行 | 📋 planned |
| 004 | [模板系统重构](004-template-system.md) | F1、F2、F7、F8 | HIGH | 3 文件 ~130 行 + 1 测试 | 📋 planned |
| 005 | [入口整合](005-entry-consolidation.md) | F9、F10 | HIGH | 3 文件 ~220 行 −90 行 | 📋 planned |
| 006 | [死动作清理与打磨](006-dead-actions-and-polish.md) | F6、F11、F13–F18 | MED | 5 文件 ~150 行 −60 行 | 📋 planned |

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
