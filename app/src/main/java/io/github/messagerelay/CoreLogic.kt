package io.github.messagerelay

import java.time.LocalTime

data class RelayMessage(val packageName: String, val app: String, val title: String, val body: String, val time: Long)

data class RelayRule(val allowedPackages: Set<String> = emptySet(), val include: List<String> = emptyList(), val exclude: List<String> = emptyList()) {
    fun matches(message: RelayMessage): Boolean = filterReason(message) == null

    // 具体过滤原因：null = 通过。记录页展示命中的是哪个排除关键词（敏感词）或差在哪条包含词，
    // 而不是笼统的「规则未命中或命中排除关键词」。排除优先报告——它才是用户主动设的敏感词。
    fun filterReason(message: RelayMessage): String? {
        if (message.packageName !in allowedPackages) return "未启用该应用的转发"
        val content = "${message.title}\n${message.body}"
        exclude.firstOrNull(content::contains)?.let { return "命中排除关键词「$it」" }
        if (include.isNotEmpty() && include.none(content::contains)) {
            return "未命中包含关键词（需要包含：${include.joinToString("、")}）"
        }
        return null
    }
}

data class QuietHours(val enabled: Boolean = false, val start: LocalTime = LocalTime.of(22, 0), val end: LocalTime = LocalTime.of(7, 0), val urgent: List<String> = emptyList()) {
    fun shouldQueue(message: RelayMessage, now: LocalTime): Boolean {
        if (!enabled || urgent.any { message.title.contains(it) || message.body.contains(it) }) return false
        return if (start <= end) now >= start && now < end else now >= start || now < end
    }
}

object RecordRetentionPolicy {
    fun cutoffMillis(retention: String, now: Long): Long? = when (retention) {
        "7", "30", "90" -> now - retention.toLong() * 24 * 60 * 60 * 1000
        else -> null
    }

    fun shouldKeepBody(retention: String): Boolean = retention != "status_only"
}

data class RenderedMessage(val text: String, val unsupportedVariables: List<String>)

// 模板变量全集（17 个），与 MessageTemplate.templateData 的键保持一致，由 TemplateRenderTest 校验。
val SUPPORTED_TEMPLATE_VARIABLES: Set<String> = setOf(
    "app", "title", "body", "time",
    "appName", "notificationTitle", "notificationBody", "packageName",
    "smsBody", "contactName", "fromLabel", "phoneNumber", "smsNumber",
    "phoneLocation", "simDisplayName", "receivedLocalTime", "callEventLabel"
)

private val VARIABLE_REGEX = "\\{\\{(\\w+)\\}\\}".toRegex()

data class MessageTemplate(
    val title: String = "{{appName}}",
    val body: String = "📝：内容：{{notificationBody}}\n\n🕒：接收时间：{{receivedLocalTime}}"
) {
    fun renderTitle(message: RelayMessage) = renderDetailed(title, message).text
    fun renderBody(message: RelayMessage) = renderDetailed(body, message).text

    // 标题 + 正文里用到的、当前不支持的变量名（去重，按出现顺序）。
    fun unsupportedVariables(): List<String> =
        (VARIABLE_REGEX.findAll(title) + VARIABLE_REGEX.findAll(body))
            .map { it.groupValues[1] }
            .filterNot { it in SUPPORTED_TEMPLATE_VARIABLES }
            .distinct()
            .toList()

    // 渲染单个模板片段。未知变量原样保留并收集告警，绝不抛异常（旧版 require 会导致转发链路崩溃）。
    fun renderDetailed(value: String, message: RelayMessage): RenderedMessage {
        val unsupported = mutableListOf<String>()
        val data = templateData(message)
        val rendered = VARIABLE_REGEX.replace(value) {
            val key = it.groupValues[1]
            if (key in data) data[key].orEmpty()
            else {
                unsupported += key
                it.value
            }
        }
        return RenderedMessage(cleanRendered(rendered), unsupported.distinct())
    }

    private fun cleanRendered(rendered: String): String = rendered.lines()
        .map { it.trimEnd() }
        .filterNot { line ->
            val text = line.trim()
            text.equals("null", ignoreCase = true) ||
                text.contains("：null", ignoreCase = true) ||
                text.contains(":null", ignoreCase = true) ||
                text.endsWith("：") ||
                text.endsWith(":")
        }
        .joinToString("\n")
        .trim()

    private fun templateData(message: RelayMessage): Map<String, String> {
        val fields = parseStructuredBody(message.body)
        val localTime = TimeFormatter.formatRecordDetailTime(message.time)
        val notificationBody = cleanText(message.body).ifBlank { "该通知未提供正文" }
        val phoneNumber = fields["号码"].orEmpty()
            .ifBlank { phoneCandidate(message.title) }
            .ifBlank { if (message.title in setOf("隐藏号码", "公用电话")) message.title else "" }
            .ifBlank { "未知号码" }
        val contactName = fields["联系人"].orEmpty()
        val fromLabel = when {
            contactName.isNotBlank() -> contactName
            phoneNumber == "隐藏号码" -> "隐藏号码"
            phoneNumber == "未知号码" -> "未知"
            else -> phoneNumber
        }
        val sim = fields["卡槽"].orEmpty().ifBlank { fields["SIM"].orEmpty() }.ifBlank { "未知 SIM" }
        val location = finalLocation(fields["归属地"].orEmpty(), fields["号码段运营商"].orEmpty())
        val callEvent = fields["提醒"].orEmpty().ifBlank { callEventLabel(message.title) }
        val smsBody = fields["短信正文"].orEmpty().ifBlank { notificationBody }.ifBlank { "该短信未提供正文" }
        return mapOf(
            "app" to message.app,
            "title" to message.title,
            "body" to message.body,
            "time" to localTime,
            "appName" to cleanText(message.app).ifBlank { "未知应用" },
            "packageName" to message.packageName,
            "notificationTitle" to cleanText(message.title),
            "notificationBody" to notificationBody,
            "smsBody" to smsBody,
            "contactName" to contactName,
            "fromLabel" to fromLabel,
            "phoneNumber" to phoneNumber,
            "smsNumber" to phoneNumber,
            "phoneLocation" to location,
            "simDisplayName" to sim,
            "receivedLocalTime" to localTime,
            "callEventLabel" to callEvent
        )
    }

    private fun parseStructuredBody(body: String): Map<String, String> =
        body.lines().mapNotNull { raw ->
            val line = raw.trim()
            val index = listOf(line.indexOf('：'), line.indexOf(':')).filter { it > 0 }.minOrNull() ?: return@mapNotNull null
            val key = line.substring(0, index)
                .trim()
                .replace(Regex("^[👤📓📍📲🔔🕒📩📝]+"), "")
                .trim()
            key to cleanText(line.substring(index + 1).trim())
        }.toMap()

    private fun finalLocation(region: String, carrier: String): String {
        val cleanRegion = simplifyRegion(region)
        val cleanCarrier = simplifyCarrier(carrier)
        return when {
            cleanRegion.isNotBlank() && cleanCarrier.isNotBlank() -> "$cleanRegion · $cleanCarrier"
            cleanRegion.isNotBlank() -> cleanRegion
            cleanCarrier.isNotBlank() -> cleanCarrier
            else -> "无法识别"
        }
    }

    private fun simplifyRegion(value: String): String {
        val cleaned = cleanText(value)
            .replace("中国", "")
            .replace("省", "")
            .replace("市", "")
            .trim()
        return when {
            cleaned.length >= 4 && cleaned.endsWith("地区") -> cleaned.removeSuffix("地区")
            cleaned.length >= 4 && cleaned.contains("深圳") -> "深圳"
            cleaned.length >= 4 && cleaned.contains("广州") -> "广州"
            cleaned.length >= 4 && cleaned.contains("上海") -> "上海"
            cleaned.length >= 4 && cleaned.contains("北京") -> "北京"
            else -> cleaned
        }
    }

    private fun simplifyCarrier(value: String): String =
        cleanText(value)
            .replace("中国", "")
            .replace("移动通信", "移动")
            .replace("联通", "联通")
            .replace("电信", "电信")
            .replace("广电", "广电")
            .trim()

    private fun callEventLabel(value: String): String = when {
        value.contains("未接") || value == "MISSED_CALL" -> "未接来电"
        value.contains("接通") || value == "CALL_ANSWERED" -> "来电已接通"
        else -> "来电提醒"
    }

    private fun cleanText(value: String): String =
        value.trim().takeUnless {
            it.equals("null", ignoreCase = true) ||
                it.startsWith("Bundle", ignoreCase = true) ||
                it.startsWith("[L", ignoreCase = true)
        }.orEmpty()

    private fun phoneCandidate(value: String): String =
        Regex("""(?<!\d)(\+?\d[\d\s-]{5,}\d)(?!\d)""").find(value)?.value?.replace(" ", "")?.replace("-", "").orEmpty()
}

class DedupeWindow {
    private val seen = mutableMapOf<String, Long>()
    fun accept(message: RelayMessage, now: Long): Boolean {
        val key = "${message.packageName}|${message.title}|${message.body}"
        val last = seen[key]
        if (last != null && now - last <= 60_000) return false
        seen[key] = now
        seen.entries.removeIf { now - it.value > 60_000 }
        return true
    }
}
