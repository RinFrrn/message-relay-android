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
internal fun AppRuleSettingsScreen(modifier: Modifier, appName: String, packageName: String, settings: AppSettings, colors: UiColors, onOpenTemplateLibrary: (String?) -> Unit) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    val existing = rules.firstOrNull { it.packageName == packageName }
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
    val isPhone = TemplateCatalog.recommend(appName, packageName) == "phone" || templateId == "phone"

    PageScaffold(appName, "以下设置仅对「$appName」生效；全局默认模板在「设置 → 消息模板」里改。", modifier, colors, scope = SettingScope.PER_APP) {
        SectionCard("转发设置", packageName, Icons.Outlined.Tune, colors) {
            SettingSwitchRow("转发这个 App 的通知", enabled, { enabled = it }, colors)
            SettingSwitchRow("仅息屏时推送", screenOffOnly, { screenOffOnly = it }, colors)
            if (screenOffOnly) StatusBadge("仅息屏时推送已开启", Indigo, colors)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("消息模板", "为这个 App 选择模板；模板内容是全局的，在「设置 → 消息模板 → 自定义模板库」维护。", Icons.Outlined.CheckCircle, colors) {
            TemplateSelector(templateId, { templateId = it }, colors, onAddTemplate = { onOpenTemplateLibrary(null) }, onEditTemplate = { onOpenTemplateLibrary(it) })
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
    }
}

@Composable
private fun TemplateSelector(selected: String, onSelect: (String) -> Unit, colors: UiColors, onAddTemplate: () -> Unit, onEditTemplate: (String) -> Unit) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    var deleting by remember { mutableStateOf<TemplateDefinition?>(null) }
    val canonicalSelected = TemplateCatalog.canonical(selected)
    Column {
        Text("这里的选择只影响这个 App；模板内容是全局的，在「设置 → 消息模板 → 自定义模板库」里维护。", color = colors.muted, fontSize = 13.sp)
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
                    TextButton(onClick = { onEditTemplate(template.id) }) { Text("编辑") }
                    TextButton(onClick = { deleting = template }) { Text("删除") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAddTemplate, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加自定义模板") }
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
    onRuleChange: (RuleEntity) -> Unit,
    onDelete: ((RuleEntity) -> Unit)? = null
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
        // 删除入口由「批量管理规则」页收敛而来：仅对已有规则显示，确认弹窗在调用方处理。
        if (onDelete != null && rule != null) {
            TextButton(onClick = { onDelete(rule) }) { Text("删除") }
        }
        // 右箭头指示：点整行进入该 App 的规则编辑页（二级页面），与系统设置的导航暗示一致。
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "打开${appName}的规则设置", tint = colors.muted)
    }
}
