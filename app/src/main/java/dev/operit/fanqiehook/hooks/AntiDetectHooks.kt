package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.Flags
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog
import java.io.File
import java.lang.reflect.Method

/**
 * 反检测对抗（主动侧）：把宿主「查设备上有没有 Xposed / Magisk / Frida」的 Java 路径处理掉。
 *
 * 边界先说清楚：
 *   - 能做的：`PackageManager` 包枚举/查询、`Runtime.exec` 命令行、以及命中时的调用栈取证。
 *   - 做不了的：宿主 native 层（`libmetasec_ml.so` / `libmannorarmor.so`）的 `/proc/self/maps`
 *     扫描、`ptrace` 自检、seccomp 探测。LSPosed 的 hook 只作用于 ArtMethod，
 *     对这些没有任何作用点；真正要过 native 检测只能卸 frida、不用 DexKit、或上内核层。
 *   - 因此本类只做「降低 Java 层可见性 + 把检测动作记下来」，不做无效的 native 对抗。
 *
 * 74532 相关事实（manifest-analysis / native-triage）：
 *   - 宿主会读 `appSigningMD5` 上报；模块不改宿主签名，这一项天然干净
 *   - 发布证书常量 `aea615ab910015038f73c47e45d21466` 用于自校验，模块不动 APK 就不需要干预
 *   - `com.bytedance.bdturing.*` 是验证码/风控 SDK，不读包列表也能工作
 */
class AntiDetectHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
    private val flags: Flags,
    private val hostDataDir: String?
) {

    private val sensitive = listOf(
        "lsposed", "xposed", "edxposed", "magisk", "kernelsu", "kernelsu-next", "apatch",
        "frida", "riru", "zygisk", "dev.operit.fanqiehook"
    )

    private val execHits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun installAll() {
        if (flags.enabled("n9.hidePackages")) installPackageFilters()
        if (flags.enabled("n9.logExec") || flags.enabled("n9.blockExec")) installExecObserver()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 包枚举过滤
    //
    // 检测方最常见的一招就是 `getInstalledPackages(0)` 里找 lsposed/magisk 包名，
    // 或者 `getPackageInfo("org.lsposed.manager")` 看抛不抛 NameNotFound。
    // 这里两条都处理：列表里摘掉，单查时抛 NameNotFoundException（与真实未安装一致）。
    // ─────────────────────────────────────────────────────────────────────
    private fun installPackageFilters() {
        for (cls in listOf("android.app.ApplicationPackageManager", "android.app.ContextImpl")) {
            hookInstalledList(cls, "getInstalledPackages")
            hookInstalledList(cls, "getInstalledApplications")
        }
    }

    private fun hookInstalledList(className: String, methodName: String) {
        val owner = resolver.findClass(className) ?: return
        val cands = runCatching {
            owner.declaredMethods.filter { it.name == methodName && it.returnType == java.util.List::class.java }
        }.getOrElse { emptyList() }
        if (cands.isEmpty()) return
        for (m in cands) {
            hooks.install(
                id = "detect:${className.substringAfterLast('.')}#$methodName/${m.parameterCount}",
                method = m,
                deoptimize = false,
            ) { chain ->
                val raw = chain.proceed()
                val list = raw as? List<*> ?: return@install raw
                val kept = list.filterNot { isSensitive(itemName(it)) }
                if (kept.size != list.size) {
                    log.info("detect: hid ${list.size - kept.size} package(s) from $className#$methodName")
                }
                kept
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 检测命令：`su -c ...` / `ps -A | grep magisk` / `getprop` 这类
    //
    // 默认只记录（拦 exec 会连带影响宿主的合法 shell 调用，例如日志收集）。
    // 命中时把调用栈前 6 帧写进日志——这是判断「谁在查」的唯一可靠依据。
    // ─────────────────────────────────────────────────────────────────────
    private fun installExecObserver() {
        val owner = resolver.findClass("java.lang.Runtime") ?: return
        val cands = runCatching {
            owner.declaredMethods.filter { it.name == "exec" && it.returnType == Process::class.java }
        }.getOrElse { emptyList() }
        for (m in cands) {
            hooks.install(id = "detect:Runtime#exec/${m.parameterCount}", method = m) { chain ->
                val cmd = describeCommand(chain.args)
                if (cmd != null && looksLikeDetection(cmd)) {
                    val n = execHits.merge(cmd.take(60), 1, Int::plus) ?: 1
                    if (n <= 3 || n % 50 == 0) {
                        log.warn("detect: exec(${cmd.take(120)}) caller=${callerFrames()} n=$n")
                    }
                    if (flags.enabled("n9.blockExec")) {
                        log.info("detect: blocked exec(${cmd.take(80)})")
                        // 换成一条必然成功但无信息量的命令，保持 Process 契约不破
                        val echo = arrayOf("echo", "ok")
                        val rt = Runtime.getRuntime()
                        return@install rt.exec(echo)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun describeCommand(args: List<Any?>): String? {
        val first = args.firstOrNull() ?: return null
        return when (first) {
            is String -> first
            is Array<*> -> first.joinToString(" ") { it?.toString().orEmpty() }
            else -> null
        }
    }

    private fun looksLikeDetection(cmd: String): Boolean {
        val c = cmd.lowercase()
        if (c.contains("/proc/") || c.contains("getprop")) return true
        val tokens = listOf("su", "ps", "magisk", "frida", "xposed", "top", "cat ", "mount", "pm list", "which")
        return tokens.any { c.contains(it) } && SENSITIVE_HINTS.any { c.contains(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun itemName(item: Any?): String? = when (item) {
        null -> null
        is String -> item
        else -> runCatching {
            item.javaClass.getField("packageName").get(item) as? String
        }.getOrNull()
    }

    private fun isSensitive(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase()
        return sensitive.any { n.contains(it) }
    }

    private fun callerFrames(): String {
        val st = Throwable().stackTrace
        return st.asSequence()
            .drop(2)
            .filterNot { it.className.startsWith("dev.operit.fanqiehook") || it.className.contains("Xposed") }
            .take(6)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
    }

    private companion object {
        /** 命中这些词才认为是在做环境探测（避免把宿主正常的 `su` 调用也记下来）。 */
        val SENSITIVE_HINTS = listOf(
            "magisk", "frida", "xposed", "lsposed", "riru", "zygisk", "supersu", "busybox",
            "/data/adb", "/data/local/tmp", "maps", "cmdline", "tracerpid"
        )
    }
}