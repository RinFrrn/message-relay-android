# 006 · 死动作清理与细节打磨

- Status: planned
- Depends on: 无强依赖（004 step 6 会顺手删掉 TemplateLibrary 的空按钮；其余可并行）
- Related findings: F6（4 个死按钮）、F11（渠道解密不记忆）、F13（无障碍）、F14（retention/privacy 无入口）、F15（BackgroundHealth 写死 Warning）、F16（Onboarding 无返回）、F17（死代码 AboutLinksSection）、F18（navigation-compose 未用 + 双色板）
- Estimated scope: 5 files，约 150 行改动 + 删除约 60 行
- Severity: MEDIUM（单项不致命，但 8 项叠加正是「交互逻辑不合理」的体感来源）

---

## Problem

审计确认的 8 项「点了没反应 / 看着不对 / 找不到入口」：

| # | 问题 | 位置 | 体感 |
| --- | --- | --- | --- |
| F6a | 模板库「发送测试」按钮 `onClick` 空实现 | `TemplateLibrary:1128-1131` | 点了没反应 |
| F6b | 记录详情「重试」把弹窗关了 | `RecordDetailDialog:467` `onRetry={selected=null}` | 像 bug |
| F6c | 记录详情某按钮 `onClick={}` 空实现 | `RecordDetailDialog:1259` | 点了没反应 |
| F6d | `LinkRow` 无 `onClick` | `MainActivity.kt:1478` | 链接点不动 |
| F11 | 渠道列表解密后不缓存，每次进页重解密 | `379 / 564 / 1498` 三处各解一次 | 有卡顿 + 安全隐患（明文多处悬挂） |
| F13 | 无 contentDescription、触摸目标 <48dp、纯色区分状态 | `1392-1428` | TalkBack 不可用 |
| F14 | `historyRetention` / `privacyDisplayMode` 有字段无 UI 入口 | `AppSettingsRepository:33-34` | 设置了没地方改 |
| F15 | 后台健康度写死 `Warning` | `831-840` | 假状态误导 |
| F16 | Onboarding 无返回上一步 | `197 / 290` | 误操作无法回头 |
| F17 | `AboutLinksSection` 死代码 | `1162` | 噪音 |
| F18 | `navigation-compose` 依赖零调用 + 明暗双色板硬编码 | `build.gradle.kts:43` / `91-118, 165-170` | 体积 + 主题不一致 |

---

## Target

### 1. 四个死按钮（F6）—— 全部「修真或删掉」，不留装饰

- **F6a 发送测试**：实现真发送 —— 复用 `RelayEngine` 的单渠道发送（传渲染后的假数据），成功 Snackbar「测试消息已发送」，失败显示渠道名 + 错因。若不愿接发送链路，则**整颗按钮删除**（004 step 6 已选删除路线，本计划确认之）。
- **F6b 重试**：`onRetry` 改为真重发该条记录（走 `RelayEngine.retry(recordId)`），弹窗保持打开并显示「重试中…」；若不实现重发则删按钮。**二选一，禁止保留现状**。
- **F6c 空 `onClick={}`**：查清语义（上下文为详情弹窗底部动作），预期是「复制内容」→ 实现 `ClipboardManager` 复制 + Snackbar；否则删。
- **F6d `LinkRow`**：补 `onClick { uriHandler.openUri(url) }`（About 页外链本来就该能点）。

### 2. F11 · 渠道解密一次、remember 一次

`PushChannelScreen` 顶部一次解密存 `remember(storedChannels)`；三处调用点（379/564/1498）统一读这一个变量。解密结果不写日志（AGENTS.md 第 6 条）。

### 3. F13 · 无障碍基线

- 所有 icon-only 按钮补 `contentDescription`（中文）。
- 可点击行/按钮触摸目标 ≥ 48dp（不足的加 `Modifier.minimumInteractiveComponentSize()` 或 padding）。
- 「已启用/已停用」不只靠颜色 —— 加文字标签或图标差异（`Icon(Icons.Outlined.Check)` vs 空）。

### 4. F14 · 补两个缺失入口

`AdvancedSettingsScreen` 增加两行：
- 「历史记录保留」：枚举（7 天 / 30 天 / 90 天 / 永久）→ 写 `historyRetention`；
- 「隐私显示模式」：枚举（完整显示 / 隐藏正文 / 隐藏号码）→ 写 `privacyDisplayMode`，主页记录列表按它打码。

### 5. F15 · 健康度不写死

`BackgroundHealth` 三态改为**可判定**：读 `RelayService` 最近一次转发结果 + 电池优化白名单状态；都正常才 `Healthy`，取不到数据显 `Unknown`（灰）而不是 `Warning`（黄）。**禁止把未验证的厂商后台能力写成 stable**（AGENTS.md 第 7 条）—— 文案如实写「检测受限」。

### 6. F16 · Onboarding 可回退

`step` 改 `rememberSaveable`（与 002 一致）+ `BackHandler(step > 0) { step-- }`；第 0 步仍由系统返回退出。

### 7. F17 / F18 · 死代码与依赖

- 删 `AboutLinksSection`（1162）及其引用。
- 删 `build.gradle.kts:43` 的 `navigation-compose`（已确认 001 不引入 NavHost；若 001 改主意则此项作废）。
- 双色板：`91-118` 与 `165-170` 合并为单一 `AppColorScheme`，暗色走 `darkColorScheme()`，消除同名色两套值。

---

## Repo conventions to follow

- `AGENTS.md` 第 1 条：修复分散在独立文件（`RecordDetailDialog`、`AboutLinksSection` 所在文件），不回灌 MainActivity。
- `AGENTS.md` 第 2 条：重试发送走 Worker/RelayEngine，UI 不直接联网。
- `AGENTS.md` 第 6 条：渠道解密结果、号码打码后的原值**不进日志**。
- `AGENTS.md` 第 7 条：`BackgroundHealth` 的判定只写**实测过**的能力；检测不到就 `Unknown`，文案不吹。
- `AGENTS.md` 第 8 条：contentDescription、Snackbar、新增设置项全部简体中文。
- `AGENTS.md` 第 10 条：不提交任何调试产物。

## Steps

1. 逐个处理 F6a–F6d：**先查实现意图**（git blame/上下文语义），能补真的补真（F6d 必补、F6c 补复制），补不了的删（F6a/F6b 若 004/无重发链路则删）。每删一个都要同步删调用方。
2. `PushChannelScreen` 解密结果上提为单一 `remember`，删 3 处重复解密。
3. 无障碍补丁：contentDescription / 触摸目标 / 状态非纯色。
4. `AdvancedSettingsScreen` 补 F14 两行（写入已有 DataStore 字段，默认值不变 —— 不动用户存量配置）。
5. `BackgroundHealth` 三态判定 + `Unknown` 样式。
6. Onboarding `rememberSaveable` + `BackHandler`。
7. 删 `AboutLinksSection`、删 `navigation-compose` 依赖、合并双色板。
8. README / CHANGELOG 同步（尤其 F14 新增设置项要进更新日志）。

## Boundaries

- 不动 RelayEngine 主链路（重试若实现，走现有 send 路径，不新建网络栈）。
- 不动 Room schema；F14 两个字段已存在，只是补 UI。
- 不做完整无障碍审计（只做基线：contentDescription / 触摸目标 / 非纯色）。
- 不改 minSdk/targetSdk。
- 双色板合并不改任何颜色**值**，只消除重复定义（视觉零变化）。

## Verification

1. `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain` 通过。
2. `.\gradlew.bat :app:lintDebug --no-daemon --console=plain` 无新增告警（删依赖后仍绿）。
3. 真机手验：
   - 4 个死按钮：每个要么有真实反馈（Snackbar/发送/打开链接），要么已消失；**盲点不存在**；
   - 反复进出渠道页，解密只发生一次（加临时日志计数验证后移除）；
   - TalkBack 走一遍主页 + 设置页，所有按钮可聚焦可读；
   - 历史记录保留设 7 天 → 存量 >7 天记录被清理；隐私模式「隐藏正文」→ 主页列表正文打码；
   - 后台健康度：正常显 Healthy，关掉电池优化白名单后显 Warning，检测不到显 Unknown（灰）；
   - Onboarding 中途返回上一步，输入不丢（配合 002）。
4. 明暗两套主题下各截一图，确认视觉与改前一致（色板合并零变化）。

## Done when

- 零个「点了没反应」的控件。
- 渠道解密单次；无障碍基线达标；两个隐藏设置项有入口且生效。
- 健康度不再写死；Onboarding 可回退；死代码与无用依赖清零。
- README/CHANGELOG 事实一致。
