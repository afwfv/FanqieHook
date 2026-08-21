package dev.operit.fanqiehook

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
 */
class ClassResolver(
    private val classLoader: ClassLoader,
    private val log: ModuleLog
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