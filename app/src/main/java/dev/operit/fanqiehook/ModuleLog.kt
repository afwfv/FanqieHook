package dev.operit.fanqiehook

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * Unified logging facade. Every call routes through `XposedModule.log`, which the framework
 * routes to LSPosed's logcat tag (visible in `adb logcat -s FanqieHook:*`).
 *
 * Levels follow `android.util.Log`:
 *   VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6, ASSERT=7
 */
class ModuleLog(private val module: XposedModule) {

    fun verbose(message: String) = module.log(Log.VERBOSE, TAG, "[VERBOSE] $message")

    fun debug(message: String) = module.log(Log.DEBUG, TAG, "[DEBUG] $message")

    fun info(message: String) = module.log(Log.INFO, TAG, "[INFO] $message")

    fun warn(message: String) = module.log(Log.WARN, TAG, "[WARN] $message")

    fun warn(message: String, tr: Throwable?) = module.log(Log.WARN, TAG, "[WARN] $message", tr)

    fun error(message: String) = module.log(Log.ERROR, TAG, "[ERROR] $message")

    fun error(message: String, tr: Throwable?) = module.log(Log.ERROR, TAG, "[ERROR] $message", tr)

    private companion object {
        const val TAG = "FanqieHook"
    }
}