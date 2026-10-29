package dev.operit.fanqiehook.hooks

import android.view.View
import android.view.ViewGroup
import dev.operit.fanqiehook.ClassResolver
import dev.operit.fanqiehook.HookManager
import dev.operit.fanqiehook.ModuleLog

/**
 * View 级别的红果主页面净化 hooks（仅 [HONGGUO_PACKAGE] 宿主安装）。
 *
 * 与 [AdHooks] 的业务闸门不同，下面两个目标在 DEX 里找不到干净的 boolean 判定点
 * （广告 item view 无文字无 id；tab 由服务端下发的 `m3.E()` 列表驱动），所以挂点选在
 * view 树的必经之路上，按运行时解析的 view id 匹配：
 *
 * ## 1. MainFragmentActivity 推荐流里的信息流广告 item
 *
 * 结构（红果 7.2.5.32，jadx 资源表核验：public.xml 中 hap=0x7f112b81、fzp=0x7f112459）：
 *
 *       RecyclerView(id=hap)          ← 广告所在列表
 *        └─ 广告 item view（无 id）    ← dispatchChildAttached 的 child 参数
 *             └─ FrameLayout(id=fzp)   ← 广告容器锚点
 *
 * 挂点：`androidx.recyclerview.widget.RecyclerView#dispatchChildAttached(View)`（包私有，
 * getDeclaredMethod 可达）。RecyclerView 的每个子 view 在 `addView` 之后、LayoutManager
 * 测量它之前都会经过这里（ChildHelper.Callback 实现，RecyclerView.java:4187 → 6278），
 * 因此在这里设置 GONE + 高度 0：
 *   - 不会闪帧 —— 改动发生在同一次布局 pass 里对该 child 的 measure 之前；
 *   - 不留空白 —— LinearLayout/FrameLayout 的 onMeasure 会跳过 GONE 子 view，
 *     item 根 layout 量出来只剩 padding；再叠加 lp.height=0 双保险。
 * 滚动回收重挂时每次都会重新进入，正好覆盖 ViewHolder 复用场景（幂等）。
 *
 * 为什么必须 proceed：dispatchChildAttached 还负责 adapter.onViewAttachedToWindow /
 * OnChildAttachStateChangeListener 分发，拦掉会破坏 RecyclerView 的回收簿记。
 * 我们只改可见性，不改变调用语义。
 *
 * ## 2. 底部 tab 栏的「福利」(id=aqp) 与「商城」(id=aqr)
 *
 * 挂点：`com.dragon.read.widget.BottomTabBarLayout#b2(bt5.h)` —— tab view 进底栏的
 * 唯一入口；MainFragmentActivity 的构建循环里 `hVar.getView().setId(R.id.aqp/aqr)` 恰好
 * 发生在 `bottomTabBarLayout.b2(...)` 之前，所以在 b2 里按 view id 拦截即可。
 * 拦截后 `d2()` 只会对剩余子 view 重连 ConstraintLayout 约束，剩余 tab 自动均分不留空白；
 * 被拦的 tab 对象照常创建（f205138o/f205143t 引用非空），不会 NPE。
 *
 * 为什么不动 `m3.E()` 的 tab 列表：MainFragmentActivity 在活动 tab 逻辑里直接解引用
 * f205138o（福利 tab），列表层面过滤会 NPE；只挡 addView 是副作用最小的层。
 *
 * ## 混淆 id 的处理
 *
 * hap/fzp/aqp/aqr 都是混淆资源名，随版本漂移（本审计版本上已核验存在）。运行时用
 * `Resources.getIdentifier` 解析并按进程缓存（[IdPairCache]）；解析不到（0）时打一次
 * WARN 并让 hook 保持直通 —— 资源名换了属于「需要重新适配」的信号，但不能因此影响
 * 其他 hook 或宿主稳定性。
 */
class HongguoViewHooks(
    private val hooks: HookManager,
    private val resolver: ClassResolver,
    private val log: ModuleLog,
    /** 当前宿主包名。这两个 hook 的资源名证据来自红果的资源表，番茄上不装。 */
    private val hostPackage: String
) {

    fun installAll() {
        if (hostPackage != HONGGUO_PACKAGE) {
            log.info(
                "hongguo view hooks n/a on host $hostPackage: view ids were derived " +
                    "from the Hongguo resource table only; skipped (not counted as lost)"
            )
            return
        }
        installMainFeedAdHook()
        installBottomTabHooks()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 推荐流信息流广告 item（RecyclerView id=hap，锚点 FrameLayout id=fzp）
    // ─────────────────────────────────────────────────────────────────────────

    private fun installMainFeedAdHook() {
        val rvClass = resolver.findClass(RECYCLERVIEW_CLASS) ?: return
        val method = try {
            rvClass.getDeclaredMethod("dispatchChildAttached", View::class.java)
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            log.warn(
                "main-feed-ad: dispatchChildAttached lookup failed on " +
                    "${rvClass.name} (${t.javaClass.simpleName})"
            )
            null
        }
        hooks.install(
            id = "hongguo-main-feed-ad-hide",
            method = method,
            // 单一调用点且是小方法，防内联。
            deoptimize = true,
            body = { chain ->
                val rv = chain.thisObject as? View
                val child = chain.args.firstOrNull() as? View
                if (rv != null && child != null) {
                    val ids = resolveIds(rv, feedIdCache)
                    if (ids != null && rv.id == ids[0] &&
                        child.hasSelfOrDescendantWithId(ids[1])
                    ) {
                        // 第一道：GONE + lp.height=0。对按 LayoutParams 测量的
                        // LayoutManager（LinearLayout/Grid/Staggered 的默认实现）这就够了。
                        val lp = child.layoutParams
                        if (lp != null && lp.height != 0) lp.height = 0
                        if (child.visibility != View.GONE) child.visibility = View.GONE
                        // 第二道：宿主信息流若用自定义 LayoutManager（不按 lp.height 生成
                        // measure spec），上面两招都会被无视——GONE 的 view 仍会被 LayoutManager
                        // 量出全高并占位。因此发现广告 item 的类后，在其 onMeasure 里把
                        // 测量结果的高度强制改 0（LayoutManager 之下，任何测量逻辑都会拿到 0）。
                        ensureMeasureZeroHook(child.javaClass)
                        log.info(
                            "blocked main feed ad item: RecyclerView(id=hap) child " +
                                "holds anchor FrameLayout(id=fzp), class=${child.javaClass.name}"
                        )
                    }
                }
                // 必须放行：adapter / attach 监听器分发不能断。
                chain.proceed()
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1b. 广告 item 的 onMeasure 强制归零（兜底自定义 LayoutManager）
    //
    //   GONE / lp.height=0 只对「按 LayoutParams 生成 measure spec」的 LayoutManager
    //   有效。宿主信息流如果用了自定义 LayoutManager（自己构造 spec、无视 lp.height），
    //   GONE 的 item 仍会被量出全高、照常占位（用户实测：广告没了但位置还在）。
    //
    //   兜底：运行时首次命中广告 item 时，取它运行时的类，找到 onMeasure(int,int) 的
    //   声明处（可能定义在父类），挂一个后置 hook——原测量照跑，然后把测量结果的高度
    //   改写为 0。这一层在 LayoutManager 之下，无论 LM 用什么 spec 都会消费到 0 高。
    //
    //   每个类只挂一次（[hookedMeasureClasses] 去重）；hook 体内按「父 view id == hap
    //   且自身子树含 fzp」重新校验，所以即使该类被复用在非广告 view 上也不会误伤。
    // ─────────────────────────────────────────────────────────────────────────

    /** 已挂过 onMeasure 强制归零的类名 -> 命中计数（限流日志用）。 */
    private val hookedMeasureClasses =
        java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun ensureMeasureZeroHook(cls: Class<*>) {
        if (hookedMeasureClasses.containsKey(cls.name)) return
        synchronized(hookedMeasureClasses) {
            if (hookedMeasureClasses.containsKey(cls.name)) return
            // onMeasure 可能声明在父类（item 根多为 sdk/app 的通用布局），向上找到为止。
            var target: java.lang.reflect.Method? = null
            var c: Class<*>? = cls
            while (c != null && c != View::class.java) {
                target = runCatching {
                    c.getDeclaredMethod(
                        "onMeasure",
                        Integer.TYPE,
                        Integer.TYPE
                    )
                }.getOrNull()
                if (target != null) break
                c = c.superclass
            }
            if (target == null) {
                hookedMeasureClasses[cls.name] = 0
                log.warn("measure-zero: no onMeasure found on ${cls.name} hierarchy")
                return
            }
            hooks.install(
                id = "hongguo-main-feed-ad-measure-zero:${cls.name}",
                method = target,
                deoptimize = true,
                body = { chain ->
                    chain.proceed()
                    val v = chain.thisObject as? View
                    if (v != null) {
                        val parent = v.parent as? View
                        val ids = resolveIds(v, feedIdCache)
                        if (ids != null && parent != null && parent.id == ids[0] &&
                            v.hasSelfOrDescendantWithId(ids[1]) &&
                            v.measuredHeight != 0
                        ) {
                            val oldHeight = v.measuredHeight
                            setMeasuredDimensionReflectively(v, v.measuredWidth, 0)
                            val n = hookedMeasureClasses.merge(cls.name, 1, Int::plus) ?: 1
                            if (n <= 5 || n % 200 == 0) {
                                log.info(
                                    "measure-zero hit[$n]: ${cls.name} " +
                                        "height $oldHeight->0"
                                )
                            }
                        }
                    }
                }
            )
            hookedMeasureClasses[cls.name] = 0
        }
    }

    /** View#setMeasuredDimension 是 protected，模块侧反射调用（缓存 Method）。 */
    private fun setMeasuredDimensionReflectively(view: View, w: Int, h: Int) {
        var m = setMeasuredDimensionMethod
        if (m == null) {
            m = runCatching {
                View::class.java.getDeclaredMethod(
                    "setMeasuredDimension",
                    Integer.TYPE,
                    Integer.TYPE
                ).apply { isAccessible = true }
            }.getOrNull()
            setMeasuredDimensionMethod = m
        }
        m?.invoke(view, w, h)
    }

    @Volatile private var setMeasuredDimensionMethod: java.lang.reflect.Method? = null

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 底部 tab 栏「福利」(aqp) /「商城」(aqr) —— 拦在 BottomTabBarLayout#b2
    // ─────────────────────────────────────────────────────────────────────────

    private fun installBottomTabHooks() {
        val method = resolver.findMethod(BOTTOM_TAB_BAR_LAYOUT_CLASS, "b2", TAB_BUTTON_CLASS)
        hooks.install(
            id = "hongguo-bottom-tab-hide",
            method = method,
            deoptimize = true,
            body = { chain ->
                val view = chain.args.firstOrNull()?.getViewReflectively()
                val ids = view?.let { resolveIds(it, tabIdCache) }
                if (view != null && ids != null && (view.id == ids[0] || view.id == ids[1])) {
                    val which = if (view.id == ids[0]) "welfare(aqp)" else "mall(aqr)"
                    log.info("blocked bottom tab: $which (BottomTabBarLayout.b2 suppressed)")
                    // b2 返回 void：不 proceed 即该 tab 永远不进底栏。
                    null
                } else {
                    chain.proceed()
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 混淆 view id 的运行时解析（带进程级缓存与一次性失败标记）
    // ─────────────────────────────────────────────────────────────────────────

    /** 一对混淆 id 名（宿主资源表里的名字）与解析结果缓存。 */
    private class IdPairCache(val firstName: String, val secondName: String) {
        @Volatile var value: IntArray? = null
        @Volatile var failed = false
    }

    private val feedIdCache = IdPairCache("hap", "fzp")
    private val tabIdCache = IdPairCache("aqp", "aqr")

    /**
     * 用任一宿主 view 的 Resources 解析一对 id。首次成功后缓存；解析失败（资源名随版本
     * 漂移）标记 failed 并只打一次 WARN，之后调用方全部直通。
     */
    private fun resolveIds(view: View, cache: IdPairCache): IntArray? {
        cache.value?.let { return it }
        if (cache.failed) return null
        synchronized(cache) {
            cache.value?.let { return it }
            if (cache.failed) return null
            val res = view.resources
            val pkg = view.context.packageName
            val first = res.getIdentifier(cache.firstName, "id", pkg)
            val second = res.getIdentifier(cache.secondName, "id", pkg)
            return if (first != 0 && second != 0) {
                log.info(
                    "resolved view ids: ${cache.firstName}=0x${Integer.toHexString(first)}, " +
                        "${cache.secondName}=0x${Integer.toHexString(second)} (package=$pkg)"
                )
                intArrayOf(first, second).also { cache.value = it }
            } else {
                cache.failed = true
                log.warn(
                    "view id lookup failed: ${cache.firstName}=$first ${cache.secondName}=$second " +
                        "(package=$pkg); hook stays passive — host likely renamed its resources"
                )
                null
            }
        }
    }

    /** 深度受限的「自身或后代中存在指定 id 的 view」判断，防止异常深树拖慢热路径。 */
    private fun View.hasSelfOrDescendantWithId(target: Int, maxDepth: Int = 6): Boolean {
        if (id == target) return true
        if (maxDepth <= 0 || this !is ViewGroup) return false
        for (i in 0 until childCount) {
            if (getChildAt(i).hasSelfOrDescendantWithId(target, maxDepth - 1)) return true
        }
        return false
    }

    /** bt5.h#getView()：tab 按钮到其根 view 的桥。按名字反查并缓存，失败返回 null。 */
    private fun Any.getViewReflectively(): View? {
        var m = getViewMethod
        if (m == null) {
            m = this.javaClass.methods.firstOrNull {
                it.name == "getView" && it.parameterCount == 0
            }
            getViewMethod = m
        }
        return m?.let { runCatching { it.invoke(this) as? View }.getOrNull() }
    }

    @Volatile private var getViewMethod: java.lang.reflect.Method? = null

    private companion object {
        const val HONGGUO_PACKAGE = "com.phoenix.read"
        const val RECYCLERVIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
        const val BOTTOM_TAB_BAR_LAYOUT_CLASS = "com.dragon.read.widget.BottomTabBarLayout"

        /** tab 按钮接口（混淆名，本审计版本上核验为 `View getView();` 的接口）。 */
        const val TAB_BUTTON_CLASS = "bt5.h"
    }
}
