package dev.operit.fanqiehook.support

import java.lang.reflect.Method

/** Swallow any reflective failure — hook code must never crash the host. */
private inline fun <T> tryOrNull(block: () -> T?): T? = try {
    block()
} catch (t: Throwable) {
    null
}

/**
 * Minimal chained-reflection helper (jOOR-style), self-contained to avoid an extra dependency.
 *
 * Every operation swallows ReflectiveOperationException and returns `null`/`this`,
 * so fake-object construction inside the host process can never crash on a missing
 * field — same philosophy as the MK module's `Reflect.on(...)` usage.
 *
 * Method dispatch matches by name + argument runtime types (and falls back to
 * assignable-from matching), so calls against obfuscated APIs survive minor
 * signature drift.
 */
class Reflect private constructor(private val target: Any?) {

    companion object {
        fun on(target: Any?): Reflect = Reflect(target)

        fun onClass(name: String, loader: ClassLoader = HookSupport.dragonLoader()): Reflect =
            Reflect(tryOrNull { loader.loadClass(name) })

        /** Wrap a static-instance getter chain, e.g. onClass(X).field("IMPL"). */
        fun onStaticField(className: String, fieldName: String): Reflect =
            Reflect(tryOrNull {
                val cls = HookSupport.dragonLoader().loadClass(className)
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                f.get(null)
            })
    }

    /** Create a new instance of the wrapped class (no-arg constructor). */
    fun newInstance(): Reflect = Reflect(tryOrNull {
        (target as? Class<*>)?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance()
    })

    /** Get a field value from the wrapped object (searches superclass chain). */
    fun field(name: String): Reflect = Reflect(tryOrNull {
        var cls: Class<*>? = target?.javaClass ?: (target as? Class<*>)
        while (cls != null) {
            val f = tryOrNull { cls.getDeclaredField(name) }
            if (f != null) {
                f.isAccessible = true
                val owner = if (target is Class<*>) null else target
                return@tryOrNull f.get(owner)
            }
            cls = cls.superclass
        }
        null
    })

    /** Set a field on the wrapped object (searches superclass chain). */
    fun set(name: String, value: Any?): Reflect {
        tryOrNull {
            var cls: Class<*>? = target?.javaClass ?: (target as? Class<*>)
            while (cls != null) {
                val f = tryOrNull { cls.getDeclaredField(name) }
                if (f != null) {
                    f.isAccessible = true
                    val owner = if (target is Class<*>) null else target
                    f.set(owner, unwrapValue(value))
                    return@tryOrNull
                }
                cls = cls.superclass
            }
        }
        return this
    }

    /**
     * Invoke a method by name with the given arguments. When the wrapped target is
     * a Class: static methods are called directly; non-static methods fall back to
     * the Kotlin `object` INSTANCE field (or a no-arg `getInstance()`), matching how
     * obfuscated RPC facades are typically exposed.
     */
    fun call(name: String, vararg args: Any?): Reflect = Reflect(tryOrNull {
        val staticTarget = target as? Class<*>
        var cls: Class<*>? = if (staticTarget != null) staticTarget else target!!.javaClass
        while (cls != null) {
            val candidates = cls.declaredMethods.filter { it.name == name }
            val m = pickMethod(candidates, args)
            if (m != null) {
                m.isAccessible = true
                val instance = if (staticTarget != null && !java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    resolveSingleton(staticTarget) ?: return@tryOrNull null
                } else if (staticTarget != null) {
                    null
                } else {
                    target
                }
                return@tryOrNull m.invoke(instance, *coerceArgs(m, args))
            }
            cls = cls.superclass
        }
        null
    })

    /** Kotlin `object` INSTANCE field or classic singleton getter. */
    private fun resolveSingleton(cls: Class<*>): Any? {
        // Kotlin object pattern
        runCatching {
            val f = cls.getDeclaredField("INSTANCE")
            f.isAccessible = true
            return f.get(null)
        }
        // Java singleton pattern
        for (name in listOf("getInstance", "a", "b")) {
            runCatching {
                val m = cls.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == 0 &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers)
                } ?: return@runCatching
                m.isAccessible = true
                val result = m.invoke(null)
                if (result != null && cls.isInstance(result)) return result
            }
        }
        return null
    }

    private fun pickMethod(candidates: List<Method>, args: Array<out Any?>): Method? {
        if (candidates.isEmpty()) return null
        // exact arity first
        val arity = candidates.filter { it.parameterCount == args.size }
        if (arity.size == 1) return arity[0]
        // type-compatible match
        arity.firstOrNull { m ->
            m.parameterTypes.withIndex().all { (i, t) ->
                args[i] == null || isAssignable(t, args[i]!!)
            }
        }?.let { return it }
        // single overload with same name regardless of arity (call site knows best)
        if (candidates.size == 1 && candidates[0].parameterCount == args.size) return candidates[0]
        return arity.firstOrNull()
    }

    private fun coerceArgs(m: Method, args: Array<out Any?>): Array<Any?> {
        val out = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) {
            val v = args[i]
            if (v == null) {
                out[i] = null
                continue
            }
            val t = m.parameterTypes.getOrNull(i) ?: v.javaClass
            out[i] = when {
                t == java.lang.Long.TYPE || t == java.lang.Long::class.java ->
                    (v as? Number)?.toLong() ?: v.toString().toLongOrNull() ?: v
                t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java ->
                    (v as? Number)?.toInt() ?: v.toString().toIntOrNull() ?: v
                t == java.lang.Short.TYPE || t == java.lang.Short::class.java ->
                    (v as? Number)?.toShort() ?: v.toString().toShortOrNull() ?: v
                t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java ->
                    v as? Boolean ?: v.toString().toBoolean()
                t == java.lang.Float.TYPE || t == java.lang.Float::class.java ->
                    (v as? Number)?.toFloat() ?: v.toString().toFloatOrNull() ?: v
                t == java.lang.Double.TYPE || t == java.lang.Double::class.java ->
                    (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull() ?: v
                else -> v
            }
        }
        return out
    }

    private fun isAssignable(param: Class<*>, value: Any): Boolean = when {
        param.isInstance(value) -> true
        (param == java.lang.Long.TYPE || param == java.lang.Long::class.java) && value is Number -> true
        (param == java.lang.Integer.TYPE || param == java.lang.Integer::class.java) && value is Number -> true
        (param == java.lang.Boolean.TYPE || param == java.lang.Boolean::class.java) && value is Boolean -> true
        else -> false
    }

    private fun unwrapValue(value: Any?): Any? = when (value) {
        is Reflect -> value.get()
        else -> value
    }

    /** Read a static enum constant from the wrapped class. */
    fun enumValue(name: String): Reflect = Reflect(tryOrNull {
        (target as? Class<*>)?.getDeclaredField(name)?.get(null)
    })

    fun get(): Any? = target

    fun getBoolean(): Boolean = target as? Boolean ?: false

    override fun toString(): String = target?.toString() ?: "null"
}
