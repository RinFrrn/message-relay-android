package io.github.messagerelay

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 已安装应用列表的进程级缓存。
// getInstalledApplications + 逐个 loadLabel 是一次遍历里上百次 Binder IPC，之前在
// remember {} 里同步跑在主线程，进「软件选择 / 规则 / 首次配置」时必卡一帧。
// 这里放 IO 线程查询并缓存到进程内，页面重进走缓存，秒开。
object InstalledAppsCache {
    @Volatile
    private var cached: List<Pair<String, String>>? = null

    // 组合期可用的瞬时值：已有缓存立即返回，否则空列表等 produceState 补上。
    fun snapshot(): List<Pair<String, String>> = cached.orEmpty()

    suspend fun load(context: Context): List<Pair<String, String>> =
        cached ?: withContext(Dispatchers.IO) { query(context.applicationContext) }

    private fun query(context: Context): List<Pair<String, String>> {
        cached?.let { return it }
        val result = runCatching {
            val packageManager = context.packageManager
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
                .map { appInfo -> appInfo.loadLabel(packageManager).toString() to appInfo.packageName }
                .filter { it.second.isNotBlank() }
                .distinctBy { it.second }
                .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase() }.thenBy { it.second })
        }.getOrDefault(emptyList())
        cached = result
        return result
    }
}

@Composable
fun rememberInstalledApps(): List<Pair<String, String>> {
    val context = LocalContext.current
    val apps by produceState(initialValue = InstalledAppsCache.snapshot()) {
        value = InstalledAppsCache.load(context)
    }
    return apps
}
