package dev.operit.fanqiehook.support

import dev.operit.fanqiehook.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Global runtime state + safe hook installation utilities.
 *
 * Adapted from the MK module's HookSupport/DragonGlobals pair:
 *  - Holds the module instance, host classloader, and host application once the
 *    MainApplication hook fires, so every hooker can reach them statically.
 *  - [hookedMethods] de-duplicates hook installation across re-entry points
 *    (initial install + activity-lifecycle retries + relocate-and-rehook).
 *  - [safeHook] / [safeHookCtor] / [hookByName] wrap the raw XposedModule hook
 *    builder with class-resolution, signature tolerance and WARN-on-miss logging,
 *    so a renamed obfuscated target never crashes the host.
 */
object HookSupport {

    @Volatile var module: XposedModule? = null
    @Volatile var log: ModuleLog? = null
    @Volatile var dragonClassLoader: ClassLoader? = null
    @Volatile var dragonApplication: android.app.Application? = null
    @Volatile var classResolver: dev.operit.fanqiehook.ClassResolver? = null

    /** Every Method/Constructor that already has a hook installed. */
    private val hookedMembers: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap<Any, Boolean>())

    fun reset() {
        module = null
        log = null
        dragonClassLoader = null
        dragonApplication = null
        classResolver = null
        hookedMembers.clear()
    }

    fun dragonLoader(): ClassLoader =
        dragonClassLoader ?: error("dragonClassLoader not initialised yet")

    private fun module(): XposedModule =
        module ?: error("XposedModule not initialised yet")

    private fun log(): ModuleLog? = log

    /** True when [member] was not hooked before; adds it to the registry. */
    fun markHooked(member: Any): Boolean = hookedMembers.add(member)

    // ─────────────────────────────────────────────────────────────────────────
    // Safe hook installers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hook an instance/static method by name + parameter type names on [className].
     * Parameter names follow [dev.operit.fanqiehook.ClassResolver] conventions.
     * Returns false (with WARN log) when class or method is missing.
     */
    fun safeHook(
        tag: String,
        className: String,
        methodName: String,
        paramTypes: Array<out String>?,
        desc: String,
        hooker: (XposedInterface.Chain) -> Any?
    ): Boolean {
        return try {
            val cls = dragonLoader().loadClass(className)
            val method = findMethod(cls, methodName, paramTypes)
            if (method == null) {
                log()?.warn("[$tag] $desc 未找到: $className#$methodName(${paramTypes?.joinToString() ?: ""})")
                return false
            }
            if (!markHooked(method)) return true // already hooked — treat as success
            module().hook(method).intercept(XposedInterface.Hooker { chain -> hooker(chain) })
            log()?.info("[$tag] 已挂钩 $desc: $className#$methodName")
            true
        } catch (t: Throwable) {
            log()?.warn("[$tag] $desc 挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Hook a constructor by parameter type names. */
    fun safeHookCtor(
        tag: String,
        className: String,
        paramTypes: Array<out String>?,
        desc: String,
        hooker: (XposedInterface.Chain) -> Any?
    ): Boolean {
        return try {
            val cls = dragonLoader().loadClass(className)
            val ctor = findCtor(cls, paramTypes)
            if (ctor == null) {
                log()?.warn("[$tag] $desc 构造器未找到: $className(${paramTypes?.joinToString() ?: ""})")
                return false
            }
            if (!markHooked(ctor)) return true
            module().hook(ctor).intercept(XposedInterface.Hooker { chain -> hooker(chain) })
            log()?.info("[$tag] 已挂钩 $desc: $className#<init>")
            true
        } catch (t: Throwable) {
            log()?.warn("[$tag] $desc 构造器挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Hook a method matched only by name + parameter count — for heavily obfuscated
     * targets whose parameter types are unstable between versions.
     */
    fun hookByName(
        tag: String,
        className: String,
        methodName: String,
        paramCount: Int,
        desc: String,
        hooker: (XposedInterface.Chain) -> Any?
    ): Boolean {
        return try {
            val cls = dragonLoader().loadClass(className)
            val method = cls.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterCount == paramCount
            }
            if (method == null) {
                log()?.warn("[$tag] $desc 未找到: $className#$methodName/$paramCount 参")
                return false
            }
            if (!markHooked(method)) return true
            method.isAccessible = true
            module().hook(method).intercept(XposedInterface.Hooker { chain -> hooker(chain) })
            log()?.info("[$tag] 已挂钩 $desc: $className#$methodName")
            true
        } catch (t: Throwable) {
            log()?.warn("[$tag] $desc 挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Raw hook for a resolved [Method]; returns false when already hooked. */
    fun hookMethod(tag: String, method: Method, desc: String,
                   hooker: (XposedInterface.Chain) -> Any?): Boolean {
        return try {
            if (!markHooked(method)) return true
            module().hook(method).intercept(XposedInterface.Hooker { chain -> hooker(chain) })
            log()?.info("[$tag] 已挂钩 $desc: ${method.declaringClass.name}#${method.name}")
            true
        } catch (t: Throwable) {
            log()?.warn("[$tag] $desc 挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Raw hook for a resolved [Constructor]; returns false when already hooked. */
    fun hookCtor(tag: String, ctor: Constructor<*>, desc: String,
                 hooker: (XposedInterface.Chain) -> Any?): Boolean {
        return try {
            if (!markHooked(ctor)) return true
            module().hook(ctor).intercept(XposedInterface.Hooker { chain -> hooker(chain) })
            log()?.info("[$tag] 已挂钩 $desc: ${ctor.declaringClass.name}#<init>")
            true
        } catch (t: Throwable) {
            log()?.warn("[$tag] $desc 挂钩失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolution helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun findMethod(cls: Class<*>, name: String, paramTypes: Array<out String>?): Method? {
        val declared = cls.declaredMethods
        if (paramTypes == null) {
            // name-only: require unique match to avoid hooking overloads blindly
            val matches = declared.filter { it.name == name }
            return when (matches.size) {
                0 -> null
                1 -> matches[0].apply { isAccessible = true }
                else -> matches.firstOrNull { it.parameterCount == 0 }?.apply { isAccessible = true }
            }
        }
        return declared.firstOrNull { m ->
            m.name == name && m.parameterCount == paramTypes.size &&
                m.parameterTypes.zip(paramTypes).all { (actual, wanted) ->
                    typeMatches(actual, wanted)
                }
        }?.apply { isAccessible = true }
    }

    private fun findCtor(cls: Class<*>, paramTypes: Array<out String>?): Constructor<*>? {
        val declared = cls.declaredConstructors
        if (paramTypes == null) return declared.firstOrNull()?.apply { isAccessible = true }
        return declared.firstOrNull { c ->
            c.parameterCount == paramTypes.size &&
                c.parameterTypes.zip(paramTypes).all { (actual, wanted) ->
                    typeMatches(actual, wanted)
                }
        }?.apply { isAccessible = true }
    }

    private fun typeMatches(actual: Class<*>, wanted: String): Boolean {
        val wt = wanted.trim()
        return when (wt) {
            actual.name -> true
            actual.simpleName -> true
            "boolean" -> actual == java.lang.Boolean.TYPE
            "int" -> actual == java.lang.Integer.TYPE
            "long" -> actual == java.lang.Long.TYPE
            "float" -> actual == java.lang.Float.TYPE
            "double" -> actual == java.lang.Double.TYPE
            "byte" -> actual == java.lang.Byte.TYPE
            "char" -> actual == java.lang.Character.TYPE
            "short" -> actual == java.lang.Short.TYPE
            "void" -> actual == java.lang.Void.TYPE
            else -> actual.name == wt || actual.simpleName == wt
        }
    }
}
