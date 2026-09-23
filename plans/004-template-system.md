# 004 — 模板系统打通：自定义模板可用 + 预设差异化 + 变量不崩

- **Status**: TODO
- **Commit**: `4ef8947`
- **Severity**: HIGH
- **Category**: Logic（功能实际不可用）
- **Estimated scope**: 3 文件（`TemplateCatalog.kt` / `CoreLogic.kt` / `MainActivity.kt`），约 130 行改动 + 1 个新测试

> **依赖**：无硬依赖。若 [005](005-entry-consolidation.md) 要做，建议**先做 004**（005 的入口收敛建立在"模板系统本身是通的"之上，先收敛入口会让 004 无处下手）。

## Problem

用户反馈：「高级设置自定义模板无效」。这是一条**四层断链**，外加一处崩溃风险。

**断链 ① —— 12 个内置模板里 10 个内容逐字节相同。**
`TemplateCatalog.kt:24-37` — current:

```kotlin
    val builtIns = listOf(
        TemplateDefinition(GENERAL_ID, "通用模板", APP_TITLE, APP_BODY),
        TemplateDefinition("simple", "简洁模板", APP_TITLE, APP_BODY),
        TemplateDefinition(STANDARD_ID, "标准模板", APP_TITLE, APP_BODY),
        TemplateDefinition("privacy", "隐私模板", APP_TITLE, APP_BODY),
        TemplateDefinition("raw", "原始通知模板", APP_TITLE, APP_BODY),
        TemplateDefinition("phone", "电话", PHONE_TITLE, PHONE_BODY),
        TemplateDefinition("sms", "短信", SMS_TITLE, SMS_BODY),
        TemplateDefinition("wechat", "微信", APP_TITLE, APP_BODY),
        TemplateDefinition("work_wechat", "企业微信", APP_TITLE, APP_BODY),
        TemplateDefinition("qq", "App 通知", APP_TITLE, APP_BODY),
        TemplateDefinition("dingtalk", "App 通知", APP_TITLE, APP_BODY),
        TemplateDefinition("feishu", "App 通知", APP_TITLE, APP_BODY)
    )
```

其中 `APP_TITLE = "{{appName}}"`、`APP_BODY = "📝：内容：{{notificationBody}}\n\n🕒：接收时间：{{receivedLocalTime}}"`（`:17-18`）。

**只有 `phone` 和 `sms` 用了不同的常量。** 其余 10 个（含「简洁模板」「隐私模板」「原始通知模板」）全部传的是同一对 `APP_TITLE`/`APP_BODY`。后果：
- 「隐私模板」**不隐藏任何内容** —— 名字在撒谎，这是隐私相关的误导，风险最高。
- 「原始通知模板」不是原始内容。
- 用户在 `SimpleTemplatePresetScreen`（`:788-805`）里切换四个预设，**预览和实际推送完全一样**，体感就是"改了没用"。
- 另有 3 个同名项「App 通知」（`qq`/`dingtalk`/`feishu`），在 `TemplateCatalog` 里无法靠 name 区分。

**断链 ② —— 自定义模板存进去就再也选不到。**
`MainActivity.kt:1132-1137` — current:

```kotlin
        PrimaryAction("保存模板", colors) {
            scope.launch {
                dao.saveTemplate(TemplateEntity("custom_${System.currentTimeMillis()}", name.ifBlank { "自定义模板" }, title, body))
                status = "模板已保存"
            }
        }
```

保存成功后，`TemplateEntity` 进了 Room 表。但**没有任何规则能引用它**：

`MainActivity.kt:1333-1343` — current:

```kotlin
@Composable
private fun TemplateSelector(selected: String, onSelect: (String) -> Unit, colors: UiColors) {
    Column {
        Text("模板可随时修改", color = colors.muted, fontSize = 13.sp)
        simpleTemplatePresets().forEach { template ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(template.name, color = colors.ink)
                RadioButton(selected == template.id, onClick = { onSelect(template.id) })
            }
        }
    }
}
```

配合 `MainActivity.kt:1567-1568` — current:

```kotlin
private fun simpleTemplatePresets(): List<TemplateDefinition> =
    listOf("simple", TemplateCatalog.STANDARD_ID, "privacy", "raw").map(TemplateCatalog::byId)
```

`TemplateSelector` **只列 4 个写死的内置 id**。`custom_<ts>` 永远不在选项里 → 永远不可能被赋给 `RuleEntity.templateId`。

于是发送时 `RelayEngine.kt:176` — current:

```kotlin
        val template = (dao.template(requestedTemplate)?.definition() ?: TemplateCatalog.byId(TemplateCatalog.STANDARD_ID)).template()
```

`dao.template("custom_<ts>")` **永远查不到**（因为没有规则带着这个 id），回落到标准模板。**用户保存的自定义模板就这样静默失效。**

DAO 层三个方法**零调用点**（已 grep 全仓库确认）：`templatesFlow()`（`Database.kt:54`）、`deleteCustomTemplate()`（`:60`）、`fallbackTemplate()`（`:59`）。自定义模板连**查看**和**删除**的入口都没有。

**断链 ③ —— 选项集与推荐集不相交，单选按钮全部不选中。**
`TemplateCatalog.recommend()`（`TemplateCatalog.kt:41-53`）返回的是 `work_wechat` / `wechat` / `dingtalk` / `feishu` / `qq` / `sms` / `phone` / `general`。
`TemplateSelector` 只认 `simple` / `standard` / `privacy` / `raw`。

两个集合**交集为空**（除 `general` 走 fallback）。后果：给微信配规则后打开 App 设置页，「消息模板」区**四个单选按钮一个都不选中** —— 用户看到的是一片空白选择。

再叠加 `MainActivity.kt:1570-1575` — current:

```kotlin
private fun simpleTemplateName(id: String): String = when (id) {
    "simple" -> "简洁模板"
    "privacy" -> "隐私模板"
    "raw" -> "原始通知模板"
    else -> "标准模板"
}
```

`else` 分支把 `wechat`/`phone`/`sms`/`qq`/`dingtalk`/`feishu`/`custom_xxx` **统统显示成"标准模板"**。`SimpleAppRow`（`:1225`）里展示给用户看的名字也是错的。

**断链 ④ —— 全局预设只影响新建规则，界面却说"选择模板即可使用"。**
`AppSettings.selectedTemplatePreset` 只在两处作为**默认值**参与：`:1220`（`SimpleAppRow` 新建规则时）和 `:725`（`AppRuleSettingsScreen` 的 `recommendedTemplate` fallback）。**发送链路从不读它**（`RelayEngine` 只看 `rule.templateId`）。

所以用户在「消息模板」页换了模板（`:791` `repository.setSelectedTemplatePreset(template.id)`），**已存在的规则一个都不会变**。但页面副标题写的是「选择模板即可使用」（`:787`）—— 与事实不符。

**崩溃风险 —— 写错变量直接崩。**
`CoreLogic.kt:39-45` — current:

```kotlin
    private fun render(value: String, message: RelayMessage): String {
        val data = templateData(message)
        val rendered = "\\{\\{(\\w+)\\}\\}".toRegex().replace(value) {
            val key = it.groupValues[1]
            require(key in data) { "不支持的模板变量：$key" }
            data[key].orEmpty()
        }
```

`require` 失败会抛 `IllegalArgumentException`。自定义模板里手滑写 `{{appname}}`（小写）或 `{{titlee}}`：
- 点「本地预览」（`:1124-1127`）→ 异常从 onClick 抛出 → **应用崩溃**
- 发送时 → `RelayWorker.doWork()` 内抛出 → 该条消息永久失败且不留记录

而 UI 只文档了 15 个变量里的 4 个（`:1120`）：

```kotlin
        Text("变量说明：{{app}} 是 App 名，{{title}} 是通知标题，{{body}} 是通知正文，{{time}} 是时间。", color = colors.muted, lineHeight = 19.sp)
```

实际可用变量 15 个（`CoreLogic.kt:79-96`）：`app` `title` `body` `time` `appName` `notificationTitle` `notificationBody` `smsBody` `contactName` `fromLabel` `phoneNumber` `smsNumber` `phoneLocation` `simDisplayName` `receivedLocalTime` `callEventLabel`。用户根本没机会知道还有 `phoneLocation` 这类电话专用变量。

## Target

### 1. 四套预设真正差异化（修断链 ①）

`TemplateCatalog.kt:17-37` — target。**每套预设的正文必须肉眼可辨不同**：

```kotlin
    private const val APP_TITLE = "{{appName}}"
    private const val APP_BODY = "📝：内容：{{notificationBody}}\n\n🕒：接收时间：{{receivedLocalTime}}"
    private const val PHONE_TITLE = "{{phoneNumber}}"
    private const val PHONE_BODY = "来自：{{fromLabel}}\n\n📍 归属地：{{phoneLocation}}\n\n📲 卡槽：{{simDisplayName}}\n\n🔔 提醒：{{callEventLabel}}\n\n🕒 接收时间：{{receivedLocalTime}}"
    private const val SMS_TITLE = "{{smsNumber}}"
    private const val SMS_BODY = "{{smsBody}}\n\n📩 来自：{{smsNumber}}\n\n📲 卡槽：{{simDisplayName}}\n\n🕒 接收时间：{{receivedLocalTime}}"

    // 四套可选预设，内容必须互不相同
    private const val SIMPLE_TITLE = "{{appName}}"
    private const val SIMPLE_BODY = "{{notificationBody}}"

    private const val PRIVACY_TITLE = "{{appName}}"
    private const val PRIVACY_BODY = "🔒 {{appName}} 有一条新消息\n（内容已隐藏）\n\n🕒 {{receivedLocalTime}}"

    private const val RAW_TITLE = "{{appName}}｜{{notificationTitle}}"
    private const val RAW_BODY = "{{notificationBody}}\n\n---\n来源包名：{{packageName}}\n通知标题：{{notificationTitle}}\n接收时间：{{receivedLocalTime}}"
```

> `{{packageName}}` 是**新变量**，必须在 `CoreLogic.kt` 的 `templateData()` 里补上（见下）。

`builtIns` — target：

```kotlin
    val builtIns = listOf(
        TemplateDefinition(GENERAL_ID, "通用模板", APP_TITLE, APP_BODY),
        TemplateDefinition("simple", "简洁模板", SIMPLE_TITLE, SIMPLE_BODY),
        TemplateDefinition(STANDARD_ID, "标准模板", APP_TITLE, APP_BODY),
        TemplateDefinition("privacy", "隐私模板", PRIVACY_TITLE, PRIVACY_BODY),
        TemplateDefinition("raw", "原始通知模板", RAW_TITLE, RAW_BODY),
        TemplateDefinition("phone", "电话", PHONE_TITLE, PHONE_BODY),
        TemplateDefinition("sms", "短信", SMS_TITLE, SMS_BODY),
        TemplateDefinition("wechat", "微信", APP_TITLE, APP_BODY),
        TemplateDefinition("work_wechat", "企业微信", APP_TITLE, APP_BODY),
        TemplateDefinition("qq", "QQ", APP_TITLE, APP_BODY),
        TemplateDefinition("dingtalk", "钉钉", APP_TITLE, APP_BODY),
        TemplateDefinition("feishu", "飞书", APP_TITLE, APP_BODY)
    )
```

改名理由：`qq`/`dingtalk`/`feishu` 三项原先都叫「App 通知」（3 个同名），列表里无法区分。改成实际来源名。

`CoreLogic.kt` 的 `templateData()`（`:79-96`）返回的 map 里补一个键：

```kotlin
            "packageName" to message.packageName,
```

> **`AGENTS.md` 规则 6：不得在日志中输出完整电话号码。** `{{packageName}}` 是包名不是号码，安全。但请确认**不要**顺手把 `message.body` 原文加进 `channelResults` 之类的日志字段。

### 2. 让自定义模板可选、可看、可删（修断链 ②）

`TemplateSelector`（`:1333-1343`）— target。改成合并「内置预设 + 仓库里所有自定义模板」：

```kotlin
@Composable
private fun TemplateSelector(selected: String, onSelect: (String) -> Unit, colors: UiColors, onDeleteCustom: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val allTemplates by dao.templatesFlow().collectAsState(initial = emptyList())
    val custom = remember(allTemplates) { allTemplates.filterNot(TemplateEntity::builtIn) }

    Text("模板可随时修改", color = colors.muted, fontSize = 13.sp)
    Text("内置模板", color = colors.ink, fontWeight = FontWeight.Bold)
    simpleTemplatePresets().forEach { template ->
        TemplateRow(template.id, template.name, selected, onSelect, colors, onDelete = null)
    }
    if (custom.isNotEmpty()) {
        Text("自定义模板", color = colors.ink, fontWeight = FontWeight.Bold)
        custom.forEach { entity ->
            TemplateRow(entity.id, entity.name, selected, onSelect, colors, onDelete = onDeleteCustom)
        }
    }
}

@Composable
private fun TemplateRow(id: String, name: String, selected: String, onSelect: (String) -> Unit, colors: UiColors, onDelete: ((String) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = colors.ink, modifier = Modifier.weight(1f))
        RadioButton(selected == id, onClick = { onSelect(id) })
        if (onDelete != null) TextButton(onClick = { onDelete(id) }) { Text("删除") }
    }
}
```

`TemplateLibrary`（`:1110-1141`）「保存模板」之后，**除了保存还要让它可被选中**。在同一个 `SectionCard` 里加一段「已保存的自定义模板」列表，复用 `TemplateSelector` 的 custom 段即可；删除走 `dao.deleteCustomTemplate(id)`（`Database.kt:60`，已存在，目前零调用点）。

**删除时必须保护规则**（`AGENTS.md` 规则 5：不得静默丢弃规则）：

```kotlin
onDeleteCustom = { id ->
    scope.launch {
        dao.fallbackTemplate(id, TemplateCatalog.STANDARD_ID)   // Database.kt:59，已存在
        dao.deleteCustomTemplate(id)
    }
}
```

`fallbackTemplate` 会把引用该模板的规则改回标准模板 —— 这正是它存在的意义（`Database.kt:59`），现在终于被用上。**不要只 `deleteCustomTemplate` 不 `fallbackTemplate`**，那会让规则指向一个不存在的 id。

### 3. 选项集取并集 + 名称映射（修断链 ③）

`TemplateSelector` 的内置段仍用 `simpleTemplatePresets()`（4 个预设），但**当 `selected` 是这 4 个之外的 id 时必须有一行显示它**，否则用户看不到当前选择。

在 `TemplateRow` 循环之前加一段兜底：

```kotlin
    val knownIds = simpleTemplatePresets().map(TemplateDefinition::id).toSet() + custom.map(TemplateEntity::id).toSet()
    if (selected !in knownIds) {
        TemplateRow(selected, TemplateCatalog.byId(selected).name, selected, onSelect, colors, onDelete = null)
    }
```

（`TemplateCatalog.byId` 在 `TemplateCatalog.kt:39`，对未知 id 回落 `builtIns.first()` —— 这里只用它的 name，可接受。）

`simpleTemplateName`（`:1570-1575`）— target，把 `else` 分支从「一律标准模板」改成查真实名字：

```kotlin
private fun simpleTemplateName(id: String): String =
    TemplateCatalog.byId(id).name.let { name ->
        if (id !in setOf("simple", TemplateCatalog.STANDARD_ID, "privacy", "raw", TemplateCatalog.GENERAL_ID)) name
        else name
    }
```

> 上面写法等价于 `TemplateCatalog.byId(id).name`，**直接简化成它**：
>
> ```kotlin
> private fun simpleTemplateName(id: String): String = TemplateCatalog.byId(id).name
> ```
>
> 但注意 `byId` 对 `custom_*` 会回落到「通用模板」。所以自定义模板的名字要走 DB —— `SimpleAppRow`（`:1225`）展示时，把 `simpleTemplateName(rule.templateId)` 换成先查 DB 的 `TemplateEntity.name`，查不到再 `simpleTemplateName`。做法：在 `SimpleAppSelectionScreen` 里 `val templates by dao.templatesFlow().collectAsState(initial = emptyList())`，然后 `remember(templates) { templates.associateBy(TemplateEntity::id) }` 传进 `SimpleAppRow`。

### 4. 全局预设的语义改成「新建规则的默认模板」（修断链 ④）

**不改数据结构**（`AGENTS.md` 规则 4：DataStore 字段必须有默认值 —— 现有默认值 `TemplateCatalog.STANDARD_ID` 保持不变），**只改文案 + 补一条批量应用**：

`SimpleTemplatePresetScreen`（`:787`）副标题 — target：

```
"选择模板作为新建 App 规则的默认模板。已有规则不会自动改变，可到「软件选择」里逐个调整。"
```

并在这个页面加一个显式的批量应用按钮（**可选操作，不自动执行**，避免"静默改变用户已有配置"）：

```kotlin
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            scope.launch {
                val dao = RelayDatabase.get(LocalContext.current).relayDao()
                dao.allRules().forEach { rule ->
                    dao.saveRule(rule.copy(templateId = settings.selectedTemplatePreset))
                }
                status = "已把全部 ${dao.allRules().size} 个规则的模板设为当前选择"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("把当前模板应用到全部已有规则") }
```

需要 import `androidx.compose.runtime.getValue`/`setValue` 已有。加 `var status by remember { mutableStateOf("") }` 并在按钮下方用 `StatusBadge` 显示（参照 `:1139` 的写法）。

**必须是用户主动点的按钮**，不能做成"换预设即批量应用"—— 那就是静默覆盖用户逐个配好的模板，违反 `AGENTS.md` 规则 5。

### 5. 变量校验不再抛异常 + 全量变量说明（修崩溃）

`CoreLogic.kt:36-58` — target。`render` 不再 `require`，改为**保留原样 + 返回告警**：

```kotlin
data class RenderedMessage(val text: String, val unsupportedVariables: List<String> = emptyList())

data class MessageTemplate(
    val title: String = "{{appName}}",
    val body: String = "📝：内容：{{notificationBody}}\n\n🕒：接收时间：{{receivedLocalTime}}"
) {
    fun renderTitle(message: RelayMessage) = render(title, message).text
    fun renderBody(message: RelayMessage) = render(body, message).text

    /** 校验模板变量，返回不被支持的变量名（不带花括号）。UI 应在保存前调用它。 */
    fun unsupportedVariables(): List<String> {
        val known = templateData(EMPTY_MESSAGE).keys
        return VARIABLE_REGEX.findAll(title + "\n" + body)
            .map { it.groupValues[1] }
            .filterNot { it in known }
            .distinct()
            .toList()
    }

    fun render(value: String, message: RelayMessage): RenderedMessage {
        val data = templateData(message)
        val unsupported = mutableListOf<String>()
        val rendered = VARIABLE_REGEX.replace(value) {
            val key = it.groupValues[1]
            if (key in data) data[key].orEmpty()
            else {
                unsupported += key
                it.value                      // 保留 {{key}} 原样，不抛异常
            }
        }
        // 后续的 null/空行清理逻辑保持不变
        ...
        return RenderedMessage(cleaned, unsupported)
    }

    private companion object {
        val VARIABLE_REGEX = "\\{\\{(\\w+)\\}\\}".toRegex()
        val EMPTY_MESSAGE = RelayMessage("", "", "", "", 0L)
    }
}
```

要点：
1. **未知变量保留 `{{key}}` 原样输出**，绝不抛异常。用户在 Bark 里看到 `{{appname}}` 字样，比应用崩溃好得多。
2. `renderTitle` / `renderBody` 签名不变（返回 `String`），**`RelayEngine.kt:179-180` 无需改动**。
3. 新增 `unsupportedVariables()` 供 UI 做**保存前校验**。
4. 原来的 null/空行清理（`:46-57`）保持不变，套在 rendered 之后。

`TemplateLibrary`（`:1132`）「保存模板」加**保存前校验**：

```kotlin
        PrimaryAction("保存模板", colors) {
            val bad = MessageTemplate(title, body).unsupportedVariables()
            if (bad.isNotEmpty()) {
                status = "不支持的变量：${bad.joinToString("、")}，请修改后再保存"
            } else {
                scope.launch {
                    dao.saveTemplate(TemplateEntity("custom_${System.currentTimeMillis()}", name.ifBlank { "自定义模板" }, title, body))
                    status = "模板已保存"
                }
            }
        }
```

「本地预览」（`:1124-1127`）也改成先校验、再渲染，把 `unsupported` 显示出来：

```kotlin
        OutlinedButton(onClick = {
            val tpl = MessageTemplate(title, body)
            val renderedTitle = tpl.renderTitle(previewMessage())
            val renderedBody = tpl.renderBody(previewMessage())
            preview = renderedTitle + "\n" + renderedBody
            val bad = tpl.unsupportedVariables()
            status = if (bad.isEmpty()) "本地预览已生成" else "预览中不支持的变量已原样保留：${bad.joinToString("、")}"
        }, modifier = Modifier.fillMaxWidth()) { Text("本地预览") }
```

`MainActivity.kt:1120` 的变量说明 — target（15 + 1 个新变量，分组列出）：

```kotlin
        Text(
            "通用变量：{{appName}} App 名 · {{notificationTitle}} 通知标题 · {{notificationBody}} 通知正文 · {{receivedLocalTime}} 接收时间 · {{packageName}} 包名\n" +
                "兼容别名：{{app}} · {{title}} · {{body}} · {{time}}\n" +
                "电话变量：{{phoneNumber}} 号码 · {{fromLabel}} 来电方 · {{phoneLocation}} 归属地 · {{simDisplayName}} 卡槽 · {{callEventLabel}} 事件类型\n" +
                "短信变量：{{smsNumber}} 号码 · {{smsBody}} 短信正文\n" +
                "其他：{{contactName}} 联系人",
            color = colors.muted,
            lineHeight = 19.sp
        )
```

同样的文案替换 `AdvancedSettingsScreen:880` 的 `SettingNavRow` 副标题（当前写的是「编辑 {{app}}、{{title}}、{{body}}、{{time}} 等变量。」）—— 至少要改成「支持 15 个变量，含电话/短信专用变量，进入后有完整说明。」

变量对照表（新增 `{{packageName}}` 后共 16 个）：

| 变量 | 别名 | 来源 | 通知 | 电话 | 短信 |
| --- | --- | --- | --- | --- | --- |
| `{{appName}}` | `{{app}}` | RenderInputs | ✅ | ✅ | ❌ 留空 |
| `{{notificationTitle}}` | `{{title}}` | RenderInputs | ✅ | ❌ | ❌ |
| `{{notificationBody}}` | `{{body}}` | RenderInputs | ✅ | ❌ | ❌ |
| `{{receivedLocalTime}}` | `{{time}}` | RenderInputs | ✅ | ✅ | ✅ |
| `{{packageName}}` | — | RenderInputs | ✅ | ❌ | ❌ |
| `{{phoneNumber}}` | — | 来电/短信 | ❌ | ✅ | ✅ |
| `{{fromLabel}}` | — | 来电 | ❌ | ✅ | ❌ |
| `{{phoneLocation}}` | — | 来电 | ❌ | ✅ | ❌ |
| `{{simDisplayName}}` | — | 来电 | ❌ | ✅ | ❌ |
| `{{callEventLabel}}` | — | 来电 | ❌ | ✅ | ❌ |
| `{{smsNumber}}` | — | 短信 | ❌ | ❌ | ✅ |
| `{{smsBody}}` | — | 短信 | ❌ | ❌ | ✅ |
| `{{contactName}}` | — | 来电/短信 | ❌ | ✅ | ✅ |

**render() 与 RelayEngine 的签名不变**（`fun render(template: String, values: Map<String, String>): String`），只是把 `require` 换成原样保留 + 收集告警。`RelayEngine.kt:176` 的调用方不需要改；`RenderedMessage` 只有 UI 预览用。

---

## Repo conventions to follow

- `AGENTS.md` 第 1 条：模板相关新逻辑放 `TemplateCatalog.kt` / `CoreLogic.kt` / 独立 Saver 对象，**不进 `MainActivity.kt`**；MainActivity 只做接线。
- `AGENTS.md` 第 2 条：模板库的增删查走 `MessageDatabase` / Repository，UI 不直接碰 DAO。现有 `TemplateLibrary` 已经是这个结构，保持。
- `AGENTS.md` 第 5 条：**不得清空用户现有配置**。12 个内置模板里 10 个同名同体 —— 本次把它们替换成 4 个差异化预设时，只动 `TemplateCatalog.builtIns` 的**静态列表**，不碰 Room 里的 `templates` 表；用户已有自定义模板零影响。
- `AGENTS.md` 第 6 条：渲染失败/变量告警日志只打变量**名字**，不打用户输入的内容（正文可能含验证码）。
- `AGENTS.md` 第 8 条：所有新增提示文案简体中文。
- `AGENTS.md` 第 9 条：本计划落地后，README「消息模板」段落要同步改成「4 个差异化预设 + 自定义模板」，CHANGELOG 记一条。

## Steps

1. **`TemplateCatalog.kt` — 差异化预设**：删掉 10 个重复项，写 4 个新预设（Simple/Privacy/Raw/IM-Push）；预设 body 按上文 target 文案；给 `TemplateInfo` 加 `id` 字段（沿用现有 `qq`/`dingtalk`/`feishu` 作为 id 不改名，避免 Room 里已存 `simpleTemplateName` 的规则失联 —— 只改 `name` 展示名）。
2. **`CoreLogic.kt` — 渲染改造**：
   - `RenderInputs` 加 `packageName: String = ""`，`toValues()` 加 `"packageName" to packageName`。
   - `render()` 去掉两处 `require(...)`，未覆盖键原样保留；顺带把 `unsupportedVariables(template)` 提为公开工具函数（UI 复用）。
   - 新增 `data class RenderedMessage(val text: String, val unsupportedVariables: List<String>)` + `fun renderDetailed(...)`；`render()` 保留为 `renderDetailed().text` 的薄壳，`RelayEngine` 不改。
3. **`MainActivity.kt:1333-1343` `TemplateSelector` 合并自定义模板**：
   - 顶部 `val templates by remember { db.templatesFlow() }.collectAsState(initial = emptyList())`；注意这里要在 `LaunchedEffect` 里 fallback 到 `db.customTemplates()` 同步取一次（Flow 首帧可能空）。
   - `RadioButton` 循环 = 内置 4 预设 + 自定义列表；自定义项显示 `模板名 · 已被 N 条规则使用`（`rules.count { it.simpleTemplateName == tpl.id }`）。
   - 长按自定义项弹确认框 → `db.fallbackTemplate(tpl.id, rules)` + `db.deleteCustomTemplate(tpl.id)`（`fallbackTemplate` 本身已把引用它的规则迁到 Simple 预设，不会孤儿）。
4. **选项集合取并集**：`TemplateSelector` 的 `options` 参数删掉「只认 4 个 id」的 `listOf("qq","dingtalk","feishu","simple")` 过滤，改成「内置 + 自定义 id 全集」；`onSelect` 回传 id。
5. **全局模板 = 新规则默认值**：`AppSettingsRepository` 新增 `val globalDefaultTemplate: Flow<String>`（默认 `"simple"`），`AdvancedSettingsScreen` 加一个「默认消息模板（仅作用于新建规则）」选择行 + 一个「批量应用到全部规则」按钮（二次确认，走 `db.rulesFlow()` 一次性 update）。
6. **UI 预览验证**：`TemplateLibrary` 编辑区加「本地预览」按钮，用 `renderDetailed()` + 假数据，把 `unsupportedVariables` 显示成黄色提示；删除 `TemplateLibrary:1128-1131` 的空发送按钮（属 006 范围，但这里顺手删掉避免误触）。
7. **变量说明文案**：按上文 target 替换 `MainActivity.kt:1120` 与 `AdvancedSettingsScreen:880` 两处。
8. **单测**（`app/src/test/.../TemplateRenderTest.kt`，新增）：
   - 渲染未知变量原样保留 + 返回告警列表；
   - 4 预设互不相同（防回归：`builtIns.map { it.body }.distinct().size == 4`）；
   - `{{packageName}}` 可替换、未传时留空不崩；
   - `unsupportedVariables()` 对 16 变量全集的正反例。
9. **同步文档**：README 模板段落、CHANGELOG、应用内更新日志。

## Boundaries

- 不动 `RelayEngine.kt` 的发送链路（除了 `RenderInputs` 多一个字段的传参）。
- 不动 Room schema：`templates` 表结构不变，**不需要 Migration**。
- 不删 `TemplateLibrary` 的导入/导出功能。
- 不在本次合并三套规则 UI（属 005）。
- 12 个旧内置模板 id（`qq`/`dingtalk`/`feishu`/`simple` + 其余）若被规则引用，`fallbackTemplate` 兜底映射到新预设，**规则不丢**。
- 不做模板云同步 / 分享。

## Verification

1. `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain` 通过。
2. `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` —— 新增 4 组用例全绿。
3. 真机手验：
   - 模板库看到 4 个**内容不同**的预设（Privacy 预设预览不含正文）；
   - 建一条规则选自定义模板 → 发一条测试通知 → 推送内容 = 自定义模板渲染结果；
   - 模板里写 `{{不存在}}` → 不崩，推送文本里原样出现 `{{不存在}}`；
   - 删除一个被 2 条规则引用的自定义模板 → 那 2 条规则自动回到 Simple 预设，规则列表无「模板丢失」；
   - 全局默认模板改成 Raw → 新建规则默认落在 Raw，已有规则不变；点「批量应用」后全部规则变 Raw。
4. `.\gradlew.bat :app:lintDebug --no-daemon --console=plain` 无新增告警。

## Done when

- 4 个预设肉眼可辨（标题/正文/后缀各不相同），10 个重复预设消失。
- 自定义模板能被规则选中、能渲染推送、能删除且不留孤儿规则。
- `{{不存在}}` 不再崩溃；UI 能列出不支持的变量。
- `{{packageName}}` 可用，变量说明列出全部 16 个。
- 单测绿，编译绿，README/CHANGELOG 事实一致。
