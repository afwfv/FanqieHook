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
        hooker: Hooker
    ): HookHandle? {
        if (method == null) {
            log.warn("skip hook $id (method not found)")
            return null
        }
        return installInternal(id, method, deoptimize, hooker)
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
        return installInternal(id, method, deoptimize, Hooker { chain ->
            if (shouldBlock(chain.args)) false else chain.proceed()
        })
    }

    /**
     * Install a logger hook: invokes the original and logs the call. Useful for dynamic validation
     * during the first deployment round.
     */
    fun installLogger(
        id: String,
        method: Method?,
        deoptimize: Boolean = false
    ): HookHandle? {
        if (method == null) {
            log.warn("skip log hook $id (method not found)")
            return null
        }
        return installInternal(id, method, deoptimize, Hooker { chain ->
            log.debug("$id invoked args=${chain.args}")
            chain.proceed()
        })
    }

    private fun installInternal(
        id: String,
        method: Method,
        deoptimize: Boolean,
        hooker: Hooker
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
                .intercept(hooker)
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
}