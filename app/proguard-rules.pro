# LSPosed 模块的 R8 保留规则

# ── 1. 模块入口 ───────────────────────────────────────────────────────────────
# META-INF/xposed/java_init.list 里是按【类名字符串】引用入口的，被 R8 改名或删除会在
# 加载期直接失败（framework 找不到类）。入口类必须整类保留。
-keep class dev.operit.fanqiehook.FanqieModule { *; }

# 模块自己的其余代码同样整包保留：体积只有 ~15 KB，删了省不出什么，但混淆后如果
# 有任何按名字查找（日志、调试、异常栈）都会变得难排查。
-keep class dev.operit.fanqiehook.** { *; }

# ── 2. libxposed API ──────────────────────────────────────────────────────────
# 该 API 由框架在运行时提供（compileOnly，不打包进 APK）。R8 看不到实现，需要
# 抑制警告；我们重写的生命周期方法由框架按签名回调，不能被改名。
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public boolean onHotReloading(...);
    public void onHotReloaded(...);
}

# ── 3. DexKit ─────────────────────────────────────────────────────────────────
# DexKit 的原生侧 (libdexkit.so) 会通过 JNI 按【类名 + 方法签名】回调 Java 侧对象
# （桥接对象、查询回调、枚举等）。JNI 的符号查找不走 R8 的映射表，因此这层必须
# 整包保留 —— 否则表现为运行期 UnsatisfiedLinkError 或静默返回空结果。
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# DexKit 依赖的 JSON 序列化（Gson）：按字段名反射映射，字段名不能被改。
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-dontwarn com.google.gson.**

# ── 4. Kotlin / 协程 ──────────────────────────────────────────────────────────
# Kotlin 标准库可以由 R8 大幅裁剪（这是本模块最大的体积来源），但元数据与
# 内联函数的断言需要保留属性。
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ── 5. 通用 ───────────────────────────────────────────────────────────────────
# 保留行号，便于实机异常栈定位（对体积影响极小）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
