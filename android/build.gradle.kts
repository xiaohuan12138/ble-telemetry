// 根构建脚本：只声明插件，不写具体依赖
// AGP 9.0 起内置 Kotlin 支持，无需再声明 org.jetbrains.kotlin.android 插件，
// 这避免了 "AGP 版本 × Kotlin 版本 × KSP 版本" 对不上的经典踩坑。
//
// 版本组合沿用本机已验证的环境（与 Studio AI-261 自带环境一致）：
//   AGP 9.5.0  —— 支持本机 SDK 已装的 android-37 编译平台
//   KSP 2.3.12 —— Room 注解处理器用
plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
