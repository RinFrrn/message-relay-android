package io.github.messagerelay

// 自定义模板库：新增 + 编辑 + 删除（此前只有新增和删除，已保存的模板点不动）。
// 编辑 = 同 id 覆盖保存（dao.saveTemplate 是 REPLACE upsert）：引用它的规则下次推送即生效，
// 零 Migration、零 DataStore 改动。内置模板不可编辑——ensureTemplates 每次启动都会回写内置内容。
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun TemplateLibrary(
    colors: UiColors,
    editIntent: String = "",
    onEditIntentConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    var name by rememberSaveable { mutableStateOf(DEFAULT_NAME) }
    var title by rememberSaveable { mutableStateOf(DEFAULT_TITLE) }
    var body by rememberSaveable { mutableStateOf(DEFAULT_BODY) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<TemplateEntity?>(null) }
    val nameFocus = remember { FocusRequester() }
    val customTemplates = TemplateCatalog.customTemplates(templates)
    val editing = customTemplates.firstOrNull { it.id == editingId }

    // 安全网：编辑期间模板被别处删除（如规则页的删除按钮）时退出编辑态，表单内容保留，
    // 之后的保存转为新建，不复活已删 id。templates 首帧是空列表（Room 未发射），有内置模板兜底，
    // 所以非空即代表数据已就绪，不会在首帧误重置。
    LaunchedEffect(editingId, templates) {
        if (editingId.isNotEmpty() && templates.isNotEmpty() && editing == null) {
            editingId = ""
            status = "原模板已不存在，当前表单内容将保存为新模板"
        }
    }

    // 外部入口（消息模板列表 / 规则编辑页）点「编辑」带来的模板 id：
    // 等 Room 首次发射后载入表单，随即消费掉意图，返回该页时不会重复载入。
    LaunchedEffect(editIntent, templates) {
        if (editIntent.isEmpty() || templates.isEmpty()) return@LaunchedEffect
        val target = customTemplates.firstOrNull { it.id == editIntent }
        if (target != null) {
            editingId = target.id
            name = target.name
            title = target.title
            body = target.body
            preview = ""
            status = "正在编辑「${target.name}」，改完点上方「更新模板」"
        } else {
            status = "要编辑的模板不存在，可能已删除"
        }
        onEditIntentConsumed()
    }

    fun resetForm() {
        editingId = ""
        name = DEFAULT_NAME
        title = DEFAULT_TITLE
        body = DEFAULT_BODY
        preview = ""
    }

    SectionCard("模板库", "模板决定转发消息在 Bark、飞书、钉钉里显示成什么样。", Icons.AutoMirrored.Outlined.List, colors) {
        TemplateVariableReference(colors)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus), singleLine = true)
        OutlinedTextField(title, { title = it }, label = { Text("标题样式") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("正文样式") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedButton(onClick = {
            val template = MessageTemplate(title, body)
            preview = template.renderTitle(previewMessage()) + "\n" + template.renderBody(previewMessage())
            val bad = template.unsupportedVariables()
            status = if (bad.isEmpty()) "本地预览已生成" else "预览中不支持的变量已原样保留：${bad.joinToString("、")}"
        }, modifier = Modifier.fillMaxWidth()) { Text("本地预览") }
        PrimaryAction(if (editingId.isEmpty()) "保存模板" else "更新模板", colors) {
            val bad = MessageTemplate(title, body).unsupportedVariables()
            if (bad.isNotEmpty()) {
                status = "存在不支持的变量：${bad.joinToString("、")}，请修正后再保存"
            } else {
                scope.launch {
                    val safeName = name.ifBlank { "自定义模板" }
                    val target = customTemplates.firstOrNull { it.id == editingId }
                    if (target != null) {
                        dao.saveTemplate(target.copy(name = safeName, title = title, body = body))
                        status = "模板已更新，引用它的规则下次推送即生效"
                    } else {
                        dao.saveTemplate(TemplateEntity("custom_${System.currentTimeMillis()}", safeName, title, body))
                        editingId = ""
                        status = "模板已保存，可在 App 规则里选择"
                    }
                }
            }
        }
        if (editingId.isNotEmpty()) {
            TextButton(onClick = {
                resetForm()
                status = "已退出编辑，表单已恢复默认"
            }, modifier = Modifier.fillMaxWidth()) { Text("取消编辑，恢复新增") }
        }
        if (preview.isNotBlank()) Text(preview, color = colors.ink, lineHeight = 19.sp)
        if (status.isNotBlank()) StatusBadge(status, if ("已" in status && "不支持" !in status) Success else Warning, colors)
    }
    Spacer(Modifier.height(12.dp))
    SectionCard("已保存的自定义模板", "点「编辑」载入上方表单修改；删除前会把正在使用它的规则自动回到通用模板。", Icons.Outlined.CheckCircle, colors) {
        if (customTemplates.isEmpty()) EmptyText("还没有自定义模板。", colors)
        customTemplates.forEach { template ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(template.name, color = colors.ink, fontWeight = FontWeight.Bold)
                        if (editingId == template.id) {
                            Spacer(Modifier.width(6.dp))
                            StatusBadge("编辑中", Indigo, colors)
                        }
                    }
                    Text("已被 ${rules.count { it.templateId == template.id }} 条规则使用", color = colors.muted, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    editingId = template.id
                    name = template.name
                    title = template.title
                    body = template.body
                    preview = ""
                    status = "正在编辑「${template.name}」，改完点上方「更新模板」"
                    runCatching { nameFocus.requestFocus() }
                }) { Text("编辑") }
                TextButton(onClick = { deleting = template }) { Text("删除") }
            }
        }
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
                    if (editingId == target) resetForm()
                    scope.launch {
                        dao.fallbackTemplate(target)
                        dao.deleteCustomTemplate(target)
                        status = "已删除模板"
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

private const val DEFAULT_NAME = "自定义模板"
private const val DEFAULT_TITLE = "{{app}}：{{title}}"
private const val DEFAULT_BODY = "{{body}}\n{{time}}"
