plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.isip"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.isip"
        // GenieX 的 AAR 声明 minSdk 27；本项目原本是 24。目标设备是 Android 16，
        // 且 App 的核心功能（MediaStore、Compose、Vulkan 后端）在 API 27 以下
        // 本来也不可用，所以直接对齐到 27 而不是用 tools:overrideLibrary 强压。
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 配置 NDK
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // 配置 CMake
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // 配置 CMake 路径
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    // 让 .so 真实解压到文件系统，而不是只在 APK 里被映射。
    //
    // 原因：Hexagon NPU 的 DSP skel（libggml-htp-v79.so）必须由高通 FastRPC 守护
    // 进程加载，而它按文件名在 ADSP_LIBRARY_PATH / 自己的搜索路径里找 —— App 设不了
    // 环境变量，`nativeLibraryDir` 是普通 App 唯一能提供、且守护进程能读到的位置。
    // Android 10 起系统默认不把 .so 解压出来（nativeLibraryDir 为空），于是
    // ggml-hexagon 打开会话时报 AEE_EUNABLETOLOAD (0x80000406)。
    //
    // 代价是安装占用增加（磁盘上多一份 .so 副本），换来 NPU 可用。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Image Loading
    implementation(libs.coil.compose)

    // ViewModel and Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Extended Icons
    implementation(libs.androidx.compose.material.icons.extended)

    // Gson for JSON parsing
    implementation(libs.gson)
    // MobileCLIP image/text encoders run fully on-device through LiteRT.
    implementation(libs.litert)

    // Room database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // llama.cpp for GGUF model inference
    // Note: de.kherud:llama library API is not compatible with Android
    // Will use a custom wrapper or wait for proper Android llama.cpp library
    // implementation(libs.llama.cpp.java)

    // Qualcomm GenieX —— 官方 NPU 推理 SDK（Apache-2.0 / Qualcomm Terms of Use）。
    //
    // 为什么需要它：我们自建的 ggml-hexagon 路线在 CLI 下可用（实测 prefill 354-452
    // tok/s），但装进 App 后卡在 DSP skel 装载（AEE_EUNABLETOLOAD 0x80000406）——
    // FastRPC 守护进程按文件名在 ADSP_LIBRARY_PATH 里找 skel，而普通 App 设不了
    // 该环境变量。GenieX 由高通自己分发同一套 ggml-hexagon 后端
    // （libggml-hexagon.so + libggml-htp-v79.so，SONAME 与我们构建的完全一致）
    // 以及官方 HTP 运行时（libQnnHtpV79Skel.so 等），把这条路的装载问题在
    // AAR 内部解决掉，是 App 内拿到 NPU 的官方途径。
    implementation("com.qualcomm.qti:geniex-android:0.4.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
