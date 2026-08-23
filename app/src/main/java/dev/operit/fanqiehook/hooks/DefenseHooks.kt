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
 * 所有钩子无条件装配；每个回调内实时读取对应开关（[ModuleConfig]），
 * 关闭时按原逻辑放行 —— 开关改动免重启即时生效。
 *
 * All targets verified present in com.dragon.read v73332 (see MK module decompile,
 * FQ 1.0). Obfuscated-short names ($c, b, k) are the same version-pinned names MK uses;
 * [HookSupport.hookByName] would be the fallback if they drift in future releases.
 */
object DefenseHooks {

    private const val TAG = "Defense"

    fun installAll() {
        installBlockUpdates()
        installBlockHotUpdate()
        installBlockPluginLoad()
        installBlockCrashReport()
        installUnlockBooks()
    }

    // ── 1. 屏蔽应用内更新 ─────────────────────────────────────────────────────

    private fun installBlockUpdates() {
        val listener = "com.ss.android.update.OnUpdateStatusChangedListener"
        val svc = "com.ss.android.update.UpdateServiceImpl"

        // Three checkUpdate overloads; each simply never runs.
        HookSupport.safeHook(TAG, svc, "checkUpdate",
            arrayOf("int", "int", listener, "boolean"), "屏蔽更新(int,int,listener,z)") { chain ->
            if (ModuleConfig.blockUpdates()) null else chain.proceed()
        }
        HookSupport.safeHook(TAG, svc, "checkUpdate",
            arrayOf("int", listener), "屏蔽更新(int,listener)") { chain ->
                if (ModuleConfig.blockUpdates()) null else chain.proceed()
            }
        HookSupport.safeHook(TAG, svc, "checkUpdate",
            arrayOf("int", listener, "boolean"), "屏蔽更新(int,listener,z)") { chain ->
                if (ModuleConfig.blockUpdates()) null else chain.proceed()
            }
    }

    // ── 2. 屏蔽 Reparo 热更新 ────────────────────────────────────────────────

    private fun installBlockHotUpdate() {
        val impl = "com.dragon.read.base.hotfix.ReparoHotFixInitServiceImpl"

        HookSupport.safeHook(TAG, impl, "initialize",
            arrayOf("android.app.Application"), "热更-initialize") { chain ->
                if (ModuleConfig.blockHotUpdate()) null else chain.proceed()
            }
        HookSupport.safeHook(TAG, impl, "loadRemotePatch",
            null, "热更-loadRemotePatch") { chain ->
                if (ModuleConfig.blockHotUpdate()) null else chain.proceed()
            }
        // Inner config class: force enable() to false instead of skipping (keeps
        // the caller's state machine consistent).
        HookSupport.safeHook(TAG, "$impl\$c", "enable",
            null, "热更-配置enable=false") { chain ->
                if (ModuleConfig.blockHotUpdate()) false else chain.proceed()
            }
    }

    // ── 3. 屏蔽 Mira 插件加载 ────────────────────────────────────────────────

    private fun installBlockPluginLoad() {
        val impl = "com.dragon.read.base.plugin.MiraPluginInitServiceImpl"

        HookSupport.safeHook(TAG, impl, "initialize",
            arrayOf("android.app.Application"), "插件-initialize") { chain ->
                if (ModuleConfig.blockPluginLoad()) null else chain.proceed()
            }
        HookSupport.safeHook(TAG, impl, "initMira",
            arrayOf("android.app.Application"), "插件-initMira") { chain ->
                if (ModuleConfig.blockPluginLoad()) null else chain.proceed()
            }
        HookSupport.safeHook(TAG, impl, "loadRemotePlugin",
            null, "插件-loadRemotePlugin") { chain ->
                if (ModuleConfig.blockPluginLoad()) null else chain.proceed()
            }
    }

    // ── 4. 屏蔽崩溃上报 ──────────────────────────────────────────────────────

    private fun installBlockCrashReport() {
        // 新的崩溃上报入口在 Npth（旧的 NpthCore.k 已不存在）。
        // 把所有 init 重载短路掉即可彻底屏蔽 native crash pipeline。
        val npth = "com.bytedance.crash.Npth"
        HookSupport.safeHook(TAG, npth, "init",
            arrayOf("android.app.Application", "android.content.Context",
                "com.bytedance.crash.ICommonParams", "boolean", "boolean",
                "boolean", "boolean", "long"),
            "崩溃-Npth.init7") { chain ->
                if (ModuleConfig.blockCrashReport()) null else chain.proceed()
            }
        HookSupport.safeHook(TAG, npth, "init",
            arrayOf("android.content.Context", "com.bytedance.crash.ICommonParams",
                "boolean", "boolean", "boolean"),
            "崩溃-Npth.init5") { chain ->
                if (ModuleConfig.blockCrashReport()) null else chain.proceed()
            }
        HookSupport.safeHook(TAG, npth, "init",
            arrayOf("android.content.Context", "com.bytedance.crash.ICommonParams"),
            "崩溃-Npth.init2") { chain ->
                if (ModuleConfig.blockCrashReport()) null else chain.proceed()
            }

        // CrashReportConfig：单例方法 a() 返回 instance。
        // 用 enable=false + 空 keyWords 重建，绕过 ssconfig 默认值。
        val cfg = "com.dragon.read.base.ssconfig.template.CrashReportConfig"
        HookSupport.safeHook(TAG, cfg, "a", null, "崩溃-Config.a") { chain ->
            if (!ModuleConfig.blockCrashReport()) return@safeHook chain.proceed()
            try {
                val cls = HookSupport.dragonLoader().loadClass(cfg)
                cls.declaredConstructors
                    .firstOrNull { it.parameterCount == 2 }
                    ?.apply { isAccessible = true }
                    ?.newInstance(false, emptyList<Any>())
                    ?: chain.proceed()
            } catch (t: Throwable) {
                chain.proceed()
            }
        }
    }

    // ── 5. 解锁下架/违禁书 ───────────────────────────────────────────────────

    private fun installUnlockBooks() {
        val utils = "com.dragon.read.util.BookUtils"

        HookSupport.safeHook(TAG, utils, "isOverallOffShelf",
            arrayOf("java.lang.Object"), "解锁-下架书") { chain ->
                if (ModuleConfig.unlockBooks()) false else chain.proceed()
            }
        HookSupport.safeHook(TAG, utils, "isUnsafeBook",
            arrayOf("java.lang.Object"), "解锁-违禁书") { chain ->
                if (ModuleConfig.unlockBooks()) false else chain.proceed()
            }
    }
}
