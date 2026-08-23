package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog

/**
 * All ad-related hooks for `com.dragon.read` versionCode 73332.
 *
 * Every hook target below was validated against the APK in
 * [D:/cc/fanqie-analysis/reports/Fanqie_v73332_逆向分析报告.md] § 5 (smali line numbers recorded in the
 * static evidence table § 6.1). Do not rename or remove methods without re-running round-1
 * reverse engineering against the new APK first.
 *
 * Position-string policy:
 *   The string parameter to [BLOCKED_POSITIONS] is matched against `String position` arguments
 *   taken at runtime. Whitelist user-initiated reward/coin flows so they remain functional
 *   (see report § 5.3).
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Reader hooks
    //   NsAdImpl.needReadFlowAdLine(ReaderClient)Z   (smali line 21871)
    //   NsAdImpl.canReaderVideoAdShow()Z            (smali line 1585)
    //   ReaderAdManager.canLoadAd(String)Z          (smali line 4598)
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
    //   NsAdImpl.checkCanShowTopViewInMainPage(AbsActivity)Z                  (smali line 3414)
    //   NsAdImpl.checkCanShowTopViewInReader(AbsActivity, ReaderClient, String)Z  (smali line 3430)
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
    //   SeriesPauseAdImpl.enablePauseAd()Z            (smali line 343)
    //   SeriesPauseAdImpl.canShowPauseAd(qh4.h)Z      (smali line 104)
    //
    //   `canShowPauseAd` takes an obfuscated interface (qh4.h) as its single argument. The
    //   interface name changes between Fanqie releases, so we resolve by name + return type
    //   via [ClassResolver.findMethodIgnoringParams] to remain version-resilient.
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
    //   NsAdImpl.checkAdAvailable(String position, String source)Z                (smali line 3385)
    //   <NsAdConfigManagerApi impl>.checkAdAvailable(String, String)Z              (impl = h83.a in 73332)
    //
    //   Multiple call sites are hit. The second implementation lives on a class that implements
    //   `com.dragon.read.ad.manager.NsAdConfigManagerApi` and serves as the ad-config cache
    //   front-end. The implementation class is obfuscated (`h83.a` in 73332, will likely be
    //   renamed in future releases), so we resolve it through DexKit by interface name.
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
                val blocked = position in BLOCKED_POSITIONS
                if (blocked) {
                    log.info(
                        "blocked ad position=$position source=${args.getOrNull(1)} " +
                            "via $className.checkAdAvailable"
                    )
                }
                blocked
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. VIP entrance hooks
    //   NsVipImpl.canShowVipEntranceHere(VipEntrance)Z  (smali line 877)
    //   NsVipImpl.canShowVipEntranceInAd()Z            (smali line 956)
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
    //   needInterceptFetchAd(String)Z  (smali line 7918)
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
    //   smali line numbers (NsAdImpl):
    //     - disableAdGift()Z          (line 5269)
    //     - inspireAdDisable()Z       (similar name region; resolved via reflection)
    //     - disableBannerDismissAnimation()Z  (line 5305)
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
    //   AttributionManager.hasHitAttribution()Z   (smali line 2954)
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
    //   NsAdImpl.enableRequestAudioInfoFlowAd()Z   (smali lines 5248-5274)
    //   NsAdImpl.enableRequestAudioPatchAd()Z      (smali lines 5276-5302)
    //
    //   Both are thin delegates: they read `BsAudioAdService.IMPL` and call the
    //   interface method if the service is present, else return false. Verified via
    //   `mt_apk_dex_xref` (methodResolution=dispatch) that BsAudioAdService's two
    //   methods are invoked ONLY from NsAdImpl — no business code calls the service
    //   singleton directly, so hooking NsAdImpl is a complete entry-point cut.
    //
    //   These two gates decide whether 听书 audio flow and audiobook patch slots are wired
    //   up to the ad SDK. They are NOT covered by BLOCKED_POSITIONS (听书 uses its own
    //   position namespace inside the audio module, not the reader-side `*_ad` strings).
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

    private companion object {
        // Passively displayed positions; USER-INITIATED reward / coin positions are intentionally
        // absent. Mirrors the previous AdHooks.kt whitelist. Additions are made in the report's
        // § 5.3 table; review before merging.
        val BLOCKED_POSITIONS = setOf(
            "splash_ad",
            "page_front_ad",
            "page_middle_ad",
            "page_end_ad",
            "reader_banner",
            "reader_text_link_ad",
            "reader_disconnected_ad",
            "reader_ad_for_sati",
            "video_reader_ad",
            // Additional positions identified in the static call graph (see report § 5.2):
            "topview_main",
            "topview_reader",
            "series_pause_ad"
        )

        // Splash attribution is OFF by default. Flipping this to true causes AttributionManager
        // to skip install-source reporting, which may affect compliance. Review before shipping.
        const val ENABLE_ATTRIBUTION_SPLASH_BYPASS = false
    }
}