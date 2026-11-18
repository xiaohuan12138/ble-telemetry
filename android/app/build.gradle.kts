plugins {
    // AGP 9.0 起内置 Kotlin，这里不能再写 org.jetbrains.kotlin.android
    id("com.android.application")
    id("com.google.devtools.ksp")   // Room 的注解处理器（比 kapt 快 2 倍）
}

android {
    namespace = "com.pixellab.app"
    // 37 = 本机 SDK 里唯一安装的编译平台（Sdk\platforms\android-37）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pixellab.app"
        minSdk = 26          // Android 8.0：通知渠道、EXIF 接口行为统一，从这里开始省心
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // 课程项目不混淆，方便老师反查逻辑
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 内置 Kotlin 后，编译器配置改用这个块（旧的 kotlinOptions {} 已移除）
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
            freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---------------- AndroidX 基础 ----------------
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")

    // ---------------- 生命周期 & 协程 ----------------
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // ---------------- Navigation ----------------
    // 单 Activity 多 Fragment；只有一处跳转参数（图片 ID），用 Bundle 显式传参
    implementation("androidx.navigation:navigation-fragment-ktx:2.10.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.2")

    // ---------------- Room（处理历史持久化）----------------
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")        // 支持返回 Flow 的 DAO
    ksp("androidx.room:room-compiler:2.8.5")              // 编译期生成 SQLite 代码

    // ---------------- DataStore（轻量配置存储）----------------
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    // ---------------- WorkManager（批量处理后台任务）----------------
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // ---------------- RecyclerView ----------------
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // ---------------- C1 强制技术点：CameraX 拍照 ----------------
    // 1.4.2 稳定版：CameraX 只提供生命周期感知的相机 API，内部自己管 Camera2 状态机
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // ---------------- C1 强制技术点：ML Kit 文字识别（离线内置模型）----------------
    // bundled 版本：模型打进 APK，离线可用，模拟器也能跑（unbundled 要连 Google 服务，模拟器跑不了）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ---------------- EXIF 元数据解析 ----------------
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ---------------- 单元测试 ----------------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}
