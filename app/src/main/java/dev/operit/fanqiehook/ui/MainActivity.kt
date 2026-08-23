package dev.operit.fanqiehook.ui

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.operit.fanqiehook.BuildConfig
import dev.operit.fanqiehook.FanqieApp
import dev.operit.fanqiehook.R
import dev.operit.fanqiehook.config.ModuleConfig
import dev.operit.fanqiehook.config.ModuleConfig.Key
import io.github.libxposed.service.XposedService

/**
 * 模块设置界面（Soft UI 新拟物风格）。
 *
 * 激活检测走 libxposed 官方链路：[FanqieApp] 经 XposedServiceHelper 绑定框架服务，
 * 服务已绑定 = 模块已在 LSPosed 启用；作用域经 [XposedService.scope] 查询，
 * 未包含番茄小说时可经 [XposedService.requestScope] 一键申请。
 *
 * 配置写入 [FanqieApp.configPrefs]：激活时为框架共享存储（RemotePreferences，
 * 宿主进程实时同步、免重启生效），未激活时回退本地 SP。
 */
class MainActivity : AppCompatActivity() {

    /** 目标宿主包名（与 FanqieModule 的 TARGET_PACKAGE 一致）。 */
    private val targetPackage = "com.dragon.read"

    /** 当前配置源：服务绑定后为框架存储，断开后为本地 SP。 */
    private fun sp(): SharedPreferences = FanqieApp.configPrefs(this)

    private lateinit var ledLsp: View
    private lateinit var statusText: TextView
    private lateinit var requestScopeButton: TextView
    private lateinit var sectionsContainer: LinearLayout

    private val switchViews = HashMap<String, SoftSwitch>()
    private val valueLabels = HashMap<String, TextView>()
    private val rowViews = HashMap<String, View>()

    /** 官方服务状态回调（binder 线程 → 主线程刷新）。 */
    private val serviceListener: (XposedService?) -> Unit = { service ->
        runOnUiThread {
            applyStatus(service)
            refreshAllStates()
        }
    }

    // ── 面板数据 ──────────────────────────────────────────────────────────────

    /** 一个开关行。 */
    private class Row(
        val key: String,
        val title: String,
        val subtitle: String,
        val default: Boolean = false,
        val indent: Boolean = false,
        /** 数值行（点击弹窗选择，无开关）。 */
        val valueRow: Boolean = false,
    )

    /** 一块拟物面板。 */
    private class Section(
        val title: String,
        val plateColor: Int,
        val rows: List<Row>,
    )

    private fun sections(): List<Section> = listOf(
        Section(
            title = "会员与激励",
            plateColor = getColor(R.color.plate_vip),
            rows = listOf(
                Row(Key.LOCAL_VIP, "本地会员伪装", "以 VIP 身份运行，解锁会员专属界面与权益"),
                Row(Key.INSTANT_REWARD, "激励视频秒过", "看视频任务无需等待，直接领取奖励"),
                Row(Key.READING_TIME_MULTIPLIER, "阅读时长倍率", "按倍率放大统计的阅读时长", valueRow = true),
            ),
        ),
        Section(
            title = "防御与解锁",
            plateColor = getColor(R.color.plate_defense),
            rows = listOf(
                Row(Key.BLOCK_UPDATES, "拦截应用更新", "阻止番茄检查与下载新版本 APK"),
                Row(Key.BLOCK_HOT_UPDATE, "拦截热更新", "阻止运行时下发补丁修改代码"),
                Row(Key.BLOCK_PLUGIN_LOAD, "拦截插件加载", "阻止加载动态下发的插件包"),
                Row(Key.BLOCK_CRASH_REPORT, "拦截崩溃上报", "阻止上传崩溃堆栈与使用数据"),
                Row(Key.UNLOCK_BOOKS, "解锁下架书籍", "已下架 / 无版权书籍仍可继续阅读"),
            ),
        ),
        Section(
            title = "界面净化",
            plateColor = getColor(R.color.plate_clean),
            rows = listOf(
                Row(Key.UI_CLEAN, "启用界面净化", "总开关，控制下方全部净化项"),
                Row(Key.UC_SEARCH_WORD, "搜索页热词", "移除搜索框下方的推广热词", default = true, indent = true),
                Row(Key.UC_SCREEN_AD, "开屏广告", "跳过启动开屏广告", default = true, indent = true),
                Row(Key.UC_VIP_CARD, "VIP 推广卡片", "移除书架 VIP 开通卡片", indent = true),
                Row(Key.UC_FUNCTION_BADGE, "功能红点", "清除各入口的小红点", default = true, indent = true),
                Row(Key.UC_MINI_GAME, "小游戏入口", "移除小游戏推广入口", default = true, indent = true),
                Row(Key.UC_TAB_BADGE, "底部标签角标", "清除底部 Tab 上的数字角标", default = true, indent = true),
                Row(Key.UC_MINE_FEED, "我的页信息流", "移除「我的」页推荐信息流", default = true, indent = true),
                Row(Key.UC_HOME_EARN_TAB, "首页赚钱标签", "隐藏首页底部「赚钱」标签", default = true, indent = true),
                Row(Key.UC_HOME_SERIES_TAB, "首页短剧标签", "隐藏首页「短剧」标签", default = true, indent = true),
                Row(Key.UC_HIDE_PUBLISH, "隐藏发布入口", "移除创作中心 / 发布按钮", default = true, indent = true),
                Row(Key.UC_HIDE_MINE_ASSET, "隐藏我的资产", "移除「我的」页资产与收益模块", default = true, indent = true),
                Row(Key.UC_HIDE_MINE_HISTORY, "隐藏浏览历史", "移除「我的」页浏览历史入口", indent = true),
                Row(Key.UC_HIDE_MINE_EARN_PENDANT, "赚钱悬浮球", "隐藏侧边赚钱悬浮球", indent = true),
                Row(Key.UC_SEARCH_RESULT, "搜索结果广告", "过滤搜索结果中的广告卡片", default = true, indent = true),
                Row(Key.UC_SEARCH_AI, "AI 搜索入口", "移除搜索页 AI 助手入口", default = true, indent = true),
            ),
        ),
        Section(
            title = "书源",
            plateColor = getColor(R.color.plate_book),
            rows = listOf(
                Row(Key.BOOK_SOURCE, "书源服务", "在番茄进程内提供阅读 App 可用的书源接口"),
                Row(Key.BOOK_SOURCE_PORT, "服务端口", "局域网内通过 http://手机IP:端口 访问", indent = true, valueRow = true),
            ),
        ),
    )

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ledLsp = findViewById(R.id.led_lsp)
        statusText = findViewById(R.id.status_text)
        requestScopeButton = findViewById(R.id.btn_request_scope)
        sectionsContainer = findViewById(R.id.sections_container)
        findViewById<TextView>(R.id.version_plate).text = "v${BuildConfig.VERSION_NAME}"

        requestScopeButton.setOnClickListener { requestScope() }
        buildSections()
        applyStatus(FanqieApp.service())
    }

    override fun onStart() {
        super.onStart()
        FanqieApp.addServiceStateListener(serviceListener)
    }

    override fun onStop() {
        FanqieApp.removeServiceStateListener(serviceListener)
        super.onStop()
    }

    // ── 激活状态与作用域（libxposed 官方 API） ────────────────────────────────

    /** 服务已绑定 = 模块已在 LSPosed 启用；再核对作用域是否包含番茄小说。 */
    private fun applyStatus(service: XposedService?) {
        if (service == null) {
            ledLsp.backgroundTintList = ColorStateList.valueOf(getColor(R.color.led_off))
            statusText.text = "模块未激活：请在 LSPosed 中启用本模块"
            requestScopeButton.visibility = View.VISIBLE
            return
        }

        ledLsp.backgroundTintList = ColorStateList.valueOf(getColor(R.color.led_on))
        val scope = runCatching { service.scope }.getOrDefault(emptyList())
        val scopeText = if (scope.isEmpty()) "（空）" else scope.joinToString("、")
        val scoped = scope.contains(targetPackage)

        statusText.text = buildString {
            append("已激活 · ")
            append(runCatching { "${service.frameworkName} ${service.frameworkVersion}" }
                .getOrDefault("框架"))
            append("\n作用域：$scopeText")
            if (!scoped) append("\n作用域未包含番茄小说，请点击下方按钮添加")
        }
        requestScopeButton.visibility = if (scoped) View.GONE else View.VISIBLE
    }

    /** 官方一键申请作用域：框架弹窗确认，结果经回调通知。 */
    private fun requestScope() {
        val service = FanqieApp.service() ?: run {
            Toast.makeText(this, "未连接框架服务，请先在 LSPosed 中启用模块", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            service.requestScope(listOf(targetPackage), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "已加入作用域：${approved.joinToString("、")}，请重启番茄小说",
                            Toast.LENGTH_LONG,
                        ).show()
                        applyStatus(service)
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "请求失败：$message", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }.onFailure {
            Toast.makeText(this, "请求作用域失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 面板构建 ──────────────────────────────────────────────────────────────

    private fun buildSections() {
        sectionsContainer.removeAllViews()
        switchViews.clear()
        valueLabels.clear()
        rowViews.clear()

        for (section in sections()) {
            val panel = LayoutInflater.from(this)
                .inflate(R.layout.item_section, sectionsContainer, false)
            panel.findViewById<TextView>(R.id.section_title).apply {
                text = section.title
                backgroundTintList = ColorStateList.valueOf(section.plateColor)
            }
            val rows = panel.findViewById<LinearLayout>(R.id.section_rows)
            for (row in section.rows) rows.addView(buildRow(row))
            sectionsContainer.addView(panel)
        }
        refreshAllStates()
    }

    private fun buildRow(row: Row): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_switch, sectionsContainer, false)
        if (row.indent) v.setPadding(dip(16), v.paddingTop, v.paddingEnd, v.paddingBottom)
        v.findViewById<TextView>(R.id.row_title).text = row.title
        v.findViewById<TextView>(R.id.row_subtitle).text = row.subtitle
        rowViews[row.key] = v

        if (row.valueRow) {
            val label = v.findViewById<TextView>(R.id.row_value)
            label.visibility = View.VISIBLE
            valueLabels[row.key] = label
            v.setOnClickListener {
                if (row.key == Key.READING_TIME_MULTIPLIER) showMultiplierDialog()
                else showPortDialog()
            }
        } else {
            val sw = v.findViewById<SoftSwitch>(R.id.row_switch)
            sw.visibility = View.VISIBLE
            sw.onCheckedChangeListener = { checked ->
                sp().edit().putBoolean(row.key, checked).apply()
                if (row.key == Key.UI_CLEAN) refreshUiCleanAvailability()
            }
            switchViews[row.key] = sw
        }
        return v
    }

    /** 将当前配置源的最新值刷入所有开关与数值铭牌。 */
    private fun refreshAllStates() {
        val sp = sp()
        for ((key, sw) in switchViews) {
            sw.setChecked(sp.getBoolean(key, defaultOf(key)), animate = false)
        }
        valueLabels[Key.READING_TIME_MULTIPLIER]?.let { refreshMultiplierLabel(it) }
        valueLabels[Key.BOOK_SOURCE_PORT]?.let { refreshPortLabel(it) }
        refreshUiCleanAvailability()
    }

    private fun refreshMultiplierLabel(label: TextView) {
        val factor = sp().getInt(Key.READING_TIME_MULTIPLIER, 1)
        label.text = if (factor > 1) "$factor×" else "关闭"
    }

    private fun refreshPortLabel(label: TextView) {
        label.text = sp().getInt(Key.BOOK_SOURCE_PORT, 18765).toString()
    }

    /** 净化子项仅在总开关开启时可用。 */
    private fun refreshUiCleanAvailability() {
        val master = sp().getBoolean(Key.UI_CLEAN, false)
        for ((key, sw) in switchViews) {
            if (key.startsWith("uc_")) {
                sw.isEnabled = master
                sw.alpha = if (master) 1f else 0.45f
            }
        }
    }

    private fun defaultOf(key: String): Boolean = when (key) {
        Key.UC_VIP_CARD, Key.UC_HIDE_MINE_HISTORY, Key.UC_HIDE_MINE_EARN_PENDANT -> false
        else -> key.startsWith("uc_")
    }

    // ── 数值选择 ──────────────────────────────────────────────────────────────

    private fun showMultiplierDialog() {
        val options = intArrayOf(1, 2, 3, 5, 8, 10)
        val labels = options.map { if (it == 1) "关闭（按实际时长）" else "$it 倍" }.toTypedArray()
        val current = sp().getInt(Key.READING_TIME_MULTIPLIER, 1)
        val checked = options.indexOfFirst { it == current }.let { if (it < 0) 0 else it }

        MaterialAlertDialogBuilder(this)
            .setTitle("阅读时长倍率")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                sp().edit().putInt(Key.READING_TIME_MULTIPLIER, options[which]).apply()
                valueLabels[Key.READING_TIME_MULTIPLIER]?.let { refreshMultiplierLabel(it) }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPortDialog() {
        val options = intArrayOf(18765, 18766, 8080, 9999)
        val labels = options.map { it.toString() }.toTypedArray()
        val current = sp().getInt(Key.BOOK_SOURCE_PORT, 18765)
        val checked = options.indexOfFirst { it == current }.let { if (it < 0) 0 else it }

        MaterialAlertDialogBuilder(this)
            .setTitle("书源服务端口")
            .setMessage("修改后重启番茄小说生效")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                sp().edit().putInt(Key.BOOK_SOURCE_PORT, options[which]).apply()
                valueLabels[Key.BOOK_SOURCE_PORT]?.let { refreshPortLabel(it) }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
