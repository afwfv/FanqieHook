package dev.operit.fanqiehook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified logging facade.
 *
 * Every message goes out through up to **three** channels, because no single one is reliable
 * across the LSPosed builds found in the wild:
 *
 *  1. `XposedModule.log(...)` — the framework's own module log. On stock LSPosed this lands in
 *     `/data/adb/lspd/log/modules_*.log` and is the canonical place to look.
 *  2. `android.util.Log` — ordinary logcat, for `adb logcat -s FanqieHook`.
 *  3. A plain status file inside the **host app's** cache dir ([statusFile]).
 *
 * Why 2 and 3 exist (both measured on real devices, not hypothetical):
 *   - Some LSPosed forks never flush the module log file: it stayed at 21 bytes — just its
 *     header — while the module was demonstrably injected and running.
 *   - Some devices ship with system-wide logging disabled: `adb logcat -d` returned zero lines
 *     even when run as root, which kills channel 2 as well.
 *
 * With channel 3, `adb shell su -c 'cat /data/data/com.dragon.read/cache/fanqiehook.log'` still
 * reports what the module did — which is the only way to verify hook installation on such a
 * device.
 *
 * Levels follow `android.util.Log`: VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6.
 */
class ModuleLog(
    private val module: XposedModule,
    /**
     * Set once the host's data dir is known (i.e. in `onPackageReady`); before that, messages only
     * go to channels 1 and 2. Mutable because the earliest lifecycle callback
     * (`onModuleLoaded`) runs before any host path is available.
     */
    @Volatile
    var statusFile: File? = null
) {

    fun verbose(message: String) = emit(Log.VERBOSE, "VERBOSE", message, null)

    fun debug(message: String) = emit(Log.DEBUG, "DEBUG", message, null)

    fun info(message: String) = emit(Log.INFO, "INFO", message, null)

    fun warn(message: String) = emit(Log.WARN, "WARN", message, null)

    fun warn(message: String, tr: Throwable?) = emit(Log.WARN, "WARN", message, tr)

    fun error(message: String) = emit(Log.ERROR, "ERROR", message, null)

    fun error(message: String, tr: Throwable?) = emit(Log.ERROR, "ERROR", message, tr)

    private fun emit(priority: Int, level: String, message: String, tr: Throwable?) {
        val line = "[$level] $message"
        runCatching { module.log(priority, TAG, line, tr) }
        runCatching { Log.println(priority, TAG, line) }
        writeStatus("$level $message")
    }

    /**
     * Append one line to the status file. Failures are ignored — diagnostics must never break the host.
     *
     * When the file crosses [MAX_STATUS_BYTES] we keep the most recent [KEEP_STATUS_BYTES] rather
     * than wiping the file. Wiping was a self-inflicted bug: the previous version truncated to a
     * single `... truncated ...` line, throwing away every diagnostic line written earlier in the
     * same process — including the `unverified host version` warning and the per-hook skip reasons
     * that the user would have needed to understand a post-upgrade regression.
     */
    private fun writeStatus(text: String) {
        val f = statusFile ?: return
        runCatching {
            if (f.length() > MAX_STATUS_BYTES.toLong()) trim(f)
            f.appendText("${TIME_FORMAT.format(Date())} $text\n")
        }
    }

    /**
     * Roll the status file: keep the newest [KEEP_STATUS_BYTES] of its content (cut at a line
     * boundary so we never preserve a half-written line) and prepend a marker.
     */
    private fun trim(f: File) {
        val all = runCatching { f.readText() }.getOrNull() ?: run {
            f.writeText("... status file could not be read for trimming ...\n")
            return
        }
        val tail = if (all.length > KEEP_STATUS_BYTES) all.takeLast(KEEP_STATUS_BYTES) else all
        val cut = tail.indexOf('\n')
        val kept = if (cut >= 0 && cut + 1 < tail.length) tail.substring(cut + 1) else tail
        f.writeText(
            "... older lines dropped (file exceeded ${MAX_STATUS_BYTES / 1024} KB) ...\n$kept"
        )
    }

    private companion object {
        const val TAG = "FanqieHook"

        /** Status file is rolled once it exceeds this size, in bytes. */
        const val MAX_STATUS_BYTES = 256 * 1024

        /** After a roll we keep this much recent content (cut at a line boundary), in bytes. */
        const val KEEP_STATUS_BYTES = 192 * 1024

        val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
