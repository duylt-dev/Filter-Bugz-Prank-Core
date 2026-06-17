# Bug Filter — Camera filter "bọ bò trên mặt" (Filament + ML Kit)

Ứng dụng demo filter camera: nhận diện khuôn mặt bằng **ML Kit Face Mesh**, dựng bề mặt 3D của mặt rồi
cho **con bọ (model GLB)** bò trên mặt bằng **Filament**, composite vào luồng camera (preview **và** video).

Project gồm 2 module:

| Module | Vai trò | Phụ thuộc chính |
|---|---|---|
| **`:core`** | Engine xử lý: đọc **File local** → nhận diện + render + composite GL. KHÔNG chạm asset/URL/network. | CameraX-core, ML Kit Face Mesh, Filament |
| **`:app`** | Tầng ứng dụng: MVVM + Hilt, đọc `filters.json` làm catalog, tải `model_zip`/IBL về **File local** rồi đưa cho `:core`. CameraX + UI. | Hilt, Retrofit/OkHttp, Room, Coil, `:core` |

> Nguyên tắc cốt lõi: **`:core` chỉ làm việc với `File` local + Surface của CameraX**. Mọi việc lấy dữ liệu
> (download URL, copy asset, giải nén zip, resolve IBL) là trách nhiệm của tầng application.

---

## Bắt đầu: kéo project về & chạy

### Yêu cầu
- **Android Studio** (bản mới).
- **JDK 17–21** nếu build bằng terminal — AGP 8.13 **chưa hỗ trợ JDK 24** (lỗi `Could not create task … Type T not present`). Android Studio dùng JBR đi kèm nên không cần lo.
- **1 thiết bị Android thật (arm64)**. Camera + ML Kit Face Mesh + Filament chạy đúng/nhanh nhất trên máy thật; emulator x86 sẽ không cài được (xem mục ABI bên dưới).

### Kéo project về
```bash
git clone ...
cd ...
```
Mở thư mục bằng Android Studio và chờ Gradle sync.

### Chạy module nào?
| Module | Chạy được? | Ghi chú |
|---|---|---|
| **`:app`** | ✅ **Đây là app để chạy** (launcher = `BugsActivity`). | Chọn cấu hình run **`app`**. |
| `:core` | ❌ Thư viện, không chạy trực tiếp | Được `:app` dùng (xem mục 1). |

### ⭐ Khuyến nghị: chạy chế độ **RELEASE** thay vì debug

App này **nặng phần native** (Filament + MediaPipe của ML Kit). Bản **debug** không bật R8/shrink, `debuggable=true`,
đóng gói nhiều ABI → **APK to hơn, chạy chậm hơn ~10–30%** (camera/ML Kit/Filament), số đo hiệu năng **không thực tế**.
Bản **release** mới phản ánh đúng trải nghiệm người dùng: R8 + shrinkResources + `abiFilters` → APK gọn (~57MB) và mượt.

> **Lưu ý về `app/key_store.jks`:** đây chỉ là keystore **đủ điều kiện để build/chạy release** (Android bắt buộc
> APK phải được ký), commit sẵn trong repo cho tiện chạy thử ngay — **KHÔNG phải khóa ký production**. Đừng dùng
> nó để phát hành lên Google Play (mật khẩu đang để công khai trong `app/build.gradle.kts`). Khi lên Store, hãy
> tạo keystore riêng và giữ bí mật.

**Trong Android Studio:**
1. Mở panel **Build Variants** (góc dưới-trái) → đổi module `app` sang **`release`**.
2. Cắm thiết bị thật → bấm **Run ▶**.

**Bằng terminal:**
```bash
# Cài thẳng bản RELEASE lên máy đang cắm:
JAVA_HOME=/path/to/jdk-17 ./gradlew :app:installRelease

# Hoặc chỉ tạo APK (ở app/build/outputs/apk/release/app-release.apk):
JAVA_HOME=/path/to/jdk-17 ./gradlew :app:assembleRelease
```

> Chỉ dùng **debug** khi cần debugger/breakpoint hoặc bật các overlay debug ở [mục 2](#2-optional-debug-cho-dev-tầng-application-bậttắt-khi-phát-triển).
> Hiện debug và release **dùng chung `applicationId`** (không cài song song được); nếu muốn cài cả hai cùng máy, bỏ comment dòng `applicationIdSuffix = ".debug"` trong `app/build.gradle.kts`.

---

## 1. Cách dùng module `:core` cho module application khác

### 1.1 Khai báo phụ thuộc

#### Kéo từ GitHub Packages (cho application khác) ⭐
Dùng bản đã publish (`com.piontech.bugfilter:core`).

**B1. Khai báo repo GitHub Packages** trong `settings.gradle.kts` (vì AndroidX/AGP mặc định
`FAIL_ON_PROJECT_REPOS`, repo phải đặt ở đây, không đặt trong module):
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/duylt-dev/Filter-Bugz-Prank-Core")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

**B2. Đặt credential** (token chỉ cần scope **`read:packages`** để TẢI về). KHÔNG commit token — để ở
file global `~/.gradle/gradle.properties`:
```properties
# ~/.gradle/gradle.properties   (ngoài repo, không bị đẩy lên git)
gpr.user=<github-username>
gpr.key=<PAT classic có scope read:packages>
```
> GitHub Packages **bắt buộc xác thực kể cả khi tải** (không có token sẽ lỗi 401 lúc resolve). Tạo PAT:
> *Settings → Developer settings → Personal access tokens → Tokens (classic)* → tick `read:packages`.

**B3. Khai báo dependency** trong app:
```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.piontech.bugfilter:core:1.0.0")   // <group>:<artifact>:<version> đã publish
}
```

> Phiên bản hiện tại: **`1.0.0`**. Mỗi lần `:core` ra bản mới, đổi version tương ứng.

<details><summary><b>(Maintainer) Publish bản <code>:core</code> mới lên GitHub Packages</b></summary>

1. Tăng `publishVersion` trong `core/build.gradle.kts` (vd `1.0.1`).
2. Đặt credential publish ở `~/.gradle/gradle.properties` (`gpr.user` + token có scope **`write:packages`**).
3. Chạy: `JAVA_HOME=<jdk17> ./gradlew :core:publishReleasePublicationToGitHubPackagesRepository`
4. Bên app: đổi version trong `implementation("com.piontech.bugfilter:core:<version mới>")`.

Cấu hình publish (group/artifact/version + repo `owner/repo`) nằm ở đầu & cuối `core/build.gradle.kts`.
</details>

#### Chung cho cả 2 cách
`:core` expose sẵn (qua `api`) 2 thư viện bạn cần để nối camera — **không phải khai báo lại** (Cách B nhận
qua POM transitively):
- `androidx.camera:camera-core` (vì `GlSurfaceProcessor` là `SurfaceProcessor`, `FaceMeshAnalyzer` là `ImageAnalysis.Analyzer`).
- `com.google.mlkit:face-mesh-detection` (vì `FaceResult.faceMesh` là kiểu ML Kit `FaceMesh`).

Tầng app vẫn cần tự thêm phần CameraX để bind lifecycle (ví dụ `camera-view`, `camera-lifecycle`, `camera-video`).
`minSdk` của `:core` là **28**.

### 1.2 Ba điểm tích hợp (toàn bộ API public bạn cần)

| Lớp (package) | Là gì | Vai trò |
|---|---|---|
| `GlSurfaceProcessor` (`core.gl`) | một `SurfaceProcessor` | Bọc vào `CameraEffect` → mọi frame camera đi qua GL của core (preview + video). |
| `FilamentRenderer` (`core.render`) | một `GlSurfaceProcessor.GlListener` | Render con bọ 3D + composite. Gắn vào `processor.glListener`. Nhận model/IBL/face. |
| `FaceMeshAnalyzer` (`core.face`) | một `ImageAnalysis.Analyzer` | Chạy ML Kit Face Mesh trên luồng nền → trả `FaceResult?`. |

Dữ liệu phụ:
- `FaceResult` (`core.face`): kết quả 1 mặt (mesh + kích thước ảnh + góc xoay + timestamp).
- `FilterModel` / `BugScrip` / `BugPath` / `Vec3` (`core.model`): model dữ liệu (Parcelable) parse từ `filters.json` bằng Gson.
- `FaceOverlayView` (`core.face`): custom `View` để vẽ landmark/bbox khi debug (xem mục 2).

### 1.3 Đầu vào (Input) → Đầu ra (Output)

**Đầu vào `:core` cần:**

| Đầu vào | Kiểu | Cấp qua | Ghi chú |
|---|---|---|---|
| Frame camera | Surface (CameraX) | `CameraEffect` bọc `GlSurfaceProcessor` | core tự nhận `onInputSurface`/`onOutputSurface`. |
| Frame để nhận diện | `ImageProxy` | `setImageAnalysisAnalyzer(executor, FaceMeshAnalyzer)` | core tự `close()` ImageProxy. |
| Model con bọ | **`java.io.File`** (.glb) | `FilamentRenderer.setModel(glb, scrip, yawOffset, sizeScale)` | app phải tải/giải nén ra File trước. |
| IBL môi trường | **`java.io.File`** (.ktx) | `FilamentRenderer.setIbl(ktx)` | tùy chọn nhưng nên có (ánh sáng đẹp). |
| Kịch bản di chuyển | `List<BugScrip>` | tham số của `setModel` | thường lấy từ `filters.json`. |

**Đầu ra `:core` trả:**

| Đầu ra | Dạng | Lấy ở đâu |
|---|---|---|
| Khung hình đã composite (camera + bọ + bóng) | vẽ thẳng ra **các Surface output** (PreviewView + VideoCapture) | tự động — video quay ra = đúng nội dung hiển thị. |
| Kết quả nhận diện mặt | `FaceResult?` (callback) | lambda `onResult` của `FaceMeshAnalyzer` (để hiện trạng thái / vẽ overlay debug). |

### 1.4 Luồng dữ liệu

```
CameraX ──(frames)──> GlSurfaceProcessor ──> [GL: vẽ camera] ─┐
                            │  glListener                      ├─> composite ─> PreviewView + VideoCapture
                            └────────────> FilamentRenderer ───┘   (camera + bọ 3D + bóng)
                                              ▲   ▲   ▲
                          setModel(File .glb) │   │   └ setFace(FaceResult, mirror)
                              setIbl(File .ktx)┘   │
                                                   │
CameraX ImageAnalysis ─(ImageProxy)─> FaceMeshAnalyzer ─(FaceResult?)─> app callback ──┘ (gọi setFace)
```

### 1.5 Lưu ý quan trọng

1. **Chỉ truyền `File` local.** `:core` không đọc asset/URL/zip. App phải resolve ra File (download/copy/unzip) rồi mới `setModel`/`setIbl`.
2. **Khóa màn hình PORTRAIT.** Phép quy đổi landmark → texture chỉ đúng ở dọc. Đặt `requestedOrientation = SCREEN_ORIENTATION_PORTRAIT` (và khóa trong manifest).
3. **Gắn `glListener` TRƯỚC khi bind camera.** `proc.glListener = renderer` phải xong trước `bindToLifecycle`.
4. **`mirror = true` cho camera trước.** Truyền cờ này vào `setFace(result, mirror = isFrontCamera)` để bọ không bị lật.
5. **Threading.** `FaceMeshAnalyzer` chạy trên executor nền bạn cấp; `setFace` thread-safe (`@Volatile`). Mọi cập nhật UI từ callback phải `runOnUiThread { }`.
6. **PHẢI giải phóng tài nguyên** (rất quan trọng — Filament/EGL/ML Kit là native, không tự thu hồi). Xem 1.7.
7. **`setModel`/`setIbl` idempotent theo path** và nạp trên GL thread ở frame kế → gọi sớm, an toàn khi gọi lặp (core tự destroy cái cũ trước khi nạp mới).

### 1.6 Demo code (tích hợp tối thiểu)

```kotlin
@AndroidEntryPoint
class MyCameraActivity : AppCompatActivity() {

    private var controller: LifecycleCameraController? = null
    private var processor: GlSurfaceProcessor? = null
    private var renderer: FilamentRenderer? = null
    private var analyzer: FaceMeshAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    // App đã chuẩn bị sẵn 2 File local này (tải/copy/unzip ở màn trước).
    private lateinit var glbFile: File          // model con bọ (.glb)
    private var iblFile: File? = null           // môi trường (.ktx), tùy chọn
    private lateinit var scrip: List<BugScrip>  // parse từ filters.json

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT  // (2)
        setContentView(R.layout.activity_my_camera)
        // ... lấy glbFile / iblFile / scrip từ Intent hoặc ViewModel ...
    }

    // Tạo khi vào foreground, giải phóng khi ra nền (xem 1.7).
    override fun onStart() {
        super.onStart()
        startCamera()
    }

    override fun onStop() {
        super.onStop()
        releaseCamera()
    }

    private fun startCamera() {
        if (controller != null) return

        val exec = Executors.newSingleThreadExecutor()
        analysisExecutor = exec

        // 1) Processor + renderer
        val proc = GlSurfaceProcessor().also { processor = it }
        val rdr = FilamentRenderer(debug = false).also { renderer = it }
        proc.glListener = rdr                                   // (3) gắn TRƯỚC khi bind
        rdr.setModel(glbFile, scrip, yawOffset = null, sizeScale = null)
        iblFile?.let { rdr.setIbl(it) }

        // 2) CameraEffect cho cả preview + video → cùng một luồng GL
        val effect = object : CameraEffect(
            PREVIEW or VIDEO_CAPTURE,
            ContextCompat.getMainExecutor(this),
            proc,
            Consumer { t -> Log.e("Cam", "effect error", t) }
        ) {}

        // 3) Analyzer ML Kit (luồng nền) → đẩy mặt cho renderer
        val az = FaceMeshAnalyzer(exec) { result ->
            val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
            rdr.setFace(result, mirror = isFront)              // (4)
            runOnUiThread { /* cập nhật UI trạng thái nếu cần */ }   // (5)
        }.also { analyzer = it }

        // 4) Bind camera
        controller = LifecycleCameraController(this).apply {
            cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            setEnabledUseCases(
                LifecycleCameraController.IMAGE_ANALYSIS or LifecycleCameraController.VIDEO_CAPTURE
            )
            // Phân giải analysis thấp → ML Kit nhanh → bọ bám mặt khi rung cam
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 360),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(exec, az)
            setEffects(setOf(effect))
            bindToLifecycle(this@MyCameraActivity)
            findViewById<PreviewView>(R.id.previewView).controller = this
        }
    }

    private fun releaseCamera() { /* xem 1.7 */ }
}
```

> Quay video: dùng `controller!!.startRecording(outputOptions, audioConfig, mainExecutor) { event -> ... }`.
> Vì effect áp cho cả `VIDEO_CAPTURE`, video quay ra đã chứa sẵn con bọ.

### 1.7 Vòng đời & giải phóng (bắt buộc)

`:core` giữ tài nguyên **native** (Filament engine, EGL context, GL texture/FBO, model ML Kit, GL thread).
Không nhả đúng cách = giữ hàng trăm MB lúc không dùng → dễ bị hệ thống kill / OOM trên máy yếu.

**Khuyến nghị: tạo ở `onStart`, giải phóng ở `onStop`** (không chỉ `onDestroy`) để khi app ra nền là trả
RAM/CPU ngay:

```kotlin
private fun releaseCamera() {
    controller?.unbind(); controller = null
    findViewById<PreviewView>(R.id.previewView).controller = null
    analyzer?.close(); analyzer = null          // đóng ML Kit detector
    processor?.release(); processor = null      // → onGlRelease: destroy Filament engine + EGL + GL (trên GL thread)
    renderer = null
    analysisExecutor?.shutdown(); analysisExecutor = null
}
```

- `processor.release()` tự gọi `glListener.onGlRelease()` → `FilamentRenderer` destroy engine/scene/view/asset/IBL/render-target, rồi `EglCore.release()` + thoát GL thread. **Không cần** destroy `FilamentRenderer` thủ công.
- `release()` chạy bất đồng bộ trên GL thread, idempotent — gọi lại an toàn.
- Đánh đổi của "release ở onStop": mỗi lần quay lại foreground sẽ **nạp lại GLB** (~vài trăm ms một lần). Đổi lại không giữ native memory khi nền.

---

## 2. Optional debug cho dev (tầng application bật/tắt khi phát triển)

### 2.1 Bật nền render của Filament — `FilamentRenderer(debug = true)`
Tô nền vùng vẽ bọ thành **xanh lá mờ** để soi đúng vùng RenderTarget đang được composite (production để `false`).

```kotlin
val renderer = FilamentRenderer(debug = BuildConfig.DEBUG)
```

### 2.2 Vẽ landmark + bounding box — `FaceOverlayView`

**Bước 1 — Thêm view vào layout XML** (chồng ĐÚNG lên `PreviewView` để toạ độ khớp). Chính view này tạo ra
`binding.faceOverlay` dùng ở bước 2 (id `faceOverlay` + ViewBinding bật sẵn). Ví dụ trong `ConstraintLayout`:

```xml
<!-- res/layout/activity_my_camera.xml -->
<androidx.camera.view.PreviewView
    android:id="@+id/previewView"
    android:layout_width="0dp"
    android:layout_height="0dp"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent" />

<!-- Overlay debug: phủ đúng vùng previewView -->
<com.piontech.bugfilter.core.face.FaceOverlayView
    android:id="@+id/faceOverlay"
    android:layout_width="0dp"
    android:layout_height="0dp"
    app:layout_constraintTop_toTopOf="@id/previewView"
    app:layout_constraintBottom_toBottomOf="@id/previewView"
    app:layout_constraintStart_toStartOf="@id/previewView"
    app:layout_constraintEnd_toEndOf="@id/previewView" />
```

> Cần bật ViewBinding để có `binding.faceOverlay`/`binding.previewView`:
> ```kotlin
> // build.gradle.kts của app
> android { buildFeatures { viewBinding = true } }
> ```
> rồi `val binding = ActivityMyCameraBinding.inflate(layoutInflater); setContentView(binding.root)`.
> (Không dùng ViewBinding thì thay bằng `findViewById<FaceOverlayView>(R.id.faceOverlay)`.)

**Bước 2 — Feed kết quả trong callback của analyzer.** Truyền `null` để tắt (production):

```kotlin
val az = FaceMeshAnalyzer(exec) { result ->
    rdr.setFace(result, mirror = isFront)
    runOnUiThread {
        binding.faceOverlay.setResult(
            if (BuildConfig.DEBUG) result else null,   // null = không vẽ
            mirror = isFront
        )
        binding.tvStatus.text =
            if (result != null) "Đã thấy mặt: ${result.faceMesh.allPoints.size} điểm"
            else "Không thấy mặt"
    }
}
```

### 2.3 Ghim bọ vào 1 landmark cố định — `BugPlacer.DEBUG_PIN_INDEX`
Hằng compile-time trong `BugPlacer` (`-1` = tắt). Đổi sang index landmark (vd `1`) để bọ đứng yên tại đúng
điểm đó → soi mapping landmark→texture có lệch không. Chỉ dùng khi dev (sửa trực tiếp trong `:core`).

### 2.4 Logcat
| Tag | Khi nào | Nội dung hữu ích |
|---|---|---|
| `FilamentRenderer` | khi nạp | `Filament engine ready`, `Loaded <path> với N instance(s)`, `IBL loaded`, kích thước `SIZES output=...x... image=...` |
| (CameraEffect Consumer) | lỗi GL pipeline | lỗi từ `CameraEffect` (đặt trong lambda khi tạo effect) |

Lọc nhanh: `adb logcat -s FilamentRenderer`.

### 2.5 Đo hiệu năng
- **JVM micro-benchmark** (CPU pipeline mapper/surface/walker): `./gradlew :core:testDebugUnitTest --tests "*PerfTest"`.
- **On-device benchmark** (ML Kit detection + Filament `setTransform`): `./gradlew :core:connectedDebugAndroidTest`.
- Chi tiết + kết quả tham chiếu: xem [`core/PERF_TESTS.md`](core/PERF_TESTS.md).

### 2.6 Lưu ý build từ CLI
AGP 8.13 **chưa hỗ trợ JDK 24** (lỗi `Could not create task … Type T not present`). Build/test từ terminal
hãy dùng **JDK 17–21**:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew :app:assembleDebug
```
(Android Studio dùng JBR đi kèm nên không gặp lỗi này.)
