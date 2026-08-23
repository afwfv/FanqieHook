package dev.operit.fanqiehook.web

import dev.operit.fanqiehook.support.HookSupport
import fi.iki.elonen.NanoHTTPD

/**
 * 书源服务：运行在番茄进程内的 HTTP 服务，仅暴露 Legado 兼容的书源 API
 * （`/reading/...`），供阅读类 App（如开源阅读）配置为第三方书源。
 *
 * 路由全部转发给 [BookApi]；非书源路径一律 404。
 * 开关与端口由模块 App 的「书源」设置项控制（[dev.operit.fanqiehook.config.ModuleConfig]）。
 *
 * 注意：服务绑定手机全部网卡接口，请仅在可信局域网内使用。
 */
object BookSourceServer {

    private const val TAG = "BookSource"

    @Volatile
    private var server: Server? = null

    fun start(port: Int): Boolean {
        if (server != null) return true
        return try {
            val s = Server(port)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = s
            HookSupport.log?.info("[$TAG] 书源服务已启动: 0.0.0.0:$port")
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

    private class Server(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val params = session.parameters
                .mapValues { (_, v) -> v.firstOrNull() ?: "" }
            val uri = session.uri

            return try {
                if (uri.startsWith("/reading/")) {
                    when {
                        uri == "/reading/debug/class" ->
                            plainJson(BookApi.debugClass(params["name"] ?: ""))
                        uri == "/reading/debug/decrypt-chain" ->
                            plainJson(BookApi.debugDecryptChain())
                        else ->
                            BookApi.handle(uri, params)?.let { plainJson(it) } ?: notFound(uri)
                    }
                } else {
                    notFound(uri)
                }
            } catch (t: Throwable) {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
                    "{\"error\":\"${t.javaClass.simpleName}: ${t.message}\"}"
                )
            }
        }

        private fun plainJson(text: String): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
                .also(::addCors)

        private fun notFound(uri: String): Response =
            newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json; charset=utf-8",
                "{\"error\":\"not found: $uri\"}"
            ).also(::addCors)

        private fun addCors(r: Response) {
            r.addHeader("Access-Control-Allow-Origin", "*")
        }
    }
}
