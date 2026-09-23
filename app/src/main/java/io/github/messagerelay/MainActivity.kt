package io.github.messagerelay

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal val Indigo = Color(0xFF5368FF)
internal val Success = Color(0xFF119B75)
internal val Warning = Color(0xFFE6A500)
internal val Danger = Color(0xFFD92D20)

internal data class UiColors(
    val ink: Color,
    val muted: Color,
    val page: Color,
    val card: Color,
    val border: Color
)

private val LightUi = UiColors(
    ink = Color(0xFF17203B),
    muted = Color(0xFF667085),
    page = Color(0xFFF7F8FF),
    card = Color.White,
    border = Color(0xFFE6E9F5)
)

private val DarkUi = UiColors(
    ink = Color(0xFFE8ECF8),
    muted = Color(0xFFB8C0D8),
    page = Color(0xFF101525),
    card = Color(0xFF182033),
    border = Color(0xFF303A55)
)

// App 名可能含空格/标点，分隔符用 ASCII SOH（U+0001），避免与名称内容冲突。
private val PAIR_SEP = Char(1).toString()

private val SourceSelectionListSaver: Saver<List<SourceSelection>, Any> = listSaver<List<SourceSelection>, String>(
    save = { list -> list.flatMap { listOf(it.appName, it.packageName, it.templateId) } },
    restore = { flat -> flat.chunked(3).filter { it.size == 3 }.map { SourceSelection(it[0], it[1], it[2]) } }
)

private val AppPairSaver: Saver<Pair<String, String>?, String> = Saver(
    save = { pair -> pair?.let { it.first + PAIR_SEP + it.second } ?: "" },
    restore = { value ->
        if (value.isEmpty()) null
        else value.split(PAIR_SEP, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    }
)

internal val CallTypesSaver: Saver<Set<CallEventType>, String> = Saver(
    save = { CallEventTypes.serialize(it) },
    restore = { CallEventTypes.parse(it) }
)

private val PackageSetSaver: Saver<Set<String>, Any> = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() }
)

private object Motion {
    val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f) // --ease-drawer
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f) // --ease-out
    const val NAV_DURATION_MS = 280
    const val TAB_DURATION_MS = 180
    const val FADE_ONLY_DURATION_MS = 150
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MessageRelayApp {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Outlined.Home),
    Records("记录", Icons.Outlined.History),
    Settings("设置", Icons.Outlined.Settings)
}

private enum class SubPage {
    PushChannels,
    AppSelection,
    TemplatePresets,
    QuietHours,
    BackgroundHealth,
    BackupRestore,
    RecordPrivacy,
    Manual,
    Advanced,
    AdvancedRules,
    AdvancedTemplates,
    SimManagement,
    AppRuleSettings,
    VersionUpdate,
    About
}

@Composable
fun MessageRelayApp(openPermission: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { AppSettingsRepository(context) }
    val settings by repository.settings.collectAsState(initial = AppSettings())
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val scheme = if (dark) {
        darkColorScheme(primary = Indigo, background = DarkUi.page, surface = DarkUi.card, onBackground = DarkUi.ink)
    } else {
        lightColorScheme(primary = Indigo, background = LightUi.page, surface = LightUi.card, onBackground = LightUi.ink)
    }
    val colors = if (dark) DarkUi else LightUi
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    val subStack = remember { mutableStateListOf<SubPage>() }
    val subPage = subStack.lastOrNull()
    var editingApp by rememberSaveable(stateSaver = AppPairSaver) { mutableStateOf<Pair<String, String>?>(null) }
    var navDirection by remember { mutableStateOf(1) } // 1 = push，-1 = pop
    val reducedMotion = rememberReducedMotion()

    fun push(page: SubPage) {
        navDirection = 1
        if (subStack.lastOrNull() != page) subStack.add(page)
    }

    fun pop() {
        navDirection = -1
        if (subStack.isNotEmpty()) subStack.removeAt(subStack.lastIndex)
    }

    LaunchedEffect(Unit) {
        val dao = RelayDatabase.get(context).relayDao()
        dao.ensureTemplates(repository.current())
        reconcileUpgradeState(context, repository, dao)
    }
    LaunchedEffect(settings.onboardingComplete, settings.autoCheckUpdates, settings.lastUpdateCheckAt) {
        if (settings.onboardingComplete && settings.autoCheckUpdates) {
            val now = System.currentTimeMillis()
            if (now - settings.lastUpdateCheckAt >= UPDATE_CHECK_INTERVAL_MS) {
                withContext(Dispatchers.IO) {
                    UpdateRepository().check(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
                }
                repository.setLastUpdateCheckAt(now)
            }
        }
    }

    MaterialTheme(colorScheme = scheme) {
        if (!settings.onboardingComplete) {
            Onboarding(openPermission, repository, colors)
            return@MaterialTheme
        }
        BackHandler(enabled = subStack.isNotEmpty()) { pop() }
        Scaffold(
            containerColor = colors.page,
            bottomBar = {
                if (subPage == null) {
                    NavigationBar(containerColor = colors.card) {
                        MainTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            val stateHolder = rememberSaveableStateHolder()
            val navSpec = if (reducedMotion) {
                fadeIn(tween(Motion.FADE_ONLY_DURATION_MS, easing = Motion.EaseOut)) togetherWith
                    fadeOut(tween(Motion.FADE_ONLY_DURATION_MS, easing = Motion.EaseOut))
            } else if (navDirection >= 0) {
                // push：新页从右侧 30% 滑入，旧页向左退 15%
                (slideInHorizontally(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)) { (it * 0.30f).toInt() } +
                    fadeIn(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer))) togetherWith
                    (slideOutHorizontally(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)) { (-it * 0.15f).toInt() } +
                        fadeOut(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)))
            } else {
                // pop：push 的严格镜像
                (slideInHorizontally(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)) { (-it * 0.15f).toInt() } +
                    fadeIn(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer))) togetherWith
                    (slideOutHorizontally(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)) { (it * 0.30f).toInt() } +
                        fadeOut(tween(Motion.NAV_DURATION_MS, easing = Motion.EaseDrawer)))
            }
            val tabSpec = fadeIn(tween(Motion.TAB_DURATION_MS, easing = Motion.EaseOut)) togetherWith
                fadeOut(tween(Motion.TAB_DURATION_MS, easing = Motion.EaseOut))
            AnimatedContent(targetState = tab, transitionSpec = { tabSpec }, label = "main-tab") { activeTab ->
                AnimatedContent(targetState = subPage, transitionSpec = { navSpec }, label = "main-nav") { page ->
                    val modifier = Modifier.padding(padding)
                    stateHolder.SaveableStateProvider(key = page to activeTab) {
                        when (page) {
                            SubPage.PushChannels -> PushChannelScreen(modifier, settings, repository, colors)
                            SubPage.AppSelection -> SimpleAppSelectionScreen(modifier, settings, repository, colors, onOpenRules = { push(SubPage.AdvancedRules) }) { app ->
                                editingApp = app
                                push(SubPage.AppRuleSettings)
                            }
                            SubPage.TemplatePresets -> SimpleTemplatePresetScreen(modifier, settings, repository, colors, onOpenCustomTemplates = { push(SubPage.AdvancedTemplates) })
                            SubPage.QuietHours -> QuietHoursScreen(modifier, settings, repository, colors)
                            SubPage.BackgroundHealth -> BackgroundHealthScreen(modifier, openPermission, colors)
                            SubPage.BackupRestore -> BackupRestoreScreen(modifier, colors)
                            SubPage.RecordPrivacy -> RecordPrivacyScreen(modifier, settings, repository, colors)
                            SubPage.Manual -> UserManualScreen(modifier, colors)
                            SubPage.Advanced -> AdvancedSettingsScreen(
                                modifier = modifier,
                                settings = settings,
                                repository = repository,
                                colors = colors
                            )
                            SubPage.AdvancedRules -> Rules(modifier, colors) { app ->
                                editingApp = app
                                push(SubPage.AppRuleSettings)
                            }
                            SubPage.AdvancedTemplates -> PageScaffold("自定义消息模板", "维护全局模板；保存后在各 App 的规则编辑页选用。", modifier, colors, scope = SettingScope.GLOBAL) { TemplateLibrary(colors) }
                            SubPage.SimManagement -> SimManagementScreen(modifier, colors)
                            SubPage.AppRuleSettings -> editingApp?.let { AppRuleSettingsScreen(modifier, it.first, it.second, settings, colors) }
                                ?: SimpleAppSelectionScreen(modifier, settings, repository, colors, onOpenRules = { push(SubPage.AdvancedRules) }) { app ->
                                    editingApp = app
                                    push(SubPage.AppRuleSettings)
                                }
                            SubPage.VersionUpdate -> VersionUpdateScreen(modifier, settings, repository, colors)
                            SubPage.About -> AboutMessageRelayScreen(modifier, colors)
                            null -> when (activeTab) {
                                MainTab.Home -> Home(
                                    modifier = modifier,
                                    settings = settings,
                                    repository = repository,
                                    openPermission = openPermission,
                                    colors = colors,
                                    onOpenChannel = { push(SubPage.PushChannels) },
                                    onOpenApps = { push(SubPage.AppSelection) },
                                    onOpenTemplates = { push(SubPage.TemplatePresets) },
                                    onOpenQuiet = { push(SubPage.QuietHours) },
                                    onOpenBackground = { push(SubPage.BackgroundHealth) },
                                    onOpenBackup = { push(SubPage.BackupRestore) },
                                    onOpenRecords = { tab = MainTab.Records }
                                )
                                MainTab.Records -> Records(modifier, colors) { push(SubPage.AdvancedRules) }
                                MainTab.Settings -> SettingsHub(
                                    modifier = modifier,
                                    settings = settings,
                                    repository = repository,
                                    colors = colors,
                                    onOpenManual = { push(SubPage.Manual) },
                                    onOpenChannel = { push(SubPage.PushChannels) },
                                    onOpenApps = { push(SubPage.AppSelection) },
                                    onOpenTemplates = { push(SubPage.TemplatePresets) },
                                    onOpenQuiet = { push(SubPage.QuietHours) },
                                    onOpenBackground = { push(SubPage.BackgroundHealth) },
                                    onOpenBackup = { push(SubPage.BackupRestore) },
                                    onOpenRecordPrivacy = { push(SubPage.RecordPrivacy) },
                                    onOpenSimManagement = { push(SubPage.SimManagement) },
                                    onOpenAdvanced = { push(SubPage.Advanced) },
                                    onOpenVersionUpdate = { push(SubPage.VersionUpdate) },
                                    onOpenAbout = { push(SubPage.About) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Onboarding(openPermission: () -> Unit, repository: AppSettingsRepository, colors: UiColors) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val apps = rememberInstalledApps()
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }
    var search by rememberSaveable { mutableStateOf("") }
    var manualPackage by rememberSaveable { mutableStateOf("") }
    var selectedSources by rememberSaveable(stateSaver = SourceSelectionListSaver) { mutableStateOf(emptyList<SourceSelection>()) }
    var selectedChannel by rememberSaveable { mutableStateOf("bark") }
    var dingtalk by rememberSaveable { mutableStateOf("") }
    var dingSecret by rememberSaveable { mutableStateOf("") }
    var feishu by rememberSaveable { mutableStateOf("") }
    var feiSecret by rememberSaveable { mutableStateOf("") }
    var bark by rememberSaveable { mutableStateOf("") }
    var testPassed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    PageScaffold("四步完成首次配置", "按顺序完成权限、来源、渠道和测试。", colors = colors) {
        StatusBadge("第 ${step + 1} 步，共 4 步", Indigo, colors)
        Spacer(Modifier.height(12.dp))
        when (step) {
            0 -> SectionCard("开启通知访问", "只读取你选择来源 App 的通知，并在本机完成筛选后转发。", Icons.Outlined.Notifications, colors) {
                PrimaryAction("打开通知访问设置", colors, onClick = openPermission)
            }
            1 -> SourceSelectionCard(apps, search, { search = it }, manualPackage, { manualPackage = it }, selectedSources, { selectedSources = it }, colors)
            2 -> SectionCard("渠道参数", "Webhook 必须使用 HTTPS。", Icons.Outlined.Notifications, colors) {
                ChannelChoice(selectedChannel, { selectedChannel = it }, colors)
                SelectedChannelFields(selectedChannel, dingtalk, { dingtalk = it }, dingSecret, { dingSecret = it }, feishu, { feishu = it }, feiSecret, { feiSecret = it }, bark, { bark = it }, colors)
            }
            else -> SectionCard("发送测试消息", "至少一个渠道测试成功后才能完成配置。", Icons.Outlined.CheckCircle, colors) {
                PrimaryAction("发送测试", colors) {
                    scope.launch {
                        val channels = selectedChannelConfig(selectedChannel, dingtalk, feishu, bark, dingSecret, feiSecret)
                        SecureStore(context).put("channels", ChannelSender.serialize(channels))
                        val results = withContext(Dispatchers.IO) { channels.map { ChannelSender.send(it, "消息接力测试", "渠道配置成功") } }
                        testPassed = results.any(DeliveryResult::success)
                        status = if (testPassed) "测试成功，可以完成配置" else results.firstOrNull()?.error ?: "请先配置渠道"
                    }
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    StatusBadge(status, if (testPassed) Success else Danger, colors)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                scope.launch {
                    if (step == 1) {
                        dao.saveRules(selectedSources.map {
                            RuleEntity(it.packageName, it.appName, defaultIncludesForTemplate(it.templateId), templateId = it.templateId)
                        })
                    }
                    if (step == 2) SecureStore(context).put("channels", ChannelSender.serialize(selectedChannelConfig(selectedChannel, dingtalk, feishu, bark, dingSecret, feiSecret)))
                    if (step < 3) step++ else repository.setOnboardingComplete(true)
                }
            },
            enabled = when (step) {
                1 -> selectedSources.isNotEmpty()
                2 -> selectedChannelConfig(selectedChannel, dingtalk, feishu, bark, dingSecret, feiSecret).isNotEmpty()
                3 -> testPassed
                else -> true
            },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) { Text(if (step == 3) "完成配置" else "继续", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Home(
    modifier: Modifier,
    settings: AppSettings,
    repository: AppSettingsRepository,
    openPermission: () -> Unit,
    colors: UiColors,
    onOpenChannel: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenQuiet: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenRecords: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val since = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val count by dao.recordCountSince(since).collectAsState(initial = 0)
    val queued by dao.queuedCount().collectAsState(initial = 0)
    val records by dao.recentRecords(5).collectAsState(initial = emptyList())
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val channels = remember { ChannelSelection.normalized(storedChannels(context)) }
    val primaryChannels = ChannelSelection.primaryEnabled(channels, settings.primaryChannelId)
    val ready = primaryChannels.isNotEmpty() && rules.isNotEmpty() && settings.selectedTemplatePreset.isNotBlank()

    PageScaffold("消息接力", "简单模式优先，按状态卡逐项修复。", modifier, colors) {
        SectionCard("运行状态", null, Icons.Outlined.PlayCircle, colors) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(if (settings.paused) "转发应用通知已关闭" else "转发应用通知已开启", color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("今日已接力 $count 条 · 待发送 $queued 条", color = colors.muted)
                }
                Switch(!settings.paused, onCheckedChange = { scope.launch { repository.setPaused(!it) } })
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard(if (ready) "首次配置进度" else "需要修复配置", if (ready) "关键配置已完成。" else "按下面三项完成基础配置。", Icons.Outlined.CheckCircle, colors) {
            SetupStep("1. 推送渠道", primaryChannels.isNotEmpty(), onOpenChannel, colors)
            SetupStep("2. 软件选择", rules.isNotEmpty(), onOpenApps, colors)
            SetupStep("3. 消息模板", settings.selectedTemplatePreset.isNotBlank(), onOpenTemplates, colors)
            OutlinedButton(onClick = openPermission, modifier = Modifier.fillMaxWidth()) { Text("检查通知访问权限") }
        }
        Spacer(Modifier.height(12.dp))
        FeatureCard("推送渠道", primaryChannels.firstOrNull()?.name ?: "待配置", Icons.Outlined.Notifications, Modifier.fillMaxWidth(), onOpenChannel, colors)
        Spacer(Modifier.height(10.dp))
        FeatureCard("软件选择", if (rules.isEmpty()) "待选择" else "已选择 ${rules.size} 个", Icons.Outlined.List, Modifier.fillMaxWidth(), onOpenApps, colors)
        Spacer(Modifier.height(10.dp))
        FeatureCard("消息模板", templateLabel(settings.selectedTemplatePreset, templates), Icons.Outlined.CheckCircle, Modifier.fillMaxWidth(), onOpenTemplates, colors)
        Spacer(Modifier.height(10.dp))
        FeatureCard("免打扰", if (settings.quietEnabled) "${settings.quietStart}-${settings.quietEnd}" else "未开启", Icons.Outlined.Schedule, Modifier.fillMaxWidth(), onOpenQuiet, colors)
        Spacer(Modifier.height(10.dp))
        FeatureCard("后台运行", "权限与保活检查", Icons.Outlined.PlayCircle, Modifier.fillMaxWidth(), onOpenBackground, colors)
        Spacer(Modifier.height(10.dp))
        FeatureCard("记录保存", retentionLabel(settings.historyRetention), Icons.Outlined.History, Modifier.fillMaxWidth(), onOpenRecords, colors)
        Spacer(Modifier.height(10.dp))
        FeatureCard("备份与恢复", "导出配置", Icons.Outlined.CheckCircle, Modifier.fillMaxWidth(), onOpenBackup, colors)
        Spacer(Modifier.height(12.dp))
        SectionCard("最近记录", null, Icons.Outlined.History, colors) {
            records.forEach { RecordLine("${it.app} · ${it.status}", PrivacyDisplay.title(it.title, settings.privacyDisplayMode), colors) }
            if (records.isEmpty()) EmptyText("暂无记录", colors)
        }
    }
}

@Composable
private fun Records(modifier: Modifier, colors: UiColors, onAdjustRules: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val records by dao.records().collectAsState(initial = emptyList())
    val settings by remember { AppSettingsRepository(context) }.settings.collectAsState(initial = AppSettings())
    val privacyMode = settings.privacyDisplayMode
    var tab by rememberSaveable { mutableStateOf("全部") }
    var selected by remember { mutableStateOf<DeliveryRecord?>(null) }
    var retryNotice by remember { mutableStateOf("") }
    val visible = when (tab) {
        "成功" -> records.filter { it.status == "成功" }
        "失败" -> records.filter { it.status != "成功" && it.status != "已过滤" }
        "已过滤" -> records.filter { it.status == "已过滤" }
        else -> records
    }
    PageScaffold("记录", "查看全部、成功、失败和已过滤消息。", modifier, colors) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "成功", "失败", "已过滤").forEach { item ->
                OutlinedButton(
                    onClick = { tab = item },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text(item, fontSize = 13.sp, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) EmptyText("暂无发送记录", colors)
        else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            items(visible, key = { it.id }) { record ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { selected = record },
                    colors = CardDefaults.outlinedCardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(record.app, color = colors.ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatusBadge(record.status, recordStatusColor(record.status), colors)
                        }
                        Text(PrivacyDisplay.title(record.title, privacyMode), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (record.status == "已过滤") {
                            ChannelResultParser.filterReason(record.channelResults)?.let { reason ->
                                Text("原因：$reason", color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(TimeFormatter.formatRecordListTime(record.createdAt), color = colors.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    selected?.let { record ->
        RecordDetailDialog(
            record = record,
            colors = colors,
            privacyMode = privacyMode,
            retryNotice = retryNotice,
            onDismiss = { selected = null; retryNotice = "" },
            onRetry = {
                scope.launch {
                    RelayEngine.enqueue(context, RelayMessage(record.packageName, record.app, record.title, record.body, record.createdAt))
                    retryNotice = "已重新发送，稍后可在记录里查看新结果"
                }
            },
            onDelete = { scope.launch { dao.deleteRecord(record.id); selected = null; retryNotice = "" } },
            onCopy = { copyToClipboard(context, "${record.title}\n${record.body}") },
            onAdjustRules = { selected = null; retryNotice = ""; onAdjustRules() }
        )
    }
}

// 发送状态徽标的颜色：含义由徽标文字承载，颜色只是辅助扫描（成功绿 / 已过滤与部分成功黄 / 其余红）。
private fun recordStatusColor(status: String): Color = when {
    status == "成功" -> Success
    status == "已过滤" || status == "部分成功" -> Warning
    else -> Danger
}

@Composable
private fun SettingsHub(
    modifier: Modifier,
    settings: AppSettings,
    repository: AppSettingsRepository,
    colors: UiColors,
    onOpenManual: () -> Unit,
    onOpenChannel: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenQuiet: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenRecordPrivacy: () -> Unit,
    onOpenSimManagement: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenVersionUpdate: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdvancedDialog by remember { mutableStateOf(false) }
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    // 渠道配置从 SecureStore 读；进入设置页时读一次即可（从渠道页返回会整体重组，值会刷新）。
    val channels = remember { ChannelSelection.normalized(storedChannels(context)) }
    val enabledApps = remember(rules) { rules.count { it.enabled } }
    val customTemplateCount = remember(templates) { TemplateCatalog.customTemplates(templates).size }
    val defaultTemplateName = remember(templates, settings.selectedTemplatePreset) {
        TemplateCatalog.allTemplates(templates).firstOrNull { it.id == settings.selectedTemplatePreset }?.name
            ?: TemplateCatalog.displayName(settings.selectedTemplatePreset)
    }
    val primaryChannelName = channels.firstOrNull { it.id == settings.primaryChannelId }?.name ?: channels.firstOrNull()?.name
    PageScaffold("设置", "「全局」对所有 App 生效，「按应用」每个 App 单独一份；常用功能放前面。", modifier, colors) {
        SettingsGroup("转发设置", colors)
        SettingNavRow("软件选择", "$enabledApps 个 App 启用转发", Icons.Outlined.Apps, onOpenApps, colors, scope = SettingScope.PER_APP)
        SettingNavRow("消息模板", "默认：$defaultTemplateName · 自定义 $customTemplateCount 个", Icons.Outlined.Description, onOpenTemplates, colors, scope = SettingScope.GLOBAL)
        SettingNavRow(
            "免打扰", "跨午夜时段与关键词例外，支持重要关键词例外。", Icons.Outlined.Schedule, onOpenQuiet, colors,
            scope = SettingScope.GLOBAL,
            trailing = {
                StatusBadge(
                    if (settings.quietEnabled) "${settings.quietStart}-${settings.quietEnd}" else "未开启",
                    if (settings.quietEnabled) Indigo else colors.muted,
                    colors
                )
            }
        )
        SettingNavRow(
            "记录与隐私",
            "保留 ${retentionLabel(settings.historyRetention)} · ${privacyLabel(settings.privacyDisplayMode)}",
            Icons.Outlined.History, onOpenRecordPrivacy, colors, scope = SettingScope.GLOBAL
        )
        SettingsGroup("推送与数据", colors)
        SettingNavRow(
            "推送渠道",
            if (channels.isEmpty()) "尚未配置，先添加 Bark、飞书或钉钉。" else "已配置 ${channels.size} 个 · 主渠道 ${primaryChannelName ?: "未选择"}",
            Icons.Outlined.Notifications, onOpenChannel, colors,
            scope = SettingScope.GLOBAL,
            trailing = if (channels.isEmpty()) ({ StatusBadge("未配置", Danger, colors) }) else null
        )
        SettingNavRow("备份与恢复", "导出或恢复基础配置。", Icons.Outlined.Save, onOpenBackup, colors, scope = SettingScope.GLOBAL)
        SettingsGroup("应用", colors)
        SectionCard("外观", "默认跟随系统，也可以固定浅色或深色。", Icons.Outlined.Palette, colors, scope = SettingScope.GLOBAL) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                    OutlinedButton(onClick = { scope.launch { repository.setThemeMode(mode) } }) { Text(label) }
                }
            }
        }
        SettingNavRow("后台运行", "检查通知访问、通知权限和后台保活。", Icons.Outlined.PlayCircle, onOpenBackground, colors)
        SettingNavRow("SIM 卡管理", "查看电话相关的 SIM 信息。", Icons.Outlined.SimCard, onOpenSimManagement, colors, scope = SettingScope.GLOBAL)
        SettingsGroup("高级设置", colors)
        SettingNavRow("高级设置区域", "多渠道同时发送、失败重试等全局行为开关。", Icons.Outlined.Tune, {
            if (settings.advancedAcknowledged) onOpenAdvanced() else showAdvancedDialog = true
        }, colors)
        SettingsGroup("帮助与关于", colors)
        SettingNavRow("使用教程", "第一次使用不知道怎么填？按步骤看这里。", Icons.Outlined.MenuBook, onOpenManual, colors)
        ChangelogSection(colors)
        SettingNavRow("版本更新", updateSubtitle(settings), Icons.Outlined.Update, onOpenVersionUpdate, colors)
        SettingNavRow("GitHub 项目", "源码、README、Release 与问题反馈", Icons.Outlined.Code, {
            openExternalLink(context, GITHUB_REPOSITORY_URL)
        }, colors)
        SettingNavRow("问题反馈", "在 GitHub Issues 反馈问题或建议", Icons.Outlined.BugReport, {
            openExternalLink(context, GITHUB_ISSUES_URL)
        }, colors)
        SettingNavRow("关于消息接力", "版本、开源许可与开发信息", Icons.Outlined.Info, onOpenAbout, colors)
    }
    if (showAdvancedDialog) {
        AlertDialog(
            onDismissRequest = { showAdvancedDialog = false },
            title = { Text("进入高级设置？") },
            text = { Text("这里包含多渠道同时发送和失败自动重试等全局开关，错误配置可能导致消息漏发或重复发送。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.setAdvancedAcknowledged(true) }
                    showAdvancedDialog = false
                    onOpenAdvanced()
                }) { Text("继续进入") }
            },
            dismissButton = { TextButton(onClick = { showAdvancedDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PushChannelScreen(modifier: Modifier, settings: AppSettings, repository: AppSettingsRepository, colors: UiColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    // 渠道列表放状态里：保存后直接更新状态驱动重组，不再靠 refreshKey 逼整个页面重算。
    var channels by remember { mutableStateOf(ChannelSelection.normalized(storedChannels(context))) }
    var type by rememberSaveable { mutableStateOf(channels.firstOrNull()?.type ?: "bark") }
    var name by rememberSaveable { mutableStateOf(channels.firstOrNull()?.name ?: "Bark 1") }
    var url by rememberSaveable { mutableStateOf(channels.firstOrNull()?.url.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(channels.firstOrNull()?.secret.orEmpty()) }
    var sound by rememberSaveable { mutableStateOf(channels.firstOrNull()?.sound.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(channels.firstOrNull()?.icon.orEmpty()) }
    var status by remember { mutableStateOf("") }
    val secureStore = remember { SecureStore(context) }
    val unreadableChannels = secureStore.hasUnreadableValue("channels")
    PageScaffold("推送渠道", "简单模式可以保存多个配置，但只选择一个主推送渠道。", modifier, colors, scope = SettingScope.GLOBAL) {
        if (unreadableChannels) {
            SectionCard("渠道配置读取失败", "系统无法解密已保存的渠道。请重新保存渠道，或导入之前导出的备份。", Icons.Outlined.Tune, colors) {
                StatusBadge("需要重新保存渠道", Danger, colors)
            }
            Spacer(Modifier.height(12.dp))
        }
        SectionCard("主推送渠道", "切换主渠道不会删除其他配置。", Icons.Outlined.Notifications, colors) {
            channels.forEach { channel ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(channel.name, color = colors.ink, fontWeight = FontWeight.Bold)
                        Text(channelName(channel.type), color = colors.muted, lineHeight = 18.sp)
                        if (channel.type == "bark" && channel.boundPackages().isNotEmpty()) {
                            Text("已绑定 ${channel.boundPackages().size} 个 App", color = Indigo, fontSize = 13.sp)
                        }
                    }
                    RadioButton(selected = settings.primaryChannelId == channel.id, onClick = { scope.launch { repository.setPrimaryChannelId(channel.id) } })
                }
            }
            if (channels.isEmpty()) EmptyText("还没有保存渠道，请先添加一个。", colors)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("添加或更新渠道", "Bark 可填写声音和图标 URL；图标 URL 需要 http/https。", Icons.Outlined.CheckCircle, colors) {
            ChannelChoice(type, { type = it }, colors)
            OutlinedTextField(name, { name = it }, label = { Text("渠道名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("${channelName(type)} 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (type != "bark") OutlinedTextField(secret, { secret = it }, label = { Text("签名密钥（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (type == "bark") {
                OutlinedTextField(sound, { sound = it }, label = { Text("Bark 消息声音（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(icon, { icon = it }, label = { Text("Bark 消息图标 URL（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            PrimaryAction("保存渠道", colors) {
                val existingSameId = channels.firstOrNull { it.type == type && it.url == url.trim() }
                val channel = ChannelConfig(
                    type,
                    url.trim(),
                    secret.trim(),
                    true,
                    id = existingSameId?.id.orEmpty(),
                    name = name.ifBlank { channelName(type) },
                    sound = sound.trim(),
                    icon = icon.trim(),
                    boundAppPackages = existingSameId?.boundAppPackages.orEmpty()
                ).normalized(channels.size)
                if (!ChannelValidation.isValid(channel)) {
                    status = "渠道地址或图标 URL 无效"
                } else {
                    val saved = ChannelSelection.normalized(channels.filterNot { it.id == channel.id } + channel)
                    SecureStore(context).put("channels", ChannelSender.serialize(saved))
                    channels = saved
                    scope.launch { repository.setPrimaryChannelId(channel.id) }
                    status = "已保存渠道"
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { ChannelSender.send(ChannelConfig(type, url, secret, name = name, sound = sound, icon = icon), "消息接力测试", "简单模式渠道测试") }
                    status = if (result.success) "测试发送成功" else result.error ?: "测试发送失败"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("发送测试消息") }
            if (status.isNotBlank()) StatusBadge(status, if ("成功" in status || "保存" in status) Success else Danger, colors)
        }
        Spacer(Modifier.height(12.dp))
        channels.filter { it.type == "bark" }.forEach { bark ->
            BarkBindingCard(
                bark = bark,
                rules = rules,
                channels = channels,
                colors = colors,
                onSave = { updated ->
                    val saved = channels.map { if (it.id == updated.id) updated else it }
                    SecureStore(context).put("channels", ChannelSender.serialize(saved))
                    channels = saved
                    status = "${updated.name} 的 App 绑定已保存"
                }
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun BarkBindingCard(
    bark: ChannelConfig,
    rules: List<RuleEntity>,
    channels: List<ChannelConfig>,
    colors: UiColors,
    onSave: (ChannelConfig) -> Unit
) {
    var selected by rememberSaveable(bark.id, bark.boundAppPackages, stateSaver = PackageSetSaver) { mutableStateOf(bark.boundPackages()) }
    SectionCard("${bark.name} 绑定 App", "可选。绑定后这些 App 的 Bark 推送只发到这个 Bark。", Icons.Outlined.Notifications, colors, scope = SettingScope.PER_APP) {
        if (rules.isEmpty()) {
            EmptyText("请先在“软件选择”里添加要转发的 App。", colors)
        } else {
            rules.forEach { rule ->
                val checked = rule.packageName in selected
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.appName, color = colors.ink, fontWeight = FontWeight.Medium)
                        Text(rule.packageName, color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked = checked, onCheckedChange = { value ->
                        selected = if (value) selected + rule.packageName else selected - rule.packageName
                    })
                }
            }
            val duplicateBindings = channels.filter { it.type == "bark" && it.id != bark.id }
                .filter { other -> selected.any { it in other.boundPackages() } }
            if (duplicateBindings.isNotEmpty()) {
                Text("提示：同一个 App 可以绑定多个 Bark，会同时发送到这些 Bark。", color = colors.muted, lineHeight = 18.sp)
            }
            PrimaryAction("保存绑定 App", colors) {
                onSave(bark.copy(boundAppPackages = selected.sorted().joinToString("\n")))
            }
        }
    }
}

@Composable
private fun SimpleAppSelectionScreen(
    modifier: Modifier,
    settings: AppSettings,
    repository: AppSettingsRepository,
    colors: UiColors,
    onOpenRules: () -> Unit,
    onOpenAppSettings: (Pair<String, String>) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val customTemplates = TemplateCatalog.customTemplates(templates)
    val hitCounts by dao.hitCounts().collectAsState(initial = emptyList())
    val hitMap = remember(hitCounts) { hitCounts.associate { it.packageName to it.count } }
    val installedApps = rememberInstalledApps()
    val ruleMap = remember(rules) { rules.associateBy { it.packageName } }
    val recommended = remember(installedApps) { recommendedApps(installedApps) }
    var search by rememberSaveable { mutableStateOf("") }
    val filteredApps by remember(installedApps) {
        derivedStateOf {
            installedApps.filter { search.isBlank() || it.first.contains(search, true) || it.second.contains(search, true) }
        }
    }
    PageScaffold("软件选择", "推荐短信、电话、微信；其他 App 也可以手动选择。", modifier, colors, scope = SettingScope.PER_APP) {
        SettingNavRow("批量管理规则", "按列表查看并启用/停用全部 App 规则。", Icons.Outlined.Tune, onOpenRules, colors, scope = SettingScope.PER_APP)
        SectionCard("推荐应用", "能识别到才会显示，避免不同手机包名不一致。", Icons.Outlined.CheckCircle, colors) {
            recommended.forEach { (name, pkg) ->
                SimpleAppRow(name, pkg, ruleMap[pkg], settings.selectedTemplatePreset, customTemplates, hitMap[pkg] ?: 0, colors, onOpenAppSettings) { rule ->
                    scope.launch { dao.saveRule(rule) }
                }
            }
            if (recommended.isEmpty()) EmptyText("暂未识别到短信、电话、微信，可在其他应用里搜索。", colors)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("其他应用", "搜索选择要转发的应用；关键词、模板等细节在 App 的规则编辑页里改。", Icons.Outlined.List, colors) {
            OutlinedTextField(search, { search = it }, label = { Text("搜索应用") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (filteredApps.isEmpty()) EmptyText("没有匹配的应用。", colors)
            else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(filteredApps, key = { it.second }) { (name, pkg) ->
                    SimpleAppRow(name, pkg, ruleMap[pkg], settings.selectedTemplatePreset, customTemplates, hitMap[pkg] ?: 0, colors, onOpenAppSettings) { rule ->
                        scope.launch { dao.saveRule(rule) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleTemplatePresetScreen(modifier: Modifier, settings: AppSettings, repository: AppSettingsRepository, colors: UiColors, onOpenCustomTemplates: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    var preview by remember { mutableStateOf("") }
    var batchStatus by remember { mutableStateOf("") }
    var confirmBatch by remember { mutableStateOf(false) }
    val choices = TemplateCatalog.allTemplates(templates)
    PageScaffold("消息模板", "全局默认模板：新建 App 规则时使用；已有规则在「软件选择」里按 App 单独修改。", modifier, colors, scope = SettingScope.GLOBAL) {
        choices.forEach { template ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable {
                    scope.launch { repository.setSelectedTemplatePreset(template.id) }
                    preview = template.template().renderTitle(previewMessage()) + "\n" + template.template().renderBody(previewMessage())
                },
                colors = CardDefaults.outlinedCardColors(containerColor = colors.card),
                border = BorderStroke(1.dp, colors.border)
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(template.name, color = colors.ink, fontWeight = FontWeight.Bold)
                        Text(template.body, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    StatusBadge(if (settings.selectedTemplatePreset == template.id) "已选择" else "可选择", if (settings.selectedTemplatePreset == template.id) Success else Warning, colors)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { confirmBatch = true }, modifier = Modifier.fillMaxWidth()) { Text("批量应用到全部已有规则") }
        if (batchStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            StatusBadge(batchStatus, Success, colors)
        }
        Spacer(Modifier.height(6.dp))
        SettingNavRow("自定义模板库", "新建、编辑和删除模板（17 个变量含电话、短信专用）。", Icons.Outlined.List, onOpenCustomTemplates, colors, scope = SettingScope.GLOBAL)
        if (preview.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SectionCard("本地预览", null, Icons.Outlined.CheckCircle, colors) { Text(preview, color = colors.ink, lineHeight = 20.sp) }
        }
    }
    if (confirmBatch) {
        val target = settings.selectedTemplatePreset
        val targetName = choices.firstOrNull { it.id == target }?.name ?: TemplateCatalog.displayName(target)
        AlertDialog(
            onDismissRequest = { confirmBatch = false },
            title = { Text("批量应用模板？") },
            text = { Text("把「$targetName」应用到全部已有规则。电话、短信专用规则会保持不变。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatch = false
                    scope.launch {
                        var changed = 0
                        val updated = dao.allRules().map { rule ->
                            val keepSpecial = rule.templateId == "phone" || rule.templateId == "sms" ||
                                TemplateCatalog.recommend(rule.appName, rule.packageName) in setOf("phone", "sms")
                            if (keepSpecial) rule
                            else {
                                changed++
                                rule.copy(templateId = target)
                            }
                        }
                        dao.saveRules(updated)
                        batchStatus = "已应用到 $changed 条规则"
                    }
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun QuietHoursScreen(modifier: Modifier, settings: AppSettings, repository: AppSettingsRepository, colors: UiColors) {
    val scope = rememberCoroutineScope()
    var start by rememberSaveable(settings.quietStart) { mutableStateOf(settings.quietStart) }
    var end by rememberSaveable(settings.quietEnd) { mutableStateOf(settings.quietEnd) }
    var urgent by rememberSaveable(settings.urgentKeywords) { mutableStateOf(settings.urgentKeywords.ifBlank { "验证码\n来电\n未接来电" }) }
    PageScaffold("免打扰", "普通消息在免打扰时段会被过滤，重要消息可以例外。", modifier, colors, scope = SettingScope.GLOBAL) {
        SectionCard("免打扰", "支持跨午夜，例如 23:00-08:00。", Icons.Outlined.Schedule, colors) {
            SettingSwitchRow("启用免打扰", settings.quietEnabled, { scope.launch { repository.setQuiet(it, start, end, urgent) } }, colors)
            OutlinedTextField(start, { start = it }, label = { Text("开始时间 HH:mm") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(end, { end = it }, label = { Text("结束时间 HH:mm") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(urgent, { urgent = it }, label = { Text("重要消息关键词，每行一个") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            PrimaryAction("保存免打扰", colors) { scope.launch { repository.setQuiet(settings.quietEnabled, start, end, urgent) } }
        }
    }
}

@Composable
private fun BackgroundHealthScreen(modifier: Modifier, openPermission: () -> Unit, colors: UiColors) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val records by dao.recentRecords(1).collectAsState(initial = emptyList())
    val listenerEnabled = remember {
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        raw.contains(context.packageName)
    }
    val notifyEnabled = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val batteryOk = remember {
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
    }
    val lastRecord = records.firstOrNull()
    val hasWarn = !listenerEnabled || !notifyEnabled || !batteryOk ||
        (lastRecord != null && (lastRecord.status == "发送失败" || lastRecord.status == "未配置渠道"))
    val overallText: String
    val overallColor: Color
    val overallHint: String
    if (!hasWarn && lastRecord != null) {
        overallText = "已检测项目正常"
        overallColor = Success
        overallHint = "以下仅列本机可实测的项目；厂商后台能力无法自动判定。"
    } else if (hasWarn) {
        overallText = "需要处理"
        overallColor = Warning
        overallHint = "按下面提示到系统设置中调整后再看。"
    } else {
        overallText = "部分项目检测受限"
        overallColor = colors.muted
        overallHint = "还没有转发记录可参考，厂商后台能力无法自动检测，此处不下结论。"
    }
    PageScaffold("后台运行", "只显示本机可实测的项目；检测不到的项目如实标注「检测受限」，不做猜测。", modifier, colors) {
        StatusBadge(overallText, overallColor, colors)
        Text(overallHint, color = colors.muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        SectionCard("检查清单", null, Icons.Outlined.PlayCircle, colors) {
            StatusRow("通知访问权限", if (listenerEnabled) "已开启" else "未开启，读取不到通知", if (listenerEnabled) Success else Warning, colors)
            StatusRow("应用通知权限", if (notifyEnabled) "已开启" else "未开启，通知可能不展示", if (notifyEnabled) Success else Warning, colors)
            StatusRow("电池优化白名单", if (batteryOk) "已加入" else "未加入，可能被后台清理", if (batteryOk) Success else Warning, colors)
            StatusRow("厂商后台（自启/锁后台）", "检测受限，请按机型手动确认", colors.muted, colors)
            StatusRow(
                "最近一次转发",
                lastRecord?.let { "${it.status} · ${TimeFormatter.formatRecordListTime(it.createdAt)}" } ?: "暂无记录",
                when {
                    lastRecord == null -> colors.muted
                    lastRecord.status == "发送失败" || lastRecord.status == "未配置渠道" -> Warning
                    else -> Success
                },
                colors
            )
            PrimaryAction("去系统设置", colors) { openPermission() }
        }
    }
}

@Composable
private fun BackupRestoreScreen(modifier: Modifier, colors: UiColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupText by rememberSaveable { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf("") }
    PageScaffold("备份与恢复", "默认备份基础配置，历史记录不默认包含。", modifier, colors, scope = SettingScope.GLOBAL) {
        SectionCard("配置文件", "备份可能包含 Bark Token / Webhook 等敏感配置，请勿公开分享。", Icons.Outlined.CheckCircle, colors) {
            OutlinedButton(onClick = { scope.launch { backupText = ConfigBackup.export(context, false); backupStatus = "已导出基础配置" } }, modifier = Modifier.fillMaxWidth()) { Text("导出基础配置") }
            OutlinedTextField(backupText, { backupText = it }, label = { Text("备份内容 / 恢复内容") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
            PrimaryAction("恢复配置", colors) {
                scope.launch {
                    runCatching { ConfigBackup.import(context, backupText) }
                        .onSuccess { backupStatus = "恢复完成" }
                        .onFailure { backupStatus = it.message ?: "恢复失败" }
                }
            }
            if (backupStatus.isNotBlank()) StatusBadge(backupStatus, if ("完成" in backupStatus || "导出" in backupStatus) Success else Danger, colors)
        }
    }
}

private fun privacyLabel(value: String): String = when (value) {
    "masked" -> "隐藏正文"
    "hidden" -> "隐藏号码"
    else -> "完整显示"
}

@Composable
private fun RecordPrivacyScreen(modifier: Modifier, settings: AppSettings, repository: AppSettingsRepository, colors: UiColors) {
    val scope = rememberCoroutineScope()
    PageScaffold("记录与隐私", "历史记录保留策略与本机显示方式；隐私模式只影响显示，不改动已存内容。", modifier, colors, scope = SettingScope.GLOBAL) {
        SectionCard("历史记录保留", "到期的记录会被自动清理；「仅状态」只保留发送状态、不保存正文。", Icons.Outlined.History, colors) {
            Text("当前：${retentionLabel(settings.historyRetention)}", color = colors.ink, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("7" to "7 天", "30" to "30 天", "90" to "90 天", "forever" to "永久", "status_only" to "仅状态").forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { scope.launch { repository.setHistoryRetention(value) } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                    ) { Text(if (settings.historyRetention == value) "✓ $label" else label, fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("隐私显示模式", "只影响本机显示，不改动已存内容。", Icons.Outlined.Settings, colors) {
            Text("当前：${privacyLabel(settings.privacyDisplayMode)}", color = colors.ink, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("full" to "完整显示", "masked" to "隐藏正文", "hidden" to "隐藏号码").forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { scope.launch { repository.setPrivacyDisplayMode(value) } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                    ) { Text(if (settings.privacyDisplayMode == value) "✓ $label" else label, fontSize = 12.sp, maxLines = 1) }
                }
            }
            Text("「隐藏正文」在记录详情里隐藏正文；「隐藏号码」把 7 位以上数字串保留后 4 位打码，列表和详情都生效。", color = colors.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AdvancedSettingsScreen(
    modifier: Modifier,
    settings: AppSettings,
    repository: AppSettingsRepository,
    colors: UiColors
) {
    val scope = rememberCoroutineScope()
    var retryCount by rememberSaveable(settings.maxRetryCount) { mutableStateOf(settings.maxRetryCount.toString()) }
    PageScaffold("高级设置", "影响所有推送渠道的全局行为开关；错误配置可能导致消息漏发或重复发送。", modifier, colors) {
        SectionCard("风险提示", "错误配置可能导致消息漏发、重复发送或模板显示异常。", Icons.Outlined.Tune, colors) {
            Text("只修改你明确理解的项目。", color = colors.muted)
        }
        SectionCard("多渠道同时发送", "开启后同一条消息会同时发送到全部启用渠道。", Icons.Outlined.Notifications, colors, scope = SettingScope.GLOBAL) {
            SettingSwitchRow("启用多渠道同时发送", settings.multiChannelSend, { scope.launch { repository.setMultiChannelSend(it) } }, colors)
        }
        SectionCard("失败自动重试", "网络错误或 429、5xx 错误会按 WorkManager 策略重试。", Icons.Outlined.History, colors, scope = SettingScope.GLOBAL) {
            SettingSwitchRow("启用失败重试", settings.retryEnabled, { scope.launch { repository.setRetryPolicy(it, retryCount.toIntOrNull() ?: settings.maxRetryCount) } }, colors)
            OutlinedTextField(retryCount, { retryCount = it.filter(Char::isDigit).take(2) }, label = { Text("最大重试次数") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            PrimaryAction("保存重试策略", colors) { scope.launch { repository.setRetryPolicy(settings.retryEnabled, retryCount.toIntOrNull() ?: 3) } }
        }
    }
}

@Composable
private fun UserManualScreen(modifier: Modifier, colors: UiColors) {
    PageScaffold("使用教程", "小白步骤版说明书。", modifier, colors) {
        listOf(
            "1. 快速开始" to "先配置推送渠道，再选择要转发的软件，最后选择模板并发送测试消息。",
            "2. 配置推送渠道" to "Bark、飞书、钉钉任选一种。配置完成后建议先发测试消息。",
            "3. 选择转发软件" to "推荐选择短信、电话、微信，也可以添加其他 App。",
            "4. 选择消息模板" to "普通用户直接选择预设模板，高级用户再修改变量。",
            "5. 设置仅锁屏时推送" to "不想使用手机时被重复提醒，可给单个应用开启仅锁屏。",
            "6. 设置电话通知类型" to "电话可选择未接来电、来电提醒和来电已接通。",
            "7. 配置免打扰" to "支持跨午夜时段和重要关键词例外。",
            "8. 后台运行与权限" to "不同手机可能需要手动开启自启、锁后台、省电白名单。",
            "9. 查看和处理转发记录" to "记录页分为全部、成功、失败和已过滤。",
            "10. 备份与恢复" to "备份可能包含渠道 Token，请妥善保存。",
            "11. 高级设置说明" to "高级设置适合了解关键词、Webhook 和规则含义的用户。",
            "12. 版本更新" to "消息接力可以通过 GitHub Releases 检查新版本。默认会自动检查，也可以在设置里的版本更新页面关闭，关闭后仍可手动检查。",
            "13. 常见问题" to "先看首页状态卡和记录详情，再按提示修复。"
        ).forEach { (title, text) -> ManualChapter(title, text, colors) }
    }
}

@Composable
private fun SimManagementScreen(modifier: Modifier, colors: UiColors) {
    val context = LocalContext.current
    val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    val hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val hasCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    val telephony = remember { context.getSystemService(TelephonyManager::class.java) }
    val supportsTelephony = telephony?.phoneType != TelephonyManager.PHONE_TYPE_NONE
    val subscriptions = remember(hasPhoneState) {
        if (!hasPhoneState) emptyList() else runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList())
    }
    PageScaffold("SIM 卡管理", "查看电话监听状态，以及 SIM 卡在 App 中使用的名称。", modifier, colors) {
        SectionCard("电话权限状态", "电话权限只影响来电、未接来电和接通提醒。", Icons.Outlined.Notifications, colors) {
            StatusRow("电话状态权限", if (hasPhoneState) "已允许" else "未允许", if (hasPhoneState) Success else Warning, colors)
            StatusRow("联系人权限", if (hasContacts) "已允许" else "未允许", if (hasContacts) Success else Warning, colors)
            StatusRow("通话记录权限", if (hasCallLog) "已允许" else "未允许", if (hasCallLog) Success else Warning, colors)
            OutlinedButton(onClick = { openAppPermissionSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("打开系统权限设置")
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("设备能力", "真机上的双卡、eSIM 和厂商电话回调可能不同。", Icons.Outlined.CheckCircle, colors) {
            StatusRow("电话能力", if (supportsTelephony) "设备支持" else "设备不支持或模拟器不可用", if (supportsTelephony) Success else Warning, colors)
            Text("如果电话权限已允许但仍看不到 SIM，通常是系统不开放订阅信息；这不影响普通 App 通知、短信和微信通知转发。", color = colors.muted, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("SIM 列表", "读取不到时会显示原因，不会影响其他转发功能。", Icons.Outlined.List, colors) {
            when {
                !hasPhoneState -> EmptyText("需要先允许电话状态权限，才能读取活动 SIM。", colors)
                subscriptions.isEmpty() -> EmptyText("当前没有读取到活动 SIM。模拟器、部分 ROM 或未插卡设备会出现这种情况。", colors)
                else -> subscriptions.forEach { info ->
                    StatusRow("卡槽 ${info.simSlotIndex + 1}", info.displayName?.toString().orEmpty().ifBlank { "未命名 SIM" }, Indigo, colors)
                    Text("运营商：${info.carrierName?.toString().orEmpty().ifBlank { "未知" }}", color = colors.muted, lineHeight = 18.sp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun VersionUpdateScreen(modifier: Modifier, settings: AppSettings, repository: AppSettingsRepository, colors: UiColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }

    fun runCheck() {
        scope.launch {
            checking = true
            result = null
            val checkResult = withContext(Dispatchers.IO) {
                UpdateRepository().check(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
            }
            repository.setLastUpdateCheckAt(System.currentTimeMillis())
            result = checkResult
            checking = false
        }
    }

    PageScaffold("版本更新", "通过 GitHub Releases 检查新版本，不会自动下载或安装。", modifier, colors) {
        SectionCard("当前版本", "v${BuildConfig.VERSION_NAME} · 内部版本 ${BuildConfig.VERSION_CODE}", Icons.Outlined.CheckCircle, colors) {
            SettingSwitchRow("自动检查更新", settings.autoCheckUpdates, { enabled ->
                scope.launch { repository.setAutoCheckUpdates(enabled) }
            }, colors)
            Text("启动应用后最多每 24 小时自动检查一次 GitHub Releases。", color = colors.muted, lineHeight = 19.sp)
            Spacer(Modifier.height(10.dp))
            PrimaryAction(if (checking) "正在检查更新" else "检查更新", colors, enabled = !checking) { runCheck() }
            if (checking) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator()
            }
        }
        Spacer(Modifier.height(12.dp))
        when (val value = result) {
            is UpdateCheckResult.UpdateAvailable -> SectionCard("发现新版本", "最新版本 ${value.release.tagName}", Icons.Outlined.Notifications, colors) {
                Text("当前版本：v${value.currentVersion}", color = colors.muted)
                value.release.publishedAt?.let { Text("发布时间：${TimeFormatter.formatRecordDetailTime(it)}", color = colors.muted) }
                val notes = value.release.body.orEmpty().take(600)
                if (notes.isNotBlank()) {
                    OutlinedButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth()) { Text("查看更新内容") }
                    if (detailsExpanded) Text(notes, color = colors.ink, lineHeight = 19.sp)
                }
                PrimaryAction("前往 GitHub 下载", colors) {
                    openExternalLink(context, value.release.htmlUrl.ifBlank { GITHUB_RELEASES_URL })
                }
            }
            is UpdateCheckResult.Latest -> SectionCard("已是最新版本", "当前 v${value.currentVersion}", Icons.Outlined.CheckCircle, colors) {
                Text("最近检查：刚刚", color = colors.muted)
            }
            is UpdateCheckResult.Error -> SectionCard("检查更新失败", "请检查网络后重试", Icons.Outlined.Tune, colors) {
                Text(value.message, color = colors.ink)
                if (value.detail.isNotBlank()) Text("高级信息：${value.detail}", color = colors.muted)
            }
            null -> SectionCard("GitHub Releases", "查看所有历史版本与 APK。", Icons.Outlined.History, colors) {
                OutlinedButton(onClick = { openExternalLink(context, GITHUB_RELEASES_URL) }, modifier = Modifier.fillMaxWidth()) { Text("查看 GitHub Releases") }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard("隐私说明", "关闭自动检查后，应用不会在启动时主动检查版本。", Icons.Outlined.CheckCircle, colors) {
            Text("更新检查通过公开 GitHub Releases 完成，不需要登录 GitHub，也不需要 GitHub Token。", color = colors.muted, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun AboutMessageRelayScreen(modifier: Modifier, colors: UiColors) {
    val context = LocalContext.current
    PageScaffold("关于消息接力", "项目版本、开源许可与开发信息。", modifier, colors) {
        SectionCard("消息接力 Message Relay", null, Icons.Outlined.CheckCircle, colors) {
            StatusRow("版本", "v${BuildConfig.VERSION_NAME}", Indigo, colors)
            StatusRow("内部版本", BuildConfig.VERSION_CODE.toString(), Indigo, colors)
            StatusRow("开源许可", "GPL-3.0-only", Success, colors)
            Text("开发方式：OpenAI Codex 辅助开发", color = colors.muted, lineHeight = 19.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { openExternalLink(context, GITHUB_REPOSITORY_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("GitHub 开源项目")
            }
        }
    }
}

@Composable
private fun TemplateLibrary(colors: UiColors) {
    val context = LocalContext.current
    val dao = remember { RelayDatabase.get(context).relayDao() }
    val scope = rememberCoroutineScope()
    val templates by dao.templatesFlow().collectAsState(initial = emptyList())
    val rules by dao.rulesFlow().collectAsState(initial = emptyList())
    var name by rememberSaveable { mutableStateOf("自定义模板") }
    var title by rememberSaveable { mutableStateOf("{{app}}：{{title}}") }
    var body by rememberSaveable { mutableStateOf("{{body}}\n{{time}}") }
    var preview by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<TemplateEntity?>(null) }
    val customTemplates = TemplateCatalog.customTemplates(templates)
    SectionCard("模板库", "模板决定转发消息在 Bark、飞书、钉钉里显示成什么样。", Icons.Outlined.List, colors) {
        TemplateVariableReference(colors)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(name, { name = it }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(title, { title = it }, label = { Text("标题样式") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("正文样式") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedButton(onClick = {
            val template = MessageTemplate(title, body)
            preview = template.renderTitle(previewMessage()) + "\n" + template.renderBody(previewMessage())
            val bad = template.unsupportedVariables()
            status = if (bad.isEmpty()) "本地预览已生成" else "预览中不支持的变量已原样保留：${bad.joinToString("、")}"
        }, modifier = Modifier.fillMaxWidth()) { Text("本地预览") }
        PrimaryAction("保存模板", colors) {
            val bad = MessageTemplate(title, body).unsupportedVariables()
            if (bad.isNotEmpty()) {
                status = "存在不支持的变量：${bad.joinToString("、")}，请修正后再保存"
            } else {
                scope.launch {
                    dao.saveTemplate(TemplateEntity("custom_${System.currentTimeMillis()}", name.ifBlank { "自定义模板" }, title, body))
                    status = "模板已保存，可在 App 规则里选择"
                }
            }
        }
        if (preview.isNotBlank()) Text(preview, color = colors.ink, lineHeight = 19.sp)
        if (status.isNotBlank()) StatusBadge(status, if ("已" in status && "不支持" !in status) Success else Warning, colors)
    }
    Spacer(Modifier.height(12.dp))
    SectionCard("已保存的自定义模板", "删除前会把正在使用它的规则自动回到通用模板。", Icons.Outlined.CheckCircle, colors) {
        if (customTemplates.isEmpty()) EmptyText("还没有自定义模板。", colors)
        customTemplates.forEach { template ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(template.name, color = colors.ink, fontWeight = FontWeight.Bold)
                    Text("已被 ${rules.count { it.templateId == template.id }} 条规则使用", color = colors.muted, fontSize = 12.sp)
                }
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

@Composable
private fun ChangelogSection(colors: UiColors) {
    val context = LocalContext.current
    SectionCard("更新日志", "按版本列出新增、优化和修复。", Icons.Outlined.History, colors) {
        ReleaseNotes.all(BuildConfig.VERSION_NAME).forEach { note ->
            Text(note.version, color = colors.ink, fontWeight = FontWeight.Bold)
            Text(note.date, color = colors.muted, fontSize = 13.sp)
            ReleaseNoteList("新增", note.added, colors)
            ReleaseNoteList("优化", note.improved, colors)
            ReleaseNoteList("修复", note.fixed, colors)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { openExternalLink(context, GITHUB_RELEASES_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("查看 GitHub Releases")
        }
    }
}

@Composable
private fun SourceSelectionCard(
    apps: List<Pair<String, String>>,
    search: String,
    onSearch: (String) -> Unit,
    manualPackage: String,
    onManualPackage: (String) -> Unit,
    selectedSources: List<SourceSelection>,
    onSelectedSources: (List<SourceSelection>) -> Unit,
    colors: UiColors
) {
    val filteredApps by remember(apps) {
        derivedStateOf {
            apps.filter { search.isBlank() || it.first.contains(search, true) || it.second.contains(search, true) }
        }
    }
    SectionCard("选择来源应用（可多选）", "只转发被选中的 App。", Icons.Outlined.List, colors) {
        StatusBadge("来源显示不全时，请允许设备应用列表 / 查询所有软件包", Warning, colors)
        Text("建议同时处理自启、锁后台、省电限制。不明白可以询问 AI。", color = colors.muted, lineHeight = 19.sp)
        OutlinedTextField(search, onSearch, label = { Text("搜索应用") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (filteredApps.isEmpty()) EmptyText("没有匹配的应用。", colors)
        else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(filteredApps, key = { it.second }) { app ->
                val checked = selectedSources.any { it.packageName == app.second }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(app.first, color = colors.ink)
                        Text(app.second, color = colors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked, { value ->
                        onSelectedSources(
                            if (value) SelectedSources.add(selectedSources, SourceSelection(app.first, app.second, TemplateCatalog.recommend(app.first, app.second)))
                            else selectedSources.filterNot { it.packageName == app.second }
                        )
                    })
                }
            }
        }
        OutlinedTextField(manualPackage, onManualPackage, label = { Text("手动输入包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(
            onClick = {
                onSelectedSources(SelectedSources.add(selectedSources, SourceSelection(manualPackage, manualPackage, TemplateCatalog.recommend(manualPackage, manualPackage))))
                onManualPackage("")
            },
            enabled = manualPackage.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加包名") }
    }
}

@Composable
private fun RecordDetailDialog(record: DeliveryRecord, colors: UiColors, privacyMode: String, retryNotice: String, onDismiss: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onAdjustRules: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(record.app, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatusBadge(record.status, recordStatusColor(record.status), colors)
            }
        },
        text = {
            Column {
                Text("标题：${PrivacyDisplay.title(record.title, privacyMode)}", color = colors.ink)
                Text("正文：${PrivacyDisplay.body(record.body, privacyMode)}", color = colors.muted, lineHeight = 18.sp)
                Text("时间：${TimeFormatter.formatRecordDetailTime(record.createdAt)}", color = colors.muted)
                if (record.status == "已过滤") {
                    ChannelResultParser.filterReason(record.channelResults)?.let { reason ->
                        Spacer(Modifier.height(6.dp))
                        Text("过滤原因：$reason", color = Warning, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
                    }
                } else {
                    val detail = ChannelResultParser.detailText(record.channelResults)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(detail, color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                if (retryNotice.isNotBlank()) Text(retryNotice, color = Success)
            }
        },
        confirmButton = {
            Row {
                when (record.status) {
                    "成功" -> TextButton(onClick = onRetry) { Text("重新发送") }
                    "已过滤" -> {
                        TextButton(onClick = onRetry) { Text("仍然发送") }
                        TextButton(onClick = onAdjustRules) { Text("调整规则") }
                    }
                    else -> TextButton(onClick = onRetry) { Text("立即重试") }
                }
                TextButton(onClick = onCopy) { Text(if ("验证码" in record.title || "验证码" in record.body) "复制验证码" else "复制内容") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ChannelChoice(selected: String, onSelect: (String) -> Unit, colors: UiColors) {
    listOf("feishu" to "飞书", "dingtalk" to "钉钉", "bark" to "Bark").forEach { (type, label) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = colors.ink, fontWeight = FontWeight.Bold)
            RadioButton(selected == type, onClick = { onSelect(type) })
        }
    }
}

@Composable
private fun SelectedChannelFields(
    selected: String,
    dingtalk: String,
    onDing: (String) -> Unit,
    dingSecret: String,
    onDingSecret: (String) -> Unit,
    feishu: String,
    onFei: (String) -> Unit,
    feiSecret: String,
    onFeiSecret: (String) -> Unit,
    bark: String,
    onBark: (String) -> Unit,
    colors: UiColors
) {
    when (selected) {
        "dingtalk" -> {
            OutlinedTextField(dingtalk, onDing, label = { Text("钉钉 Webhook") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(dingSecret, onDingSecret, label = { Text("加签密钥（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        "feishu" -> {
            OutlinedTextField(feishu, onFei, label = { Text("飞书 Webhook") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(feiSecret, onFeiSecret, label = { Text("签名密钥（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("不知道怎么配置？可以先参考教程。", color = colors.muted)
            LinkRow("飞书推送配置参考（推荐）", FEISHU_BOT_GUIDE_URL, colors)
        }
        else -> {
            OutlinedTextField(bark, onBark, label = { Text("Bark 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LinkRow("Bark 推送参考", BARK_HOME_URL, colors)
        }
    }
}

@Composable
private fun ManualChapter(title: String, text: String, colors: UiColors) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = if (expanded) "教程章节已展开：$title" else "展开教程章节：$title"
        }.clickable { expanded = !expanded },
        colors = CardDefaults.outlinedCardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = colors.ink, fontWeight = FontWeight.Bold)
            if (expanded) Text(text, color = colors.muted, lineHeight = 19.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun PageScaffold(title: String, subtitle: String? = null, modifier: Modifier = Modifier, colors: UiColors, scope: SettingScope? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black, color = colors.ink)
            if (scope != null) {
                Spacer(Modifier.width(8.dp))
                ScopeBadge(scope, colors)
            }
        }
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.muted, lineHeight = 19.sp)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
internal fun SectionCard(title: String, subtitle: String? = null, icon: ImageVector = Icons.Outlined.CheckCircle, colors: UiColors, scope: SettingScope? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = colors.card, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Indigo)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.muted, lineHeight = 19.sp)
                }
                if (scope != null) {
                    Spacer(Modifier.width(8.dp))
                    ScopeBadge(scope, colors)
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun FeatureCard(title: String, status: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit, colors: UiColors) {
    Surface(shape = RoundedCornerShape(12.dp), color = colors.card, shadowElevation = 1.dp, modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = Indigo)
            Spacer(Modifier.height(8.dp))
            Text(title, color = colors.ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = colors.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SetupStep(label: String, done: Boolean, onClick: () -> Unit, colors: UiColors) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.ink, fontWeight = FontWeight.Medium)
        StatusBadge(if (done) "已完成" else "待配置", if (done) Success else Warning, colors)
    }
}

@Composable
private fun SettingNavRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, colors: UiColors, scope: SettingScope? = null, trailing: (@Composable () -> Unit)? = null) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = Indigo)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (scope != null) {
                        Spacer(Modifier.width(8.dp))
                        ScopeBadge(scope, colors)
                    }
                }
                Text(subtitle, color = colors.muted, lineHeight = 18.sp)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            Text("›", color = Indigo, fontSize = 24.sp)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, colors: UiColors) {
    Text(title, color = colors.ink, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
internal fun SettingSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, colors: UiColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = colors.ink, fontWeight = FontWeight.Medium)
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color, colors: UiColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.muted)
        StatusBadge(value, color, colors)
    }
}

@Composable
internal fun StatusBadge(text: String, color: Color, colors: UiColors) {
    Surface(shape = RoundedCornerShape(100.dp), color = color.copy(alpha = 0.12f)) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PrimaryAction(text: String, colors: UiColors, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun EmptyText(text: String, colors: UiColors) {
    Text(text, color = colors.muted, lineHeight = 19.sp)
}

@Composable
private fun RecordLine(title: String, subtitle: String, colors: UiColors) {
    Text(title, color = colors.ink, fontWeight = FontWeight.Medium)
    Text(subtitle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LinkRow(label: String, url: String, colors: UiColors) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { openExternalLink(context, url) }.padding(vertical = 7.dp)
            .semantics { contentDescription = "打开链接：$label" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Indigo, fontWeight = FontWeight.Bold)
        Text("打开", color = colors.muted)
    }
}

@Composable
private fun ReleaseNoteList(title: String, items: List<String>, colors: UiColors) {
    Text(title, color = colors.ink, fontWeight = FontWeight.Bold)
    items.forEach { Text("· $it", color = colors.muted, lineHeight = 18.sp) }
}

// 配置参考链接：飞书官方自定义机器人文档、Bark 官网。
private const val FEISHU_BOT_GUIDE_URL = "https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot"
private const val BARK_HOME_URL = "https://bark.day.app/"

private fun channelsFromInputs(dingtalk: String, feishu: String, bark: String, dingSecret: String = "", feiSecret: String = "") =
    listOf(ChannelConfig("dingtalk", dingtalk.trim(), dingSecret), ChannelConfig("feishu", feishu.trim(), feiSecret), ChannelConfig("bark", bark.trim()))
        .filter { it.url.isNotBlank() }

private fun selectedChannelConfig(type: String, dingtalk: String, feishu: String, bark: String, dingSecret: String = "", feiSecret: String = "") =
    ChannelSelection.singleEnabled(channelsFromInputs(dingtalk, feishu, bark, dingSecret, feiSecret).filter { it.type == type })

private fun storedChannels(context: Context): List<ChannelConfig> =
    SecureStore(context).get("channels")?.let { runCatching { ChannelSender.parse(it) }.getOrDefault(emptyList()) }.orEmpty()

private suspend fun reconcileUpgradeState(context: Context, repository: AppSettingsRepository, dao: RelayDao) {
    val settings = repository.current()
    val channels = storedChannels(context)
    val rules = dao.allRules()
    if (!settings.onboardingComplete && (channels.isNotEmpty() || rules.isNotEmpty())) {
        repository.setOnboardingComplete(true)
    }
    val enabledChannels = ChannelSelection.enabled(channels)
    if (enabledChannels.isNotEmpty() && settings.primaryChannelId !in enabledChannels.map(ChannelConfig::id)) {
        repository.setPrimaryChannelId(enabledChannels.first().id)
    }
}

private fun openAppPermissionSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun recommendedApps(apps: List<Pair<String, String>>): List<Pair<String, String>> {
    val byPackage = apps.associateBy { it.second }
    fun firstKnown(label: String, packages: List<String>): Pair<String, String>? =
        packages.firstNotNullOfOrNull { pkg -> byPackage[pkg]?.let { label to it.second } }
    return listOfNotNull(
        firstKnown(
            "短信",
            listOf(
                "com.google.android.apps.messaging",
                "com.android.mms",
                "com.android.messaging",
                "com.samsung.android.messaging",
                "com.miui.mms"
            )
        ),
        firstKnown(
            "电话",
            listOf(
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.android.dialer",
                "com.android.phone",
                "com.android.server.telecom"
            )
        ),
        firstKnown(
            "微信",
            listOf(
                "com.tencent.mm",
                "com.tencent.wework"
            )
        )
    ).distinctBy { it.second }
}

internal fun templateLabel(id: String, all: List<TemplateEntity>): String =
    TemplateCatalog.customTemplates(all).firstOrNull { it.id == id }?.name ?: TemplateCatalog.displayName(id)

private fun retentionLabel(value: String): String = when (value) {
    "7" -> "7 天"
    "90" -> "90 天"
    "forever" -> "永久"
    "status_only" -> "仅状态"
    else -> "30 天"
}

internal fun defaultIncludesForTemplate(id: String): String = when (id) {
    "sms" -> "验证码"
    "phone" -> "未接来电\n来电提醒"
    else -> ""
}

private fun previewMessage() = RelayMessage("com.tencent.mm", "微信", "张三", "明天 10 点开会", System.currentTimeMillis())

private fun channelName(type: String): String = when (type) {
    "dingtalk" -> "钉钉"
    "feishu" -> "飞书"
    "bark" -> "Bark"
    else -> type
}

internal fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message-relay-record", value))
}

private fun updateSubtitle(settings: AppSettings): String =
    if (settings.lastUpdateCheckAt > 0) "当前 v${BuildConfig.VERSION_NAME} · 最近检查 ${TimeFormatter.formatRecordListTime(settings.lastUpdateCheckAt)}"
    else "检查新版本"

private fun openExternalLink(context: Context, url: String): Boolean =
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
