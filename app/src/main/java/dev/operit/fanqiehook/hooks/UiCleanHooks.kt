package dev.operit.fanqiehook.hooks

import android.view.View
import android.view.ViewGroup
import dev.operit.fanqiehook.config.ModuleConfig
import dev.operit.fanqiehook.support.HookSupport
import dev.operit.fanqiehook.support.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * UI cleanup hooks — 15 independent sub-switches, adapted from MK's UiCleanHooker.
 *
 * Short obfuscated names (bd2.c, bf4.c0, i44.c, e44.c, o53.h0, za1.m) are
 * version-pinned for v73332 exactly like MK hard-codes them; every hook goes
 * through [HookSupport.safeHook] so a miss only logs WARN and moves on.
 *
 * The mine-feed scene gate deserves explanation: the "我的" tab renders a
 * staggered recommendation feed (StaggeredFeedLayout). Blocking it outright
 * crashes the page, so instead we detect the feed scene (via the `gt3.r`-typed
 * scene-config field → getScene() → enum name) and short-circuit load/request
 * methods only when scene == MINE, then force-show the empty-content state.
 */
object UiCleanHooks {

    private const val TAG = "UiClean"

    // ── View-id constants resolved lazily via resources ──────────────────────
    private var viewIdHiderInstalled = false

    fun installAll() {
        if (!ModuleConfig.uiClean()) return
        if (ModuleConfig.uiCleanSearchWord()) hookSearchWordHint()
        if (ModuleConfig.uiCleanScreenAd()) hookScreenAd()
        if (ModuleConfig.uiCleanVipCard()) hookVipCard()
        if (ModuleConfig.uiCleanFunctionBadge()) hookFunctionBadge()
        if (ModuleConfig.uiCleanMiniGame()) hookMiniGame()
        if (ModuleConfig.uiCleanTabBadge()) hookTabBadge()
        if (ModuleConfig.uiCleanMineFeed()) hookMineFeed()
        if (ModuleConfig.uiCleanHomeEarnTab() || ModuleConfig.uiCleanHomeSeriesTab()) hookHomeTabBar()
        if (ModuleConfig.uiCleanHidePublish()) hookPublishButton()
        if (ModuleConfig.uiCleanHideMineAsset()) hookMineAsset()
        if (ModuleConfig.uiCleanHideMineHistory()) hookMineHistory()
        if (ModuleConfig.uiCleanHideMineEarnPendant()) hookMineEarnPendant()
        if (ModuleConfig.uiCleanSearchResult()) hookSearchResult()
        if (ModuleConfig.uiCleanSearchAi()) hookSearchAi()
    }

    // ── 1. 搜索框轮播提示词 ───────────────────────────────────────────────────

    private fun hookSearchWordHint() {
        val cls = "com.dragon.read.kmp.bookmall.search.SearchWordDisplayViewKMP"
        for (m in listOf("d", "e", "h")) {
            HookSupport.safeHook(TAG, cls, m, arrayOf("java.util.List"), "搜索轮播词-$m") { chain ->
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) args[0] = ArrayList<Any>()
                chain.proceed(args)
            }
        }
    }

    // ── 2. 全屏弹窗广告 ───────────────────────────────────────────────────────

    private fun hookScreenAd() {
        // Obfuscated manager bd2.c — three entry gates.
        val mgr = "bd2.c"
        HookSupport.safeHook(TAG, mgr, "b", null, "全屏广告-b") { false }
        HookSupport.safeHook(TAG, mgr, "c", null, "全屏广告-c") { null }
        HookSupport.safeHook(TAG, mgr, "onScreenAdDialogShow", null, "全屏广告-dialog") { false }

        // Root gates on the stable-named classes.
        HookSupport.safeHook(TAG, "com.dragon.read.msg.ScreenAdManager", "canShowScreenAd",
            arrayOf("java.lang.Object"), "全屏广告-根闸") { false }
        HookSupport.safeHook(TAG, "com.dragon.read.component.NsUtilsDependImpl", "canShowScreenAd",
            arrayOf("java.lang.Object"), "全屏广告-依赖闸") { false }
    }

    // ── 3. 会员卡 ────────────────────────────────────────────────────────────

    private fun hookVipCard() {
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.NsVipImpl",
            "isDisableVipInGoogle", null, "会员卡-禁用开关") { true }
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.FanqieMineFragmentV2",
            "oh", null, "会员卡-跳过构建") { null }
    }

    // ── 4. 功能区红点角标 ─────────────────────────────────────────────────────

    private fun hookFunctionBadge() {
        // Sidebar item ctor: 6th param (hasRedDot) forced false.
        HookSupport.safeHookCtor(TAG, "i44.c",
            arrayOf("int", "java.lang.String", "java.lang.String",
                "java.lang.String", "java.lang.String", "boolean"),
            "功能区角标-构造器"
        ) { chain ->
            val args = chain.args.toTypedArray()
            if (args.size >= 6) args[5] = false
            chain.proceed(args)
        }
        HookSupport.safeHook(TAG, "i44.c", "f", null, "功能区角标-f") { false }
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.reddot.MineRedDotManager",
            "l", arrayOf("boolean"), "我的角标-l") { false }
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.reddot.MineRedDotManager",
            "m", arrayOf("java.lang.String"), "我的角标-m") { false }
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.NsMineImpl",
            "enableShowRedDot", arrayOf("java.lang.String"), "我的角标-总闸") { false }
    }

    // ── 5. 小游戏入口 ────────────────────────────────────────────────────────

    private fun hookMiniGame() {
        HookSupport.safeHook(TAG, "e44.c", "a", null, "小游戏-禁用") { true }
    }

    // ── 6. 底部 TAB 角标 ─────────────────────────────────────────────────────

    private fun hookTabBadge() {
        val c = "bf4.c0"
        HookSupport.safeHook(TAG, c, "c0",
            arrayOf("com.dragon.read.model.GetTabBubbleResult"), "TAB角标-缓存清零") { chain ->
                val args = chain.args.toTypedArray()
                val result = args.getOrNull(0)
                if (result != null) {
                    Reflect.on(result).set("bubbleList", emptyList<Any>())
                }
                chain.proceed(args)
            }
        HookSupport.safeHook(TAG, c, "h0",
            arrayOf("java.lang.String", "com.dragon.read.model.GetTabBubbleResp"), "TAB角标-回包丢弃") { null }
        HookSupport.safeHook(TAG, c, "F0",
            arrayOf("db5.h", "java.lang.String"), "TAB角标-总闸F0") { null }
        HookSupport.safeHook(TAG, c, "g0", arrayOf("java.lang.String"), "TAB角标-g0") { null }
        HookSupport.safeHook(TAG, c, "l0", arrayOf("java.lang.String"), "TAB角标-l0") { null }
        HookSupport.safeHook(TAG, c, "G0",
            arrayOf("android.app.Activity", "th4.d3", "com.dragon.read.model.TabBubble"),
            "TAB角标-单点否决") { false }
        HookSupport.safeHook(TAG, c, "T",
            arrayOf("com.dragon.read.model.TabBubble"), "TAB角标-T判定") { false }
    }

    // ── 7. 我的页推荐 feed（场景闸） ─────────────────────────────────────────

    private var sceneCfgField: Field? = null
    private var sceneGetter: Method? = null
    private var sceneGetterOwner: String = ""

    private fun hookMineFeed() {
        val layout = "com.dragon.read.component.biz.impl.bookmall.holder.staggeredinfinite.container.StaggeredFeedLayout"

        // Load / request entries matched by name+paramCount (signatures drift).
        HookSupport.hookByName(TAG, layout, "g", 2, "我的feed-主入口g") { chain ->
            feedSceneGate(chain)
        }
        HookSupport.hookByName(TAG, layout, "q", 2, "我的feed-请求q") { chain ->
            feedSceneGate(chain)
        }
        HookSupport.safeHook(TAG, layout, "b", arrayOf("java.util.List"), "我的feed-场景闸b") { chain ->
            feedSceneGate(chain)
        }
        HookSupport.safeHook(TAG, layout, "e", arrayOf("java.util.List"), "我的feed-场景闸e") { chain ->
            feedSceneGate(chain)
        }

        // KMP variant (newer feed implementation).
        val vm = "com.dragon.read.kmp.common_feed.staggeredfeed.StaggeredFeedLayoutViewModel"
        HookSupport.safeHook(TAG, vm, "b", arrayOf("java.util.List"), "我的feed-KMP闸b") { chain ->
            kmpFeedSceneGate(chain)
        }
        HookSupport.hookByName(TAG, vm, "F", 5, "我的feed-KMP下发F") { chain ->
            kmpFeedSceneGate(chain)
        }
    }

    /**
     * Short-circuit a StaggeredFeedLayout method when its scene == MINE:
     * show the "no content" state instead of loading the feed.
     */
    private fun feedSceneGate(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        return try {
            val target = chain.thisObject ?: chain.args.getOrNull(0)
                ?: return chain.proceed()
            val scene = currentFeedScene(target)
            if (scene == "MINE") {
                showFeedContent(target)
                null
            } else {
                chain.proceed()
            }
        } catch (t: Throwable) {
            chain.proceed()
        }
    }

    private fun kmpFeedSceneGate(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        return try {
            val target = chain.thisObject ?: chain.args.getOrNull(0)
                ?: return chain.proceed()
            val scene = kmpScene(target)
            if (scene == "MINE") {
                // KMP variant exposes show-content through P().
                try {
                    target.javaClass.getMethod("P").apply { isAccessible = true }
                        .invoke(target)
                } catch (ignored: Throwable) {
                }
                null
            } else {
                chain.proceed()
            }
        } catch (t: Throwable) {
            chain.proceed()
        }
    }

    /** Resolve the feed scene enum name via the gt3.r-typed config field. */
    private fun currentFeedScene(layout: Any): String {
        // Known field name first, then scan by type signature.
        var field = tryOrNull { layout.javaClass.getDeclaredField("f134378a") }
        if (field == null) {
            field = layout.javaClass.declaredFields.firstOrNull { isSceneConfigType(it.type) }
        }
        if (field == null) return ""
        field.isAccessible = true
        val cfg = field.get(layout) ?: return ""

        if (sceneGetter == null || sceneGetterOwner != cfg.javaClass.name) {
            sceneGetter = tryOrNull { cfg.javaClass.getMethod("getScene") }?.apply {
                isAccessible = true
            }
            sceneGetterOwner = cfg.javaClass.name
        }
        val value = sceneGetter?.invoke(cfg) ?: return ""
        return (value as? Enum<*>)?.name ?: ""
    }

    private fun kmpScene(vm: Any): String {
        val field = tryOrNull { vm.javaClass.getDeclaredField("f162764a") } ?: return ""
        field.isAccessible = true
        val cfg = field.get(vm) ?: return ""
        val value = tryOrNull {
            cfg.javaClass.getMethod("getScene").apply { isAccessible = true }.invoke(cfg)
        } ?: return ""
        return (value as? Enum<*>)?.name ?: ""
    }

    /** MK's isGt3r: interface whose name is `gt3.r` or ends with `.r`. */
    private fun isSceneConfigType(type: Class<*>): Boolean =
        type.name == "gt3.r" || (type.name.endsWith(".r") && type.isInterface)

    /** Trigger the "show empty content" path on a StaggeredFeedLayout. */
    private fun showFeedContent(layout: Any) {
        try {
            layout.javaClass.getMethod("a1").apply { isAccessible = true }.invoke(layout)
        } catch (ignored: Throwable) {
        }
    }

    // ── 8. 主页底部导航 tab 过滤 ─────────────────────────────────────────────

    private fun hookHomeTabBar() {
        val blockEarn = ModuleConfig.uiCleanHomeEarnTab()
        val blockSeries = ModuleConfig.uiCleanHomeSeriesTab()

        val depend = "com.dragon.read.component.NsCommonDependImpl"
        HookSupport.safeHook(TAG, depend, "getMainTabBarItems", null, "主页tab-类型过滤") { chain ->
            val result = chain.proceed()
            if (result is List<*>) filterTabTypes(result, blockEarn, blockSeries) else result
        }
        HookSupport.safeHook(TAG, depend, "getBottomTabBarItemDataList", null, "主页tab-数据过滤") { chain ->
            val result = chain.proceed()
            if (result is List<*>) filterTabData(result, blockEarn, blockSeries) else result
        }

        HookSupport.safeHook(TAG, "com.dragon.read.pages.main.u2", "z", null, "主页tab-渲染源z") { chain ->
            val result = chain.proceed()
            if (result is List<*>) filterTabTypes(result, blockEarn, blockSeries) else result
        }
        HookSupport.safeHook(TAG, "com.dragon.read.pages.main.u2", "m", null, "主页tab-渲染源m") { chain ->
            val result = chain.proceed()
            if (result is List<*>) filterTabData(result, blockEarn, blockSeries) else result
        }
    }

    private fun filterTabTypes(list: List<*>, blockEarn: Boolean, blockSeries: Boolean): List<*> =
        list.filter { item ->
            val name = (item as? Enum<*>)?.name ?: return@filter true
            !(blockedTab(name, blockEarn, blockSeries))
        }

    private fun filterTabData(list: List<*>, blockEarn: Boolean, blockSeries: Boolean): List<*> =
        list.filter { item ->
            if (item == null) return@filter true
            val type = tryOrNull {
                item.javaClass.getField("tabType").get(item)
            }
            val name = (type as? Enum<*>)?.name ?: return@filter true
            !blockedTab(name, blockEarn, blockSeries)
        }

    private fun blockedTab(name: String, blockEarn: Boolean, blockSeries: Boolean): Boolean =
        (blockEarn && name == "LuckyBenefit") || (blockSeries && name == "VideoSeriesFeedTab")

    // ── 9. 发表按钮 ──────────────────────────────────────────────────────────

    private fun hookPublishButton() {
        val cls = "com.dragon.read.component.biz.impl.bookmall.videoseriespost.entrance.m"
        HookSupport.safeHook(TAG, cls, "onAttachedToWindow", null, "发表按钮-attach后GONE") { chain ->
            val result = chain.proceed()
            (chain.thisObject as? View)?.let { v ->
                if (v.visibility != View.GONE) v.visibility = View.GONE
            }
            result
        }
        HookSupport.safeHook(TAG, cls, "isShown", null, "发表按钮-isShown=false") { false }
    }

    // ── 10. 我的页金币信息 ───────────────────────────────────────────────────

    private fun hookMineAsset() {
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.VariantMineFragment",
            "qg", null, "金币信息-跳过") { null }
    }

    // ── 11. 我的页浏览历史卡 ─────────────────────────────────────────────────

    private fun hookMineHistory() {
        HookSupport.safeHook(TAG, "o53.h0", "r", arrayOf(), "历史卡-外露条总闸") { false }

        HookSupport.safeHook(TAG,
            "com.dragon.read.component.biz.impl.mine.highfreq.history.BasicExperienceCardProvider",
            "parseModel", arrayOf("com.dragon.read.rpc.model.CellViewData"),
            "历史卡-服务端卡拦截") { chain ->
                val arg0 = chain.args.getOrNull(0)
                val groupType = tryOrNull {
                    arg0?.javaClass?.getField("groupIdType")?.get(arg0)
                }
                if ((groupType as? Enum<*>)?.name == "ReadHistory") {
                    emptyList<Any>()
                } else {
                    chain.proceed()
                }
            }

        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.FanqieMineFragmentV2",
            "handleInsertHistoryCard", arrayOf("o53.z"), "历史卡-跳过插入") { null }

        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.card.model.MineHistoryCard",
            "V", arrayOf("boolean"), "历史卡-强制隐藏V") { chain ->
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty() && args[0] == true) args[0] = false
                chain.proceed(args)
            }

        // Ctor: after construction, force the card's root view GONE.
        HookSupport.safeHookCtor(TAG,
            "com.dragon.read.component.biz.impl.mine.card.model.MineHistoryCard",
            arrayOf("com.dragon.read.base.AbsFragment"), "历史卡-构造后GONE") { chain ->
                val result = chain.proceed()
                try {
                    val card = chain.thisObject
                    val view = card.javaClass.getMethod("h").invoke(card) as? View
                    view?.visibility = View.GONE
                } catch (ignored: Throwable) {
                }
                result
            }
    }

    // ── 12. 赚钱挂件 ─────────────────────────────────────────────────────────

    private fun hookMineEarnPendant() {
        HookSupport.safeHook(TAG, "com.bytedance.ug.sdk.novel.pendant.manager.b\$b",
            "run", null, "赚钱挂件-禁用创建") { null }
    }

    // ── 13. 搜索结果卡片清理 ─────────────────────────────────────────────────

    private fun hookSearchResult() {
        val cls = "com.dragon.read.component.biz.impl.help.z"
        val param = arrayOf("com.dragon.read.rpc.model.CellViewData")
        for (m in listOf("S", "M", "I", "R", "T")) {
            HookSupport.safeHook(TAG, cls, m, param, "搜索结果清理-$m") { null }
        }
    }

    // ── 14. AI 入口 ──────────────────────────────────────────────────────────

    private fun hookSearchAi() {
        val cls = "com.dragon.read.component.biz.impl.SearchActivity"
        for (m in listOf("G3", "H3", "I3")) {
            HookSupport.safeHook(TAG, cls, m, null, "AI入口-$m") { false }
        }
        // Static factories returning "enabled" config: replace with default instances.
        hookStaticFactory("com.dragon.read.base.ssconfig.template.SearchResultPageAiFloatButton")
        hookStaticFactory("com.dragon.read.base.ssconfig.template.SearchPageAiEntranceV675")
    }

    private fun hookStaticFactory(className: String) {
        HookSupport.safeHook(TAG, className, "b", null, "AI静态工厂") { _ ->
            try {
                val cls = HookSupport.dragonLoader().loadClass(className)
                cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            } catch (t: Throwable) {
                null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private inline fun <T> tryOrNull(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }
}
