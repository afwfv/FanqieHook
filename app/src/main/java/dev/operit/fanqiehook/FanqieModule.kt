package dev.operit.fanqiehook

import android.content.pm.PackageInfo
import java.io.File
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
 * Safety gates applied BEFORE installing any hook:
 *   - Package name must be one of [TARGET_PACKAGES] (番茄小说 / 红果免费短剧).
 *   - Process name must equal the package name, i.e. the host's main process (do NOT touch
 *     `:push`, `:widgetProvider`, `:miniappX`, etc.).
 *   - versionCode is read and reported, but is **advisory only** — see [SUPPORTED_VERSION_CODES].
 *
 * ## Why the version gate is advisory
 *
 * Every hook resolves its own target through [ClassResolver] and skips itself with a WARN when
 * that target is gone; [HookManager] isolates each install in try/catch and keeps a list of the
 * ones that were skipped. A host update therefore degrades hook-by-hook instead of disabling the
 * module, and `install summary: installed=N skipped=M lost=[...]` names exactly what was lost.
 *
 * The previous design was fail-closed: an unknown versionCode meant *no* hook was installed at
 * all, so the module looked broken after every host update until someone re-audited the DEX.
 * That traded a small, visible, partial loss for a total one. Best-effort is strictly better,
 * because the remaining failure mode (a target that moved) is reported in one log line rather
 * than requiring a full re-adaptation.
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

        // Third log channel: a plain file under the host's cache dir. Needed because some LSPosed
        // forks never flush their module log, and some devices disable logging system-wide
        // (logcat returns nothing even as root) — see ModuleLog.
        //
        // Opened BEFORE the version check so the "unverified host version" warning and the
        // install summary below always land in the file, on the channel that actually works.
        log.statusFile = runCatching {
            File(param.applicationInfo.dataDir, "cache/fanqiehook.log")
        }.getOrNull()

        val auditedVersions = SUPPORTED_VERSION_CODES.getValue(packageName)
        val versionCode = readVersionCode(param)
        when {
            versionCode in auditedVersions -> {
                log.info("host versionCode=$versionCode is in the audited set")
            }
            versionCode == -1L -> {
                log.warn(
                    "versionCode could not be read on this device (all strategies failed); " +
                        "installing best-effort against an unknown host version"
                )
            }
            else -> {
                log.warn(
                    "unverified host version: versionCode=$versionCode " +
                        "(audited: ${auditedVersions.sorted()}). Installing best-effort — each " +
                        "hook looks up its own target and skips itself if the target moved, so " +
                        "check the install summary below to see what this version lost."
                )
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

        // The one line that answers "did this host version break anything?".
        // INFO when nothing that this host is expected to have went missing, WARN (naming the
        // unexpected losses) when the host moved a target the module still expects.
        val summary = manager.summary()
        if (manager.unexpectedSkips.isEmpty()) {
            log.info("install summary: $summary")
        } else {
            log.warn("install summary: $summary")
        }
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
     *  1. [ApkVersion.readVersionCode] – parse `versionCode` out of the host APK's binary
     *     AndroidManifest.xml. Public formats only (ZIP + AXML chunks), no Context, no hidden API;
     *     this is the strategy that actually works on Android 14+ (see [ApkVersion] for the
     *     measured failure of strategies 2 and 3 there).
     *  2. [PackageManager.getPackageArchiveInfo] – public static API, no Context required.
     *  3. `ActivityThread.currentApplication()` reflection – the legacy path; retained as a
     *     fallback but unusable at this lifecycle stage (the Application does not exist yet).
     *  4. Return `-1` so the fail-closed gate can decide whether to refuse hook installation.
     */
    private fun readVersionCode(param: PackageReadyParam): Long {
        // Strategy 1: parse the host APK's AndroidManifest.xml directly.
        val viaManifest = runCatching {
            ApkVersion.readVersionCode(param.applicationInfo.sourceDir)
        }.getOrElse { e ->
            log.warn("manifest versionCode parse failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (viaManifest != null) return viaManifest

        // Strategy 2: static PackageManager.getPackageArchiveInfo(sourceDir, flags)
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

        // Strategy 3: reflect into ActivityThread.currentApplication() → PackageManager
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

        log.error("all versionCode strategies failed; continuing best-effort with an unknown version")
        return -1L
    }

    private companion object {
        const val UNKNOWN_PROCESS = "<unknown>"

        // ---- Module gates -------------------------------------------------------
        // [SUPPORTED_VERSION_CODES] is an **audit record, not a gate**: it lists the versions that
        // went through the full DEX-level hook-target audit. A versionCode outside these sets is
        // logged as "unverified host version" and then installed best-effort anyway.
        //
        // Adding an entry here is a documentation act ("these targets were verified"), never a
        // prerequisite for the module to work on a new host release.
        //
        // `com.dragon.read`  – 番茄小说
        // `com.phoenix.read` – 红果免费短剧
        //
        // Both are built from the same ByteDance "dragon" baseline (identical versionCode per
        // release) and still ship the ad classes under the `com.dragon.read.*` namespace, so a
        // single AdHooks implementation covers both. Obfuscated delegate names DO differ between
        // them and between releases (e.g. the NsAdConfigManagerApi impl is `fe3.a` on Fanqie
        // 73532, `lf3.a` on Fanqie 73732, `yb3.a` on Hongguo 73732), which is exactly why those
        // are resolved through DexKit by interface rather than by hardcoded name.
        //
        // Audit history (DEX-level target + call-site verification performed for each entry):
        //   73532 (7.3.5.32) Fanqie + Hongguo – 26 targets, all class/method signatures match;
        //                    Fanqie side misses only the Hongguo-only HongguoBannerServiceImpl
        //   73732 (7.3.7.32) Fanqie – 25/26 (same Hongguo-only miss), every hook's invoke-site
        //                    count inside the target's type family identical to 73532
        //   73732 (7.3.7.32) Hongguo – 26/26
        //   73718 (7.3.7.18) Fanqie – 25/26 (same Hongguo-only miss); all 21 blocked position
        //                    strings present with identical occurrence counts to 73732;
        //                    DexKit-resolved impl identical (lf3.a). Hongguo 73718 not audited
        //                    (no APK available), hence not registered below.
        // Versions sharing one AdHooks implementation because no target moved between them.
        val SUPPORTED_VERSION_CODES = mapOf(
            "com.dragon.read" to setOf(73532L, 73718L, 73732L),
            "com.phoenix.read" to setOf(73532L, 73732L)
        )

        val TARGET_PACKAGES = SUPPORTED_VERSION_CODES.keys
    }
}
