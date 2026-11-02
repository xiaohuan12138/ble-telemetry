// 根构建脚本：只声明插件，不写具体依赖
// 注意：这里用的是 plugins { ... apply false }，表示"声明但不立刻应用"，
// 真正的 apply 发生在 app/build.gradle.kts 里。这是 Gradle 8+ 的推荐写法。
//
// 版本说明（为什么要这几个版本）：
//   AGP 9.5.0 —— 本机 Android Studio (AI-261) 自带环境，且只有它支持
//                本机 SDK 里已安装的 android-37 编译平台
//
// ⚠️ 重要变化：AGP 9.0 起**内置了 Kotlin 支持**，
//    不再需要（也不允许）声明 org.jetbrains.kotlin.android 插件。
//    Kotlin 编译器版本由 AGP 自己决定，这也避免了
//    "AGP 版本 × Kotlin 版本 × KSP 版本"三者对不上的经典踩坑。
//
//   KSP 2.3.12 —— 仍需要单独声明（Room 的注解处理器），
//                 新版 KSP 用独立版本号，不再写成 "kotlin版本-1.0.x"
plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
