package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.Flags
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog
import java.io.File
import java.lang.reflect.Method
import org.json.JSONObject

/**
 * 激励视频 / 金币发奖全链观测（对应方案 N7 的实验分支，也是「激励秒领」的判定工具）。
 *
 * 74532 上这条链是五跳，每一跳都能挂：
 *
 *   1. `com.ss.android.excitingvideo.IRewardCompleteListener.onRewardComplete(int, RewardCompleteParams)`
 *        —— SDK 认为「看够了」，把 watchTime / inspireTime 交给宿主
 *   2. `com.dragon.read.ad.exciting.video.inspire.impl.NovelRewardAdDependImpl#getReward(String, JSONObject, ls1.e)`
 *        —— 宿主把 SDK 给的 token 转给任务服务
 *   3. `com.dragon.read.component.NsCommonDependImpl#getReward` → `ITaskService#getReward`
 *   4. `com.dragon.read.component.biz.impl.service.TaskServiceImpl#getReward` → `com.dragon.read.polaris.audio.p#c`
 *   5. `com.bytedance.ug.sdk.luckyhost.api.api.ILuckyCatService#getReward(String, JSONObject, n12.h)`
 *        —— 实现类 `s72.b`，真正发请求给 LuckyCat 服务端；回调 `n12.h.onSuccess(JSONObject)` /
 *        `onFailed(int, String)`，宿主侧实现是 `NovelRewardAdDependImpl$b`
 *
 * 为什么观测而不是直接改：发奖是**服务端判断题**。客户端提前触发回调只会拿到服务端的拒绝码，
 * 金币不会增加（v0.7.0 的「激励秒领」正是这样失败的）。要先拿到真实 token / 真实回调码，
 * 才能判断「秒领」到底卡在哪一跳。
 *
 * 输出：<dataDir>/cache/fanqiehook.reward.jsonl，每行一个事件。
 */
class RewardHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
    private val flags: Flags,
    private val hostDataDir: String?
) {

    private val depend = "com.dragon.read.ad.exciting.video.inspire.impl.NovelRewardAdDependImpl"
    private val dependCallback = "$depend\$b"
    private val params = "com.ss.android.excitingvideo.IRewardCompleteListener\$RewardCompleteParams"

    private val outFile: File? by lazy {
        hostDataDir?.let { File("$it/cache/fanqiehook.reward.jsonl") }
    }

    fun installAll() {
        if (!flags.enabled("n8.rewardTrace")) return
        installGetReward()
        installRewardCallback()
        installLuckyCatDispatch()
        installWatchTime()
        installCompleteListener()
    }

    // ── 2. 宿主把 token 交给任务服务 ───────────────────────────────────────
    //
    // 参数里的第三个是混淆接口 `ls1.e`（回调），签名不能硬写，所以按
    // 「名字 = getReward 且参数个数 = 3」定位；该类只有这一个 getReward。
    private fun installGetReward() {
        val m = methodByNameAndArity(depend, "getReward", 3)
        hooks.install(id = "reward:getReward", method = m, deoptimize = true) { chain ->
            val token = chain.args.getOrNull(0) as? String
            val json = chain.args.getOrNull(1) as? JSONObject
            if (json != null) {
                log.info("reward getReward: taskKey=${token?.take(48)} jsonKeys=${keysOf(json)}")
                append("getReward", token, json, null)
            }
            chain.proceed()
        }
    }

    // ── 3/4/5. 回调：服务端到底给了什么 ────────────────────────────────────
    private fun installRewardCallback() {
        val cls = resolver.findClass(dependCallback)
        if (cls == null) {
            log.warn("reward: $dependCallback not found; callback result will be invisible")
            return
        }
        val onSuccess = runCatching {
            cls.getDeclaredMethod("onSuccess", JSONObject::class.java).apply { isAccessible = true }
        }.getOrNull()
        hooks.install(id = "reward:onSuccess", method = onSuccess, deoptimize = true) { chain ->
            val j = chain.args.getOrNull(0) as? JSONObject
            log.info("reward onSuccess: ${j?.toString()?.take(400)}")
            append("onSuccess", null, j, null)
            chain.proceed()
        }
        val onFailed = runCatching {
            cls.getDeclaredMethod(
                "onFailed", java.lang.Integer.TYPE, java.lang.String::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
        hooks.install(id = "reward:onFailed", method = onFailed, deoptimize = true) { chain ->
            val code = chain.args.getOrNull(0)
            val msg = chain.args.getOrNull(1) as? String
            log.info("reward onFailed: code=$code msg=${msg?.take(300)}")
            append("onFailed", null, null, "code=$code msg=${msg?.take(200)}")
            chain.proceed()
        }
    }

    // ── LuckyCat 分发表：确认转发目标与参数一致 ────────────────────────────
    private fun installLuckyCatDispatch() {
        // LuckyCat 实现类名每次发版都变（74532 上是 s72.b），按接口反查，不写死。
        val cls = resolver
            .findClassImplementingInterface(
                "com.bytedance.ug.sdk.luckyhost.api.api.ILuckyCatService",
                "getReward"
            )
            .firstOrNull()
        if (cls == null) {
            log.info("reward: no ILuckyCatService impl resolved; skipping dispatch hook")
            return
        }
        val m = methodByNameAndArity(cls.name, "getReward", 3)
        hooks.install(id = "reward:luckycat", method = m, deoptimize = true) { chain ->
            val token = chain.args.getOrNull(0) as? String
            val json = chain.args.getOrNull(1) as? JSONObject
            log.info("reward luckycat: taskKey=${token?.take(48)} jsonKeys=${json?.let { keysOf(it) }}")
            chain.proceed()
        }
    }

    // ── watchTime / inspireTime：SDK 判「看够」的依据 ─────────────────────
    private fun installWatchTime() {
        val cls = resolver.findClass(params) ?: return
        for (name in listOf("getWatchTime", "getInspireTime", "getShowTimes")) {
            val m = runCatching {
                cls.getDeclaredMethod(name).apply { isAccessible = true }
            }.getOrNull() ?: continue
            hooks.install(id = "reward:$name", method = m, deoptimize = false) { chain ->
                val v = chain.proceed()
                log.info("reward params: $name=$v")
                v
            }
        }
    }

    // ── 1. SDK 的完成回调 ────────────────────────────────────────────────
    private fun installCompleteListener() {
        val iface = "com.ss.android.excitingvideo.IRewardCompleteListener"
        val impls = resolver.findClassImplementingInterface(iface, "onRewardComplete")
        if (impls.isEmpty()) {
            log.info("reward: no IRewardCompleteListener impl resolved via DexKit")
            return
        }
        for (cls in impls) {
            val m = runCatching {
                cls.getDeclaredMethod(
                    "onRewardComplete",
                    java.lang.Integer.TYPE,
                    Class.forName(params, false, cls.classLoader)
                ).apply { isAccessible = true }
            }.getOrNull() ?: continue
            hooks.install(id = "reward:complete:${cls.name}", method = m, deoptimize = true) { chain ->
                val p = chain.args.getOrNull(1)
                val wt = runCatching {
                    p?.javaClass?.getDeclaredMethod("getWatchTime")?.apply { isAccessible = true }?.invoke(p)
                }.getOrNull()
                val it2 = runCatching {
                    p?.javaClass?.getDeclaredMethod("getInspireTime")?.apply { isAccessible = true }?.invoke(p)
                }.getOrNull()
                log.info("reward complete: watchTime=$wt inspireTime=$it2 via ${cls.name}")
                append("complete", null, null, "watch=$wt inspire=$it2")
                chain.proceed()
            }
        }
    }

    private fun methodByNameAndArity(className: String, name: String, arity: Int): Method? {
        val cls = resolver.findClass(className) ?: return null
        val cands = runCatching {
            cls.declaredMethods.filter { it.name == name && it.parameterCount == arity && !it.isSynthetic }
        }.getOrElse { emptyList() }
        if (cands.size != 1) {
            log.warn("reward: $className#$name/$arity candidates=${cands.size}, skipping")
            return null
        }
        return cands[0].apply { isAccessible = true }
    }

    private fun keysOf(j: JSONObject): String =
        j.keys().asSequence().take(24).joinToString(",")

    private fun append(kind: String, token: String?, json: JSONObject?, note: String?) {
        val f = outFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(
                buildString {
                    append("{\"t\":").append(System.currentTimeMillis())
                    append(",\"kind\":\"").append(kind).append('"')
                    if (token != null) append(",\"taskKey\":\"").append(token.replace("\"", "")).append('"')
                    if (json != null) append(",\"json\":").append(json.toString().replace("\n", " ").take(1200))
                    if (note != null) append(",\"note\":\"").append(note.replace("\"", "'")).append('"')
                    append("}\n")
                }
            )
        }.onFailure { log.warn("reward dump write failed: ${it.javaClass.simpleName}") }
    }
}