package com.piontech.bugfilter.core.testutil

import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import com.piontech.bugfilter.core.face.FaceResult
import com.piontech.bugfilter.core.model.BugPath
import com.piontech.bugfilter.core.model.BugScrip
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy
import net.bytebuddy.implementation.FieldAccessor
import net.bytebuddy.matcher.ElementMatchers.named
import org.objenesis.ObjenesisStd
import java.lang.reflect.Modifier
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Dựng dữ liệu khuôn mặt cho perf/unit test mà KHÔNG cần thiết bị/ML Kit native — nhưng dùng ĐỐI TƯỢNG THẬT
 * để benchmark đo đúng chi phí CODE, không phải chi phí test-double.
 *
 * Vì sao không mock: [FaceMesh.getAllPoints]/[FaceMeshPoint.getPosition] bị code gọi HÀNG TRIỆU lần trong
 * vòng đo; mock Mockito tốn ~2µs/lần (gấp ~100× toán thật) ⇒ số đo phản ánh Mockito chứ không phải code.
 *
 * Cách dựng thật:
 *  - [FaceMeshPoint]: gọi constructor package-private (int, PointF3D) qua reflection → getPosition() là field-read.
 *  - [FaceMesh]: constructor cần holder nội bộ (không có trên classpath) → tạo bằng Objenesis (bỏ constructor)
 *    rồi nhét list điểm vào field backing của getAllPoints() bằng Unsafe (field kiểu nội bộ obfuscated nên
 *    Field.set báo sai-kiểu; Unsafe.putObject bỏ qua kiểm tra — getfield+areturn vẫn trả về list của ta).
 *
 * Nếu bản ML Kit đổi layout, các check() bên dưới sẽ fail SỚM với thông báo rõ ràng.
 */
object FakeFace {

    /** ML Kit Face Mesh trả về đúng 468 điểm. */
    const val LANDMARK_COUNT = 468

    /** Kích thước ảnh nguồn (upright) ML Kit thường trả cho camera trước. */
    const val IMAGE_W = 480
    const val IMAGE_H = 640

    /** Kích thước output texture của Filament (landscape) — w×h như processor cấp. */
    const val TEX_W = 1280
    const val TEX_H = 720

    private val objenesis = ObjenesisStd()

    private val pointCtor by lazy {
        FaceMeshPoint::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType, PointF3D::class.java)
            .apply { isAccessible = true }
    }

    private fun realPoint(index: Int, p: PointF3D): FaceMeshPoint = pointCtor.newInstance(index, p)

    /**
     * Subclass FaceMesh (sinh lúc runtime, KHÔNG constructor) với 1 field `pts` + override getAllPoints()
     * trả thẳng field. Vì sao KHÔNG dùng Unsafe nhét list vào field nội bộ: field đó khai báo kiểu nội bộ
     * (abstract, 1 impl) ⇒ JIT devirtualize List.get() theo kiểu khai báo, gọi nhầm vào layout ArrayList → NPE.
     * Override hợp lệ (getAllPoints non-final) cho JIT thấy đúng kiểu trả về ⇒ an toàn & vẫn là field-read rẻ.
     */
    private val fakeMeshClass: Class<out FaceMesh> by lazy {
        ByteBuddy()
            .subclass(FaceMesh::class.java, ConstructorStrategy.Default.NO_CONSTRUCTORS)
            .defineField("pts", java.util.List::class.java, Modifier.PUBLIC)
            .method(named("getAllPoints"))
            .intercept(FieldAccessor.ofField("pts"))
            .make()
            .load(FaceMesh::class.java.classLoader)
            .loaded
    }
    private val fakeMeshPtsField by lazy { fakeMeshClass.getField("pts") }

    /** FaceMesh mà getAllPoints() trả về đúng [points] (field-read O(1), không native, không mock, JIT-safe). */
    private fun realFaceMesh(points: List<FaceMeshPoint>): FaceMesh {
        val mesh = objenesis.newInstance(fakeMeshClass)
        fakeMeshPtsField.set(mesh, points)
        check(mesh.allPoints === points) { "FaceMesh.getAllPoints() không trả list đã set — cập nhật FakeFace." }
        return mesh
    }

    /** 468 điểm mặc định (seed 42) tạo MỘT lần rồi tái dùng giữa các test. */
    private val defaultPoints: List<FaceMeshPoint> by lazy { buildPoints(42) }

    private fun buildPoints(seed: Int): List<FaceMeshPoint> {
        val rnd = Random(seed)
        val cx = IMAGE_W / 2f
        val cy = IMAGE_H / 2f
        val rx = IMAGE_W * 0.30f
        val ry = IMAGE_H * 0.28f
        return List(LANDMARK_COUNT) { i ->
            val ang = rnd.nextFloat() * (2.0 * Math.PI).toFloat()
            val rad = sqrt(rnd.nextDouble()).toFloat()        // sqrt → phân bố đều trong đĩa, không dồn tâm
            val x = cx + rad * rx * cos(ang.toDouble()).toFloat()
            val y = cy + rad * ry * sin(ang.toDouble()).toFloat()
            val z = (rnd.nextFloat() - 0.5f) * 60f
            realPoint(i, PointF3D.from(x, y, z))
        }
    }

    /**
     * 468 landmark phân bố như một khuôn mặt: rải trong ellipse (đầy giữa, thưa mép) + jitter z.
     * Deterministic theo [seed] → số đo lặp lại được. Các index đặc biệt (10 trán, 152 cằm, 168 mũi,
     * 234/454 má) đều < 468 nên luôn hợp lệ. Seed mặc định tái dùng cache.
     */
    fun landmarkPoints(seed: Int = 42): List<FaceMeshPoint> =
        if (seed == 42) defaultPoints else buildPoints(seed)

    /** [FaceResult] quanh một FaceMesh THẬT. rotationDegrees 270 = tư thế PORTRAIT front-cam (nhánh hot). */
    fun faceResult(
        points: List<FaceMeshPoint> = landmarkPoints(),
        rotationDegrees: Int = 270,
        imageWidth: Int = IMAGE_W,
        imageHeight: Int = IMAGE_H,
        timestampNs: Long = 1_000_000L,
    ): FaceResult = FaceResult(realFaceMesh(points), imageWidth, imageHeight, rotationDegrees, timestampNs)

    /**
     * Scrip mẫu với nhiều ĐOẠN path nối liền (chung node) + 2 đoạn chạm landmark mũi 168 → kích hoạt
     * nhánh ẩn/hiện "chui vào mũi". Đủ để BugWalker.advance đi qua mọi trạng thái (delay/crawl/pickNext).
     */
    fun sampleScrip(): BugScrip = BugScrip(
        nameModel = "test-bug",
        progressPerSecond = 0.2f,
        isRandomPath = true,
        listPath = listOf(
            BugPath(listPathIndex = listOf(168, 197, 195, 5, 4), delayTimeStartSecond = 0.1f, delayTimeEndSecond = 0.1f),
            BugPath(listPathIndex = listOf(4, 1, 19, 94, 2), delayTimeStartSecond = 0.05f, delayTimeEndSecond = 0.05f),
            BugPath(listPathIndex = listOf(234, 93, 132, 58, 172), delayTimeStartSecond = 0.05f, delayTimeEndSecond = 0.05f),
            BugPath(listPathIndex = listOf(454, 323, 361, 288, 397), delayTimeStartSecond = 0.05f, delayTimeEndSecond = 0.05f),
            BugPath(listPathIndex = listOf(10, 109, 67, 103, 54), delayTimeStartSecond = 0.05f, delayTimeEndSecond = 0.05f),
        ),
    )
}
