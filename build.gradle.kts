// 版本号（versionCode / version）定义见 app/src/main/resources/META-INF/xposed/module.prop
// 更新说明见 CHANGELOG.md，发布时按当前版本号自动读取对应「## x.x.x」段
// 构建产物同时发布到模块仓库与本站 Releases（见 .github/workflows/sync-xposed-modules-repo.yml）
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
