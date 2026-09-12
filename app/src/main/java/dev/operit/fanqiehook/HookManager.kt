package dev.operit.fanqiehook

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Centralised hook installation with consistent error handling, hot-reload-safe identity,
 * and per-hook try/catch isolation. One failed hook must NEVER cause another hook to be skipped.
 *
 * Convention:
 *   - Hook `id` matches the readable name used in `[FanqieHook][INFO] hook installed: ...`.
 *   - Every hook uses `ExceptionMode.PROTECTIVE` so a thrown exception cannot crash the host app.
 *   - The set of installed hooks is exposed via [installed] for `onHotReloaded()` to remove or
 *     atomically replace.
 */
class HookManager(
    private val module: XposedModule,
    private val log: ModuleLog
) {

    private val installed = mutableListOf<HookHandle>()

    /** 每个 hook 的命中次数（审计用）。 */
    private val hitCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    val installedHandles: List<HookHandle> get() = installed.toList()

    /**
     * Replace a method with one that always returns `false`.
     * For boolean methods only. Safe to call with null `method` — it logs and skips.
     */
    fun replaceBooleanFalse(
        id: String,
        method: Method?,
        deoptimize: Boolean = false
    ): HookHandle? = installBooleanReplacement(id, method, value = false, deoptimize = deoptimize)

    /**
     * Replace a method with one that always returns `true`.
     */
    fun replaceBooleanTrue(
        id: String,
        method: Method?,
        deoptimize: Boolean = false
    ): HookHandle? = installBooleanReplacement(id, method, value = true, deoptimize = deoptimize)

    private fun installBooleanReplacement(
        id: String,
        method: Method?,
        value: Boolean,
        deoptimize: Boolean
    ): HookHandle? {
        if (method == null) {
            log.warn("skip hook $id (method not found)")
            return null
        }
        if (method.returnType != java.lang.Boolean.TYPE) {
            log.warn("skip hook $id: not a boolean primitive method (returnType=${method.returnType.simpleName})")
            return null
        }
        return installInternal(id, method, deoptimize) {
            // Boolean primitive replacement: chain is unused.
            value
        }
    }

    /**
     * Install a hook that runs the supplied [hooker] against [method]. Returns null and logs WARN
     * if [method] is null.
     */
    fun install(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        body: (Chain) -> Any?
    ): HookHandle? {
        if (method == null) {
            log.warn("skip hook $id (method not found)")
            return null
        }
        return installInternal(id, method, deoptimize, body)
    }

    /**
     * Install a filter-style hook: original method is invoked unless [shouldBlock] matches.
     * [shouldBlock] receives the immutable argument list; return `true` to short-circuit with
     * `false`, `false` to call the original.
     */
    fun installBooleanFilter(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        shouldBlock: (args: List<Any?>) -> Boolean
    ): HookHandle? {
        if (method == null) {
            log.warn("skip hook $id (method not found)")
            return null
        }
        if (method.returnType != java.lang.Boolean.TYPE) {
            log.warn("skip hook $id: not a boolean primitive method")
            return null
        }
        return installInternal(id, method, deoptimize) { chain ->
            if (shouldBlock(chain.args)) false else chain.proceed()
        }
    }

    /**
     * 命中审计：每个 hook 被真实调用时记一行日志，用于**在真机上证明这条 hook 在链路上**。
     *
     * 为什么需要：`replaceBooleanFalse` 这类 hook 命中时是静默的（方法直接返回 false，
     * App 只是"没展示广告"），日志里什么都看不到——于是「目标方法存在 + 调用点数量没变」
     * 就成了唯一证据，而这并不能证明它真的被调用过。激励秒领那轮已经证明这种
     * 「静态看着对、实机不在链路上」的坑有多致命。
     *
     * 每个 id 只记前 [AUDIT_MAX_LOGS] 次，避免高频 hook（如 ExperimentUtil.p）刷屏；
     * 之后每 [AUDIT_EVERY] 次再记一行，用来确认它仍在被调用。
     */
    private fun auditHit(id: String) {
        if (!AUDIT_HOOK_HITS) return
        val n = hitCounts.merge(id, 1, Int::plus) ?: 1
        if (n <= AUDIT_MAX_LOGS || n % AUDIT_EVERY == 0) {
            log.info("hook hit[$n]: $id")
        }
    }

    private fun installInternal(
        id: String,
        method: Method,
        deoptimize: Boolean,
        body: (Chain) -> Any?
    ): HookHandle? {
        return try {
            if (deoptimize) {
                // Force callers to not inline the callee, so the hook can take effect.
                runCatching { module.deoptimize(method) }
                    .onFailure { log.warn("deoptimize failed for $id: ${it.javaClass.simpleName}") }
            }
            val handle = module.hook(method)
                .setId(id)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    auditHit(id)
                    body(chain)
                })
            installed += handle
            log.info("hook installed: $id -> ${method.declaringClass.name}#${method.name}")
            handle
        } catch (t: Throwable) {
            log.error("hook install failed: $id (${method.declaringClass.name}#${method.name})", t)
            null
        }
    }

    /**
     * Used by `onHotReloaded()` to retire every hook created by this manager.
     */
    fun unhookAll() {
        installed.forEach { runCatching { it.unhook() } }
        installed.clear()
    }

    private companion object {
        /**
         * 是否记录 hook 命中（每个 id 前 3 次 + 之后每 200 次一条）。
         *
         * 真机核验 hook 是否真的在链路上时打开它——
eplaceBooleanFalse 这类 hook 命中时是
         * 静默的，不开审计就只能靠"目标方法存在 + 调用点数量没变"来推断，而那并不能证明
         * 它真的被调用过。发布版保持 false，日志更安静。
         */
        const val AUDIT_HOOK_HITS = false

        const val AUDIT_MAX_LOGS = 3
        const val AUDIT_EVERY = 200
    }
}