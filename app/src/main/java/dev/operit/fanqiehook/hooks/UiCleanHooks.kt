package dev.operit.fanqiehook.hooks

import android.view.View
import dev.operit.fanqiehook.config.ModuleConfig
import dev.operit.fanqiehook.support.HookSupport
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * UI cleanup hooks — 14 independent sub-switches, adapted from MK's UiCleanHooker.
 *
 * 残留的混淆名 (i44.c, e44.c) 仍指向 APK 中真实存在的类；其余 (bd2.c / bf4.c0 /
 * o53.h0 / bytedance pendant b$b / videoseriespost.entrance.m) 在当前版本已不存在，
 * 相关钩子全部移除或转为稳定的 NsUtils/NsVip/NsMine/NsCommonDependImpl 兜底闸。
 * 所有钩子走 [HookSupport.safeHook]，未命中仅 WARN 不崩。
 *
 * 所有钩子无条件装配；每个回调内实时读取总开关 + 子开关（[enabled]），
 * 关闭时按原逻辑放行 —— 开关改动免重启即时生效。
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

    /** 总开关 + 子开关联合判断（回调内实时读取，免重启生效）。 */
    private inline fun enabled(sub: () -> Boolean): Boolean =
        ModuleConfig.uiClean() && sub()

    fun installAll() {
        hookSearchWordHint()
        hookScreenAd()
        hookVipCard()
        hookFunctionBadge()
        hookMiniGame()
        hookTabBadge()
        hookMineFeed()
        hookHomeTabBar()
        hookPublishButton()
        hookMineAsset()
        hookMineHistory()
        hookMineEarnPendant()
        hookSearchResult()
        hookSearchAi()
    }

    // ── 1. 搜索框轮播提示词 ───────────────────────────────────────────────────

    private fun hookSearchWordHint() {
        // dex 73332: 真实方法 f/h/e(List)V，d(List) 已不存在；改用 f 替代 d。
        val cls = "com.dragon.read.kmp.bookmall.search.SearchWordDisplayViewKMP"
        for (m in listOf("f", "e", "h")) {
            HookSupport.safeHook(TAG, cls, m, arrayOf("java.util.List"), "搜索轮播词-$m") { chain ->
                if (!enabled(ModuleConfig::uiCleanSearchWord)) return@safeHook chain.proceed()
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) args[0] = ArrayList<Any>()
                chain.proceed(args)
            }
        }
    }

    // ── 2. 全屏弹窗广告 ───────────────────────────────────────────────────────

    private fun hookScreenAd() {
        // NsUtilsDependImpl.canShowScreenAd(Object)Z → false 即可拦截全屏弹窗广告。
        // 旧版本中的 bd2.c (Function1 lambda) 和 com.dragon.read.msg.ScreenAdManager
        // 在当前 APK 已不存在/结构变化，相关钩子移除。
        HookSupport.safeHook(TAG, "com.dragon.read.component.NsUtilsDependImpl", "canShowScreenAd",
            arrayOf("java.lang.Object"), "全屏广告-依赖闸") { chain ->
                if (enabled(ModuleConfig::uiCleanScreenAd)) false else chain.proceed()
            }
        HookSupport.safeHook(TAG, "com.dragon.read.component.NsUtilsDependImpl",
            "onScreenAdDialogShow",
            arrayOf("android.app.Activity", "android.app.Dialog"),
            "全屏广告-依赖闸-dialog") { chain ->
                if (enabled(ModuleConfig::uiCleanScreenAd)) false else chain.proceed()
            }
    }

    // ── 3. 会员卡 ────────────────────────────────────────────────────────────

    private fun hookVipCard() {
        // NsVipImpl.isDisableVipInGoogle=true → 全局禁会员卡入口。
        // 旧版本有 FanqieMineFragmentV2#oh 钩子，但当前 APK 已无此方法，故移除。
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.NsVipImpl",
            "isDisableVipInGoogle", null, "会员卡-禁用开关") { chain ->
                if (enabled(ModuleConfig::uiCleanVipCard)) true else chain.proceed()
            }
    }

    // ── 4. 功能区红点角标 ─────────────────────────────────────────────────────

    private fun hookFunctionBadge() {
        // dex 73332: i44.c 类名仍存在但已重构为 Kotlin Function1 lambda (只有 invoke(Object)Object)，
        // 原 6-arg 业务构造器与 f() 方法均已消失；红点角标改由下方 3 个稳定钩子兜底：
        //   MineRedDotManager#d(boolean) / #e(String) 与 NsMineImpl#enableShowRedDot(String)。
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.reddot.MineRedDotManager",
            "d", arrayOf("boolean"), "我的角标-d") { chain ->
                if (enabled(ModuleConfig::uiCleanFunctionBadge)) false else chain.proceed()
            }
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.reddot.MineRedDotManager",
            "e", arrayOf("java.lang.String"), "我的角标-e") { chain ->
                if (enabled(ModuleConfig::uiCleanFunctionBadge)) false else chain.proceed()
            }
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.NsMineImpl",
            "enableShowRedDot", arrayOf("java.lang.String"), "我的角标-总闸") { chain ->
                if (enabled(ModuleConfig::uiCleanFunctionBadge)) false else chain.proceed()
            }
    }

    // ── 5. 小游戏入口 ────────────────────────────────────────────────────────

    private fun hookMiniGame() {
        HookSupport.safeHook(TAG, "e44.c", "a", null, "小游戏-禁用") { chain ->
            if (enabled(ModuleConfig::uiCleanMiniGame)) true else chain.proceed()
        }
    }

    // ── 6. 底部 TAB 角标 ─────────────────────────────────────────────────────

    private fun hookTabBadge() {
        // 旧版本的 bf4.c0 类在当前 APK 已不存在，6 个钩子全部移除。
        // 底部 TAB 角标改由 hookFunctionBadge 中 NsMineImpl.enableShowRedDot 兜底。
    }

    // ── 7. 我的页推荐 feed（场景闸） ─────────────────────────────────────────

    private var sceneCfgField: Field? = null
    private var sceneGetter: Method? = null
    private var sceneGetterOwner: String = ""

    private fun hookMineFeed() {
        val layout = "com.dragon.read.component.biz.impl.bookmall.holder.staggeredinfinite.container.StaggeredFeedLayout"

        // dex 73332: 真实方法是 A(List)V / n(List)V（数据下发）与 g/h(s05.z, s05.y)V（回调）；
        // 原 q/b/e(List) 方法已不存在。
        HookSupport.hookByName(TAG, layout, "g", 2, "我的feed-主入口g") { chain ->
            feedSceneGate(chain)
        }
        HookSupport.hookByName(TAG, layout, "h", 2, "我的feed-回调h") { chain ->
            feedSceneGate(chain)
        }
        HookSupport.safeHook(TAG, layout, "A", arrayOf("java.util.List"), "我的feed-数据下发A") { chain ->
            feedSceneGate(chain)
        }
        HookSupport.safeHook(TAG, layout, "n", arrayOf("java.util.List"), "我的feed-数据下发n") { chain ->
            feedSceneGate(chain)
        }

        // KMP variant (newer feed implementation).
        // dex 73332: 真实方法是 n(List,Z)V / o(List,Z)V；原 b(List) / F/5参 已不存在。
        val vm = "com.dragon.read.kmp.common_feed.staggeredfeed.StaggeredFeedLayoutViewModel"
        HookSupport.safeHook(TAG, vm, "n", arrayOf("java.util.List", "boolean"), "我的feed-KMP闸n") { chain ->
            kmpFeedSceneGate(chain)
        }
        HookSupport.safeHook(TAG, vm, "o", arrayOf("java.util.List", "boolean"), "我的feed-KMP闸o") { chain ->
            kmpFeedSceneGate(chain)
        }
    }

    /**
     * Short-circuit a StaggeredFeedLayout method when its scene == MINE:
     * show the "no content" state instead of loading the feed.
     */
    private fun feedSceneGate(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
        if (!enabled(ModuleConfig::uiCleanMineFeed)) return chain.proceed()
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
        if (!enabled(ModuleConfig::uiCleanMineFeed)) return chain.proceed()
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

    /** 赚钱 tab 资源 id（aot），懒解析。 */
    private var tabEarnId = 0

    /** 短剧 tab 资源 id（aou），懒解析。 */
    private var tabSeriesId = 0

    private fun hookHomeTabBar() {
        val depend = "com.dragon.read.component.NsCommonDependImpl"
        HookSupport.safeHook(TAG, depend, "getMainTabBarItems", null, "主页tab-类型过滤") { chain ->
            val result = chain.proceed()
            val blockEarn = enabled(ModuleConfig::uiCleanHomeEarnTab)
            val blockSeries = enabled(ModuleConfig::uiCleanHomeSeriesTab)
            if (result is List<*> && (blockEarn || blockSeries)) {
                filterTabTypes(result, blockEarn, blockSeries)
            } else {
                result
            }
        }
        HookSupport.safeHook(TAG, depend, "getBottomTabBarItemDataList", null, "主页tab-数据过滤") { chain ->
            val result = chain.proceed()
            val blockEarn = enabled(ModuleConfig::uiCleanHomeEarnTab)
            val blockSeries = enabled(ModuleConfig::uiCleanHomeSeriesTab)
            if (result is List<*> && (blockEarn || blockSeries)) {
                filterTabData(result, blockEarn, blockSeries)
            } else {
                result
            }
        }

        // View 层隐藏：BottomTabBarLayout 绑定 tab 时，比对资源 id 直接 GONE。
        // dex 73332: 只有 U1(v37/g)V 与 V1(v37/g;ZZ)V 两个虚拟方法，O1 已不存在。
        val tabLayout = "com.dragon.read.widget.BottomTabBarLayout"
        HookSupport.hookByName(TAG, tabLayout, "U1", 1, "主页tab-view层隐藏U1") { chain ->
            val result = chain.proceed()
            hideTabViewIfNeeded(chain.args.getOrNull(0))
            result
        }
        // 3 参变体（V1(g, boolean, boolean)）同样走 view 隐藏。
        HookSupport.hookByName(TAG, tabLayout, "V1", 3, "主页tab-view层隐藏V1") { chain ->
            val result = chain.proceed()
            hideTabViewIfNeeded(chain.args.getOrNull(0))
            result
        }
    }

    /** holder.getView() → 比对 aot/aou 资源 id → GONE（MK 的 view 层隐藏逻辑）。 */
    private fun hideTabViewIfNeeded(holder: Any?) {
        if (holder == null) return
        val blockEarn = enabled(ModuleConfig::uiCleanHomeEarnTab)
        val blockSeries = enabled(ModuleConfig::uiCleanHomeSeriesTab)
        if (!blockEarn && !blockSeries) return
        try {
            val view = holder.javaClass.getMethod("getView").invoke(holder) as? View ?: return
            if (tabEarnId == 0 || tabSeriesId == 0) {
                val res = view.resources
                tabEarnId = res.getIdentifier("aot", "id", "com.dragon.read")
                tabSeriesId = res.getIdentifier("aou", "id", "com.dragon.read")
            }
            val shouldHide =
                (blockEarn && tabEarnId != 0 && view.id == tabEarnId) ||
                    (blockSeries && tabSeriesId != 0 && view.id == tabSeriesId)
            if (shouldHide && view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun filterTabTypes(list: List<*>, blockEarn: Boolean, blockSeries: Boolean): List<*> {
        // 诊断：打印当前 tab 枚举名，便于核对过滤目标是否漂移。
        val names = list.mapNotNull { (it as? Enum<*>)?.name }
        if (names.isNotEmpty()) HookSupport.log?.info("[$TAG] 主页tab枚举: $names")
        return list.filter { item ->
            val name = (item as? Enum<*>)?.name ?: return@filter true
            !(blockedTab(name, blockEarn, blockSeries))
        }
    }

    private fun filterTabData(list: List<*>, blockEarn: Boolean, blockSeries: Boolean): List<*> {
        val names = mutableListOf<String>()
        for (item in list) {
            if (item == null) continue
            val type = tryOrNull { item.javaClass.getField("tabType").get(item) }
            (type as? Enum<*>)?.name?.let { names.add(it) }
        }
        if (names.isNotEmpty()) HookSupport.log?.info("[$TAG] 主页tab数据: $names")
        return list.filter { item ->
            if (item == null) return@filter true
            val type = tryOrNull {
                item.javaClass.getField("tabType").get(item)
            }
            val name = (type as? Enum<*>)?.name ?: return@filter true
            !blockedTab(name, blockEarn, blockSeries)
        }
    }

    private fun blockedTab(name: String, blockEarn: Boolean, blockSeries: Boolean): Boolean =
        (blockEarn && name == "LuckyBenefit") || (blockSeries && name == "VideoSeriesFeedTab")

    // ── 9. 发表按钮 ──────────────────────────────────────────────────────────

    private fun hookPublishButton() {
        // 旧版本的 com.dragon.read.component.biz.impl.bookmall.videoseriespost.entrance.m
        // 类在当前 APK 已不存在，相关钩子移除。
        // 发表按钮（创作中心入口）若需要隐藏，可改用 hookHomeTabBar 屏蔽 VideoSeriesFeedTab，
        // 或后续找到新的入口类再加回。
    }

    // ── 10. 我的页金币信息 ───────────────────────────────────────────────────

    private fun hookMineAsset() {
        // VariantMineFragment.qg()Z (PUBLIC STATIC) 返回是否显示金币信息；
        // 关闭开关时返 false 即可隐藏。
        HookSupport.safeHook(TAG, "com.dragon.read.component.biz.impl.mine.VariantMineFragment",
            "qg", null, "金币信息-跳过") { chain ->
                if (enabled(ModuleConfig::uiCleanHideMineAsset)) false else chain.proceed()
            }
    }

    // ── 11. 我的页浏览历史卡 ─────────────────────────────────────────────────

    private fun hookMineHistory() {
        // 旧版本的 o53.h0.r 和 MineHistoryCard#V(boolean) 在当前 APK 已不存在，移除。
        // handleInsertHistoryCard 参数类型从 o53.z 修正为 g44.a0 (dexdump 验证)。

        HookSupport.safeHook(TAG,
            "com.dragon.read.component.biz.impl.mine.highfreq.history.BasicExperienceCardProvider",
            "parseModel", arrayOf("com.dragon.read.rpc.model.CellViewData"),
            "历史卡-服务端卡拦截") { chain ->
                if (!enabled(ModuleConfig::uiCleanHideMineHistory)) return@safeHook chain.proceed()
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
            "handleInsertHistoryCard", arrayOf("g44.a0"), "历史卡-跳过插入") { chain ->
                if (enabled(ModuleConfig::uiCleanHideMineHistory)) null else chain.proceed()
            }

        // Ctor: after construction, force the card's root view GONE.
        HookSupport.safeHookCtor(TAG,
            "com.dragon.read.component.biz.impl.mine.card.model.MineHistoryCard",
            arrayOf("com.dragon.read.base.AbsFragment"), "历史卡-构造后GONE") { chain ->
                val result = chain.proceed()
                if (!enabled(ModuleConfig::uiCleanHideMineHistory)) return@safeHookCtor result
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
        // 旧版本的 com.bytedance.ug.sdk.novel.pendant.manager.b$b 类在当前 APK 已不存在，钩子移除。
    }

    // ── 13. 搜索结果卡片清理 ─────────────────────────────────────────────────

    private fun hookSearchResult() {
        // dex 73332: help.z#S/M/I/R/T 已消失；5 个搜索结果卡解析方法迁移到 3 个 BrickService
        // (V1/V2/V3) 的 parseRoot(CellViewData)AbsSearchModel。3 个都是 Kotlin 接口，
        // 实际实现类为同名 $Companion$IMPL$1 单例，钩在实现类上才能真正拦截。
        val cellData = "com.dragon.read.rpc.model.CellViewData"
        val pkg = "com.dragon.read.component.biz.brickservice"
        for (ver in listOf("V1", "V2", "V3")) {
            val implCls = "$pkg.SearchSeriesCard${ver}BrickService\$Companion\$IMPL\$1"
            HookSupport.safeHook(TAG, implCls, "parseRoot", arrayOf(cellData), "搜索结果清理-${ver}-parseRoot") { chain ->
                if (enabled(ModuleConfig::uiCleanSearchResult)) null else chain.proceed()
            }
        }
    }

    // ── 14. AI 入口 ──────────────────────────────────────────────────────────

    private fun hookSearchAi() {
        // SearchActivity 没有 G3/H3/I3，这些方法属于 holder/k2，不是搜索入口。
        // 真正的 AI 入口由 SearchPageAiEntranceV675 / SearchResultPageAiFloatButton
        // 这两个 Kotlin object 的构造器决定（entryInBanner / entryInSearchBox / hideSearchBarButton / showFloatButton）。
        // 通过挂钩构造器并强制传入 false，让两个 AI 入口都不显示。
        replaceAiConfigWithDefault(
            "com.dragon.read.base.ssconfig.template.SearchResultPageAiFloatButton",
            arrayOf("boolean", "boolean")
        )
        replaceAiConfigWithDefault(
            "com.dragon.read.base.ssconfig.template.SearchPageAiEntranceV675",
            arrayOf("boolean", "boolean")
        )
    }

    /**
     * Hook 构造器，强制 (false, false) 实例，绕过 SSConfig 默认值。
     * SearchResultPageAiFloatButton: hideSearchBarButton=false, showFloatButton=false
     * SearchPageAiEntranceV675:       entryInBanner=false,    entryInSearchBox=false
     */
    private fun replaceAiConfigWithDefault(className: String, params: Array<String>) {
        HookSupport.safeHookCtor(TAG, className, params, "AI静态配置-构造器") { chain ->
            if (!enabled(ModuleConfig::uiCleanSearchAi)) return@safeHookCtor chain.proceed()
            val args = chain.args.toTypedArray()
            // 把所有 boolean 参数置 false（两个类的字段语义都是“是否显示/启用”）。
            for (i in args.indices) {
                if (args[i] is Boolean) args[i] = false
            }
            chain.proceed(args)
        }
        // 兜底：无参构造器（Kotlin object 风格 <init>()V）也会被调用，直接返回零值实例。
        HookSupport.safeHookCtor(TAG, className, arrayOf(), "AI静态配置-无参构造") { chain ->
            if (!enabled(ModuleConfig::uiCleanSearchAi)) return@safeHookCtor chain.proceed()
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private inline fun <T> tryOrNull(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }
}
