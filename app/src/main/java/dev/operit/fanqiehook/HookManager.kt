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

    /** ids installed on this host version, in install order. */
    private val installedIds = mutableListOf<String>()

    /**
     * ids whose target could not be resolved on this host version (class/method moved or gone).
     *
     * This is the module's update-resilience contract: versionCode is only advisory, so the
     * verdict on whether a host update broke something comes from this list rather than from a
     * static re-audit of the whole target set.
     */
    private val skipped = mutableListOf<String>()

    /**
     * 其中**不是**宿主升级造成的、可以预期的缺失（例如红果专属类在番茄侧本来就不存在）。
     *
     * 单独分出来是为了让摘要保持"一眼可判定"：如果 known-missing 也算进丢失里，番茄上每次开屏
     * 都会看到 `skipped=1`，"有东西丢了"这个信号就被稀释成噪音，升级后真正掉了一条反而看不出来。
     */
    private val knownMissing = mutableListOf<String>()

    /** 每个 hook 的命中次数（审计用）。 */
    private val hitCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    val installedHandles: List<HookHandle> get() = installed.toList()

    /**
     * hook ids that were skipped and are NOT explained by host-specific differences — i.e. the ones
     * that actually indicate this host version moved a target the module still expects.
     */
    val unexpectedSkips: List<String> get() = skipped.filterNot { it in knownMissing }

    /**
     * 安装结果摘要：一行说明「这次装上了几条、丢了哪几条」。
     *
     * 宿主升级后不需要重新适配，靠的就是这条摘要——模块会自行逐条退化，而丢掉的 hook id
     * 直接写在日志里，不用把全部目标重新静态审计一遍。
     */
    fun summary(): String {
        val lost = unexpectedSkips
        val expected = skipped.filter { it in knownMissing }
        val base = "hooks installed=${installedIds.size} skipped=${skipped.size}"
        return buildString {
            append(base)
            if (lost.isNotEmpty()) append(" lost=[${lost.joinToString(", ")}]")
            if (expected.isNotEmpty()) append(" known-missing=[${expected.joinToString(", ")}]")
        }
    }

    /**
     * Record a hook that could not be installed, and explain why in the log.
     *
     * @param knownMissing true when this target is legitimately absent on this host (e.g. a
     *   Hongguo-only class while running under 番茄小说). Such a skip is logged at INFO and shown
     *   separately in [summary] so it cannot mask a real regression.
     */
    private fun noteSkip(id: String, reason: String, knownMissing: Boolean = false): HookHandle? {
        skipped += id
        if (knownMissing) {
            this.knownMissing += id
            log.info("hook $id n/a on this host: $reason")
        } else {
            log.warn("skip hook $id: $reason")
        }
        return null
    }

    /**
     * Replace a method with one that always returns `false`.
     * For boolean methods only. Safe to call with null `method` — it logs and skips.
     *
     * @param knownMissingOnMiss set when the target is expected to be absent on one of the two
     *   supported hosts; keeps it out of the "lost" list in [summary].
     */
    fun replaceBooleanFalse(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        knownMissingOnMiss: Boolean = false
    ): HookHandle? = installBooleanReplacement(
        id, method, value = false, deoptimize = deoptimize, knownMissingOnMiss = knownMissingOnMiss
    )

    /**
     * Replace a method with one that always returns `true`.
     *
     * @param knownMissingOnMiss see [replaceBooleanFalse].
     */
    fun replaceBooleanTrue(
        id: String,
        method: Method?,
        deoptimize: Boolean = false,
        knownMissingOnMiss: Boolean = false
    ): HookHandle? = installBooleanReplacement(
        id, method, value = true, deoptimize = deoptimize, knownMissingOnMiss = knownMissingOnMiss
    )

    private fun installBooleanReplacement(
        id: String,
        method: Method?,
        value: Boolean,
        deoptimize: Boolean,
        knownMissingOnMiss: Boolean = false
    ): HookHandle? {
        if (method == null) {
            return noteSkip(id, "method not found", knownMissingOnMiss)
        }
        if (method.returnType != java.lang.Boolean.TYPE) {
            return noteSkip(
                id,
                "not a boolean primitive method (returnType=${method.returnType.simpleName})"
            )
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
            return noteSkip(id, "method not found")
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
            return noteSkip(id, "method not found")
        }
        if (method.returnType != java.lang.Boolean.TYPE) {
            return noteSkip(id, "not a boolean primitive method")
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
            installedIds += id
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
        installedIds.clear()
        skipped.clear()
        knownMissing.clear()
    }

    private companion object {
        /**
         * 是否记录 hook 命中（每个 id 前 3 次 + 之后每 200 次一条）。
         *
         * 真机核验 hook 是否真的在链路上时打开它——`replaceBooleanFalse` 这类 hook 命中时是
         * 静默的，不开审计就只能靠"目标方法存在 + 调用点数量没变"来推断，而那并不能证明
         * 它真的被调用过。发布版保持 false，日志更安静。
         */
        const val AUDIT_HOOK_HITS = false

        const val AUDIT_MAX_LOGS = 3
        const val AUDIT_EVERY = 200
    }
}