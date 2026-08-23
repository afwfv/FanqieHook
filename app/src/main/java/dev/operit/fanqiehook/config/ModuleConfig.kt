package dev.operit.fanqiehook.config

import android.content.SharedPreferences

/**
 * Unified feature-switch registry, adapted from the MK module's ConfigManager pattern.
 *
 * Preference resolution order (mirrors MK):
 *   1. [prefs] — libxposed `getRemotePreferences("fanqiehook")`: values saved by the module's
 *      own app UI propagate to the injected host process in real time.
 *   2. Host-process SP `fanqiehook` — fallback when the module app never saved anything
 *      (framework lacks service support or user only flipped switches via the web console).
 *   3. [EMPTY] — read-only zeros; every feature gate defaults to its declared default.
 *
 * All new feature gates default to OFF so behaviour stays identical to v0.2.0 unless the
 * user explicitly enables them. The ad-blocking hooks (AdHooks) remain unconditional.
 */
object ModuleConfig {

    const val SP_NAME = "fanqiehook"

    /** Read-only no-op preferences used when neither remote nor host SP is reachable. */
    private val EMPTY: SharedPreferences = object : SharedPreferences {
        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getString(key: String, defValue: String?): String? = defValue
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun contains(key: String): Boolean = false
        override fun edit(): SharedPreferences.Editor =
            throw UnsupportedOperationException("EMPTY preferences are read-only")
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit
    }

    @Volatile
    private var prefsSource: SharedPreferences? = null

    /** Writable view used by the web console (host-process SP). */
    @Volatile
    var writablePrefs: SharedPreferences? = null
        private set

    /**
     * Bind the preference sources. Called once from [dev.operit.fanqiehook.FanqieModule]
     * when the host application is ready.
     *
     * Priority: host-process SP first (it is the write target of the web console, and
     * this module ships without a settings UI, so the console IS the primary entry);
     * remote prefs (module app, if ever added) only when the host SP is empty.
     *
     * @param remote libxposed remote preferences (module app's saved config), may be null.
     * @param hostWritable host-process SP, writable; used as the main source and as the
     *   write target for the web console.
     */
    fun init(remote: SharedPreferences?, hostWritable: SharedPreferences?) {
        val hostHasData = hostWritable != null && hostWritable.all.isNotEmpty()
        prefsSource = when {
            hostHasData -> hostWritable
            remote != null && remote.all.isNotEmpty() -> remote
            else -> hostWritable ?: remote ?: EMPTY
        }
        writablePrefs = hostWritable
    }

    private fun prefs(): SharedPreferences = prefsSource ?: EMPTY

    // ── Top-level feature gates ───────────────────────────────────────────────

    /** 本地 VIP 伪装 (UserModel / VipInfo / PrivilegeManager 全链路伪造). */
    fun localVip(): Boolean = prefs().getBoolean(Keys.LOCAL_VIP, false)

    /** 激励视频秒领：跳过观看直接触发发奖回调. */
    fun instantReward(): Boolean = prefs().getBoolean(Keys.INSTANT_REWARD, false)

    /** 阅读时长上报倍率 (1 = 关闭). */
    fun readingTimeMultiplier(): Int = prefs().getInt(Keys.READING_TIME_MULTIPLIER, 1)

    /** 屏蔽应用内更新检查. */
    fun blockUpdates(): Boolean = prefs().getBoolean(Keys.BLOCK_UPDATES, false)

    /** 屏蔽 Reparo 热更新. */
    fun blockHotUpdate(): Boolean = prefs().getBoolean(Keys.BLOCK_HOT_UPDATE, false)

    /** 屏蔽 Mira 插件加载. */
    fun blockPluginLoad(): Boolean = prefs().getBoolean(Keys.BLOCK_PLUGIN_LOAD, false)

    /** 屏蔽 Npth/CrashReport 崩溃上报. */
    fun blockCrashReport(): Boolean = prefs().getBoolean(Keys.BLOCK_CRASH_REPORT, false)

    /** 解锁下架/违禁书籍 (isOverallOffShelf / isUnsafeBook → false). */
    fun unlockBooks(): Boolean = prefs().getBoolean(Keys.UNLOCK_BOOKS, false)

    /** 界面净化总开关. */
    fun uiClean(): Boolean = prefs().getBoolean(Keys.UI_CLEAN, false)

    /** Web 控制台：随宿主启动（默认开——本模块无设置界面，控制台是唯一配置入口）. */
    fun startWebServer(): Boolean = prefs().getBoolean(Keys.START_WEB_SERVER, true)

    /** Web 控制台端口. */
    fun webPort(): Int = prefs().getInt(Keys.WEB_PORT, 18765)

    // ── UI clean sub-switches (defaults mirror MK's sensible-on set) ──────────

    fun uiCleanSearchWord(): Boolean = prefs().getBoolean(Keys.UC_SEARCH_WORD, true)
    fun uiCleanScreenAd(): Boolean = prefs().getBoolean(Keys.UC_SCREEN_AD, true)
    fun uiCleanVipCard(): Boolean = prefs().getBoolean(Keys.UC_VIP_CARD, false)
    fun uiCleanFunctionBadge(): Boolean = prefs().getBoolean(Keys.UC_FUNCTION_BADGE, true)
    fun uiCleanMiniGame(): Boolean = prefs().getBoolean(Keys.UC_MINI_GAME, true)
    fun uiCleanTabBadge(): Boolean = prefs().getBoolean(Keys.UC_TAB_BADGE, true)
    fun uiCleanMineFeed(): Boolean = prefs().getBoolean(Keys.UC_MINE_FEED, true)
    fun uiCleanHomeEarnTab(): Boolean = prefs().getBoolean(Keys.UC_HOME_EARN_TAB, true)
    fun uiCleanHomeSeriesTab(): Boolean = prefs().getBoolean(Keys.UC_HOME_SERIES_TAB, true)
    fun uiCleanHidePublish(): Boolean = prefs().getBoolean(Keys.UC_HIDE_PUBLISH, true)
    fun uiCleanHideMineAsset(): Boolean = prefs().getBoolean(Keys.UC_HIDE_MINE_ASSET, true)
    fun uiCleanHideMineHistory(): Boolean = prefs().getBoolean(Keys.UC_HIDE_MINE_HISTORY, false)
    fun uiCleanHideMineEarnPendant(): Boolean = prefs().getBoolean(Keys.UC_HIDE_MINE_EARN_PENDANT, false)
    fun uiCleanSearchResult(): Boolean = prefs().getBoolean(Keys.UC_SEARCH_RESULT, true)
    fun uiCleanSearchAi(): Boolean = prefs().getBoolean(Keys.UC_SEARCH_AI, true)

    /** Snapshot of every switch, for the web console status page. */
    fun snapshot(): Map<String, Any> = mapOf(
        Keys.LOCAL_VIP to localVip(),
        Keys.INSTANT_REWARD to instantReward(),
        Keys.READING_TIME_MULTIPLIER to readingTimeMultiplier(),
        Keys.BLOCK_UPDATES to blockUpdates(),
        Keys.BLOCK_HOT_UPDATE to blockHotUpdate(),
        Keys.BLOCK_PLUGIN_LOAD to blockPluginLoad(),
        Keys.BLOCK_CRASH_REPORT to blockCrashReport(),
        Keys.UNLOCK_BOOKS to unlockBooks(),
        Keys.UI_CLEAN to uiClean(),
        Keys.START_WEB_SERVER to startWebServer(),
        Keys.WEB_PORT to webPort(),
        Keys.UC_SEARCH_WORD to uiCleanSearchWord(),
        Keys.UC_SCREEN_AD to uiCleanScreenAd(),
        Keys.UC_VIP_CARD to uiCleanVipCard(),
        Keys.UC_FUNCTION_BADGE to uiCleanFunctionBadge(),
        Keys.UC_MINI_GAME to uiCleanMiniGame(),
        Keys.UC_TAB_BADGE to uiCleanTabBadge(),
        Keys.UC_MINE_FEED to uiCleanMineFeed(),
        Keys.UC_HOME_EARN_TAB to uiCleanHomeEarnTab(),
        Keys.UC_HOME_SERIES_TAB to uiCleanHomeSeriesTab(),
        Keys.UC_HIDE_PUBLISH to uiCleanHidePublish(),
        Keys.UC_HIDE_MINE_ASSET to uiCleanHideMineAsset(),
        Keys.UC_HIDE_MINE_HISTORY to uiCleanHideMineHistory(),
        Keys.UC_HIDE_MINE_EARN_PENDANT to uiCleanHideMineEarnPendant(),
        Keys.UC_SEARCH_RESULT to uiCleanSearchResult(),
        Keys.UC_SEARCH_AI to uiCleanSearchAi(),
    )

    /** Apply a single boolean/int override from the web console (host SP write). */
    fun applyOverride(key: String, value: Any): Boolean {
        val editor = writablePrefs?.edit() ?: return false
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
            else -> return false
        }
        editor.apply()
        // Re-bind so subsequent reads see the new value even when remote prefs was the source.
        if (prefsSource !== writablePrefs) {
            prefsSource = writablePrefs
        }
        return true
    }

    /** All valid override keys (web console validation). */
    val allKeys: Set<String> = Keys.ALL

    private object Keys {
        const val LOCAL_VIP = "local_vip"
        const val INSTANT_REWARD = "instant_reward"
        const val READING_TIME_MULTIPLIER = "reading_time_multiplier"
        const val BLOCK_UPDATES = "block_updates"
        const val BLOCK_HOT_UPDATE = "block_hot_update"
        const val BLOCK_PLUGIN_LOAD = "block_plugin_load"
        const val BLOCK_CRASH_REPORT = "block_crash_report"
        const val UNLOCK_BOOKS = "unlock_books"
        const val UI_CLEAN = "ui_clean"
        const val START_WEB_SERVER = "start_web_server"
        const val WEB_PORT = "web_port"
        const val UC_SEARCH_WORD = "uc_search_word"
        const val UC_SCREEN_AD = "uc_screen_ad"
        const val UC_VIP_CARD = "uc_vip_card"
        const val UC_FUNCTION_BADGE = "uc_function_badge"
        const val UC_MINI_GAME = "uc_mini_game"
        const val UC_TAB_BADGE = "uc_tab_badge"
        const val UC_MINE_FEED = "uc_mine_feed"
        const val UC_HOME_EARN_TAB = "uc_home_earn_tab"
        const val UC_HOME_SERIES_TAB = "uc_home_series_tab"
        const val UC_HIDE_PUBLISH = "uc_hide_publish"
        const val UC_HIDE_MINE_ASSET = "uc_hide_mine_asset"
        const val UC_HIDE_MINE_HISTORY = "uc_hide_mine_history"
        const val UC_HIDE_MINE_EARN_PENDANT = "uc_hide_mine_earn_pendant"
        const val UC_SEARCH_RESULT = "uc_search_result"
        const val UC_SEARCH_AI = "uc_search_ai"

        val ALL: Set<String> = setOf(
            LOCAL_VIP, INSTANT_REWARD, READING_TIME_MULTIPLIER, BLOCK_UPDATES,
            BLOCK_HOT_UPDATE, BLOCK_PLUGIN_LOAD, BLOCK_CRASH_REPORT, UNLOCK_BOOKS,
            UI_CLEAN, START_WEB_SERVER, WEB_PORT,
            UC_SEARCH_WORD, UC_SCREEN_AD, UC_VIP_CARD, UC_FUNCTION_BADGE, UC_MINI_GAME,
            UC_TAB_BADGE, UC_MINE_FEED, UC_HOME_EARN_TAB, UC_HOME_SERIES_TAB,
            UC_HIDE_PUBLISH, UC_HIDE_MINE_ASSET, UC_HIDE_MINE_HISTORY,
            UC_HIDE_MINE_EARN_PENDANT, UC_SEARCH_RESULT, UC_SEARCH_AI,
        )
    }
}
