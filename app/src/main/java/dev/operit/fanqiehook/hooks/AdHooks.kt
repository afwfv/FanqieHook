package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog

/**
 * All ad-related hooks for `com.dragon.read` versionCodes 73532 (v7.3.5.32) and 73732 (v7.3.7.32).
 *
 * Audit status (DEX-level; re-run the whole target audit for every supported versionCode):
 *
 *   | versionCode | host                 | class/method targets                            | invoke sites |
 *   |-------------|----------------------|-------------------------------------------------|--------------|
 *   | 73532       | 番茄 com.dragon.read | 25/26 (only Hongguo-only HongguoBannerServiceImpl absent) | baseline |
 *   | 73732       | 番茄 com.dragon.read | 25/26 (same Hongguo-only miss)                  | identical to 73532 |
 *   | 73732       | 红果 com.phoenix.read| 26/26                                           | n/a |
 *
 * No hook target moved between 73532 and 73732, so one implementation covers both. What did drift:
 *   - `SeriesPauseAdImpl.canShowPauseAd`'s obfuscated parameter (`so4.h` on Fanqie 73532 →
 *     `vq4.i` on Fanqie 73732) — handled by [ClassResolver.findMethodIgnoringParams].
 *   - The DexKit-resolved `NsAdConfigManagerApi` impl class (`fe3.a` → `lf3.a` on Fanqie,
 *     `yb3.a` on Hongguo) — handled by resolving through the interface.
 *   - One `video_reader_ad` string-literal reference disappeared in 73732; the position itself
 *     is still present and still filtered.
 *
 * Position-string policy:
 *   The string parameter to [BLOCKED_POSITIONS] is matched against `String position` arguments
 *   taken at runtime. Whitelist user-initiated reward/coin flows so they remain functional.
 */
class AdHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog
) {

    /**
     * Convenience bundle: install every category. Each `installXxx` is internally try/caught;
     * one failure never short-circuits another.
     */
    fun installAll() {
        installReaderHooks()
        installTopViewHooks()
        installSeriesPauseHooks()
        installPositionFilter()
        installVipEntranceHooks()
        installReaderAdManagerHooks()
        installInspireAdHooks()
        installAudioAdHooks()
        installExperimentalSplashHook()
        installShortSeriesAdHooks()
        installSplashAdHooks()
        installFullScreenAdHooks()
        installUiPurifyHooks()
    }


    // ─────────────────────────────────────────────────────────────────────────
    // 12. 全屏广告 / 开屏的「根闸」
    //
    //   实测结论：番茄的开屏与全屏福利广告**不经过** NsAdImpl 的开屏位置与
    //   OpeningScreenADActivity（那是红果的 Activity 路径），而是由一个专门的管理器决策：
    //
    //     - 依赖层闸门：`NsUtilsDependImpl.canShowScreenAd(Object)Z`
    //       由接口 `com.dragon.read.NsUtilsDepend` 声明；全库（287353 个类）只有这一个实现，
    //       即它是一个干净的公共判定点。
    //     - 管理器：接口 `com.dragon.read.ad.screen.IActivityScreenAdManager`
    //       （“全屏福利广告管理器”），其实现类上的零参 boolean 方法即展示判定。
    //       73732 上实现类唯一（`ua3.f`），两个判定方法是 `a()Z` 与 `onScreenAdDialogShow()Z`。
    //
    //   为什么按「接口反查 + 零参 boolean」而不是写死名字：类名和方法名都是混淆的，且
    //   73732 相对更早版本已经改过名（旧版实现类是别的混淆名、判定方法叫 b/c），写死必然失效。
    //
    //   证据来源：同类模块目标表交叉核对 + 本机 73732 DEX 核验（接口实现类唯一、
    //   boolean 方法签名一一对应、两次调用点计数一致）。
    // ─────────────────────────────────────────────────────────────────────────

    private fun installFullScreenAdHooks() {
        // 依赖层闸门：命中时打一条日志，便于确认它确实是开屏路径上的公共判定点。
        hooks.install(
            id = "fullscreen-ad-depend-gate",
            method = resolver.findMethod(
                "com.dragon.read.component.NsUtilsDependImpl",
                "canShowScreenAd",
                "Object"
            ),
            deoptimize = true,
            body = {
                log.info("blocked fullscreen-ad gate: NsUtilsDependImpl.canShowScreenAd")
                false
            },
        )

        val iface = "com.dragon.read.ad.screen.IActivityScreenAdManager"
        val impls = resolver.findClassImplementingInterface(iface, "onScreenAdDialogShow")
        if (impls.isEmpty()) {
            log.warn("fullscreen-ad: DexKit found no $iface impl; only the depend gate is hooked")
            return
        }
        for (cls in impls) {
            val gates = runCatching {
                cls.declaredMethods.filter {
                    it.parameterCount == 0 &&
                        it.returnType == java.lang.Boolean.TYPE &&
                        !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
            }.getOrElse { t ->
                log.warn("fullscreen-ad: cannot enumerate ${cls.name} methods (${t.javaClass.simpleName})")
                emptyList()
            }
            if (gates.isEmpty()) {
                log.warn("fullscreen-ad: no zero-arg boolean gate found on ${cls.name}")
            }
            for (m in gates) {
                hooks.replaceBooleanFalse(
                    id = "fullscreen-ad:${cls.name}#${m.name}",
                    method = m,
                    deoptimize = true,
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Reader hooks
    //   NsAdImpl.needReadFlowAdLine(ReaderClient)Z
    //   NsAdImpl.canReaderVideoAdShow()Z
    //   ReaderAdManager.canLoadAd(String)Z
    // ─────────────────────────────────────────────────────────────────────────

    private fun installReaderHooks() {
        val nsAd = "com.dragon.read.component.biz.impl.NsAdImpl"

        hooks.replaceBooleanFalse(
            id = "read-flow-ad-line",
            method = resolver.findMethod(
                nsAd,
                "needReadFlowAdLine",
                "com.dragon.reader.lib.ReaderClient"
            ),
            deoptimize = true,
        )

        hooks.replaceBooleanFalse(
            id = "reader-video-ad",
            method = resolver.findMethod(nsAd, "canReaderVideoAdShow"),
        )

        hooks.replaceBooleanFalse(
            id = "reader-ad-for-sati",
            method = resolver.findMethod(
                "com.dragon.read.reader.ad.ReaderAdManager",
                "canLoadAd",
                "String"
            ),
            deoptimize = true,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. TopView hooks
    //   NsAdImpl.checkCanShowTopViewInMainPage(AbsActivity)Z
    //   NsAdImpl.checkCanShowTopViewInReader(AbsActivity, ReaderClient, String)Z
    // ─────────────────────────────────────────────────────────────────────────

    private fun installTopViewHooks() {
        val nsAd = "com.dragon.read.component.biz.impl.NsAdImpl"

        hooks.replaceBooleanFalse(
            id = "topview-main",
            method = resolver.findMethod(
                nsAd,
                "checkCanShowTopViewInMainPage",
                "com.dragon.read.base.AbsActivity"
            ),
        )

        hooks.replaceBooleanFalse(
            id = "topview-reader",
            method = resolver.findMethod(
                nsAd,
                "checkCanShowTopViewInReader",
                "com.dragon.read.base.AbsActivity",
                "com.dragon.reader.lib.ReaderClient",
                "String"
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Short-series pause-ad hooks
    //   SeriesPauseAdImpl.enablePauseAd()Z
    //   SeriesPauseAdImpl.canShowPauseAd(ti4.h)Z
    //
    //   `canShowPauseAd` takes an obfuscated interface (ti4.h / so4.h / vq4.i depending on
    //   version and host) as its single argument. The interface name changes between Fanqie
    //   releases, so we resolve by name + return type via [ClassResolver.findMethodIgnoringParams]
    //   to remain version-resilient.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installSeriesPauseHooks() {
        val pause = "com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl"

        hooks.replaceBooleanFalse(
            id = "series-pause-enable",
            method = resolver.findMethod(pause, "enablePauseAd"),
        )

        hooks.replaceBooleanFalse(
            id = "series-pause-show",
            method = resolver.findMethodIgnoringParams(pause, "canShowPauseAd", returnTypeName = "boolean"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Position filter (the "surgical" hook — most defensive)
    //
    //   NsAdImpl.checkAdAvailable(String position, String source)Z
    //   <NsAdConfigManagerApi impl>.checkAdAvailable(String, String)Z              (impl = h83.a in 73532)
    //
    //   Multiple call sites are hit. The second implementation lives on a class that implements
    //   `com.dragon.read.ad.manager.NsAdConfigManagerApi` and serves as the ad-config cache
    //   front-end. The implementation class is obfuscated (`fe3.a` in 73532, `lf3.a` in Fanqie
    //   73732, `yb3.a` in Hongguo 73732 — it is renamed every release), so we resolve it through
    //   DexKit by interface name.
    //   Hooking both gives defence-in-depth; the DexKit lookup degrades to a no-op if the bridge
    //   fails to initialise (logged WARN) or no impl class can be located.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installPositionFilter() {
        val nsAd = "com.dragon.read.component.biz.impl.NsAdImpl"
        installPositionFilterOn(nsAd)

        val impls = resolver.findClassImplementingInterface(
            interfaceName = "com.dragon.read.ad.manager.NsAdConfigManagerApi",
            methodName = "checkAdAvailable"
        )
        if (impls.isEmpty()) {
            log.warn("position-filter: DexKit found no NsAdConfigManagerApi impl; only NsAdImpl hooked")
        } else {
            for (cls in impls) {
                if (cls.name == nsAd) continue
                installPositionFilterOn(cls.name)
            }
        }
    }

    private fun installPositionFilterOn(className: String) {
        val method = resolver.findMethod(className, "checkAdAvailable", "String", "String")
        hooks.installBooleanFilter(
            id = "position-filter:$className",
            method = method,
            deoptimize = false,
            shouldBlock = { args ->
                val position = args.getOrNull(0)?.toString().orEmpty()
                val source = args.getOrNull(1)
                // 零广告：checkAdAvailable 问的是「这个位置现在能不能出广告」，一律回答「不能」，
                // 于是任何广告位都拿不到广告。不再区分被动位 / 用户主动的激励金币位 / 未知位，
                // 也不再需要维护拦截名单——宿主以后新增广告位同样自动被拦。
                //
                // 只影响本查询的返回值，不触碰任何权益数据、会员状态或服务端校验的东西。
                // 代价：用户主动点的「看视频得金币 / 免广告 / 催更 / 解锁章节」等入口会失效，
                // 因为那里已经无广告可播。
                //
                // 日志仍按来源分类，便于回看拦到了什么：
                //   (passive)  名单里的被动广告位
                //   (reward)   原本保留的激励/金币位
                //   (unnamed)  两边名单都没有的新位置
                val kind = when {
                    position in BLOCKED_POSITIONS -> "passive"
                    position in PRESERVED_POSITIONS -> "reward"
                    else -> "unnamed"
                }
                log.info(
                    "blocked ad position=$position source=$source " +
                        "kind=$kind via $className.checkAdAvailable"
                )
                true
            }
        )
    }

    // 13. 界面净化（隐藏骚扰板块）
    //
    //   只隐藏"这个入口显不显示"的判断，不碰任何权益、会员、内容数据——与第 5 节的
    //   VIP 入口隐藏同一性质，属于纯 UI 取舍。
    //
    //   搜索页的两个 AI 入口由 ssconfig 模板承载，模板字段是 public final，改不了；
    //   但它们各自被 FanqieSearchActivity 上一个**无参 boolean 方法**读取，把那个方法
    //   改成返回 false 就等于"这个入口不显示"：
    //     模板字段 showFloatButton    ← FanqieSearchActivity#q1()Z   （73967）
    //     模板字段 entryInBanner      ← FanqieSearchActivity#Y1()Z
    //     模板字段 entryInSearchBox   ← FanqieSearchActivity#Z1()Z
    //   三个方法名都是混淆名、会随版本换字母（和 ExperimentUtil 那次的坑一模一样），
    //   所以一律走字段反查定位而不是硬编码方法名；命中不唯一时反查会返回 null 并打 WARN，
    //   宁可少隐藏一个入口也不挂错方法。
    //
    //   静态核验（73967）：三个字段的读取者各自唯一——模板自身的 <init> 加上述一个方法；
    //   按 declaredClass=FanqieSearchActivity 过滤后每个字段恰好一个命中。
    // ─────────────────────────────────────────────────────────────────────────

    private fun installUiPurifyHooks() {
        val searchActivity = "com.dragon.read.component.biz.impl.FanqieSearchActivity"

        // 搜索页 AI 悬浮球
        hooks.replaceBooleanFalse(
            id = "purify-search-ai-float-button",
            method = resolver.findNoArgBooleanGetterReadingField(searchActivity, "showFloatButton"),
            knownMissingOnMiss = true,
        )
        // 搜索页 banner 里的 AI 入口
        hooks.replaceBooleanFalse(
            id = "purify-search-ai-banner-entry",
            method = resolver.findNoArgBooleanGetterReadingField(searchActivity, "entryInBanner"),
            knownMissingOnMiss = true,
        )
        // 搜索框里的 AI 入口
        hooks.replaceBooleanFalse(
            id = "purify-search-ai-box-entry",
            method = resolver.findNoArgBooleanGetterReadingField(searchActivity, "entryInSearchBox"),
            knownMissingOnMiss = true,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. VIP entrance hooks
    //   NsVipImpl.canShowVipEntranceHere(VipEntrance)Z
    //   NsVipImpl.canShowVipEntranceInAd()Z
    //
    //   Cosmetic only: hides VIP upsell entry points; does NOT touch entitlement data, VipInfoModel,
    //   or any server-validated VIP flag.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installVipEntranceHooks() {
        val nsVip = "com.dragon.read.component.biz.impl.NsVipImpl"

        hooks.replaceBooleanFalse(
            id = "hide-vip-entrance",
            method = resolver.findMethod(
                nsVip,
                "canShowVipEntranceHere",
                "com.dragon.read.component.biz.api.data.VipEntrance"
            ),
        )

        hooks.replaceBooleanFalse(
            id = "hide-vip-entrance-in-ad",
            method = resolver.findMethod(nsVip, "canShowVipEntranceInAd"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. ReaderAdManager extended hooks
    //   needInterceptFetchAd(String)Z
    //
    //   When `canLoadAd` already returns false, `needInterceptFetchAd` is the second line of defence
    //   that decides whether to actually issue the network request. Hooking it is safer than hooking
    //   the request layer because we still let non-passive code paths fall through.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installReaderAdManagerHooks() {
        hooks.replaceBooleanTrue(
            id = "reader-fetch-intercept",
            method = resolver.findMethod(
                "com.dragon.read.reader.ad.ReaderAdManager",
                "needInterceptFetchAd",
                "String"
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Inspire / reward hooks
    //
    //   These methods live on NsAdImpl and control whether an "inspire"-style ad surfaces.
    //   We DO NOT blanket-disable them (reward/coin flows rely on them); we only force the
    //   passive "isXxxAvailable" flags to false so the entry-point UI hides the slot.
    //
    //   If a method name turns out not to exist on NsAdImpl in a future version, [resolver.findMethod]
    //   returns null and [HookManager.replaceBooleanFalse] logs a WARN. No silent skip.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installInspireAdHooks() {
        val nsAd = "com.dragon.read.component.biz.impl.NsAdImpl"

        // Hide the "no-ad gift" UI banner. Does NOT disable user-initiated rewards.
        hooks.replaceBooleanTrue(
            id = "inspire-disable-ad-gift",
            method = resolver.findMethod(nsAd, "disableAdGift"),
        )

        // Disable banner dismiss animation, which is purely cosmetic and tightly bound to ad UX.
        hooks.replaceBooleanTrue(
            id = "inspire-disable-banner-dismiss-anim",
            method = resolver.findMethod(nsAd, "disableBannerDismissAnimation"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Experimental splash attribution hook
    //
    //   AttributionManager.hasHitAttribution()Z
    //
    //   Defaults to OFF. Splash attribution is the channel by which the splash ad tracks
    //   installation source. Returning false skips it, but the splash ad may still show.
    //   Enable only if you understand the compliance implication.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installExperimentalSplashHook() {
        if (!ENABLE_ATTRIBUTION_SPLASH_BYPASS) return
        hooks.replaceBooleanFalse(
            id = "experimental-splash-attribution",
            method = resolver.findMethod(
                "com.dragon.read.pages.splash.AttributionManager",
                "hasHitAttribution"
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Audio-book ad hooks (听书贴片广告)
    //
    //   NsAdImpl.enableRequestAudioInfoFlowAd()Z
    //   NsAdImpl.enableRequestAudioPatchAd()Z
    //
    //   Both are thin delegates: they read `BsAudioAdService.IMPL` and call the
    //   interface method if the service is present, else return false. Call-site analysis
    //   (invoke-site counting, interface dispatch included) shows BsAudioAdService's two
    //   methods are invoked ONLY from NsAdImpl — no business code calls the service
    //   singleton directly, so hooking NsAdImpl is a complete entry-point cut.
    //
    //   These two gates decide whether 听书 audio flow and audiobook patch slots are wired
    //   up to the ad SDK. The audio module uses its own position namespace, so in addition
    //   to these two switches the positions `audio_info_flow_ad` / `audio_patch_ad` are
    //   listed in [BLOCKED_POSITIONS] as a second line of defence.
    //   Forcing both to false cuts the audio-book ad pipeline at the entry point without
    //   touching reward / coin flows. Same resilience pattern as section 7 — if either
    //   method is renamed in a future build, [resolver.findMethod] returns null and the
    //   hook is skipped with a WARN log; nothing else is affected.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installAudioAdHooks() {
        val nsAd = "com.dragon.read.component.biz.impl.NsAdImpl"

        hooks.replaceBooleanFalse(
            id = "audio-info-flow-ad",
            method = resolver.findMethod(nsAd, "enableRequestAudioInfoFlowAd"),
            deoptimize = true,
        )

        hooks.replaceBooleanFalse(
            id = "audio-patch-ad",
            method = resolver.findMethod(nsAd, "enableRequestAudioPatchAd"),
            deoptimize = true,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Short-series ad hooks (红果短剧 / com.phoenix.read 侧)
    //
    //   Targeted at the Hongguo-only ad slots on the dragon baseline (versionCode 73532).
    //   A single `AdHooks` instance covers both Fanqie (`com.dragon.read`) and Hongguo
    //   (`com.phoenix.read`): classes absent on the Fanqie side resolve to null via
    //   [ClassResolver.findMethod] and [HookManager.replaceBooleanFalse] logs a WARN.
    //
    //   Hook points (validated against the Hongguo APK):
    //     - SeriesBannerAdConfig.enableBanner()Z / enableSdkSettings()Z
    //       (implements ISeriesBannerAdConfig) — short-series banner ad master switch.
    //     - HongguoBannerServiceImpl.enableShortSeriesAdJoinRevert()Z
    //       (implements BsBannerService) — Hongguo-specific banner service.
    //     - ExperimentUtil.p()Z / q0()Z — multi-series flow ad master switch and
    //       landscape insert ad switch (read from SeriesAdConfig / ShortSeriesLandscapeInsertAdConfig).
    //       NOTE: previously hardcoded `p0()` which does NOT exist in v7.3.5.32 (fixed to `p()`).
    // ─────────────────────────────────────────────────────────────────────────

    private fun installShortSeriesAdHooks() {
        // 短剧 banner 广告（红果 / com.phoenix.read 侧）
        hooks.replaceBooleanFalse(
            id = "series-banner-enable",
            method = resolver.findMethod(
                "com.dragon.read.ad.onestop.seriesbanner.config.SeriesBannerAdConfig",
                "enableBanner"
            ),
            deoptimize = true,
        )
        hooks.replaceBooleanFalse(
            id = "series-banner-sdk-settings",
            method = resolver.findMethod(
                "com.dragon.read.ad.onestop.seriesbanner.config.SeriesBannerAdConfig",
                "enableSdkSettings"
            ),
        )
        // 红果专属 banner 服务（番茄侧没有这个类，缺失属于正常，不计入「丢失」）
        hooks.replaceBooleanFalse(
            id = "hongguo-banner-join-revert",
            method = resolver.findMethod(
                "com.dragon.read.ad.banner.impl.HongguoBannerServiceImpl",
                "enableShortSeriesAdJoinRevert"
            ),
            knownMissingOnMiss = true,
        )
        // 短剧广告总开关 + 横屏插入广告开关
        //
        // 两个开关原本硬编码为 `ExperimentUtil#p()` / `ExperimentUtil#q0()`，但混淆名宿主
        // 每次升级都可能换——实测 73732 的 `q0()Z` 在 73917 改名 `s0()Z`，原 `q0` 现在
        // 返回 `long`，不再是这个开关。继续硬编码就要每次升级后重新对字母。
        //
        // 改用「按读取的稳定配置字段反查 getter」：字段名是业务名，跨版本不变；ClassResolver
        // 用 DexKit 跑查询，唯一命中即用，多命中/不命中都返回 null 并 WARN（绝不在这里猜）。
        // DexKit 不可用时回退按名字查——老版本仍能命中；73917 上 `q0` 解析为 long 方法，按
        // `replaceBooleanFalse` 的返回类型检查自然跳过，至少不会挂错开关。
        hooks.replaceBooleanFalse(
            id = "short-series-ad-enable",
            method = resolver.findNoArgBooleanGetterReadingField(
                "com.dragon.read.reader.ad.experiment.ExperimentUtil",
                "enableMultiSeriesFlowAd"
            ) ?: resolver.findMethod(
                "com.dragon.read.reader.ad.experiment.ExperimentUtil",
                "p"
            ),
            deoptimize = true,
        )
        hooks.replaceBooleanFalse(
            id = "short-series-landscape-insert-ad",
            method = resolver.findNoArgBooleanGetterReadingField(
                "com.dragon.read.reader.ad.experiment.ExperimentUtil",
                "landscapeInsertAdEnable"
            ) ?: resolver.findMethod(
                "com.dragon.read.reader.ad.experiment.ExperimentUtil",
                "q0"
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Splash-ad bypass (开屏广告)
    //
    //   Hongguo shows an interstitial splash ad when returning to the app (hot start)
    //   via OpeningScreenADActivity. Reverse engineering confirmed:
    //     - The flow does NOT route through the boolean switches above nor the position
    //       filter ("splash_ad" strings in SplashHelper are telemetry only).
    //     - `NsAdImpl.openOpeningScreenAdActivity(Context, PageRecorder)V` is the launch
    //       entry (dispatched from NsAppNavigator through NsAdApi).
    //     - OpeningScreenADActivity mounts the ad views via showBrandAdView /
    //       showImcSplashView / showNaturalAdView(View)V.
    //
    //   Strategy: no-op the launch entry (main cut) plus the three view-mounting methods
    //   (belt-and-braces in case some other path starts the activity). All four targets
    //   exist on both Fanqie and Hongguo (same 73532 baseline); a missing target degrades
    //   to a WARN like every other hook.
    // ─────────────────────────────────────────────────────────────────────────

    private fun installSplashAdHooks() {
        val nsAd = "com.dragon.read.component.biz.impl.NsAdImpl"
        val splashActivity = "com.dragon.read.ad.openingscreenad.OpeningScreenADActivity"

        // 阻断热启动开屏广告 Activity 的启动入口（void no-op）
        hooks.install(
            id = "splash-ad-activity-open",
            method = resolver.findMethod(
                nsAd,
                "openOpeningScreenAdActivity",
                "android.content.Context",
                "com.dragon.read.report.PageRecorder"
            ),
            deoptimize = true,
            body = { /* 不调用 proceed，直接阻断 */ },
        )
        // 双保险：即使 Activity 被其他途径拉起，广告 View 也不会挂载
        hooks.install(
            id = "splash-ad-brand-view",
            method = resolver.findMethod(splashActivity, "showBrandAdView", "android.view.View"),
            body = { /* no-op */ },
        )
        hooks.install(
            id = "splash-ad-imc-view",
            method = resolver.findMethod(splashActivity, "showImcSplashView", "android.view.View"),
            body = { /* no-op */ },
        )
        hooks.install(
            id = "splash-ad-natural-view",
            method = resolver.findMethod(splashActivity, "showNaturalAdView", "android.view.View"),
            body = { /* no-op */ },
        )

        // ── 番茄侧的品牌开屏（补充闸门）──────────────────────────────────────
        //
        // 下面三个 OpeningScreenADActivity 的 hook 只覆盖**红果**的 Activity 路径。番茄实测
        // 不走它：热启动时启动的是 `com.dragon.read/.pages.splash.SplashActivity`。
        //
        // 番茄开屏的真正「根闸」在 installFullScreenAdHooks()（NsUtilsDependImpl.canShowScreenAd
        // + IActivityScreenAdManager 判定方法），实测已能拦住。下面这两个是针对品牌 TopView
        // 的补充闸门，用于覆盖根闸之外的品牌位展示路径（同样只针对被动展示，不影响用户主动激励）：
        //   - BrandTopViewDisplayStrategy.c(AbsActivity)Z：调用点反汇编可见其依次校验
        //     Activity 未 finishing、有网络、非基础模式、checkAdAvailable("splash_ad","Brand") 后
        //     返回是否展示品牌 TopView；强制 false 即不展示。
        //   - enableSeriesFeedTopViewAd()Z：系列 / 单列 TopView 的总开关。
        hooks.replaceBooleanFalse(
            id = "splash-brand-topview-strategy",
            method = resolver.findMethod(
                "com.dragon.read.ad.splash.BrandTopViewDisplayStrategy",
                "c",
                "com.dragon.read.base.AbsActivity"
            ),
            deoptimize = true,
        )
        hooks.replaceBooleanFalse(
            id = "splash-enable-series-feed-topview",
            method = resolver.findMethod(nsAd, "enableSeriesFeedTopViewAd"),
            deoptimize = true,
        )
    }

    private companion object {
        // Passively displayed positions; USER-INITIATED reward / coin positions are intentionally
        // absent. Additions must be justified by a call-site analysis of
        // `checkAdAvailable(position, source)` — see the derivation note further down.
        //
        // Audited against both supported versionCodes (73532 and 73732): every string below is
        // present in both APK string pools, so the filter keeps matching after an app update.
        //
        // `topview_main` / `topview_reader` used to be listed here, but neither string exists in
        // ANY version's string pool — they could never match. TopView is cut structurally instead,
        // by forcing NsAdImpl.checkCanShowTopViewInMainPage / checkCanShowTopViewInReader to false
        // (both verified to still have live call sites in 73732). Removing the dead entries changes
        // no behaviour.
        //
        // The second group below was derived by constant-flow analysis rather than by reading the
        // string pool: every position constant that actually reaches `checkAdAvailable` (including
        // through pass-through wrappers) was extracted, then each call site was disassembled to
        // classify it as a passive slot or a user-initiated flow. Both versionCodes reach the gate
        // with the same 29 constants, i.e. this was a long-standing coverage gap and not a
        // 7.3.7.32 regression. The gate is demonstrably live in production:
        //   "[INFO] blocked ad position=splash_ad source=Brand via lf3.a.checkAdAvailable"
        val BLOCKED_POSITIONS = setOf(
            // ── reader / main-page slots (v0.1 set) ──────────────────────────────
            "splash_ad",
            "page_front_ad",
            "page_middle_ad",
            "page_end_ad",
            "reader_banner",
            "reader_text_link_ad",
            "reader_disconnected_ad",
            "reader_ad_for_sati",
            "video_reader_ad",
            "series_pause_ad",
            // ── slots added in v0.6.0 from constant-flow evidence ───────────────
            // 评论列表原生广告（NscommunityadImpl.isSatisfyFreq 频控前置检查）
            "comment_list_ad",
            // 短剧评论广告（r63.b / l63.d）
            "series_comment_ad",
            // 故事 / 短篇插页广告（StoryAdController.tryTriggerStoryAdInsert）
            "story_ad",
            // 创作者广告（com.dragon.read.ad.util.s0 → Args 构造）
            "creator_ad",
            // 短视频进度条插入广告（a93.p；埋点名为 pos=progress_ad）
            "processed_ad",
            // 横屏短剧插入广告 / 横屏短剧暂停广告（w73.k、h83.a）
            "landscape_short_series_ad",
            "landscape_short_series_pause_ad",
            // 短剧信息流广告与短剧 banner（t83.l、BannerDependImpl.canRequestSeriesBanner）
            "short_series_ad",
            "short_series_banner",
            // 听书信息流 / 贴片广告（AudioAdManager.checkInfoFlowAdAvailable / checkPatchAdAvailable）
            "audio_info_flow_ad",
            "audio_patch_ad",
            // ── v0.8.2：红果推荐流广告 ──────────────────────────────────────────
            // 真机实测红果启动后走到 `checkAdAvailable("recommend_video", null)`，
            // 此前被记为 unlisted（未拦截）。定位依据：
            //   - 与 landscape_short_series_ad / series_comment_ad / story_ad /
            //     short_series_banner 等 8 个**已拦截**位置同在 checkAdAvailableByAbTest
            //     的同一张 switch 表里（番茄 lf3.a#b 与红果 yb3.a#b 均有，且偏移一致）
            //   - 调用点 e43.f 操作 OneStopAdModel（onVisible / onHolderSelected），
            //     属于信息流被动广告位，不是用户主动触发的激励流程
            // 两个宿主共用同一条常量，故只登记一份。
            "recommend_video"
        )

        /**
         * Positions that are **user-initiated reward / coin surfaces** — the user taps something
         * like "看视频得金币", "看视频免广告", "看视频解锁章节" and thereby asks for a video.
         *
         * These are **also blocked now**: the position filter answers "no ad available" for every
         * position (零广告), so this set no longer changes what gets blocked. It is kept as the
         * record of the call-site analysis and is still used to tag the log line with `kind=reward`
         * so a block caused by a user tapping a reward entry is distinguishable afterwards.
         *
         * Analysed call sites:
         *   - `reader_gold_coin_popup` — 金币弹窗
         *   - `video_tts_ad` / `video_voice_ad` — 听书激励入口（AudioInspireUtil.adUnavailable）
         *   - `video_reward_gift_ad` — 激励视频礼包
         *   - `video_reader_end_urge_update` — 看视频催更
         *   - 73967 复核补入的 9 个 video_* 见集合内注释
         */
        val PRESERVED_POSITIONS = setOf(
            "reader_gold_coin_popup",
            "video_tts_ad",
            "video_voice_ad",
            "video_reward_gift_ad",
            "video_reader_end_urge_update",
            // v0.8.2：把 checkAdAvailableByAbTest 表里剩下的金币 / 奖励位一次性登记齐全。
            // 这些名字里带 coin / reward，语义无歧义，都是"看广告换金币/奖励"的**用户主动**
            // 流程。登记进来是为了让意图显式化，而不是让它们绕过日志分类。
            "gold_coin_reward_box_other",
            "gold_coin_reward_box_welfare",
            "gold_coin_reward_dialog_ad_audio_page",
            "gold_coin_reward_dialog_ad_general",
            "gold_coin_reward_dialog_ad_open_treasure",
            "video_gold_coin_reward_dialog_audio_page",
            "video_gold_coin_reward_dialog_general",
            "video_gold_coin_reward_dialog_open_treasure",
            "listen_coin",
            "video_coin_ad",
            // 73967 复核：把 AB 表 (checkAdAvailableByAbTest) 里剩下未分类的 9 个 video_*
            // 一次性登记。判据是调用点证据，不是名字：
            //   - video_chapter_front  ← FanqieRewardAdRequestConfigServiceImpl（激励广告请求配置）
            //   - video_book_download  ← NsVipImpl#evaluateBookDownloadPrivilege（VIP 特权评估）
            //   - 其余 7 个只被配置查询引用（eg3.a#b/e、fs1.a#b/d、mv1.b#m），
            //     与 reader_gold_coin_popup 等同一族
            // 即「看视频解锁/换取某功能」的用户主动流程。
            "video_chapter_front",
            "video_chapter_middle",
            "video_book_download",
            "video_comic_book_download",
            "video_reader_ad_free_dialog",
            "video_reader_auto_page_turn",
            "video_reader_offline_reading",
            "video_reading_latest_chapter",
            "video_short_story"
        )

        // Splash attribution is OFF by default. Flipping this to true causes AttributionManager
        // to skip install-source reporting, which may affect compliance. Review before shipping.
        const val ENABLE_ATTRIBUTION_SPLASH_BYPASS = false
    }
}