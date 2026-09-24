# 008 UI 信息架构重设计：标准标题栏 + 交互模式统一 + 入口收敛

> 依据 apple-design skill（WWDC Designing Fluid Interfaces / Principles of Great Design）。
> 用户点名问题：① 添加/编辑交互混乱；② 入口层级记不清、重复；③ 缺标准标题栏。

## 现状诊断

### P1 三种添加/编辑模式并存（交互混乱）
- 渠道：列表页 + 编辑**弹窗**（ChannelEditorDialog）✓ 已是好模式
- 应用：添加**弹窗**（AddAppDialog）+ 编辑跳**独立页** ✓ 可接受（复杂表单）
- 模板：**页面内常驻表单** + 跨页 `templateEditIntent` 跳转载入 —— 最混乱：
  从规则编辑页点「编辑模板」→ 跳到模板库页 → 载入表单 → 改完返回规则页，用户被甩出上下文

### P2 入口重复（Home 与 Settings 双导航树）
- Home 有 7 张 FeatureCard（推送渠道/软件选择/消息模板/免打扰/后台运行/记录保存/备份）
  ——全部与设置页入口重复，Home 实为「第二个设置页」，层级记不住的根源
- 模板维护入口 3 处：消息模板页、规则编辑页（跳页）、软件选择页（跳页）

### P3 无标准标题栏
- PageScaffold = 「28sp 大字 + 18dp padding」，标题随内容滚走
- 「＋ 添加」按钮沉在页面正文底部，不是标题栏动作
- 主 Tab 页与子页头部结构无差异，无层级感

## 设计规则（定死，全 App 一致）

**R1 标题栏**：所有页面统一 `AppTopBar`：返回按钮（子页）+ 20sp SemiBold 标题 + scope 徽标 + 右侧动作槽（`actions`）；固定不随滚动；副标题 13sp muted 随内容滚动。

**R2 添加/编辑二选一**：
- 简单实体（≤4 字段）→ **弹窗**（渠道/应用添加/模板）
- 复杂多分区表单 → **独立页**（App 规则编辑）
- 禁止页面内常驻表单、禁止跨页编辑跳转（`templateEditIntent` 删除）

**R3 入口唯一**：
- 首页 = 状态仪表盘（运行开关 + 首次配置进度 + 最近记录），只保留进度卡三个修复入口，删除 7 张 FeatureCard
- 设置 = 唯一功能入口树，两层封顶（Tab → 设置 → 子页），无跨页跳转
- 模板维护单入口：设置 → 消息模板（页内：预设选择 + 自定义模板列表，增删改全弹窗）
- `SubPage.AdvancedTemplates` 删除，模板库不再是独立页

## 最终层级

```
底部 Tab
├─ 首页   运行状态 · 首次配置进度(3 修复入口) · 最近记录
├─ 记录   过滤 Tab · 记录列表 · 详情弹窗
└─ 设置   转发设置：软件选择(→App规则编辑页) / 消息模板(预设+自定义弹窗) / 免打扰 / 记录与隐私
          推送与数据：推送渠道(弹窗) / 备份与恢复
          应用：外观 / 后台运行 / SIM
          高级 / 帮助与关于
```

## 实施清单

1. `PageScaffold` → 固定 AppTopBar（返回并入 + actions 槽）+ 滚动内容区
2. `TemplateLibraryScreen.kt` 重写：`TemplateEditorDialog`（表单弹窗）+ 纯列表；删 editIntent 机制
3. `SimpleTemplatePresetScreen` 吸收自定义模板列表（弹窗增删改）；删 SubPage.AdvancedTemplates / templateEditIntent
4. `RulesScreens.kt`：TemplateSelector 的添加/编辑改就地弹窗，删 onOpenTemplateLibrary 跳页
5. Home 删 7 张 FeatureCard，回归仪表盘
6. 「＋ 添加」类主按钮上移到标题栏 actions 位（推送渠道 / 消息模板）

Principles 映射：R1→Craft/Familiarity（一致的结构可预测）；R2→Simplicity（同一件事只有一种做法）；R3→Purpose（每个入口的职责唯一）+ Wayfinding（两层封顶永不迷路）。
