package dev.operit.fanqiehook

import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * Reflective class / method lookup against the host app's class loader.
 *
 * The resolver accepts **class name strings** as primary inputs. Every miss is logged at WARN
 * (class) or WARN (method) so missing targets never silently turn into runtime crashes.
 *
 * Supported parameter type specifiers (case-sensitive):
 *   - Primitives: `boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`
 *   - java.lang wrappers: `Boolean`, `Byte`, `Char`, `Short`, `Integer`, `Long`, `Float`, `Double`
 *   - `void`
 *   - Any `java.lang.*` or `android.*` type
 *   - Any application class (resolved through [classLoader])
 *   - Array notation: `<type>[]` (single-dimension only; multi-dim must be passed via [Class])
 *
 * For arrays, generic collections, or generic types, resolve the leaf class first and pass
 * the resulting `Class<*>` via overloads below.
 *
 * DexKit fallback:
 *   - [findClassImplementingInterface] uses DexKit 2.x at runtime to find targets whose class
 *     names are obfuscated and may change between Fanqie releases. The bridge is lazily created
 *     from [apkPath] + [classLoader] and reused.
 *   - DexKit failure (e.g. on unsupported ART versions) is logged at WARN and degrades to an
 *     empty result; callers should treat empty results the same as a hard miss.
 */
class ClassResolver(
    private val classLoader: ClassLoader,
    private val log: ModuleLog,
    /**
     * APK source directory for the host app — needed by DexKit 2.x because it loads the DEX
     * straight off disk rather than from the class loader. Passed in by [FanqieModule] from
     * `PackageReadyParam.applicationInfo.sourceDir`. May be null in early lifecycle phases; in
     * that case DexKit-backed lookups degrade to reflection-only.
     */
    private val apkPath: String? = null
) {

    fun findClass(name: String): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (cnfe: ClassNotFoundException) {
            log.warn("class not found: $name")
            null
        } catch (t: Throwable) {
            log.warn("class lookup failed: $name (${t.javaClass.simpleName}: ${t.message})")
            null
        }
    }

    /**
     * Look up a method by name + parameter types given as class name strings.
     * Use [findMethod] overload that takes [Class] for arrays or generic types.
     */
    fun findMethod(
        className: String,
        methodName: String,
        vararg parameterTypeNames: String
    ): Method? {
        val owner = findClass(className) ?: return null
        val params = parameterTypeNames.map { name ->
            resolveType(name) ?: run {
                log.warn("method resolution aborted: param type '$name' not found")
                return null
            }
        }.toTypedArray()
        return findMethodOn(owner, methodName, params, className)
    }

    /**
     * Look up a method by name + parameter types given as resolved [Class] instances.
     * Use this overload when the parameter types are arrays, generics, or otherwise not expressible
     * as a simple class-name string. For ordinary class names prefer [findMethod] with `String` args.
     */
    fun findMethod(
        className: String,
        methodName: String,
        parameterTypes: Array<out Class<*>>
    ): Method? {
        val owner = findClass(className) ?: return null
        return findMethodOn(owner, methodName, parameterTypes, className)
    }

    private fun findMethodOn(
        owner: Class<*>,
        methodName: String,
        params: Array<out Class<*>>,
        className: String
    ): Method? {
        return try {
            owner.getDeclaredMethod(methodName, *params).apply { isAccessible = true }
        } catch (nsm: NoSuchMethodException) {
            log.warn("method not found: $className#$methodName(${params.joinToString { it.simpleName }})")
            null
        } catch (t: Throwable) {
            log.warn("method lookup failed: $className#$methodName (${t.javaClass.simpleName})")
            null
        }
    }

    /**
     * Find a single method on a specific class by name + return type, ignoring parameter types.
     * Used when a hook target's parameters include obfuscated classes (e.g. `qh4.h`) that may
     * be renamed between Fanqie versions.
     */
    fun findMethodIgnoringParams(
        className: String,
        methodName: String,
        returnTypeName: String = "boolean"
    ): Method? {
        val owner = findClass(className) ?: return null
        return try {
            owner.declaredMethods.firstOrNull { m ->
                m.name == methodName && matchesReturnType(m.returnType, returnTypeName)
            }?.apply { isAccessible = true }
        } catch (t: Throwable) {
            log.warn("method scan failed: $className#$methodName (${t.javaClass.simpleName})")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DexKit-backed lookups. Used for hooks whose target is obfuscated and may
    // move between Fanqie versions (see FanqieHook_hooks_assessment.md).
    // ─────────────────────────────────────────────────────────────────────────

    private var dexKitBridge: DexKitBridge? = null

    private fun bridge(): DexKitBridge? {
        if (dexKitBridge == null) {
            val path = apkPath
            if (path == null) {
                log.warn("DexKit unavailable: no apkPath supplied to ClassResolver")
                return null
            }
            dexKitBridge = try {
                DexKitBridge.create(path)
            } catch (t: Throwable) {
                log.warn("DexKit init failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
        return dexKitBridge
    }

    /**
     * Find every loaded class that implements the given fully-qualified interface name.
     *
     * Returns a list of resolved [Class] objects (resolved through [classLoader]); failed
     * resolutions are silently dropped. Returns an empty list if DexKit is unavailable or the
     * interface itself cannot be found.
     *
     * Optional [methodName] further restricts results to classes declaring a method with that
     * name (no signature filtering — useful when parameter types are obfuscated).
     */
    fun findClassImplementingInterface(
        interfaceName: String,
        methodName: String? = null
    ): List<Class<*>> {
        val b = bridge() ?: return emptyList()
        return try {
            val hits = b.findClass {
                matcher {
                    interfaces {
                        add(interfaceName)
                    }
                    if (methodName != null) {
                        methods {
                            add {
                                name = methodName
                            }
                        }
                    }
                }
            }
            hits.mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
        } catch (t: Throwable) {
            log.warn("DexKit findClass implementing $interfaceName failed: ${t.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun matchesReturnType(actual: Class<*>, requested: String): Boolean = when (requested) {
        "boolean" -> actual == java.lang.Boolean.TYPE
        "void" -> actual == java.lang.Void.TYPE
        "int" -> actual == java.lang.Integer.TYPE
        "long" -> actual == java.lang.Long.TYPE
        else -> actual.name == requested
    }

    private fun resolveType(name: String): Class<*>? {
        // Primitives
        when (name) {
            "void" -> return java.lang.Void.TYPE
            "boolean" -> return java.lang.Boolean.TYPE
            "byte" -> return java.lang.Byte.TYPE
            "char" -> return java.lang.Character.TYPE
            "short" -> return java.lang.Short.TYPE
            "int" -> return java.lang.Integer.TYPE
            "long" -> return java.lang.Long.TYPE
            "float" -> return java.lang.Float.TYPE
            "double" -> return java.lang.Double.TYPE
        }
        // java.lang wrappers
        when (name) {
            "Boolean" -> return java.lang.Boolean::class.java
            "Byte" -> return java.lang.Byte::class.java
            "Char" -> return java.lang.Character::class.java
            "Short" -> return java.lang.Short::class.java
            "Integer", "Int" -> return java.lang.Integer::class.java
            "Long" -> return java.lang.Long::class.java
            "Float" -> return java.lang.Float::class.java
            "Double" -> return java.lang.Double::class.java
            "String" -> return java.lang.String::class.java
            "CharSequence" -> return java.lang.CharSequence::class.java
            "Object" -> return java.lang.Object::class.java
            "Throwable" -> return java.lang.Throwable::class.java
        }
        // Array notation: "<type>[]"
        if (name.endsWith("[]")) {
            val componentName = name.removeSuffix("[]")
            val component = resolveType(componentName) ?: return null
            return java.lang.reflect.Array.newInstance(component, 0)::class.java
        }
        // Fallback: resolve via class loader
        return findClass(name)
    }
}