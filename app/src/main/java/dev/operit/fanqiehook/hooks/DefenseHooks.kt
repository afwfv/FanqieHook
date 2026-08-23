package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.config.ModuleConfig
import dev.operit.fanqiehook.support.HookSupport

/**
 * Defensive hooks adapted from MK's HookManager:
 *
 *  - blockUpdates      : UpdateServiceImpl.checkUpdate × 3 overloads → null (never prompts upgrade)
 *  - blockHotUpdate    : ReparoHotFixInitServiceImpl initialize/loadRemotePatch/$c.enable
 *  - blockPluginLoad   : MiraPluginInitServiceImpl initialize/initMira/loadRemotePlugin
 *  - blockCrashReport  : NpthCore.k + CrashReportConfig.b() forced enable=false
 *  - unlockBooks       : BookUtils.isOverallOffShelf/isUnsafeBook → false
 *
 * All targets verified present in com.dragon.read v73332 (see MK module decompile,
 * FQ 1.0). Obfuscated-short names ($c, b, k) are the same version-pinned names MK uses;
 * [HookSupport.hookByName] would be the fallback if they drift in future releases.
 */
object DefenseHooks {

    private const val TAG = "Defense"

    fun installAll() {
        if (ModuleConfig.blockUpdates()) installBlockUpdates()
        if (ModuleConfig.blockHotUpdate()) installBlockHotUpdate()
        if (ModuleConfig.blockPluginLoad()) installBlockPluginLoad()
        if (ModuleConfig.blockCrashReport()) installBlockCrashReport()
        if (ModuleConfig.unlockBooks()) installUnlockBooks()
    }

    // ── 1. 屏蔽应用内更新 ─────────────────────────────────────────────────────

    private fun installBlockUpdates() {
        val listener = "com.ss.android.update.OnUpdateStatusChangedListener"
        val svc = "com.ss.android.update.UpdateServiceImpl"

        // Three checkUpdate overloads; each simply never runs.
        HookSupport.safeHook(TAG, svc, "checkUpdate",
            arrayOf("int", "int", listener, "boolean"), "屏蔽更新(int,int,listener,z)") { null }
        HookSupport.safeHook(TAG, svc, "checkUpdate",
            arrayOf("int", listener), "屏蔽更新(int,listener)") { null }
        HookSupport.safeHook(TAG, svc, "checkUpdate",
            arrayOf("int", listener, "boolean"), "屏蔽更新(int,listener,z)") { null }
    }

    // ── 2. 屏蔽 Reparo 热更新 ────────────────────────────────────────────────

    private fun installBlockHotUpdate() {
        val impl = "com.dragon.read.base.hotfix.ReparoHotFixInitServiceImpl"

        HookSupport.safeHook(TAG, impl, "initialize",
            arrayOf("android.app.Application"), "热更-initialize") { null }
        HookSupport.safeHook(TAG, impl, "loadRemotePatch",
            null, "热更-loadRemotePatch") { null }
        // Inner config class: force enable() to false instead of skipping (keeps
        // the caller's state machine consistent).
        HookSupport.safeHook(TAG, "$impl\$c", "enable",
            null, "热更-配置enable=false") { false }
    }

    // ── 3. 屏蔽 Mira 插件加载 ────────────────────────────────────────────────

    private fun installBlockPluginLoad() {
        val impl = "com.dragon.read.base.plugin.MiraPluginInitServiceImpl"

        HookSupport.safeHook(TAG, impl, "initialize",
            arrayOf("android.app.Application"), "插件-initialize") { null }
        HookSupport.safeHook(TAG, impl, "initMira",
            arrayOf("android.app.Application"), "插件-initMira") { null }
        HookSupport.safeHook(TAG, impl, "loadRemotePlugin",
            null, "插件-loadRemotePlugin") { null }
    }

    // ── 4. 屏蔽崩溃上报 ──────────────────────────────────────────────────────

    private fun installBlockCrashReport() {
        // Npth native crash pipeline init: k(Context, File) never runs.
        HookSupport.safeHook(TAG, "com.bytedance.crash.NpthCore", "k",
            arrayOf("android.content.Context", "java.io.File"), "崩溃-NpthCore.k") { null }

        // CrashReportConfig.b(): reconstruct with enable=false + empty processors.
        val cfg = "com.dragon.read.base.ssconfig.template.CrashReportConfig"
        HookSupport.safeHook(TAG, cfg, "b", null, "崩溃-Config.b") { chain ->
            try {
                val cls = HookSupport.dragonLoader().loadClass(cfg)
                val ctor = cls.declaredConstructors.first()
                ctor.isAccessible = true
                ctor.newInstance(false, emptyList<Any>())
            } catch (t: Throwable) {
                chain.proceed()
            }
        }
    }

    // ── 5. 解锁下架/违禁书 ───────────────────────────────────────────────────

    private fun installUnlockBooks() {
        val utils = "com.dragon.read.util.BookUtils"

        HookSupport.safeHook(TAG, utils, "isOverallOffShelf",
            arrayOf("java.lang.Object"), "解锁-下架书") { false }
        HookSupport.safeHook(TAG, utils, "isUnsafeBook",
            arrayOf("java.lang.Object"), "解锁-违禁书") { false }
    }
}
