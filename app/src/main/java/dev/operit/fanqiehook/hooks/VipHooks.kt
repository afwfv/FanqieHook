package dev.operit.fanqiehook.hooks

import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog

/**
 * 会员（VIP）判定放行。
 *
 * ## 为什么挂在这里
 *
 * 番茄的会员判定只有一个收敛点：`PrivilegeManager`（`com.dragon.read.component.biz.impl
 * .privilege.PrivilegeManager`）。全进程里有 **65 处** `invoke-interface` 打在
 * `IPrivilegeManager.isVip()` 上（我的页、阅读器免下载、广告 SDK、书城各 holder……），
 * 最终都落到 `PrivilegeManager.isVip()`；而 `NsVipImpl` 里面那批 `isVip(subType)` /
 * `canReadPaidBook` / `has*Privilege` 只是对它的包装。
 *
 * 所以只要把 `isVip()` 与它的几个包装点按下，**所有走客户端的会员判定**就都放行了，
 * 不需要去追那 65 个调用点。
 *
 * ## 边界（写清楚，免得被当成"破解成功"）
 *
 * 客户端只能改「我是不是会员」这个本地判断：
 *
 *   - 会员标识、免广告、下载、听书、离线、自动翻页这些**客户端闸门**会放行；
 *   - **VIP 章节正文仍然由服务端按账号下发**。服务端不认这个账号的会员，正文接口该锁还是锁，
 *     界面上会出现"已解锁但内容拉不到"的空态 —— 这是服务端权威，不是 hook 没生效。
 *
 * ## 为什么每条都 deoptimize
 *
 * 这批 getter 全是高频小方法，ART 会把它们内联进调用方；内联出去的副本不经过 hook，
 * 表现就是"大部分时候生效、偶尔失效且日志里一片安静"。v0.8.8 的「偶发漏广告」就是
 * 这一条踩出来的，所以这里一律 `deoptimize = true`。
 */
class VipHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog
) {

    fun installAll() {
        installPrivilegeManagerHooks()
        installNsVipImplHooks()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. PrivilegeManager —— 会员判定的唯一收敛点
    // ─────────────────────────────────────────────────────────────────────────

    private fun installPrivilegeManagerHooks() {
        val pm = "com.dragon.read.component.biz.impl.privilege.PrivilegeManager"

        // 唯一一条 native 方法：Java 层没有实现，签名校验把它沉到了 native（Java2C 那套）。
        // LSPlant 挂在 ArtMethod 上，native 方法同样可挂；挂不上会走 WARN 而不是整体失败。
        hooks.replaceBooleanTrue(
            id = "vip:isVip",
            method = resolver.findMethod(pm, "isVip"),
            deoptimize = true
        )

        // 「当前账号是不是任意一种会员」：我的页、广告位判定都读它。
        hooks.replaceBooleanTrue(
            id = "vip:isAnyVip",
            method = resolver.findMethod(pm, "isAnyVip"),
            deoptimize = true
        )

        // 按子类型（Default / 听书 / 短剧…）判断会员：NsVipImpl.isVip(subType) 直接转调这里。
        // 参数类型是混淆过的（VipCommonSubType），按名字 + 返回类型定位。
        hooks.replaceBooleanTrue(
            id = "vip:isVipBySubType",
            method = resolver.findMethodIgnoringParams(pm, "b", returnTypeName = "boolean"),
            deoptimize = true
        )

        // 权益类：每条都是 `hasPrivilege("<权益id>")` 的包装，native 查表。
        // 逐个放行，比只挂 hasPrivilege 更稳（后者是 native，参数是权益 id 字符串）。
        val privileges = listOf(
            "hasReadPaidBookPrivilege",      // 付费书阅读
            "hasTtsPrivilege",               // 听书
            "hasTtsNaturePrivilege",         // 听书（原生音色）
            "hasTtsConsumptionPrivilege",    // 听书（时长包）
            "hasAutoPagePrivilege",          // 自动翻页
            "hasOfflineReadingPrivilege",    // 离线阅读
            "hasVipShortSeriesPrivilege",    // 会员短剧
            "hasInspireBookPrivilege"        // 激励书
        )
        privileges.forEach { name ->
            hooks.replaceBooleanTrue(
                id = "vip:$name",
                method = resolver.findMethod(pm, name),
                deoptimize = true
            )
        }

        // 带书 id / 场景参数的权益判断。
        hooks.replaceBooleanTrue(
            id = "vip:hasBookDownloadPrivilege",
            method = resolver.findMethod(pm, "hasBookDownloadPrivilege", "String"),
            deoptimize = true
        )
        hooks.replaceBooleanTrue(
            id = "vip:hasLocalOfflineReadPrivilege",
            method = resolver.findMethod(pm, "hasLocalOfflineReadPrivilege", "String"),
            deoptimize = true
        )
        hooks.replaceBooleanTrue(
            id = "vip:gotLocalOfflineReadPrivilege",
            method = resolver.findMethod(pm, "gotLocalOfflineReadPrivilege", "String"),
            deoptimize = true
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. NsVipImpl —— 上层包装
    // ─────────────────────────────────────────────────────────────────────────

    private fun installNsVipImplHooks() {
        val impl = "com.dragon.read.component.biz.impl.NsVipImpl"

        // isNormalUser = !isVip。放行 VIP 时它必须转成 false，否则「我的」页会同时出现
        // 会员态与非会员态两套 UI。
        hooks.replaceBooleanFalse(
            id = "vip:isNormalUser",
            method = resolver.findMethod(impl, "isNormalUser"),
            deoptimize = true
        )

        hooks.replaceBooleanTrue(
            id = "vip:nsIsVip",
            method = resolver.findMethodIgnoringParams(impl, "isVip", returnTypeName = "boolean"),
            deoptimize = true
        )

        // 阅读 / 听书 / 已购判定。
        // canReadPaidBook / canReadPaidBookEnhance 的参数类型是混淆过的（dw3.f / boolean），
        // 按名字 + 返回类型定位，不锁参数签名。
        listOf(
            "canReadPaidBook",
            "canReadPaidBookEnhance",
            "canListenPaidBook"
        ).forEach { name ->
            hooks.replaceBooleanTrue(
                id = "vip:$name",
                method = resolver.findMethodIgnoringParams(impl, name, returnTypeName = "boolean"),
                deoptimize = true
            )
        }

        hooks.replaceBooleanTrue(
            id = "vip:isBuyPaidBook",
            method = resolver.findMethod(impl, "isBuyPaidBook", "String"),
            deoptimize = true
        )
    }
}
