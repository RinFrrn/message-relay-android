package io.github.messagerelay

data class TemplateDefinition(
    val id: String,
    val name: String,
    val title: String,
    val body: String = "{{body}}\n{{time}}",
    val builtIn: Boolean = true
) {
    fun template() = MessageTemplate(title, body)
}

object TemplateCatalog {
    const val GENERAL_ID = "general"
    const val STANDARD_ID = "standard"

    private const val APP_TITLE = "{{appName}}"
    private const val APP_BODY = "📝：内容：{{notificationBody}}\n\n🕒：接收时间：{{receivedLocalTime}}"
    private const val SIMPLE_BODY = "{{notificationBody}}\n{{receivedLocalTime}}"
    private const val PRIVACY_BODY = "🔒 内容已隐藏\n🕒：接收时间：{{receivedLocalTime}}"
    private const val RAW_TITLE = "{{notificationTitle}}"
    private const val RAW_BODY = "{{notificationBody}}\n\n📦 包名：{{packageName}}\n🕒：接收时间：{{receivedLocalTime}}"
    private const val PHONE_TITLE = "{{phoneNumber}}"
    private const val PHONE_BODY = "来自：{{fromLabel}}\n\n📍 归属地：{{phoneLocation}}\n\n📲 卡槽：{{simDisplayName}}\n\n🔔 提醒：{{callEventLabel}}\n\n🕒 接收时间：{{receivedLocalTime}}"
    private const val SMS_TITLE = "{{smsNumber}}"
    private const val SMS_BODY = "{{smsBody}}\n\n📩 来自：{{smsNumber}}\n\n📲 卡槽：{{simDisplayName}}\n\n🕒 接收时间：{{receivedLocalTime}}"

    // 用户可直接选择的预设：4 个通用预设内容互不相同（简洁 / 标准 / 隐私 / 原始）+ 电话、短信专用。
    val presetIds = listOf("simple", STANDARD_ID, "privacy", "raw", "phone", "sms")

    // 历史版本遗留的别名 id：渲染等同标准模板；规则引用它们时归一到 standard 做显示和选择。
    private val standardAliases = setOf(GENERAL_ID, "wechat", "work_wechat", "qq", "dingtalk", "feishu")

    val builtIns = listOf(
        TemplateDefinition(GENERAL_ID, "通用模板", APP_TITLE, APP_BODY),
        TemplateDefinition("simple", "简洁模板", APP_TITLE, SIMPLE_BODY),
        TemplateDefinition(STANDARD_ID, "标准模板", APP_TITLE, APP_BODY),
        TemplateDefinition("privacy", "隐私模板", APP_TITLE, PRIVACY_BODY),
        TemplateDefinition("raw", "原始通知模板", RAW_TITLE, RAW_BODY),
        TemplateDefinition("phone", "电话", PHONE_TITLE, PHONE_BODY),
        TemplateDefinition("sms", "短信", SMS_TITLE, SMS_BODY),
        TemplateDefinition("wechat", "微信", APP_TITLE, APP_BODY),
        TemplateDefinition("work_wechat", "企业微信", APP_TITLE, APP_BODY),
        TemplateDefinition("qq", "QQ", APP_TITLE, APP_BODY),
        TemplateDefinition("dingtalk", "钉钉", APP_TITLE, APP_BODY),
        TemplateDefinition("feishu", "飞书", APP_TITLE, APP_BODY)
    )

    fun byId(id: String) = builtIns.firstOrNull { it.id == id } ?: builtIns.first()

    fun presets(): List<TemplateDefinition> = presetIds.map(::byId)

    // 自定义模板的统一判定；管理面列表和名称回查都从这里取，避免各处自己 filter。
    fun customTemplates(all: List<TemplateEntity>): List<TemplateEntity> = all.filter { !it.builtIn }

    // 可选模板集合的单一来源：可选预设 + 自定义。所有「选模板」入口都用它，选项必须一致。
    fun allTemplates(all: List<TemplateEntity>): List<TemplateDefinition> =
        presets() + customTemplates(all).map(TemplateEntity::definition)

    // 别名 id 归一到可选预设 id；自定义模板 id 原样返回。
    fun canonical(id: String): String = if (id in standardAliases) STANDARD_ID else id

    // 展示名：别名显示为「标准模板」；自定义模板 id 在此查不到时原样返回 id。
    fun displayName(id: String): String = builtIns.firstOrNull { it.id == canonical(id) }?.name ?: id

    fun recommend(appName: String, packageName: String): String {
        val value = "$appName $packageName".lowercase()
        return when {
            "企业微信" in value || "wework" in value -> "work_wechat"
            "微信" in value || "com.tencent.mm" in value -> "wechat"
            "钉钉" in value || "dingtalk" in value -> "dingtalk"
            "飞书" in value || "feishu" in value || "lark" in value -> "feishu"
            "qq" in value -> "qq"
            "短信" in value || "信息" in value || "messaging" in value || "mms" in value -> "sms"
            "电话" in value || "拨号" in value || "dialer" in value || "incallui" in value -> "phone"
            else -> GENERAL_ID
        }
    }
}

data class SourceSelection(val appName: String, val packageName: String, val templateId: String)

object SelectedSources {
    fun add(sources: List<SourceSelection>, source: SourceSelection): List<SourceSelection> =
        if (sources.any { it.packageName == source.packageName }) sources else sources + source
}

object ChannelSelection {
    const val NO_BARK_TARGETS = "__none__"

    fun normalized(channels: List<ChannelConfig>): List<ChannelConfig> {
        val normalized = channels.mapIndexed { index, channel -> channel.normalized(index) }
        val bark = normalized.filter { it.type == "bark" && it.url.isNotBlank() }
        val dingtalk = normalized.firstOrNull { it.type == "dingtalk" && it.url.isNotBlank() }?.let(::listOf).orEmpty()
        val feishu = normalized.firstOrNull { it.type == "feishu" && it.url.isNotBlank() }?.let(::listOf).orEmpty()
        return dingtalk + feishu + bark
    }

    fun enabled(channels: List<ChannelConfig>, barkTargetIds: String = ""): List<ChannelConfig> {
        val active = normalized(channels).filter(ChannelConfig::enabled)
        val selectedBarkIds = barkTargetIds.lines().map(String::trim).filter(String::isNotBlank).toSet()
        val noBarkTargets = NO_BARK_TARGETS in selectedBarkIds
        return active.filter { channel ->
            channel.type != "bark" || (!noBarkTargets && (selectedBarkIds.isEmpty() || channel.id in selectedBarkIds))
        }
    }

    fun primaryEnabled(channels: List<ChannelConfig>, primaryChannelId: String, barkTargetIds: String = ""): List<ChannelConfig> {
        val candidates = enabled(channels, barkTargetIds)
        val primary = candidates.firstOrNull { it.id == primaryChannelId } ?: candidates.firstOrNull()
        return primary?.let { listOf(it.copy(enabled = true)) }.orEmpty()
    }

    fun singleEnabled(channels: List<ChannelConfig>): List<ChannelConfig> =
        enabled(channels).firstOrNull()?.let { listOf(it.copy(enabled = true)) }.orEmpty()
}
