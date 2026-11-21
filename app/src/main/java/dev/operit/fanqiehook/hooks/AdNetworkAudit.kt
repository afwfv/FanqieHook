package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.Flags
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog
import java.io.File
import java.lang.reflect.Method

/**
 * 网络层审计（对应方案 N4 v1：只观测，不改写）。
 *
 * 为什么需要：现有 69 条 hook 全在「宿主决定要不要展示」的门控上。金币宝箱那条
 * 「看视频领 2317 金币」实测走的是 H5/Lynx → bridge → 发奖接口，**不经过**
 * `NovelRewardAdDependImpl#getReward`（小说阅读器的激励链）。也就是说：
 * 同一个 App 里至少有两套激励发奖入口，靠猜类名永远覆盖不全。
 *
 * 这一层挂在请求出口，直接看 URL：谁能发奖、发给谁、带什么参数，一目了然。
 *
 * 74532 定位：
 *   m44.a            ← 签名头拦截器（写 6 个 X-* 头），每发一个带签名的请求都会过
 *   com.bytedance.ttnet.INetworkApi        ← doGet/doPost/postBody
 *   com.dragon.read.kmp.network.INetworkApi ← KMP get/post
 *
 * 输出：<dataDir>/cache/fanqiehook.net.jsonl
 */
class AdNetworkAudit(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
    private val flags: Flags,
    private val hostDataDir: String?
) {

    private val outFile: File? by lazy {
        hostDataDir?.let { File("$it/cache/fanqiehook.net.jsonl") }
    }

    private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun installAll() {
        if (!flags.enabled("n4.netAudit")) return
        installSignHeaderInterceptor()
        installTTNetBoundary()
        installKmpBoundary()
    }

    private fun installSignHeaderInterceptor() {
        val m = pick("m44.a", "intercept") ?: return
        log.info("net: m44.a#intercept -> ${signatureOf(m)}")
        hooks.install(id = "net:sign-interceptor", method = m, deoptimize = false) { chain ->
            scan(chain.args, "m44")
            chain.proceed()
        }
    }

    private fun installTTNetBoundary() {
        val cls = "com.bytedance.ttnet.INetworkApi"
        for ((id, name) in listOf(
            "net:ttnet-get" to "doGet",
            "net:ttnet-post" to "doPost",
            "net:ttnet-postbody" to "postBody",
        )) {
            val m = pick(cls, name) ?: continue
            hooks.install(id = id, method = m, deoptimize = false) { chain ->
                scan(chain.args, "ttnet.$name")
                chain.proceed()
            }
        }
    }

    private fun installKmpBoundary() {
        val cls = "com.dragon.read.kmp.network.INetworkApi"
        for ((id, name) in listOf("net:kmp-get" to "get", "net:kmp-post" to "post")) {
            val m = pick(cls, name) ?: continue
            hooks.install(id = id, method = m, deoptimize = false) { chain ->
                scan(chain.args, "kmp.$name")
                chain.proceed()
            }
        }
    }

    /** 只捞「可能发奖」的请求：广告召回 / 激励发奖 / 任务完成 / 金币。 */
    private fun scan(args: List<Any?>, from: String) {
        for (a in args) {
            val url = a as? String ?: continue
            if (!url.startsWith("http")) continue
            val host = url.substringAfter("://").substringBefore('/')
            if (!HOSTS.any { host.contains(it) }) continue
            val path = url.substringAfter(host).substringBefore('?')
            if (!INTERESTING.any { path.contains(it) }) continue
            val key = host + path
            val n = seen.merge(key, 1, Int::plus) ?: 1
            if (n > 50) continue
            if (n <= 3 || n % 20 == 0) {
                log.info("net[$from]: $host$path${queryHint(url)} (n=$n)")
            }
            if (n <= 3) append(host, path, queryHint(url), from)
        }
    }

    /** 只留几个有诊断价值的 query 键，避免把整条 URL（4 KB）写进日志。 */
    private fun queryHint(url: String): String {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return ""
        val wanted = q.split('&').filter { kv ->
            val k = kv.substringBefore('=')
            k in QUERY_KEYS
        }
        return if (wanted.isEmpty()) "" else " ?" + wanted.joinToString("&") { it.take(80) }
    }

    private fun append(host: String, path: String, hint: String, from: String) {
        val f = outFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(
                "{\"t\":${System.currentTimeMillis()},\"host\":\"$host\",\"path\":\"$path\"," +
                    "\"query\":\"${hint.replace("\"", "")}\",\"src\":\"$from\"}\n"
            )
        }
    }

    /** 重载多于一个时不猜（与 O4 一致），把候选签名打进日志。 */
    private fun pick(className: String, methodName: String): Method? {
        val cls = resolver.findClass(className) ?: return null
        val cands = runCatching {
            cls.declaredMethods.filter { it.name == methodName && !it.isSynthetic }
        }.getOrElse { emptyList() }
        if (cands.size > 1) {
            log.warn(
                "net: $className#$methodName ${cands.size} overloads, refusing to guess: " +
                    cands.joinToString("; ") { signatureOf(it) }
            )
            return null
        }
        return cands.firstOrNull()?.apply { isAccessible = true }
    }

    private fun signatureOf(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})->${m.returnType.simpleName}"

    private companion object {
        val HOSTS = listOf("zijieapi", "snssdk", "fqnovel", "fanqiesdk", "ulpay")

        /** 路径里出现这些片段才记：广告召回 / 激励发奖 / 任务 / 金币 / 会员权益。 */
        val INTERESTING = listOf(
            "ad/", "incentive", "task/done", "tasks/", "reward", "coin", "vip/info",
            "privilege", "read_card", "query_user_right", "growth_task",
        )

        val QUERY_KEYS = setOf(
            "pos", "position", "ad_pos", "task_key", "taskKey", "act_id", "actID",
            "task_module_id", "reward_amount", "aid", "book_id", "item_id", "device_id",
        )
    }
}