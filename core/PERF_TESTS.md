# `:core` — Performance test suite

JVM unit-tests kiêm micro-benchmark cho **đường tính toán CPU mỗi frame** của module `core`
(map landmark → dựng mesh mặt → cho bọ bò theo path). Mỗi test in một dòng `[perf] … ns/op …`
và assert một trần budget để **bắt regression** (thuật toán xấu đi, cấp phát rác, vô tình quay lại
dùng mock đắt…).

## Chạy

```bash
./gradlew :core:testDebugUnitTest --tests "com.piontech.bugfilter.core.render.*PerfTest"
```

Báo cáo HTML: `core/build/reports/tests/testDebugUnitTest/index.html`
Các dòng `[perf] …` in thẳng ra console (đã bật `showStandardStreams`).

### ⚠️ JDK

AGP 8.13 **chưa hỗ trợ JDK 24** — chạy Gradle bằng JDK 24 sẽ lỗi cấu hình
`Could not create task ':core:testDebugUnitTest' … Type T not present`. Dùng **JDK 17–21**:

```bash
JAVA_HOME="/path/to/jdk-17" ./gradlew :core:testDebugUnitTest --tests "*PerfTest"
```

(Android Studio chạy bằng JBR đi kèm nên không gặp lỗi này; chỉ là vấn đề của CLI khi `JAVA_HOME`
trỏ vào JDK quá mới.)

## Đo cái gì

| Test | Lớp / hàm | Tần suất thực tế |
|---|---|---|
| `FaceCoordinateMapperPerfTest` | `imageToTex` / `world3` / `faceWidthPx` | ~936 lần/frame (dày đặc nhất) |
| `FaceSurfaceModelPerfTest` | `FaceSurfaceModel.update` | 1 lần/frame (nặng nhất) |
| `BugWalkerPerfTest` | `BugWalker.advance` / `buildPath` | mỗi bug/frame |
| `FramePipelinePerfTest` | toàn bộ CPU 1 frame (faceWidthPx + update + N×advance) | headline, so với budget 60fps |

## On-device microbenchmark (AndroidX Benchmark, `src/androidTest`)

Đo phần **native** mà JVM không chạm được, trên **thiết bị thật**:

```bash
JAVA_HOME=<jdk17> ./gradlew :core:connectedDebugAndroidTest
# Kết quả JSON: core/build/outputs/connected_android_test_additional_output/.../*-benchmarkData.json
```

| Benchmark | Đo gì |
|---|---|
| `MlKitFaceMeshBenchmark.faceMeshDetectionLatency` | độ trễ nhận diện ML Kit Face Mesh / frame |
| `FilamentTransformBenchmark.buildAndSetTransform_perBugPerFrame` | dựng ma trận + `setTransform` (JNI) mỗi bọ |
| `FilamentTransformBenchmark.buildTransformMatrix_cpuOnly` | chỉ phần `android.opengl.Matrix` |

> Chạy qua `connectedDebugAndroidTest` ⇒ APK **debuggable** (đã `suppressErrors=DEBUGGABLE,…`). Số
> debuggable cao hơn release ~10–30% nhưng đúng bậc + tốt cho so sánh. Muốn số "thật" hết cỡ: thêm
> plugin `androidx.benchmark` + build type `benchmark` (debuggable=false).

### Kết quả đo thực tế — Samsung Galaxy A16 (SM-A165F, entry-level, arm64, Android 16, clocks locked)

| Benchmark | median | min | alloc/op |
|---|---|---|---|
| **ML Kit FaceMesh detection** | **43.0 ms** | 29.0 ms | 5334 |
| Filament build+setTransform / bọ | 0.0029 ms | 0.0029 ms | **0** |
| android.opengl.Matrix (chỉ CPU) | 0.0026 ms | 0.0026 ms | **0** |

**Đọc kết quả:**
- **ML Kit là chi phí áp đảo (~43ms/frame ≈ 23 fps detection).** Nhưng nó chạy trên **luồng analysis nền**
  (`FaceMeshAnalyzer`, `STRATEGY_KEEP_ONLY_LATEST`), KHÔNG trên render thread → **không gây ANR**, không
  chặn render 60fps. `FaceTracker` buffer mesh theo timestamp nên Filament vẫn vẽ 60fps bằng mesh mới nhất;
  chỉ là vị trí bọ cập nhật ~23–34 Hz. Đây là điểm cần để mắt nếu muốn tracking "mượt" hơn (model nhẹ hơn,
  hạ resolution input, hoặc nội suy giữa các lần detect).
- **Filament transform + ma trận: ~3µs/bọ, 0 cấp phát** → 10 bọ ≈ 30µs, hoàn toàn không đáng kể, không tạo
  rác GC. Không phải nguồn jank/OOM/ANR.

## Ghi chú thiết kế (vì sao đo được mà không cần thiết bị)

`FaceCoordinateMapper`/`FaceSurfaceModel`/`BugWalker` là toán thuần, nhưng đọc landmark qua
`FaceMesh`/`FaceMeshPoint` của ML Kit. Để benchmark đo **chi phí code thật** chứ không phải chi phí
test-double, [`FakeFace`](src/test/java/com/piontech/bugfilter/core/testutil/FakeFace.kt) dựng đối
tượng thật:

- `FaceMeshPoint`: gọi constructor package-private `(int, PointF3D)` qua reflection → `getPosition()`
  là field-read (~1ns). (Mock Mockito tốn ~2µs/lần × hàng triệu lần gọi ⇒ vừa OOM vừa làm số đo sai
  ~100×.)
- `FaceMesh`: constructor cần holder nội bộ (không có trên classpath) ⇒ **ByteBuddy** sinh subclass
  (không constructor) override `getAllPoints()` trả thẳng 1 field, khởi tạo bằng **Objenesis**.
  (Thử nhét list vào field nội bộ bằng `Unsafe` đã hỏng: field khai báo kiểu nội bộ abstract 1-impl
  nên JIT devirtualize `List.get()` sai → NPE khi vòng đo bị JIT-compile.)

Test deps (chỉ `testImplementation`, không ảnh hưởng app): `junit`, `mockito-core` (mock
`FilamentInstance` cho qua constructor `Bug`), `objenesis`, `byte-buddy`.

## Cách đọc trần assert

Trần được đặt **nới rộng có chủ ý** (số thực hiện ~µs, trần tính bằng ms) để không flaky theo tốc độ
máy/CI — chúng chỉ bắt regression *nghiêm trọng*. Tín hiệu mịn nằm ở **con số in ra**: so dòng
`[perf]` giữa các lần chạy để thấy xu hướng. Trần `FramePipelinePerfTest` (≤8ms, ½ của budget 60fps)
là khẳng định sản phẩm: *toàn bộ CPU mỗi frame của `:core` phải lọt nửa khung hình*, chừa nửa còn lại
cho Filament/GL native.
