package dev.operit.fanqiehook.web

import dev.operit.fanqiehook.config.ModuleConfig
import dev.operit.fanqiehook.support.HookSupport
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded web console running inside the host (com.dragon.read) process.
 *
 * Routes:
 *   GET  /                     — control panel (inline HTML)
 *   GET  /api/config           — current feature-switch snapshot (JSON)
 *   POST /api/config           — apply overrides: {"key": value, ...}
 *   GET  /api/status           — module + hook status
 *   GET  /reading/...          — book source API (Legado-compatible), see [BookApi]
 *
 * Security note: the server binds on all interfaces of the phone. Only the
 * config/book endpoints are exposed; anything unknown returns 404. Enable via
 * the `start_web_server` switch (default off).
 */
object WebConsole {

    private const val TAG = "WebConsole"

    @Volatile
    private var server: Server? = null

    @Volatile
    var bookApiEnabled: Boolean = true

    fun start(port: Int): Boolean {
        if (server != null) return true
        return try {
            val s = Server(port)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = s
            HookSupport.log?.info("[$TAG] 控制台已启动: 0.0.0.0:$port")
            true
        } catch (t: Throwable) {
            HookSupport.log?.warn("[$TAG] 启动失败: ${t.message}")
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean = server != null

    // ─────────────────────────────────────────────────────────────────────────
    // Server implementation
    // ─────────────────────────────────────────────────────────────────────────

    private class Server(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            // Parse the body exactly once — NanoHTTPD's parseBody cannot be re-invoked.
            var postBody: String? = null
            if (Method.POST == session.method) {
                val files = HashMap<String, String>()
                try {
                    session.parseBody(files)
                    postBody = files["postData"]
                } catch (t: Throwable) {
                    // body-less POST is fine
                }
            }
            val params = session.parameters
                .mapValues { (_, v) -> v.firstOrNull() ?: "" }
            val uri = session.uri

            return try {
                when {
                    uri == "/" || uri.startsWith("/index") ->
                        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PANEL_HTML)

                    uri == "/api/config" && session.method == Method.GET ->
                        json(configSnapshot())

                    uri == "/api/config" && session.method == Method.POST ->
                        json(applyOverrides(postBody))

                    uri == "/api/status" ->
                        json(statusSnapshot())

                    uri.startsWith("/reading/") && bookApiEnabled ->
                        BookApi.handle(uri, params)?.let { plainJson(it) }
                            ?: notFound(uri)

                    uri == "/api/debug/facade" ->
                        plainJson(BookApi.debugFacade())

                    uri == "/api/debug/class" ->
                        plainJson(BookApi.debugClass(params["name"] ?: ""))

                    else -> notFound(uri)
                }
            } catch (t: Throwable) {
                json(JSONObject().put("error", "${t.javaClass.simpleName}: ${t.message}"),
                    Response.Status.INTERNAL_ERROR)
            }
        }

        private fun configSnapshot(): JSONObject {
            val obj = JSONObject()
            ModuleConfig.snapshot().forEach { (k, v) -> obj.put(k, v) }
            return obj
        }

        private fun applyOverrides(postBody: String?): JSONObject {
            val req = try {
                JSONObject(postBody ?: "")
            } catch (t: Throwable) {
                return JSONObject().put("error", "invalid JSON body")
            }
            val applied = JSONArray()
            val skipped = JSONArray()
            for (key in req.keys()) {
                if (key !in ModuleConfig.allKeys) {
                    skipped.put(key)
                    continue
                }
                val value = req.get(key)
                val ok = ModuleConfig.applyOverride(key, value)
                if (ok) applied.put(key) else skipped.put(key)
            }
            // Return the (possibly updated) snapshot so the UI refreshes in one round trip.
            val result = configSnapshot()
                .put("applied", applied)
                .put("skipped", skipped)
            return result
        }

        private fun statusSnapshot(): JSONObject {
            return JSONObject()
                .put("module", "FanqieHook")
                .put("target", "com.dragon.read")
                .put("web_console_running", isRunning())
                .put("book_api_enabled", bookApiEnabled)
                .put("process_alive", HookSupport.dragonApplication != null)
        }

        private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response {
            val r = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
            addCors(r)
            return r
        }

        private fun plainJson(text: String): Response {
            val r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
            addCors(r)
            return r
        }

        private fun notFound(uri: String): Response =
            newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json; charset=utf-8",
                JSONObject().put("error", "not found: $uri").toString()
            )

        private fun addCors(r: Response) {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inline control panel (single file, no external assets)
    // ─────────────────────────────────────────────────────────────────────────

    private val PANEL_HTML: String = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FanqieHook 控制台</title>
<style>
  :root{--bg:#111418;--card:#1a1f26;--txt:#e6e9ee;--sub:#8a94a3;--acc:#4f9cff;--ok:#3ecf8e;--warn:#ffb454}
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:var(--bg);color:var(--txt);font:14px/1.6 -apple-system,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;padding:24px}
  h1{font-size:20px;margin-bottom:4px}
  .sub{color:var(--sub);font-size:12px;margin-bottom:20px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px}
  .group{grid-column:1/-1;font-size:13px;color:var(--acc);margin-top:8px;font-weight:600}
  .card{background:var(--card);border-radius:10px;padding:12px 14px;display:flex;align-items:center;justify-content:space-between}
  .card .name{font-size:13px}
  .card .hint{font-size:11px;color:var(--sub);margin-top:2px}
  .sw{position:relative;width:42px;height:24px;flex:none}
  .sw input{opacity:0;width:0;height:0}
  .slider{position:absolute;inset:0;background:#333c47;border-radius:12px;transition:.2s;cursor:pointer}
  .slider:before{content:"";position:absolute;width:18px;height:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.2s}
  input:checked + .slider{background:var(--ok)}
  input:checked + .slider:before{transform:translateX(18px)}
  .bar{position:fixed;top:0;left:0;right:0;padding:10px 24px;background:var(--card);display:flex;gap:10px;align-items:center;z-index:9;box-shadow:0 2px 8px rgba(0,0,0,.4)}
  .bar h1{font-size:16px;margin:0}
  .bar button{margin-left:auto;background:var(--acc);color:#fff;border:0;border-radius:8px;padding:8px 18px;font-size:13px;cursor:pointer}
  body{padding-top:64px}
  .toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:var(--ok);color:#fff;padding:10px 20px;border-radius:8px;font-size:13px;opacity:0;transition:.3s;pointer-events:none}
  .toast.show{opacity:1}
  .num{width:64px;background:#0d1117;border:1px solid #333c47;color:var(--txt);border-radius:6px;padding:4px 8px;font-size:13px;text-align:center}
  .on{color:var(--ok)}
</style>
</head>
<body>
<div class="bar">
  <h1>FanqieHook 控制台</h1>
  <span class="sub" id="proc">加载中…</span>
  <button onclick="save()">保存</button>
</div>
<div class="grid" id="grid"></div>
<div class="toast" id="toast"></div>

<script>
const META = [
  {g:'核心功能'},
  {k:'local_vip', n:'本地 VIP 伪装', h:'伪造 VipInfo/PrivilegeManager，会员中心/免广告全场景生效', def:false},
  {k:'instant_reward', n:'激励视频秒领', h:'跳过看视频直接触发发奖回调', def:false},
  {k:'reading_time_multiplier', n:'阅读时长倍率', h:'ReadingTiming 上报时长倍数（1=关闭，5=×5）', num:true, def:1},
  {g:'防御'},
  {k:'block_updates', n:'屏蔽应用更新', h:'UpdateServiceImpl.checkUpdate 全拦截', def:false},
  {k:'block_hot_update', n:'屏蔽热更新', h:'Reparo 热更初始化/补丁下载', def:false},
  {k:'block_plugin_load', n:'屏蔽插件加载', h:'Mira 插件框架', def:false},
  {k:'block_crash_report', n:'屏蔽崩溃上报', h:'Npth + CrashReportConfig', def:false},
  {k:'unlock_books', n:'解锁下架/违禁书', h:'isOverallOffShelf / isUnsafeBook → false', def:false},
  {g:'界面净化'},
  {k:'ui_clean', n:'界面净化总开关', h:'关闭后下列子项全部失效', def:false},
  {k:'uc_search_word', n:'搜索框轮播词', h:'搜索框滚动提示词', def:true},
  {k:'uc_screen_ad', n:'全屏弹窗广告', h:'bd2.c + ScreenAdManager 根闸', def:true},
  {k:'uc_vip_card', n:'我的页会员卡', h:'会员营销卡片', def:false},
  {k:'uc_function_badge', n:'功能区红点角标', h:'侧边栏/我的页红点', def:true},
  {k:'uc_mini_game', n:'小游戏入口', h:'禁用小游戏', def:true},
  {k:'uc_tab_badge', n:'底部 TAB 角标', h:'TAB 红点气泡', def:true},
  {k:'uc_mine_feed', n:'我的页推荐流', h:'我的 tab 底部推荐 feed', def:true},
  {k:'uc_home_earn_tab', n:'主页"赚钱"tab', h:'LuckyBenefit 过滤', def:true},
  {k:'uc_home_series_tab', n:'主页短剧 tab', h:'VideoSeriesFeedTab 过滤', def:true},
  {k:'uc_hide_publish', n:'发表按钮', h:'视频短剧发表入口', def:true},
  {k:'uc_hide_mine_asset', n:'我的页金币信息', h:'金币/资产展示条', def:true},
  {k:'uc_hide_mine_history', n:'我的页浏览历史卡', h:'外露历史卡片', def:false},
  {k:'uc_hide_mine_earn_pendant', n:'赚钱挂件', h:'悬浮赚钱挂件', def:false},
  {k:'uc_search_result', n:'搜索结果卡片清理', h:'搜索页营销卡片', def:true},
  {k:'uc_search_ai', n:'AI 入口', h:'搜索页 AI 按钮/浮窗', def:true},
  {g:'Web 控制台'},
  {k:'start_web_server', n:'控制台随应用启动', h:'番茄启动时自动开 HTTP 服务', def:false},
  {k:'web_port', n:'控制台端口', h:'默认 18765，修改后需重启番茄', num:true, def:18765},
];
let config = {};

async function load(){
  try{
    const r = await fetch('/api/config');
    config = await r.json();
    document.getElementById('proc').textContent = '已连接';
  }catch(e){
    document.getElementById('proc').textContent = '连接失败';
    config = {};
  }
  render();
}

function render(){
  const grid = document.getElementById('grid');
  grid.innerHTML = '';
  for(const m of META){
    if(m.g){
      const g = document.createElement('div');
      g.className = 'group'; g.textContent = m.g;
      grid.appendChild(g); continue;
    }
    const cur = config[m.k] !== undefined ? config[m.k] : m.def;
    const card = document.createElement('div');
    card.className = 'card';
    if(m.num){
      card.innerHTML = '<div><div class="name">' + m.n + '</div><div class="hint">' + (m.h||'') + '</div></div>' +
        '<input class="num" type="number" id="num_' + m.k + '" value="' + cur + '" min="1" max="99">';
    } else {
      card.innerHTML = '<div><div class="name">' + m.n + '</div><div class="hint">' + (m.h||'') + '</div></div>' +
        '<label class="sw"><input type="checkbox" id="sw_' + m.k + '"' + (cur?' checked':'') + '><span class="slider"></span></label>';
    }
    grid.appendChild(card);
  }
}

async function save(){
  const payload = {};
  for(const m of META){
    if(m.g) continue;
    if(m.num){
      const el = document.getElementById('num_'+m.k);
      if(el) payload[m.k] = parseInt(el.value)||1;
    } else {
      const el = document.getElementById('sw_'+m.k);
      if(el) payload[m.k] = el.checked;
    }
  }
  try{
    const r = await fetch('/api/config', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify(payload)
    });
    config = await r.json();
    toast('已保存，重启番茄小说生效');
  }catch(e){ toast('保存失败: '+e); }
}

function toast(msg){
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  setTimeout(()=>t.classList.remove('show'), 2500);
}

load();
</script>
</body>
</html>
    """.trimIndent()
}
