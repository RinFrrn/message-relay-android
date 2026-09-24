package io.github.messagerelay

// 规则 UI 单一编辑面：AppRuleSettingsScreen 是唯一能改规则字段的地方；
// SimpleAppRow（软件选择行内）和 Rules（规则列表页）只做展示、enabled 开关和进入编辑页。
// 模板选择器 TemplateSelector 是「选模板」的唯一入口（维护模板在 TemplateLibrary）。
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun AppRuleSettingsScreen(modifier: Modifier, appName: String, packageName: String, settings: AppSettings, colors: UiColors, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    val existing = rules.firstOrNull { it.packageName == packageName }
    // 「发送渠道」卡（009 A2）：规则页必须能回答「这个 App 的消息去哪」。
    // 渠道存在 SecureStore；刷新靠本页 state，弹窗保存后直接更新。
    var channels by remember { mutableStateOf(ChannelSelection.normalized(storedChannels(context))) }
    var showTargetsDialog by remember { mutableStateOf(false) }
    val defaultTarget = if (settings.multiChannelSend) {
        "全部渠道（${channels.size} 个）"
    } else {
        "主渠道「${channels.firstOrNull { it.id == settings.primaryChannelId }?.name ?: channels.firstOrNull()?.name ?: "未配置"}」"
    }
    val boundBarks = channels.filter { it.type == "bark" && packageName in it.boundPackages() }
    val recommendedTemplate = TemplateCatalog.recommend(appName, packageName).takeIf { it != TemplateCatalog.GENERAL_ID } ?: settings.selectedTemplatePreset
    var enabled by rememberSaveable(existing?.enabled, packageName) { mutableStateOf(existing?.enabled ?: true) }
    var screenOffOnly by rememberSaveable(existing?.screenOffOnly, packageName) { mutableStateOf(existing?.screenOffOnly ?: false) }
    var includes by rememberSaveable(existing?.includes, packageName) { mutableStateOf(existing?.includes ?: defaultIncludesForTemplate(recommendedTemplate)) }
    var excludes by rememberSaveable(existing?.excludes, packageName) { mutableStateOf(existing?.excludes.orEmpty()) }
    var templateId by rememberSaveable(existing?.templateId, packageName) { mutableStateOf(existing?.templateId ?: recommendedTemplate) }
    var callTypes by rememberSaveable(existing?.enabledCallEventTypes, packageName, stateSaver = CallTypesSaver) {
        mutableStateOf(CallEventTypes.parse(existing?.enabledCallEventTypes ?: CallEventTypes.serialize(CallEventTypes.default)))
    }
    var status by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val isPhone = TemplateCatalog.recommend(appName, packageName) == "phone" || templateId == "phone"

    PageScaffold(appName, "以下设置仅对「$appName」生效；全局默认模板在「设置 → 消息模板」里改。", modifier, colors) {
        SectionCard("转发设置", packageName, Icons.Outlined.Tune, colors) {
            SettingSwitchRow("转发这个 App 的通知", enabled, { enabled = it }, colors)
            SettingSwitchRow("仅息屏时推送", screenOffOnly, { screenOffOnly = it }, colors)
            if (screenOffOnly) StatusBadge("仅息屏时推送已开启", Indigo, colors)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard(
            "发送渠道",
            "这个 App 的消息发到哪里；管理所有渠道在「设置 → 推送渠道」。",
            Icons.Outlined.Notifications, colors
        ) {
            Text("默认：$defaultTarget", color = colors.ink, fontWeight = FontWeight.Medium)
            if (boundBarks.isNotEmpty()) {
                Text(
                    "指定 Bark：${boundBarks.joinToString("、") { it.name }}（替代默认目标里的 Bark）",
                    color = Indigo, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
            TextButton(onClick = { showTargetsDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (boundBarks.isEmpty()) "为这个 App 指定 Bark（可选）" else "管理指定 Bark")
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("消息模板", "为这个 App 选择模板；模板内容是全局的，在「设置 → 消息模板」维护，此处添加/编辑用弹窗完成，不离开本页。", Icons.Outlined.CheckCircle, colors) {
            TemplateSelector(templateId, { templateId = it }, colors)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("关键词规则", "包含为空表示不过滤；排除命中即记为已过滤，记录里会写明命中的具体关键词。", Icons.Outlined.List, colors) {
            OutlinedTextField(includes, { includes = it }, label = { Text("包含关键词，每行一个") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedTextField(excludes, { excludes = it }, label = { Text("排除关键词，每行一个") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
        if (isPhone) {
            Spacer(Modifier.height(12.dp))
            SectionCard("电话通知类型", "至少选择一种。", Icons.Outlined.Notifications, colors) {
                CallTypeSelector(callTypes, { callTypes = it }, colors)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction("保存 App 设置", colors) {
            if (isPhone && callTypes.isEmpty()) {
                status = "请至少选择一种电话通知类型"
            } else {
                scope.launch {
                    dao.saveRule(
                        (existing ?: RuleEntity(packageName, appName)).copy(
                            appName = appName,
                            includes = includes,
                            excludes = excludes,
                            enabled = enabled,
                            templateId = templateId,
                            screenOffOnly = screenOffOnly,
                            enabledCallEventTypes = CallEventTypes.serialize(callTypes)
                        )
                    )
                    status = "App 设置已保存"
                }
            }
        }
        if (status.isNotBlank()) StatusBadge(status, if ("已保存" in status) Success else Danger, colors)
        Spacer(Modifier.height(12.dp))
        // 删除规则收敛到详情页（列表行不再放删除，避免在列表里误触）。
        // 删除时顺带把这个 App 从所有 Bark 渠道的绑定里清掉，避免残留死绑定。
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("删除这个 App 的转发规则", color = Danger)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「$appName」的转发规则？") },
            text = { Text("关键词、模板等配置会一并删除；历史记录保留。重新添加应用可随时重建规则。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        dao.deleteRule(packageName)
                        val cleaned = channels.map { ch ->
                            if (packageName in ch.boundPackages()) {
                                ch.copy(boundAppPackages = ch.boundPackages().filter { it != packageName }.joinToString("\n"))
                            } else ch
                        }
                        SecureStore(context).put("channels", ChannelSender.serialize(cleaned))
                        onDeleted()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
    if (showTargetsDialog) {
        AppBarkTargetsDialog(
            appName = appName,
            packageName = packageName,
            channels = channels,
            colors = colors,
            onDismiss = { showTargetsDialog = false },
            onSave = { updated ->
                SecureStore(context).put("channels", ChannelSender.serialize(updated))
                channels = updated
                showTargetsDialog = false
            }
        )
    }
}

/**
 * 按 App 视角的指定 Bark 弹窗：勾选这个 App 要发到哪些 Bark（009 A2）。
 * 与渠道页的 BarkBindingDialog 是同一份绑定数据的两个视角：绑定存于渠道的 boundAppPackages，
 * 此处按 App 反选渠道。绑定语义与 PerAppRouteResolver 一致：勾选后这个 App 不再发主渠道里的其他 Bark。
 */
@Composable
private fun AppBarkTargetsDialog(
    appName: String,
    packageName: String,
    channels: List<ChannelConfig>,
    colors: UiColors,
    onDismiss: () -> Unit,
    onSave: (List<ChannelConfig>) -> Unit
) {
    val barks = channels.filter { it.type == "bark" }
    var selected by rememberSaveable(packageName, channels, stateSaver = PackageSetSaver) {
        mutableStateOf(barks.filter { packageName in it.boundPackages() }.map { it.id }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$appName」指定 Bark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("勾选后，这个 App 只发到选中的 Bark（替代默认发送目标里的 Bark）；全部不勾选则走默认发送目标。", color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                if (barks.isEmpty()) {
                    EmptyText("还没有 Bark 渠道；在「设置 → 推送渠道」里添加。", colors)
                } else {
                    barks.forEach { bark ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(bark.name, color = colors.ink, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Checkbox(checked = bark.id in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + bark.id else selected - bark.id
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(channels.map { channel ->
                    if (channel.type != "bark") channel
                    else {
                        val rest = channel.boundPackages() - packageName
                        val next = if (channel.id in selected) rest + packageName else rest
                        channel.copy(boundAppPackages = next.sorted().joinToString("\n"))
                    }
                })
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TemplateSelector(selected: String, onSelect: (String) -> Unit, colors: UiColors) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    var deleting by remember { mutableStateOf<TemplateDefinition?>(null) }
    // 添加 / 编辑自定义模板一律就地弹窗（与渠道页同一模式），不再跳到模板库页。
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    val canonicalSelected = TemplateCatalog.canonical(selected)
    Column {
        TemplateCatalog.allTemplates(templates).forEach { template ->
            if (template.builtIn) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(template.name, color = colors.ink, modifier = Modifier.weight(1f))
                    RadioButton(canonicalSelected == template.id, onClick = { onSelect(template.id) })
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(template.name, color = colors.ink)
                        Text("自定义 · 已被 ${rules.count { it.templateId == template.id }} 条规则使用", color = colors.muted, fontSize = 12.sp)
                    }
                    RadioButton(selected == template.id, onClick = { onSelect(template.id) })
                    TextButton(onClick = {
                        editing = TemplateCatalog.customTemplates(templates).firstOrNull { it.id == template.id }
                    }) { Text("编辑") }
                    TextButton(onClick = { deleting = template }) { Text("删除") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加自定义模板") }
    }
    if (adding) {
        TemplateEditorDialog(initial = null, colors = colors, onDismiss = { adding = false }, onSave = { name, title, body ->
            scope.launch { dao.saveTemplate(TemplateEntity("custom_${System.currentTimeMillis()}", name, title, body)) }
            adding = false
        })
    }
    editing?.let { target ->
        TemplateEditorDialog(initial = target, colors = colors, onDismiss = { editing = null }, onSave = { name, title, body ->
            scope.launch { dao.saveTemplate(target.copy(name = name, title = title, body = body)) }
            editing = null
        })
    }
    deleting?.let { template ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除模板「${template.name}」？") },
            text = { Text("正在使用它的规则会自动回到通用模板，规则不会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = template.id
                    deleting = null
                    scope.launch {
                        dao.fallbackTemplate(target)
                        dao.deleteCustomTemplate(target)
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CallTypeSelector(selected: Set<CallEventType>, onChange: (Set<CallEventType>) -> Unit, colors: UiColors) {
    Column {
        Text("电话通知类型", color = colors.ink, fontWeight = FontWeight.Bold)
        listOf(
            CallEventType.MISSED_CALL to "未接来电",
            CallEventType.INCOMING_RINGING to "来电提醒",
            CallEventType.CALL_ANSWERED to "来电已接通"
        ).forEach { (type, label) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = colors.ink)
                Checkbox(checked = type in selected, onCheckedChange = { checked: Boolean ->
                    onChange(if (checked) selected + type else selected - type)
                })
            }
        }
    }
}

@Composable
internal fun SimpleAppRow(
    appName: String,
    packageName: String,
    rule: RuleEntity?,
    templatePreset: String,
    customTemplates: List<TemplateEntity>,
    hitCount: Int,
    colors: UiColors,
    onOpenSettings: (Pair<String, String>) -> Unit,
    onRuleChange: (RuleEntity) -> Unit
) {
    val templateId = TemplateCatalog.recommend(appName, packageName).takeIf { it != TemplateCatalog.GENERAL_ID } ?: templatePreset
    val enabled = rule?.enabled == true
    val label = if (rule?.screenOffOnly == true) "仅息屏时推送 · ${templateLabel(rule.templateId, customTemplates)}" else templateLabel(rule?.templateId ?: templateId, customTemplates)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(onClick = { onOpenSettings(appName to packageName) }).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(appName, color = colors.ink, fontWeight = FontWeight.Bold)
            Text(if (hitCount > 0) "$label · 近期命中 $hitCount 次" else label, color = colors.muted, fontSize = 13.sp, lineHeight = 17.sp)
            if (rule?.screenOffOnly == true) StatusBadge("仅息屏", Indigo, colors)
        }
        Switch(enabled, modifier = Modifier.semantics { contentDescription = "${appName}转发开关" }, onCheckedChange = { checked ->
            onRuleChange((rule ?: RuleEntity(packageName, appName, defaultIncludesForTemplate(templateId), templateId = templateId)).copy(enabled = checked))
        })
        // 删除规则在各 App 的规则编辑页底部（带确认弹窗），列表行不放删除。
        // 右箭头指示：点整行进入该 App 的规则编辑页（二级页面），与系统设置的导航暗示一致。
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "打开${appName}的规则设置", tint = colors.muted)
    }
}
