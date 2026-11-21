package dev.operit.fanqiehook

import java.io.File

/**
 * 运行期开关：`<hostDataDir>/cache/fanqiehook.flags`，`key=0|1`，`#` 开头注释。
 *
 * 为什么需要：新增的观测/对抗 hook 全是「先看数据再决定」，没有开关就只能反复出包重装，
 * 也就不可能真的先观测。默认值写死在这里，文件只覆盖；文件不存在时模块行为与以前一致。
 */
class Flags(private val hostDataDir: String?) {

    private val file: File? by lazy {
        hostDataDir?.let { File("$it/cache/fanqiehook.flags") }
    }

    @Volatile
    private var values: Map<String, Boolean> = emptyMap()

    fun enabled(key: String): Boolean {
        if (values.isEmpty()) reload()
        return values[key] ?: defaults[key] ?: false
    }

    @Synchronized
    fun reload() {
        val f = file
        if (f == null || !f.exists()) {
            values = emptyMap()
            return
        }
        values = runCatching {
            f.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.contains('=') }
                .mapNotNull { line ->
                    val k = line.substringBefore('=').trim()
                    val v = line.substringAfter('=').trim()
                    if (k.isEmpty()) null else k to (v == "1" || v.equals("true", true))
                }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    private companion object {
        val defaults = mapOf(
            // 激励链观测：默认开，只读、只写 jsonl，不改任何返回值
            "n8.rewardTrace" to true,
            // 网络层审计：把广告召回 / 发奖 / 任务 / 金币类请求的 URL 记下来
            "n4.netAudit" to true,
            // 包枚举过滤：把 LSPosed / Magisk / 模块自己从 PackageManager 结果里摘掉
            "n9.hidePackages" to true,
            // 检测命令观测：命中 su/ps/magisk/frida 类命令只记日志
            "n9.logExec" to true,
            // 检测命令拦截：把命令换成无害语句；默认关，开了可能影响宿主功能
            "n9.blockExec" to false,
        )
    }
}