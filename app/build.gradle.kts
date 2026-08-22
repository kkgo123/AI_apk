/*
 * ============================================================
 * MindSoul AGI 人工生命 APP - 应用级构建配置
 * ============================================================
 * 
 * 本配置文件定义了 MindSoul APP 的编译参数、依赖关系和构建行为。
 * 
 * 核心依赖策略：
 * - 仅使用 AndroidX 原生组件
 * - 禁止引入任何第三方 AI/ML 库
 * - 神经网络、意识模拟全部手写原生实现
 * ============================================================
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.kkgo.mindsoul"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.kkgo.mindsoul"
        minSdk = 26          // Android 8.0
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 神经网络相关 - NDK配置预留
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // ============ AndroidX 核心组件 ============
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // ============ UI 组件 ============
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // ============ 协程支持 ============
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ============ Room 数据库（角色扮演系统等持久化存储） ============
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // ============ 序列化（仅用于基础JSON，不使用AI相关） ============
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ============ 测试依赖 ============
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
