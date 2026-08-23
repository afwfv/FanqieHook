package dev.operit.fanqiehook.config

import android.content.SharedPreferences

/**
 * 功能开关注册表 —— 宿主进程配置源。
 *
 * 配置流向（单一写入方）：
 *   模块 App UI → XposedService.getRemotePreferences → 框架共享存储
 *     → 宿主 XposedInterface.getRemotePreferences 实时读取
 *
 * 读取按调用实时进行（Hook 回调内判断），开关改动免重启即时生效。
 * 所有功能默认关闭，保证安装后行为与用户预期一致。
 */
object ModuleConfig {

    const val SP_NAME = "fanqiehook"

    /** 只读空实现：配置源不可用时兜底。 */
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
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
    }

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    /**
     * 绑定配置源。宿主 Application 就绪后调用一次。
     *
     * @param remote 框架共享存储（模块 App 写入），唯一配置源
     */
    fun init(remote: SharedPreferences?) {
        remotePrefs = remote
    }

    private fun prefs(): SharedPreferences = remotePrefs ?: EMPTY

    // ── 读取接口 ──────────────────────────────────────────────────────────────

    /** 本地 VIP 伪装（UserModel / VipInfo / PrivilegeManager 全链路伪造）。 */
    fun localVip(): Boolean = prefs().getBoolean(Key.LOCAL_VIP, false)

    /** 激励视频秒过：跳过观看直接触发发奖回调。 */
    fun instantReward(): Boolean = prefs().getBoolean(Key.INSTANT_REWARD, false)

    /** 阅读时长上报倍率（1 = 关闭）。 */
    fun readingTimeMultiplier(): Int = prefs().getInt(Key.READING_TIME_MULTIPLIER, 1)

    /** 拦截应用内更新检查。 */
    fun blockUpdates(): Boolean = prefs().getBoolean(Key.BLOCK_UPDATES, false)

    /** 拦截 Reparo 热更新。 */
    fun blockHotUpdate(): Boolean = prefs().getBoolean(Key.BLOCK_HOT_UPDATE, false)

    /** 拦截 Mira 插件加载。 */
    fun blockPluginLoad(): Boolean = prefs().getBoolean(Key.BLOCK_PLUGIN_LOAD, false)

    /** 拦截 Npth/CrashReport 崩溃上报。 */
    fun blockCrashReport(): Boolean = prefs().getBoolean(Key.BLOCK_CRASH_REPORT, false)

    /** 解锁下架/违禁书籍（isOverallOffShelf / isUnsafeBook → false）。 */
    fun unlockBooks(): Boolean = prefs().getBoolean(Key.UNLOCK_BOOKS, false)

    /** 界面净化总开关。 */
    fun uiClean(): Boolean = prefs().getBoolean(Key.UI_CLEAN, false)

    // ── 界面净化子开关 ────────────────────────────────────────────────────────

    fun uiCleanSearchWord(): Boolean = prefs().getBoolean(Key.UC_SEARCH_WORD, true)
    fun uiCleanScreenAd(): Boolean = prefs().getBoolean(Key.UC_SCREEN_AD, true)
    fun uiCleanVipCard(): Boolean = prefs().getBoolean(Key.UC_VIP_CARD, false)
    fun uiCleanFunctionBadge(): Boolean = prefs().getBoolean(Key.UC_FUNCTION_BADGE, true)
    fun uiCleanMiniGame(): Boolean = prefs().getBoolean(Key.UC_MINI_GAME, true)
    fun uiCleanTabBadge(): Boolean = prefs().getBoolean(Key.UC_TAB_BADGE, true)
    fun uiCleanMineFeed(): Boolean = prefs().getBoolean(Key.UC_MINE_FEED, true)
    fun uiCleanHomeEarnTab(): Boolean = prefs().getBoolean(Key.UC_HOME_EARN_TAB, true)
    fun uiCleanHomeSeriesTab(): Boolean = prefs().getBoolean(Key.UC_HOME_SERIES_TAB, true)
    fun uiCleanHidePublish(): Boolean = prefs().getBoolean(Key.UC_HIDE_PUBLISH, true)
    fun uiCleanHideMineAsset(): Boolean = prefs().getBoolean(Key.UC_HIDE_MINE_ASSET, true)
    fun uiCleanHideMineHistory(): Boolean = prefs().getBoolean(Key.UC_HIDE_MINE_HISTORY, false)
    fun uiCleanHideMineEarnPendant(): Boolean =
        prefs().getBoolean(Key.UC_HIDE_MINE_EARN_PENDANT, false)
    fun uiCleanSearchResult(): Boolean = prefs().getBoolean(Key.UC_SEARCH_RESULT, true)
    fun uiCleanSearchAi(): Boolean = prefs().getBoolean(Key.UC_SEARCH_AI, true)

    // ── 书源 API ──────────────────────────────────────────────────────────────

    /** 启用进程内书源服务（Legado 兼容 API，供阅读类 App 配置书源）。 */
    fun bookSource(): Boolean = prefs().getBoolean(Key.BOOK_SOURCE, false)

    /** 书源服务端口。 */
    fun bookSourcePort(): Int = prefs().getInt(Key.BOOK_SOURCE_PORT, 18765)

    /** SP 键名常量。UI 与 Hook 共用，键名一处定义。 */
    object Key {
        const val LOCAL_VIP = "local_vip"
        const val INSTANT_REWARD = "instant_reward"
        const val READING_TIME_MULTIPLIER = "reading_time_multiplier"
        const val BLOCK_UPDATES = "block_updates"
        const val BLOCK_HOT_UPDATE = "block_hot_update"
        const val BLOCK_PLUGIN_LOAD = "block_plugin_load"
        const val BLOCK_CRASH_REPORT = "block_crash_report"
        const val UNLOCK_BOOKS = "unlock_books"
        const val UI_CLEAN = "ui_clean"
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
        const val BOOK_SOURCE = "book_source"
        const val BOOK_SOURCE_PORT = "book_source_port"
    }
}
