package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog
import io.github.libxposed.api.XposedInterface.Hooker

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
    /** 激励回调实例（yh.e 的实现，实测为 lv1.r），由 showInspire 钩子捕获。 */
    @Volatile
    private var inspireCallback: Any? = null

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
        installInstantRewardHooks()
        installRewardProbes()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 13. 激励秒领：**经完整实验证伪，客户端做不到**（代码保留作调研记录，默认关闭）
    //
    //   目标：把「看 30 秒激励视频才发金币」变成「激励视频一露头就发奖并退出」。
    //
    //   结论：**在番茄 7.3.7.32（73732）上，客户端伪造发奖回调拿不到金币** —— 发奖由
    //   服务端权威校验。下面是完整的实验与证据链（三轮真机实验，均有日志）：
    //
    //   第 1 轮（错误结论）：以为 `RewardDisplayImpl#onRewardVerifyCommon(Z Z I)` 是发奖实现，
    //     调它 → 调用成功、无异常、金币不增加。
    //     原始指令转储证伪：该方法只有 45 条指令
    //       iget-object → 拼日志 → 打日志(bs1.b#c) → 取单例(iv1.b#o()) → iput → return
    //     即它是**埋点函数**。`RewardDisplayImpl` 整体只是 `IRewardDisplayService` 的桥接实现。
    //
    //   第 2 轮（探针采集真实样本）：用 installProbe 抓完整看完一次激励视频的真实成功回调：
    //       probe[reward-open]      ATInspireOpenerImpl#showInspire(Activity, ATParams, yh.e)
    //                               → 第三个参数就是回调实例（真实类型 lv1.r$a）
    //       probe[reward-result]    uh.k = InspireVerifyResult(
    //                                 rewardType=2, rewardStage=0, customRewardType=1,
    //                                 isMoreOne=false, adSource=AT, moreOneTime=0, passThroughParams=null)
    //                               混淆字段 a=2 b=0 c=1 d=false e=AT f=0 g=null
    //       probe[reward-cb-e]      e(1,false,false)（`yh.e#e(IZZ)` = onAdClose(?, 能否得奖励, 是否再得)）
    //
    //   第 3 轮（合成派发，两轮）：把上述**与真实样本逐字段一致**的结果对象合成出来，
    //     反射调用 `lv1.r$a#b(结果)`（SDK 真正回传成功时调的就是它）：
    //       - 立即派发 + 补发 e(...) + onAdClose → App 提示「活动繁忙」，广告继续播放，金币不到账
    //       - 最小实验：延后 3 秒、只派发 b(结果)、不补 e、不关广告 → 广告正常播放，金币仍不到账
    //     两次派发都被日志与探针确认执行成功（零异常），对象与真实样本完全一致。
    //
    //   根本原因：发奖申领走 RPC `com.dragon.read.rpc.model.ReaderAdRewardRequest`，其**全部字段**只有
    //       fieldTypeClassRef : Class
        //      serialVersionUID  : long
    //       reqType           : ReaderAdReawrdType（奖励类型枚举）
    //     —— **请求里没有任何来自广告 SDK 的完成凭证**。即客户端只能说"给我这类奖励"，
    //     是否发放完全由服务端根据自己的广告完成记录决定。客户端伪造回调无法改变服务端记录，
    //     因此必然被拒（表现为「活动繁忙」）。
    //
    //   怎样才有用（都不建议做）：伪造/重放服务端的发奖响应，或修改服务端可见的完成记录。
    //   前者只会得到本地假象、且极可能触发风控；后者已不属于客户端改包范畴。
    //
    //   风险（若将来有人开启）：这是代替广告平台上报「视频已完成」。广告主按完成量付费，
    //   该行为在广告平台侧属于作弊，且番茄有服务端风控，存在账号被风控的风险。
    // ─────────────────────────────────────────────────────────────────────────

    private fun installInstantRewardHooks() {
        if (!ENABLE_INSTANT_REWARD) return

        // ── 第一步：捕获激励回调实例 ──────────────────────────────────────────
        // 实测（探针 reward-open）：`ATInspireOpenerImpl#showInspire(Activity, ATParams, yh.e)`
        // 的第三个参数就是回调实例（真实类型 `lv1.r$a`）。它就是 SDK 回传发奖结果的那一端。
        hooks.install(
            id = "instant-reward-capture",
            method = resolver.findMethod(
                "com.bytedance.admetaversesdk.inspire.impl.ATInspireOpenerImpl", "showInspire",
                "android.app.Activity", "uh.b", "yh.e"
            ),
            deoptimize = true,
            hooker = Hooker { chain ->
                inspireCallback = chain.args.getOrNull(2)
                log.info("instant-reward: captured inspire callback = ${inspireCallback?.javaClass?.name}")
                chain.proceed() // 广告照常展示与上报，只是不等它播完
            },
        )

        // ── 第二步：广告一展示出来，就直接把「发奖成功」结果派发下去 ──────────
        hooks.install(
            id = "instant-reward",
            method = resolver.findMethod(IMPL, "onAdShow", "boolean", "int", "Object", "Object"),
            deoptimize = true,
            hooker = Hooker { chain ->
                val result = chain.proceed()
                val cb = inspireCallback
                if (cb == null) {
                    log.warn("instant-reward: no inspire callback captured yet; skipped")
                } else if (INSTANT_REWARD_DELAY_MS > 0) {
                    // 延后派发：上一次实验是「广告一展示就立刻申领」，实测被业务层拒绝
                    // （提示"活动繁忙"）。发奖申领请求里没有广告凭证，服务端靠自己的完成记录
                    // 判断，因此立刻申领必然过早。这里改为延后，隔离"时序"这一个变量。
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching { dispatchSyntheticReward(cb, null, emptyList()) }
                            .onFailure { log.warn("instant-reward: delayed dispatch failed (${it.javaClass.simpleName})") }
                    }, INSTANT_REWARD_DELAY_MS)
                    log.info("instant-reward: dispatch scheduled in ${INSTANT_REWARD_DELAY_MS}ms")
                } else {
                    runCatching { dispatchSyntheticReward(cb, null, emptyList()) }
                }
                result
            },
        )
    }

    /**
     * 合成一个「发奖成功」结果并沿 App 自己的回调链派发下去。
     *
     * 结果对象的类型**从回调的 `b(...)` 方法签名里取**，不写死 `uh.k`——混淆类名随时会变，
     * 而"回调上那个单参数方法"这个关系是稳定的。
     *
     * 字段取值来自探针 `reward-result` 在真机上采到的成功样本（番茄 7.3.7.32，完整看完一次
     * 激励视频时 SDK 回传的对象）：
     *
     *     InspireVerifyResult(rewardType=2, rewardStage=0, customRewardType=1,
     *                         isMoreOne=false, adSource=AT, moreOneTime=0, passThroughParams=null)
     *
     * 混淆字段名 `a..g` 与该顺序一一对应（探针 dump 出的字段顺序与 toString 顺序一致）。
     */
    private fun dispatchSyntheticReward(cb: Any, display: Any?, adShowArgs: List<Any?>) {
        val dispatch = runCatching {
            cb.javaClass.methods.firstOrNull { it.name == "b" && it.parameterCount == 1 }
        }.getOrNull()
        if (dispatch == null) {
            log.warn("instant-reward: no single-arg 'b(...)' on ${cb.javaClass.name}")
            return
        }
        val resType = dispatch.parameterTypes[0]
        val res = runCatching { resType.getDeclaredConstructor().newInstance() }.getOrElse { t ->
            log.warn("instant-reward: cannot instantiate ${resType.name} (${t.javaClass.simpleName})")
            return
        }
        setField(res, "a", 2)
        setField(res, "b", 0)
        setField(res, "c", 1)
        setField(res, "d", false)
        setEnumField(res, "e", "AT")
        setField(res, "f", 0)
        setField(res, "g", null)
        log.info("instant-reward: synthesized reward result = $res")

        runCatching {
            dispatch.isAccessible = true
            dispatch.invoke(cb, res)
            log.info("instant-reward: dispatched ${dispatch.name}($res)")
        }.onFailure { log.warn("instant-reward: dispatch failed (${it.javaClass.simpleName}: ${it.message})") }

        // 最小实验：只派发 `b(结果)`，不补 `e(...)`、不关广告 —— 用来隔离变量。
        // 若这次金币到账，说明之前失败是「补发的 e(能否得奖励=false) 或过早关闭广告」造成的；
        // 若仍不到账，则基本可以断定发奖由服务端权威校验（申领请求里没有任何广告凭证）。
        if (INSTANT_REWARD_MINIMAL) {
            log.info("instant-reward: minimal mode — skipping e(...) / onAdClose")
            return
        }

        // 真实流程里 `b(结果)` 之后紧跟一个 `e(I Z Z)` 回调（实测参数 1,false,false），一并补上。
        runCatching {
            val e = cb.javaClass.getDeclaredMethod(
                "e", Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE
            )
            e.isAccessible = true
            e.invoke(cb, 1, false, false)
            log.info("instant-reward: dispatched e(1,false,false)")
        }.onFailure { log.warn("instant-reward: e(...) failed (${it.javaClass.simpleName})") }

        // 退出广告界面（内部 NsAdDepend.exitAdVideo("jili video")）。
        // 第二参数必须用真实广告对象，传 null 会抛 InvocationTargetException（实测已复现）。
        val adObj = adShowArgs.getOrNull(3) ?: adShowArgs.getOrNull(2)
        if (display == null) return
        invokeRewardCallback(
            display, "onAdClose",
            arrayOf(java.lang.Boolean.TYPE, java.lang.Object::class.java),
            arrayOf(true, adObj)
        )
    }

    /** 反射写字段；失败只记日志。 */
    private fun setField(target: Any, name: String, value: Any?) {
        runCatching {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
        }.onFailure { log.warn("instant-reward: set $name failed (${it.javaClass.simpleName})") }
    }

    /** 反射写枚举字段：优先取指定常量名，取不到就退化为第一个常量。 */
    private fun setEnumField(target: Any, name: String, constName: String) {
        runCatching {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val constants = f.type.enumConstants
            val v = constants?.firstOrNull { (it as? Enum<*>)?.name == constName } ?: constants?.firstOrNull()
            f.set(target, v)
        }.onFailure { log.warn("instant-reward: set enum $name failed (${it.javaClass.simpleName})") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 14. 激励链路探针（仅打日志；`REWARD_PROBE` 开关）
    //
    //   为什么需要探针：激励链路横跨混淆类，纯静态阅读已经连续给出错误结论——
    //   `onRewardVerifyCommon` 那个 45 条指令的埋点函数，被只打印 invoke/const-string 的
    //   过滤式反汇编看起来就像"里面有逻辑"。探针让**设备自己**说出真相：
    //   哪些回调被调用、顺序如何、结果对象里各字段是什么。
    //
    //   本次要观测：
    //     - `lv1.r$a` 实现 `yh.e`（激励回调接口），它的 b/c/d/e/g 就是 SDK 回传的各阶段回调；
    //       `b(uh.k)` 携带「发奖结果对象」，把它的字段全部打出来 → 才能知道"成功"长什么样
    //     - `lv1.s#b(Activity, uh.b, yh.e)`：拉起激励广告的入口（App 任务层调用）
    //     - `ReaderSeeAdTask#a(J,String,Z)` / `#i()` / `#k(I,ShowTimeModel)`：
    //       番茄「看广告任务」侧的反应，用来确认发奖是否真的走到任务层
    // ─────────────────────────────────────────────────────────────────────────

    private fun installRewardProbes() {
        if (!REWARD_PROBE) return

        // 回传结果对象：把已知字段与全部声明字段都打出来
        hooks.installProbe(
            id = "reward-result",
            method = resolver.findMethod("lv1.r\$a", "b", "uh.k"),
        ) { chain ->
            val arg = chain.args.firstOrNull()
            val fields = runCatching {
                arg?.javaClass?.declaredFields?.joinToString(" ") { f ->
                    f.isAccessible = true
                    "${f.name}=${runCatching { f.get(arg) }.getOrNull()}"
                } ?: "?"
            }.getOrElse { "fielddump-error:${it.javaClass.simpleName}" }
            "uh.k=${arg} [${fields}]"
        }

        // 其余阶段回调：只记参数，用来看真实顺序
        for ((name, params) in listOf(
            "c" to arrayOf("boolean"),
            "d" to arrayOf("boolean", "int", "com.bytedance.admetaversesdk.adbase.entity.enums.AdSource"),
            "e" to arrayOf("int", "boolean", "boolean"),
            "g" to arrayOf("int", "String", "boolean"),
        )) {
            hooks.installProbe(
                id = "reward-cb-$name",
                method = resolver.findMethod("lv1.r\$a", name, *params),
            )
        }

        // 拉起激励广告：SDK 入口（接口 yh.a 的实现）+ 番茄侧 facade
        hooks.installProbe(
            id = "reward-open",
            method = resolver.findMethod(
                "com.bytedance.admetaversesdk.inspire.impl.ATInspireOpenerImpl", "showInspire",
                "android.app.Activity", "uh.b", "yh.e"
            ),
        )
        hooks.installProbe(
            id = "reward-open-facade",
            method = resolver.findMethod("lv1.s", "b", "uh.g", "uh.h"),
        )

        // 番茄任务侧
        hooks.installProbe(
            id = "task-see-ad-a",
            method = resolver.findMethod(
                "com.dragon.read.polaris.tasks.ReaderSeeAdTask", "a", "long", "String", "boolean"
            ),
        )
        hooks.installProbe(
            id = "task-see-ad-i",
            method = resolver.findMethod("com.dragon.read.polaris.tasks.ReaderSeeAdTask", "i"),
        )
        hooks.installProbe(
            id = "task-see-ad-k",
            method = resolver.findMethod(
                "com.dragon.read.polaris.tasks.ReaderSeeAdTask", "k", "int",
                "com.dragon.read.polaris.tasks.ReaderSeeAdTask\$ShowTimeModel"
            ),
        )
    }

    /**
     * 反射调用一个激励回调；失败只记日志，绝不抛出到宿主进程。
     * 用反射而不是直接引用，是因为这些方法都在混淆类上、且优先按 `getDeclaredMethod` 命中，
     * 签名对不上时只降级为「这条不生效」，不会影响其它 hook。
     */
    private fun invokeRewardCallback(
        target: Any,
        name: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ) {
        try {
            val m = target.javaClass.getDeclaredMethod(name, *paramTypes)
            m.isAccessible = true
            m.invoke(target, *args)
            log.info("instant-reward: invoked $name(${args.joinToString { it?.toString() ?: "null" }})")
        } catch (t: Throwable) {
            log.warn("instant-reward: $name failed (${t.javaClass.simpleName}: ${t.message})")
        }
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
            hooker = Hooker {
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
                val blocked = position in BLOCKED_POSITIONS
                if (blocked) {
                    log.info("blocked ad position=$position source=$source via $className.checkAdAvailable")
                } else if (LOG_UNLISTED_POSITIONS &&
                    position.isNotEmpty() &&
                    position !in PRESERVED_POSITIONS &&
                    reportedPositions.add(position)
                ) {
                    // Debug-grade discovery: the ad-position namespace is server-driven and grows
                    // silently between releases. Anything the block list does not know about (and
                    // is not a deliberate keep) gets reported exactly once per process, so the next
                    // adaptation round can extend BLOCKED_POSITIONS from real device data instead
                    // of guesswork. Log-only: the return value is unaffected.
                    log.info(
                        "unlisted ad position=$position source=$source via $className.checkAdAvailable " +
                            "(not blocked; report upstream so it can be classified)"
                    )
                }
                blocked
            }
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
        // 红果专属 banner 服务
        hooks.replaceBooleanFalse(
            id = "hongguo-banner-join-revert",
            method = resolver.findMethod(
                "com.dragon.read.ad.banner.impl.HongguoBannerServiceImpl",
                "enableShortSeriesAdJoinRevert"
            ),
        )
        // 短剧广告总开关 + 横屏插入广告开关
        //
        // 验证结果 (versionCodes 73532 与 73732, 番茄+红果 均有调用点):
        //   - q0() → ShortSeriesLandscapeInsertAdConfig.landscapeInsertAdEnable (横屏插入广告)
        //   - p()  → SeriesAdConfig.enableMultiSeriesFlowAd (系列信息流广告总开关)
        //   - p0() 不存在,旧版硬编码为 p0 的 hook 会静默 WARN 跳过
        // 73732 复核: 两者调用点数量与 73532 完全一致 (p: 8, q0: 6), 无需改动。
        hooks.replaceBooleanFalse(
            id = "short-series-ad-enable",
            method = resolver.findMethod(
                "com.dragon.read.reader.ad.experiment.ExperimentUtil",
                "p"
            ),
            deoptimize = true,
        )
        hooks.replaceBooleanFalse(
            id = "short-series-landscape-insert-ad",
            method = resolver.findMethod(
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
            hooker = Hooker { /* 不调用 proceed，直接阻断 */ },
        )
        // 双保险：即使 Activity 被其他途径拉起，广告 View 也不会挂载
        hooks.install(
            id = "splash-ad-brand-view",
            method = resolver.findMethod(splashActivity, "showBrandAdView", "android.view.View"),
            hooker = Hooker { /* no-op */ },
        )
        hooks.install(
            id = "splash-ad-imc-view",
            method = resolver.findMethod(splashActivity, "showImcSplashView", "android.view.View"),
            hooker = Hooker { /* no-op */ },
        )
        hooks.install(
            id = "splash-ad-natural-view",
            method = resolver.findMethod(splashActivity, "showNaturalAdView", "android.view.View"),
            hooker = Hooker { /* no-op */ },
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
            "audio_patch_ad"
        )

        /**
         * Positions that deliberately stay ENABLED. All of them are user-initiated reward / coin
         * surfaces — blocking them would remove the user's ability to earn coins by watching a
         * video, which this module explicitly preserves.
         *
         * Analysed call sites:
         *   - `reader_gold_coin_popup` — 金币弹窗
         *   - `video_tts_ad` / `video_voice_ad` — 听书激励入口（AudioInspireUtil.adUnavailable）
         *   - `video_reward_gift_ad` — 激励视频礼包
         *   - `video_reader_end_urge_update` — 看视频催更
         *
         * Listing them here (rather than only omitting them) keeps the intent explicit and stops
         * the discovery logger from re-reporting them as unclassified.
         */
        val PRESERVED_POSITIONS = setOf(
            "reader_gold_coin_popup",
            "video_tts_ad",
            "video_voice_ad",
            "video_reward_gift_ad",
            "video_reader_end_urge_update"
        )

        /**
         * Log each previously-unseen ad position once per process (log-only; never changes the
         * hook's return value). Purpose: the position namespace is server-driven and grows without
         * any APK-side signal, so this turns every device into an instrument for finding the next
         * gap. Disable if the extra INFO lines are unwanted.
         */
        const val LOG_UNLISTED_POSITIONS = true

        /**
         * 激励秒领：**实验性，默认关闭**。
         *
         * 当前实现经实测**不生效**（hook 装上、回调能调到，但金币不增加）——原因见
         * [installInstantRewardHooks] 的注释：可调用的那层只是埋点函数，真正的发奖在
         * App 任务层的 `yh.e` 回调里且由服务端权威校验。保留代码是为了记录调研结论，
         * 不是可用功能；在拿到可行方案前不要打开。
         *
         * 若将来启用，需知悉：这是代替广告平台上报「视频已完成」，广告主按完成量付费，
         * 该行为在广告平台侧属于作弊，且番茄有服务端风控，存在账号被风控的风险。
         */
        const val ENABLE_INSTANT_REWARD = false

        /** 激励显示服务实现（混淆名，仅此处集中引用）。 */
        const val IMPL = "com.dragon.read.ad.tomato.reward.impl.RewardDisplayImpl"

        /** 派发合成的发奖结果前延后多少毫秒（0 = 立刻）。用于隔离「时序」变量。 */
        const val INSTANT_REWARD_DELAY_MS = 3000L

        /** 最小实验：只派发 b(结果)，不补 e(...)、不关广告。 */
        const val INSTANT_REWARD_MINIMAL = true

        /**
         * 激励链路探针（仅打日志，见 [installRewardProbes]）。定位完成后应改回 false。
         */
        const val REWARD_PROBE = false

        // Splash attribution is OFF by default. Flipping this to true causes AttributionManager
        // to skip install-source reporting, which may affect compliance. Review before shipping.
        const val ENABLE_ATTRIBUTION_SPLASH_BYPASS = false
    }

    /**
     * Positions already reported by [LOG_UNLISTED_POSITIONS]; process-scoped so a hot reload
     * starts a fresh discovery pass. Concurrent because hook callbacks arrive on many threads.
     */
    private val reportedPositions: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
}