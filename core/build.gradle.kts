plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    `maven-publish`
}

// ============================================================================
//  Publish :core lên GitHub Packages (Maven). BẠN ĐẶT CỐ ĐỊNH 4 giá trị dưới đây:
// ============================================================================
val publishGroupId = "com.piontech.bugfilter"          // ← tên group của lib
val publishArtifactId = "core"                         // ← tên artifact (lib) của bạn
val publishVersion = "1.0.0"                           // ← version phát hành
// OWNER/REPO của GitHub Packages (mặc định lấy theo repo hiện tại):
val githubOwnerRepo = "duylt-dev/Filter-Bugz-Prank-Core"   // ← "owner/repo"

android {
    namespace = "com.piontech.bugfilter.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        // Keep-rules cho Filament/ML Kit/Gson/model/custom-view áp tự động khi app consume core.
        consumerProguardFiles("consumer-rules.pro")

        // On-device microbenchmark (androidTest) dùng runner của AndroidX Benchmark.
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Chạy qua connectedDebugAndroidTest → APK debuggable; hạ các lỗi "không lý tưởng" xuống cảnh báo để
        // vẫn ra số (số debuggable hơi cao hơn release ~10-30% nhưng đúng bậc + tốt cho so sánh tương đối).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "DEBUGGABLE,LOW-BATTERY,EMULATOR,UNLOCKED,ACTIVITY-MISSING"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests {
            // FilterModels (@Parcelize) & FaceResult chạm android stub khi class-load → trả default thay vì ném.
            isReturnDefaultValues = true
            // In dòng [perf] ... ns/op của benchmark ra console + cấp heap rộng cho vòng đo nhiều iteration.
            all {
                it.testLogging { showStandardStreams = true }
                it.maxHeapSize = "1g"
            }
        }
    }

    // Tạo component "release" để maven-publish đóng gói AAR (kèm sources cho IDE của người dùng).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    //  - camera-core: GlSurfaceProcessor là SurfaceProcessor, FaceMeshAnalyzer là ImageAnalysis.Analyzer.
    //  - mlkit: FaceResult.faceMesh là com.google.mlkit...FaceMesh (app đọc allPoints).
    api(libs.camera.core)
    api(libs.mlkit.face.mesh)

    // implementation: chi tiết nội bộ, không lộ ra app.
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio)
    implementation(libs.filament.utils)
    implementation(libs.gson)

    // --- Unit / performance tests (JVM, src/test) ---
    testImplementation(libs.junit)
    // Mock FilamentInstance (Bug giữ tham chiếu nhưng BugWalker không gọi) cho qua constructor.
    testImplementation(libs.mockito.core)
    // Dựng FaceMesh/landmark THẬT (không qua native ML Kit) → benchmark đo đúng chi phí code, không phải mock.
    testImplementation(libs.objenesis)
    testImplementation(libs.byte.buddy)

    // --- On-device microbenchmark (instrumented, src/androidTest) ---
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// component "release" của AGP được tạo trễ → cấu hình publication trong afterEvaluate.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = publishGroupId
                artifactId = publishArtifactId
                version = publishVersion
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/$githubOwnerRepo")
                // BẠN ĐẶT CỐ ĐỊNH username/token ở đây. KHUYẾN NGHỊ để trong ~/.gradle/gradle.properties
                // (KHÔNG commit token vào repo — GitHub sẽ thu hồi token bị lộ):
                //   gpr.user=<github-username>
                //   gpr.key=<personal-access-token có quyền write:packages>
                credentials {
                    username = (findProperty("gpr.user") as String?)
                        ?: System.getenv("GITHUB_ACTOR")
                        ?: "<GITHUB_USERNAME>"
                    password = (findProperty("gpr.key") as String?)
                        ?: System.getenv("GITHUB_TOKEN")
                        ?: "<GITHUB_TOKEN_write_packages>"
                }
            }
        }
    }
}
