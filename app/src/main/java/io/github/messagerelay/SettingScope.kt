package io.github.messagerelay

// 设置作用域标记：让「全局」和「按应用」的配置入口一眼可辨。
// 全局 = 一处设置对所有 App 生效（如默认模板、推送渠道）；
// 按应用 = 每个 App 各有一份配置，在该 App 的规则编辑页里改（如单应用模板、关键词）。
// 标记只做静态区分，不做动效；含义由文字承载，不依赖颜色。
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal enum class SettingScope(val label: String) {
    GLOBAL("全局"),
    PER_APP("按应用")
}

@Composable
internal fun ScopeBadge(scope: SettingScope, colors: UiColors) {
    val tint: Color = if (scope == SettingScope.GLOBAL) Indigo else Success
    StatusBadge(scope.label, tint, colors)
}
