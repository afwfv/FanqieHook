package dev.operit.fanqiehook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.operit.fanqiehook.config.ModuleConfig
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 入口：绑定 libxposed 框架服务。
 *
 * - LSPosed 激活本模块后主动绑定服务 → [onServiceBind] 回调，[service] 非空即已激活
 * - 配置经 [configPrefs] 写入框架共享存储（XposedService.getRemotePreferences），
 *   宿主进程通过 XposedInterface.getRemotePreferences 实时读取 —— 官方同步链路
 * - 服务不可用（模块未激活）时回退本地 SP，激活后本地值不会自动迁移，
 *   故 UI 层引导用户在激活状态下保存一次配置
 */
class FanqieApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        boundService = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        boundService = null
        listeners.forEach { it(null) }
    }

    companion object {

        @Volatile
        private var boundService: XposedService? = null

        private val listeners = mutableListOf<(XposedService?) -> Unit>()

        /** 框架服务；null = 模块未激活或服务已断开。 */
        fun service(): XposedService? = boundService

        /** 激活状态变化回调（绑定线程调用，UI 需自行切换主线程）。 */
        fun addServiceStateListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(boundService)
        }

        fun removeServiceStateListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }

        /**
         * 配置读写入口：优先框架共享存储（与宿主进程实时同步），
         * 服务不可用时回退本地 SP。
         */
        fun configPrefs(context: Context): SharedPreferences = try {
            boundService?.getRemotePreferences(ModuleConfig.SP_NAME)
                ?: context.getSharedPreferences(ModuleConfig.SP_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            context.getSharedPreferences(ModuleConfig.SP_NAME, Context.MODE_PRIVATE)
        }
    }
}
