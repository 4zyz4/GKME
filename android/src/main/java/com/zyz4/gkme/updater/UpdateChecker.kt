package com.zyz4.gkme.updater

import android.content.Context
import androidx.core.content.edit
import com.zyz4.gkme.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一次检查更新的结果。 */
sealed interface UpdateCheckResult {

    /**
     * 有可用的新版本。
     *
     * [version] 是规范化后的纯数字版本号（例如 `4.11.0`），可以直接展示或与当前版本比较。
     */
    data class Available(val version: String) : UpdateCheckResult

    /** 已是最新版本（远端版本不高于当前版本）。 */
    data object UpToDate : UpdateCheckResult

    /** 网络请求或解析失败；不会记录本次检查时间，下次启动会重新尝试。 */
    data class Failed(val message: String? = null) : UpdateCheckResult
}

/**
 * 通过 GitHub Releases API 检测新版本。
 *
 * 只读取 `/releases/latest`，GitHub 会自动忽略 nightly 这类 pre-release，因此测试版不会
 * 被推送给用户。检查节奏固定为每 [CHECK_INTERVAL_MS]（3 天）一次，节流状态存放在独立的
 * SharedPreferences 中，不必为了一个后台检查去读取设置 DataStore。
 */
object UpdateChecker {

    /** 官网地址；更新提示的「前往官网下载」和关于页的「官网」徽章共用。 */
    const val OFFICIAL_WEBSITE_URL = "https://gkme.databk.top"

    /** 官方仓库的最新正式版接口；未认证请求的配额为 60 次/小时，3 天一次完全够用。 */
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/4zyz4/GKME/releases/latest"

    private const val CHECK_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_IGNORED_VERSION = "ignored_version"

    /** 距离上次成功检查是否已超过 3 天。 */
    fun isCheckDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK_AT, 0L)
        val elapsed = System.currentTimeMillis() - last
        // 首次启动（last == 0）与系统时钟回拨（elapsed < 0）都重新检查。
        return last == 0L || elapsed >= CHECK_INTERVAL_MS || elapsed < 0
    }

    /** 用户点「关闭」时忽略的版本；同一版本不再自动弹窗，但手动检查仍会提示。 */
    fun ignoredVersion(context: Context): String? =
        prefs(context).getString(KEY_IGNORED_VERSION, null)

    fun ignoreVersion(context: Context, version: String) {
        prefs(context).edit { putString(KEY_IGNORED_VERSION, version) }
    }

    /**
     * 请求 GitHub API 并比较版本。内部切到 IO 线程，可直接在协程里调用。
     *
     * @param currentVersion 当前安装版本，默认取 [BuildConfig.VERSION_NAME]。
     */
    suspend fun check(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatestRelease() }
            .getOrElse { return@withContext UpdateCheckResult.Failed(it.message) }

        val tag = release.optString("tag_name").trim()
        val latest = versionNumbers(tag)
            ?: return@withContext UpdateCheckResult.Failed("invalid tag_name: $tag")
        val current = versionNumbers(currentVersion)
            ?: return@withContext UpdateCheckResult.Failed("invalid current version: $currentVersion")

        // 已经成功解析出版本号，无论是否有更新都记录检查时间。
        markChecked(context)

        if (compareVersion(latest, current) <= 0) return@withContext UpdateCheckResult.UpToDate

        UpdateCheckResult.Available(version = latest.joinToString("."))
    }

    private fun fetchLatestRelease(): JSONObject {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API 要求请求带 User-Agent。
            setRequestProperty("User-Agent", "GKME-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun markChecked(context: Context) {
        prefs(context).edit { putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 从 GitHub tag（`v4.11.0`）或 APK versionName（`4.11.0-nightly.20261128`）中取出纯数字
 * 版本段，返回 `[4, 11, 0]`；无法解析时返回 null。
 */
internal fun versionNumbers(raw: String): List<Int>? {
    val core = raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .takeWhile { it.isDigit() || it == '.' }
        .trimEnd('.')
    if (core.isEmpty()) return null
    return core.split('.').map { part -> part.toIntOrNull() ?: return null }
}

/** 逐段比较版本号，缺失的段按 0 处理；返回正数表示 [a] 更新。 */
internal fun compareVersion(a: List<Int>, b: List<Int>): Int {
    val size = maxOf(a.size, b.size)
    for (index in 0 until size) {
        val diff = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
