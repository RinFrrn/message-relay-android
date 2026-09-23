package io.github.messagerelay

// 记录展示层的隐私打码：只影响本机显示，不改动数据库原值。
// 打码后的号码原值也不写入任何日志（AGENTS.md 第 6 条）。
object PrivacyDisplay {
    const val FULL = "full"
    const val MASKED = "masked"
    const val HIDDEN = "hidden"

    private val LONG_DIGITS = Regex("\\d{7,}")

    // hidden：长数字串（手机号等）保留后 4 位，其余打星号。
    fun title(raw: String, mode: String): String = if (mode == HIDDEN) maskDigits(raw) else raw

    // masked：正文整体隐藏；hidden：正文里的号码打码。
    fun body(raw: String, mode: String): String = when (mode) {
        MASKED -> if (raw.isBlank()) raw else "正文已隐藏"
        HIDDEN -> maskDigits(raw)
        else -> raw
    }

    private fun maskDigits(raw: String): String = LONG_DIGITS.replace(raw) { match ->
        val value = match.value
        "*".repeat(value.length - 4) + value.takeLast(4)
    }
}
