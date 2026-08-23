package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.config.ModuleConfig
import dev.operit.fanqiehook.support.HookSupport
import dev.operit.fanqiehook.support.Reflect

/**
 * Local-VIP impersonation + instant reward + reading-time multiplier.
 *
 * VIP chain (all four layers must agree, otherwise the app shows "会员已过期"):
 *  1. AcctManager.userModel — patch the logged-in user model in place: freeAd flags,
 *     adVipAvailable, vipInfo/vipInfoList (4 sub-types), vipProfileShow.
 *  2. AcctManager.P() singleton getter — re-apply the patch after every refresh.
 *  3. VipInfoModel constructor — 8-arg ctor forced to far-future expire + isVip.
 *  4. PrivilegeManager — 14 gate methods forced true (isVip/isNoAd/hasPrivilege/...).
 *  5. NsVipImpl display gates forced true so the VIP centre renders consistently.
 *
 * Instant reward: ExcitingVideoFragment.onCreateView → immediately closeFragment(true),
 * which the SDK treats as "video finished" and pays out without playing.
 *
 * Reading time: ReadingTiming.a(long, boolean) — the first (duration) argument is
 * multiplied by the configured factor before proceeding.
 *
 * 所有钩子无条件装配；每个回调内实时读取对应开关（[ModuleConfig]），
 * 关闭时按原逻辑放行 —— 开关改动免重启即时生效。
 */
object VipHooks {

    private const val TAG = "Vip"

    /** Far-future timestamp (ms) used for all fake expire fields. */
    private const val FAKE_EXPIRE = "218342534400"

    private const val ACCT = "com.dragon.read.user.AcctManager"
    private const val VIP_INFO_MODEL = "com.dragon.read.user.model.VipInfoModel"
    private const val VIP_SUB_TYPE = "com.dragon.read.rpc.model.VipCommonSubType"
    private const val PRIVILEGE = "com.dragon.read.component.biz.impl.privilege.PrivilegeManager"

    fun installAll() {
        installLocalVip()
        installInstantReward()
        installReadingTime()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local VIP
    // ─────────────────────────────────────────────────────────────────────────

    private fun installLocalVip() {
        installAcctManagerPatches()
        installVipInfoModelCtor()
        installPrivilegeManager()
        installVipDisplayGates()
    }

    private fun installAcctManagerPatches() {
        // Singleton getter: patch the user model after each (re)initialisation.
        HookSupport.safeHook(TAG, ACCT, "g", null, "本地VIP-单例g") { chain ->
            val result = chain.proceed()
            if (ModuleConfig.localVip()) applyUserModelPatch(result)
            result
        }

        // Direct model getters — return fully fake objects when logged in.
        HookSupport.safeHook(TAG, ACCT, "getVipInfo", null, "本地VIP-getVipInfo") { chain ->
            if (!ModuleConfig.localVip()) return@safeHook chain.proceed()
            val fake = buildVipInfo()
            if (fake != null) fake else chain.proceed()
        }
        HookSupport.safeHook(TAG, ACCT, "getVipProfileShow", null, "本地VIP-getVipProfileShow") { chain ->
            if (!ModuleConfig.localVip()) return@safeHook chain.proceed()
            val fake = buildVipProfileShow()
            if (fake != null) fake else chain.proceed()
        }

        // Simple boolean/numeric gates on AcctManager itself.
        mapOf(
            "isFreeAd" to true,
            "adVipAvailable" to true,
            "getFreeAdDay" to 100000.0f,
            "getFreeAdExpire" to FAKE_EXPIRE.toLong(),
            "getFreeAdLeft" to FAKE_EXPIRE.toLong(),
        ).forEach { (name, value) ->
            HookSupport.safeHook(TAG, ACCT, name, null, "本地VIP-$name") { chain ->
                if (ModuleConfig.localVip()) value else chain.proceed()
            }
        }
    }

    /** Patch the userModel object inside an AcctManager instance (MK's applyUserModel). */
    private fun applyUserModelPatch(acct: Any?) {
        if (acct == null) return
        try {
            val model = Reflect.on(acct).field("userModel").get() ?: return
            val logged = Reflect.on(acct).call("islogin").getBoolean()
            if (!logged) return
            modifyUserModel(model)
        } catch (t: Throwable) {
            // silent — patching is best-effort
        }
    }

    private fun modifyUserModel(model: Any) {
        val r = Reflect.on(model)
        r.set("adVipAvailable", true)
        r.set("freeAd", true)
        r.set("freeAdDay", 100000.0f)
        r.set("freeAdExpire", FAKE_EXPIRE.toLong())
        r.set("freeAdLeft", FAKE_EXPIRE.toLong())
        r.set("isOfficialCert", true)
        r.set("canSetUsernamePrivilege", true)
        r.set("needSetUserinfoPrivilege", false)
        r.set("hasPublishTopicPrivilege", true)
        r.set("vipLastExpiredTime", FAKE_EXPIRE)

        buildVipInfo()?.let { vipInfo ->
            r.set("vipInfo", vipInfo)
            // vipInfoList needs one entry per sub-type; reuse the same object with
            // subType swapped (MK does the same).
            val list = ArrayList<Any>()
            for (sub in listOf("ShortStory", "Publish", "AdFree", "Default")) {
                val subVal = Reflect.onClass(VIP_SUB_TYPE).enumValue(sub).get()
                if (subVal != null) {
                    Reflect.on(vipInfo).set("subType", subVal)
                    list.add(vipInfo)
                }
            }
            r.set("vipInfoList", list)
        }

        buildVipProfileShow()?.let { r.set("vipProfileShow", it) }
    }

    private fun buildVipInfo(): Any? {
        val r = Reflect.onClass("com.dragon.read.rpc.model.VipInfo")
        val cls = r.get() as? Class<*> ?: return null
        val instance = try {
            cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (t: Throwable) {
            return null
        }
        val v = Reflect.on(instance)
        v.set("expireTime", FAKE_EXPIRE)
        v.set("isVip", "1")
        v.set("isAdVip", true)
        v.set("isUnionVip", true)
        v.set("leftTime", FAKE_EXPIRE)
        v.set("autoRenew", true)
        Reflect.onClass("com.dragon.read.rpc.model.VipRenewType").enumValue("VipRenewYear")
            .get()?.let { v.set("renewType", it) }
        v.set("continueMonth", true)
        v.set("continueMonthBuy", true)
        Reflect.onClass(VIP_SUB_TYPE).enumValue("Default").get()?.let { v.set("subType", it) }
        v.set("unionSource", 1)
        return instance
    }

    private fun buildVipProfileShow(): Any? {
        // 注意：VipProfileShow 在 73332 已迁移到 com.dragon.read.rpc.model.VipProfileShow，
        // 不再位于 com.dragon.read.user.model。
        val r = Reflect.onClass("com.dragon.read.rpc.model.VipProfileShow")
        val cls = r.get() as? Class<*> ?: return null
        return try {
            cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (t: Throwable) {
            null
        }
    }

    /** VipInfoModel 8-arg constructor: force the fake values regardless of caller. */
    private fun installVipInfoModelCtor() {
        // 当前 APK (73332) 中 VipInfoModel 构造签名：
        //   (String expireTime, String isVip, String leftTime,
        //    boolean isAdVip, boolean isUnionVip, int unionSource,
        //    boolean isAutoCharge, VipCommonSubType subType)
        // 原代码把第二个 String 当作"version"，实际是 isVip 字段。
        HookSupport.safeHookCtor(
            TAG, VIP_INFO_MODEL,
            arrayOf(
                "java.lang.String",   // expireTime
                "java.lang.String",   // isVip (String, 不是 boolean)
                "java.lang.String",   // leftTime
                "boolean",            // isAdVip
                "boolean",            // isUnionVip
                "int",                // unionSource
                "boolean",            // isAutoCharge
                VIP_SUB_TYPE,         // subType
            ),
            "本地VIP-VipInfoModel构造"
        ) { chain ->
            if (!ModuleConfig.localVip()) return@safeHookCtor chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = FAKE_EXPIRE
            args[1] = "1"               // isVip = "1"
            args[2] = FAKE_EXPIRE
            args[3] = true              // isAdVip
            args[4] = true              // isUnionVip
            args[5] = 1                 // unionSource
            args[6] = true              // isAutoCharge
            Reflect.onClass(VIP_SUB_TYPE).enumValue("Default").get()?.let { args[7] = it }
            chain.proceed(args)
        }
    }

    /** PrivilegeManager: force all 14 privilege gates true. */
    private fun installPrivilegeManager() {
        val zeroArgGates = listOf(
            "canShowVipRelational", "isAnyVip", "hasNoAdFollAllScene",
            "hasNoAdForShortSeries", "hasNoAdPrivilege", "hasNoAdReadConsumptionPrivilege",
            "isForeverNoAd", "isVip", "showPayVipEntranceInChapterEnd",
        )
        for (m in zeroArgGates) {
            HookSupport.safeHook(TAG, PRIVILEGE, m, null, "本地VIP-特权/$m") { chain ->
                if (ModuleConfig.localVip()) true else chain.proceed()
            }
        }
        for (m in listOf("isNoAd", "hasPrivilege")) {
            HookSupport.safeHook(TAG, PRIVILEGE, m, arrayOf("java.lang.String"),
                "本地VIP-特权/$m(String)") { chain ->
                    if (ModuleConfig.localVip()) true else chain.proceed()
                }
        }
        HookSupport.safeHook(TAG, PRIVILEGE, "isBookAdFree", arrayOf("java.lang.String"),
            "本地VIP-特权/isBookAdFree") { chain ->
                // isBookAdFree 返回 I（int），0=有广告，1=免广告，>1 视为免广告。
                // 原代码错返 true（boolean），在 native 桥下会被 autobox 成 Integer，
                // 且语义混乱，改为返 1。
                if (ModuleConfig.localVip()) 1 else chain.proceed()
            }

        // updateVipInfo(VipInfoModel, boolean): inject fake model first, then proceed.
        HookSupport.safeHook(TAG, PRIVILEGE, "updateVipInfo",
            arrayOf(VIP_INFO_MODEL, "boolean"), "本地VIP-updateVipInfo注入") { chain ->
                if (!ModuleConfig.localVip()) return@safeHook chain.proceed()
                val args = chain.args.toTypedArray()
                fakeVipInfoModel()?.let { args[0] = it }
                chain.proceed(args)
            }

        // getInstance(): patch the singleton's internal vipInfoModel after creation.
        HookSupport.safeHook(TAG, PRIVILEGE, "getInstance", null, "本地VIP-特权单例") { chain ->
            val result = chain.proceed()
            if (ModuleConfig.localVip()) injectFakeVipInfo(result)
            result
        }
    }

    private fun fakeVipInfoModel(): Any? {
        val subType = Reflect.onClass(VIP_SUB_TYPE).enumValue("Default").get() ?: return null
        return try {
            val cls = HookSupport.dragonLoader().loadClass(VIP_INFO_MODEL)
            cls.getConstructor(
                java.lang.String::class.java, java.lang.String::class.java,
                java.lang.String::class.java, java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE, java.lang.Integer.TYPE,
                java.lang.Boolean.TYPE, HookSupport.dragonLoader().loadClass(VIP_SUB_TYPE),
            ).apply { isAccessible = true }
                .newInstance(FAKE_EXPIRE, "1", FAKE_EXPIRE, true, true, 1, true, subType)
        } catch (t: Throwable) {
            null
        }
    }

    private fun injectFakeVipInfo(privilegeManager: Any?) {
        if (privilegeManager == null) return
        val fake = fakeVipInfoModel() ?: return
        Reflect.on(privilegeManager).set("vipInfoModel", fake)

        val list = ArrayList<Any>()
        for (sub in listOf("ShortStory", "Publish", "AdFree", "Default")) {
            val subVal = Reflect.onClass(VIP_SUB_TYPE).enumValue(sub).get() ?: continue
            Reflect.on(fake).set("subType", subVal)
            list.add(fake)
        }
        Reflect.on(privilegeManager).set("vipInfoList", list)
    }

    /** NsVipImpl display gates forced true so the VIP centre renders consistently. */
    private fun installVipDisplayGates() {
        val impl = "com.dragon.read.component.biz.impl.NsVipImpl"
        for (m in listOf("willShowNativeBanner", "canShowVipCenter", "willShowLynxBanner", "canShowMulVip")) {
            HookSupport.safeHook(TAG, impl, m, null, "本地VIP-展示/$m") { chain ->
                if (ModuleConfig.localVip()) true else chain.proceed()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Instant reward
    // ─────────────────────────────────────────────────────────────────────────

    private fun installInstantReward() {
        // After the exciting-video fragment creates its view, immediately report
        // "finished" so the reward is paid without playing the video.
        HookSupport.safeHook(
            "InstantReward",
            "com.ss.android.excitingvideo.sdk.ExcitingVideoFragment",
            "onCreateView",
            arrayOf("android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle"),
            "激励秒领-立即发奖"
        ) { chain ->
            val result = chain.proceed()
            if (!ModuleConfig.instantReward()) return@safeHook result
            try {
                val fragment = chain.thisObject
                fragment.javaClass.getMethod("closeFragment", java.lang.Boolean.TYPE)
                    .invoke(fragment, true)
            } catch (t: Throwable) {
                // best-effort
            }
            result
        }

        // 旧版本中"隐藏激励视频加载弹窗"依赖 za1.m.o；
        // 当前 APK 仅剩 za1.a/b/c 三个类，原钩子直接失败，故移除。
        // 若后续需要隐藏弹窗，需先在 dexdump 中定位新类名再加回。
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reading time
    // ─────────────────────────────────────────────────────────────────────────

    private fun installReadingTime() {
        // ReadingTiming 在当前版本中只有 a(J, Z)V 和 b()LinkedHashSet 两个静态方法。
        // a(long durationMs, boolean flag) 是上报阅读时长的入口。
        HookSupport.safeHook(
            TAG, "com.dragon.read.polaris.timing.ReadingTiming",
            "a", arrayOf("long", "boolean"), "阅读时长倍率"
        ) { chain ->
            val factor = ModuleConfig.readingTimeMultiplier().toLong().coerceAtLeast(1)
            if (factor <= 1) return@safeHook chain.proceed()
            val args = chain.args.toTypedArray()
            if (args.isNotEmpty() && args[0] is Long) {
                args[0] = (args[0] as Long) * factor
                chain.proceed(args)
            } else {
                chain.proceed()
            }
        }
    }
}
