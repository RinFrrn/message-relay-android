package io.github.messagerelay

// 模板变量文档：每个变量单独一行 + 复制按钮（复制完整「{{变量}}」）。
// 名称全集与 CoreLogic.SUPPORTED_TEMPLATE_VARIABLES 一一对应，由 TemplateRenderTest 校验；
// UI 参考列表与复制入口都从 TemplateVariableDocs 取数，不再手写说明文本。
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class TemplateVariableDoc(val name: String, val desc: String)

object TemplateVariableDocs {
    val categories: List<Pair<String, List<TemplateVariableDoc>>> = listOf(
        "通用" to listOf(
            TemplateVariableDoc("appName", "App 名"),
            TemplateVariableDoc("app", "App 名（{{appName}} 的简写）"),
            TemplateVariableDoc("notificationTitle", "通知标题"),
            TemplateVariableDoc("title", "通知标题（{{notificationTitle}} 的简写）"),
            TemplateVariableDoc("notificationBody", "通知正文"),
            TemplateVariableDoc("body", "通知正文（{{notificationBody}} 的简写）"),
            TemplateVariableDoc("receivedLocalTime", "接收时间"),
            TemplateVariableDoc("time", "接收时间（{{receivedLocalTime}} 的简写）"),
            TemplateVariableDoc("packageName", "包名")
        ),
        "电话" to listOf(
            TemplateVariableDoc("phoneNumber", "来电号码"),
            TemplateVariableDoc("fromLabel", "来电方：有联系人显示名字，否则显示号码"),
            TemplateVariableDoc("phoneLocation", "号码归属地"),
            TemplateVariableDoc("simDisplayName", "SIM 卡槽名"),
            TemplateVariableDoc("callEventLabel", "事件类型：未接来电 / 来电提醒 / 来电已接通")
        ),
        "短信" to listOf(
            TemplateVariableDoc("smsNumber", "短信号码"),
            TemplateVariableDoc("smsBody", "短信正文")
        ),
        "其他" to listOf(
            TemplateVariableDoc("contactName", "联系人名字")
        )
    )

    val all: List<TemplateVariableDoc> = categories.flatMap { it.second }
    val allNames: Set<String> = all.mapTo(mutableSetOf()) { it.name }
}

// 复制确认是偶发操作：只做 120ms 图标淡变（状态指示），不做弹跳等装饰动效。
private val CopyFeedbackEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val COPIED_FEEDBACK_MS = 1200L

@Composable
internal fun TemplateVariableReference(colors: UiColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        TemplateVariableDocs.categories.forEach { (category, variables) ->
            Spacer(Modifier.height(10.dp))
            Text(category, color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            variables.forEach { variable ->
                TemplateVariableRow(
                    variable = variable,
                    copied = copied == variable.name,
                    colors = colors,
                    onCopy = {
                        copyToClipboard(context, "{{${variable.name}}}")
                        copied = variable.name
                        scope.launch {
                            delay(COPIED_FEEDBACK_MS)
                            if (copied == variable.name) copied = null
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TemplateVariableRow(variable: TemplateVariableDoc, copied: Boolean, colors: UiColors, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("{{${variable.name}}}", color = colors.ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(variable.desc, color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        IconButton(onClick = onCopy, modifier = Modifier.semantics { contentDescription = "复制变量 ${variable.name}" }) {
            Crossfade(targetState = copied, animationSpec = tween(120, easing = CopyFeedbackEase), label = "copy-feedback") { done ->
                Icon(
                    imageVector = if (done) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = if (done) Success else colors.muted
                )
            }
        }
    }
}
