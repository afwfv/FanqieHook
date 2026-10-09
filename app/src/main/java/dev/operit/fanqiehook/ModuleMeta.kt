package dev.operit.fanqiehook

import java.util.zip.ZipFile

/**
 * 从**模块自己的 APK** 里读出 `META-INF/xposed/module.prop`，用于在日志里自报版本。
 *
 * 为什么值得单独做：排查「装了但没效果」的报告时，第一件要知道的事是「跑起来的到底是哪个
 * 版本」——但日志里原先没有任何版本号，只能靠用户口述（"我装的是 0.8.5"），而用户口述经常
 * 和实际不符（升级没成功、装了两个包、管理器显示的和进程里加载的不是同一份）。
 * module.prop 就是 LSPosed 模块中心显示的那一份，直接读它最不容易骗人；
 * 顺带把 APK 路径打出来，克隆/多用户/多安装来源的情况一眼可见。
 *
 * 读失败不抛异常：诊断信息永远不能反过来弄坏宿主。
 */
internal object ModuleMeta {

    private const val ENTRY = "META-INF/xposed/module.prop"

    /** 形如 `version=v0.8.6 (versionCode=29) apk=/data/app/…/base.apk`。 */
    fun describe(apkPath: String?): String {
        if (apkPath.isNullOrEmpty()) return "unknown (module APK path unavailable)"
        return runCatching {
            ZipFile(apkPath).use { zf ->
                val entry = zf.getEntry(ENTRY)
                    ?: return@use "unknown ($ENTRY missing) apk=$apkPath"
                val props = zf.getInputStream(entry).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                    }
                    .toMap()
                val version = props["version"] ?: "?"
                val code = props["versionCode"] ?: "?"
                "$version (versionCode=$code) apk=$apkPath"
            }
        }.getOrElse { t ->
            "unknown (${t.javaClass.simpleName}: ${t.message}) apk=$apkPath"
        }
    }
}
