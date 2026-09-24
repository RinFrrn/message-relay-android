package io.github.messagerelay

// 自定义模板库（008 重构）：纯列表 + 编辑弹窗，与渠道页同一交互模式（R2：简单实体一律弹窗）。
// 此前是「页面内常驻表单 + 跨页 editIntent 跳转载入」，编辑要把用户甩出原页面上下文，已废弃。
// 编辑 = 同 id 覆盖保存（dao.saveTemplate 是 REPLACE upsert）：引用它的规则下次推送即生效，
// 零 Migration。内置模板不可编辑——ensureTemplates 每次启动都会回写内置内容。
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 自定义模板管理块：列表 + 「添加」按钮 + 编辑弹窗 + 删除确认。
 * 嵌入在「消息模板」页内，不再是独立页面。
 */
@Composable
internal fun TemplateLibrary(colors: UiColors) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    val customTemplates = TemplateCatalog.customTemplates(templates)
    // 编辑对象：null = 关闭弹窗；非 null 且 id 空 = 新增；非空 id = 编辑该模板。
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TemplateEntity?>(null) }

    Column {
        SectionCard(
            "自定义模板",
            if (customTemplates.isEmpty()) "还没有自定义模板；点「添加模板」新建一个。" else "点「编辑」修改；删除前会自动把使用它的规则换回通用模板。",
            Icons.Outlined.CheckCircle, colors
        ) {
            customTemplates.forEach { template ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(template.name, color = colors.ink, fontWeight = FontWeight.Bold)
                        Text("已被 ${rules.count { it.templateId == template.id }} 条规则使用", color = colors.muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { editing = template }) { Text("编辑") }
                    TextButton(onClick = { deleting = template }) { Text("删除") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("添加模板")
        }
    }

    if (adding) {
        TemplateEditorDialog(initial = null, colors = colors, onDismiss = { adding = false }, onSave = { name, title, body ->
            scope.launch { dao.saveTemplate(TemplateEntity("custom_${System.currentTimeMillis()}", name, title, body)) }
            adding = false
        })
    }
    editing?.let { target ->
        // key 绑定目标 id：换编辑对象时表单整体重置，与渠道弹窗一致。
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
                    if (editing?.id == target) editing = null
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

/**
 * 模板编辑弹窗：新增 / 编辑共用。表单状态随弹窗生灭（rememberSaveable 以 initial?.id 为 key），
 * 校验与本地预览在弹窗内完成，保存即关。
 */
@Composable
internal fun TemplateEditorDialog(
    initial: TemplateEntity?,
    colors: UiColors,
    onDismiss: () -> Unit,
    onSave: (name: String, title: String, body: String) -> Unit
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "自定义模板") }
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title ?: "{{app}}：{{title}}") }
    var body by rememberSaveable(initial?.id) { mutableStateOf(initial?.body ?: "{{body}}\n{{time}}") }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加模板" else "编辑模板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text("标题样式") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("正文样式") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedButton(onClick = {
                    val template = MessageTemplate(title, body)
                    preview = template.renderTitle(previewMessage()) + "\n" + template.renderBody(previewMessage())
                    val bad = template.unsupportedVariables()
                    error = if (bad.isEmpty()) "" else "预览中不支持的变量已原样保留：${bad.joinToString("、")}"
                }, modifier = Modifier.fillMaxWidth()) { Text("本地预览") }
                if (preview.isNotBlank()) Text(preview, color = colors.ink, lineHeight = 19.sp)
                if (error.isNotBlank()) Text(error, color = Warning, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val bad = MessageTemplate(title, body).unsupportedVariables()
                when {
                    bad.isNotEmpty() -> error = "存在不支持的变量：${bad.joinToString("、")}"
                    else -> onSave(name.ifBlank { "自定义模板" }, title, body)
                }
            }) { Text(if (initial == null) "保存" else "更新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
