import com.android.build.api.dsl.ApplicationBuildType

fun ApplicationBuildType.stringConfigField(key: String, value: String) =
    this.buildConfigField("String", key, "\"$value\"")

fun ApplicationBuildType.booleanConfigField(key: String, value: Boolean) =
    this.buildConfigField("Boolean", key, value.toString())

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.google.service)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.piontech.bugfilter.demo"
    compileSdk = 36

    signingConfigs {
        create("release") {
            keyAlias = "key0"
            keyPassword = "com.piontech.bugfilter.demo"
            storePassword = "com.piontech.bugfilter.demo"
            storeFile = File(projectDir, "../app/key_store.jks")
        }
    }
    defaultConfig {
        applicationId = "com.piontech.bugfilter.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Native libs (MediaPipe/ML Kit + Filament) là phần nặng nhất của APK. Chỉ đóng gói ABI cho THIẾT BỊ
            // THẬT; bỏ x86 & x86_64 (chỉ dành cho emulator) → giảm ~53MB. Bỏ thêm "armeabi-v7a" nếu không cần
            // hỗ trợ máy 32-bit (xuống ~35MB, chỉ còn arm64). Tốt nhất cho Play Store: phát hành .aab (bundleRelease)
            // để mỗi người chỉ tải đúng 1 ABI — khi đó có thể bỏ abiFilters này.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // Cài SONG SONG với release (khác applicationId) để test cùng máy.
            /*applicationIdSuffix = ".debug"*/          // → com.piontech.bugfilter.demo.debug
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true

            booleanConfigField("isDebug", true)
        }
        release {
            // R8: rút gọn + obfuscate code và tài nguyên. Keep rules cho Filament/ML Kit/Gson/custom-view
            // nằm ở proguard-rules.pro (BẮT BUỘC, thiếu là vỡ runtime: JSON không parse, bọ không render).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ký bằng keystore release thật (app/key_store.jks) khai báo ở signingConfigs trên.
            signingConfig = signingConfigs.getByName("release")

            booleanConfigField("isDebug", false)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    // GLB models phải để nguyên (không nén) để load nhanh qua ByteBuffer.
    androidResources {
        noCompress += "glb"
    }
}

dependencies {
    // Core tính năng (face mesh + GL + render bọ). camera-core & mlkit lộ qua api(core).
    // Dùng package đã publish trên GitHub Packages (thay cho project(":core")) để test consume registry.
    implementation("com.piontech.bugfilter:core:1.0.0")
    // implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)

    // Dagger Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Retrofit + OkHttp: catalog (filters.json qua interceptor) + tải model_zip từ CDN.
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Room: SSOT local DB cho catalog filter + local_path.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX phần app (controller/preview/quay video/effect). camera-core đến qua api(core).
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.video)
    implementation(libs.camera.effects)

    // Coil: load ảnh thumbnail bọ (URL remote) ở màn chọn bọ.
    implementation(libs.coil)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
