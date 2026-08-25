package dev.operit.fanqiehook

import android.content.pm.PackageInfo
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import dev.operit.fanqiehook.hooks.AdHooks

/**
 * Modern libxposed API 102 entry point.
 *
 * Lifecycle (verbatim from the official interface):
 *   1. [onModuleLoaded]   – once per process the module is loaded into
 *   2. [onPackageLoaded]  – package parsed, default classloader available (Q+)
 *   3. [onPackageReady]   – AppComponentFactory created; the classloader we want is here
 *   4. [onHotReloading] / [onHotReloaded] – module reloaded in place; tear down old hooks
 *
 * Safety gates applied BEFORE installing any hook (fail-closed):
 *   - Package name must be one of [TARGET_PACKAGES] (番茄小说 / 红果免费短剧).
 *   - Process name must equal the package name, i.e. the host's main process (do NOT touch
 *     `:push`, `:widgetProvider`, `:miniappX`, etc. – see § 6 of the analysis report).
 *   - versionCode must equal the value registered for that package in
 *     [SUPPORTED_VERSION_CODES] exactly. A new APK version that refactors a single class will
 *     silently break hardcoded hooks; refuse to install instead of crashing inside the host app.
 */
class FanqieModule : XposedModule() {

    private val log by lazy { ModuleLog(this) }

    @Volatile
    private var processName: String = UNKNOWN_PROCESS

    @Volatile
    private var hookManager: HookManager? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log.info(
            "module loaded: process=$processName api=$apiVersion " +
                "framework=$frameworkName v${frameworkVersion}"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Optional hook point for early init; we wait for onPackageReady because that is when the
        // app classloader is fully wired.
        if (param.packageName !in TARGET_PACKAGES) return
        if (!param.isFirstPackage) return
        log.debug("package loaded: ${param.packageName} (process=$processName)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName !in TARGET_PACKAGES) {
            // Scope list should already filter, but defensively short-circuit.
            return
        }
        if (!param.isFirstPackage) {
            log.debug("not first package: $packageName, skip")
            return
        }
        // Every supported host names its main process exactly after the package name; any other
        // value means we landed in `:push`, `:widgetProvider`, `:miniappX`, … which must stay clean.
        if (processName != packageName) {
            log.info("skip non-main process: $processName (package=$packageName)")
            return
        }

        val expectedVersionCode = SUPPORTED_VERSION_CODES.getValue(packageName)
        val versionCode = readVersionCode(param)
        if (versionCode != expectedVersionCode) {
            if (FAIL_OPEN && versionCode == -1L) {
                log.warn(
                    "versionCode unknown (hidden-API blocked on this device). FAIL_OPEN=true; " +
                        "proceeding to install hooks. Verify hook targets manually via logcat."
                )
            } else {
                log.warn(
                    "unsupported versionCode=$versionCode for $packageName; " +
                        "expected=$expectedVersionCode. " +
                        "Refusing to install hooks to avoid version mismatch."
                )
                return
            }
        }

        log.info(
            "target ready: package=$packageName process=$processName versionCode=$versionCode"
        )

        val resolver = ClassResolver(
            classLoader = param.classLoader,
            log = log,
            // DexKit 2.x loads the DEX straight off disk rather than from the classloader, so it
            // needs the host's APK source dir.
            apkPath = param.applicationInfo.sourceDir,
            // The module's own APK: source of libdexkit.so. DexKit never loads it itself, and in
            // an injected host process System.loadLibrary cannot see the host's lib path, so the
            // resolver must extract + System.load() it from here (see ClassResolver docs).
            moduleApkPath = runCatching { getModuleApplicationInfo()?.sourceDir }.getOrNull(),
            // Host-writable dir used when the .so must be extracted (module runs as host UID).
            hostDataDir = param.applicationInfo.dataDir
        )
        val manager = HookManager(this, log).also { hookManager = it }

        // Single entry point for every ad-related hook.
        // Each `installXxx` is internally try/catch; one failure cannot stop the rest.
        AdHooks(manager, resolver, log).installAll()
    }

    /**
     * Hot reload: release all hooks so the new generation can re-register them atomically.
     * The framework guarantees the hook chain is snapshotted; in-flight calls are unaffected.
     *
     * Return `true` to confirm that all handles have been retired; `false` would tell the framework
     * to keep the old handles in place (rarely useful; we always want a clean swap).
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log.info("hot reloading; releasing ${hookManager?.installedHandles?.size ?: 0} hook handles")
        hookManager?.unhookAll()
        hookManager = null
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // The new generation will be re-installed through its own `onPackageReady` callback.
        log.info("hot reload complete; new generation will re-hook via onPackageReady")
    }

    /**
     * Read the host app's `versionCode` with multiple strategies, ordered by independence from
     * host state.
     *
     *  1. [PackageManager.getPackageArchiveInfo] – public static API, no Context required,
     *     works around the Android 14+ hidden-API greylist that blocks reflective access to
     *     `ActivityThread.currentApplication()`.
     *  2. `ActivityThread.currentApplication()` reflection – the legacy path; kept as fallback.
     *  3. Return `-1` so the fail-closed gate can decide whether to refuse hook installation.
     */
    private fun readVersionCode(param: PackageReadyParam): Long {
        // Strategy 1: static PackageManager.getPackageArchiveInfo(sourceDir, flags)
        val viaArchive = runCatching {
            val apkPath = param.applicationInfo.sourceDir
            val pmClass = Class.forName(
                "android.content.pm.PackageManager",
                false,
                param.classLoader
            )
            val method = pmClass.getMethod(
                "getPackageArchiveInfo",
                java.lang.String::class.java,
                java.lang.Integer.TYPE
            )
            val packageInfo = method.invoke(null, apkPath, 0) as? PackageInfo
                ?: throw IllegalStateException("getPackageArchiveInfo returned null")
            packageInfo.longVersionCode
        }.getOrElse { e ->
            log.warn("getPackageArchiveInfo failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (viaArchive != null) return viaArchive

        // Strategy 2: reflect into ActivityThread.currentApplication() → PackageManager
        val viaActivityThread = runCatching {
            val activityThread = Class.forName(
                "android.app.ActivityThread",
                false,
                param.classLoader
            )
            val application = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? android.app.Application
                ?: throw IllegalStateException("currentApplication returned null")
            val packageInfo: PackageInfo = application.packageManager
                .getPackageInfo(param.packageName, 0)
            packageInfo.longVersionCode
        }.getOrElse { e ->
            log.warn("ActivityThread.currentApplication failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (viaActivityThread != null) return viaActivityThread

        log.error("all versionCode strategies failed; gate will refuse hooks unless FAIL_OPEN is true")
        return -1L
    }

    private companion object {
        const val UNKNOWN_PROCESS = "<unknown>"

        // ---- Module gates -------------------------------------------------------
        // Bump an entry in SUPPORTED_VERSION_CODES only after re-running the round-1 reverse
        // analysis on that APK.
        //
        // `com.dragon.read`  – 番茄小说
        // `com.phoenix.read` – 红果免费短剧
        //
        // Both are built from the same ByteDance "dragon" baseline (identical versionCode 73332)
        // and still ship the ad classes under the `com.dragon.read.*` namespace, so a single
        // AdHooks implementation covers both. Obfuscated delegate names DO differ between them
        // (`h83.a` vs `n83.a` for the NsAdConfigManagerApi impl), which is exactly why those are
        // resolved through DexKit by interface rather than by hardcoded name.
        val SUPPORTED_VERSION_CODES = mapOf(
            "com.dragon.read" to 73332L,
            "com.phoenix.read" to 73332L
        )

        val TARGET_PACKAGES = SUPPORTED_VERSION_CODES.keys

        /**
         * If the host's `versionCode` cannot be determined (e.g. Android 14+ greylist blocks every
         * reflective path), set this to `true` to bypass the gate and install hooks anyway. The
         * hooks themselves will log WARN/ERROR when their targets are missing or refactored, so
         * breakage is observable; the user accepts the risk of an unverified version match.
         */
        const val FAIL_OPEN = true
    }
}