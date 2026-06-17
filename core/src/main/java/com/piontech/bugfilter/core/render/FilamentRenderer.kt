package com.piontech.bugfilter.core.render

import android.opengl.GLES20
import android.util.Log
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.RenderTarget
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import com.piontech.bugfilter.core.face.FaceResult
import com.piontech.bugfilter.core.gl.EglCore
import com.piontech.bugfilter.core.gl.GlSurfaceProcessor
import com.piontech.bugfilter.core.model.BugScrip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Render model GLB con bọ bằng Filament (dùng CHUNG EGL context với [GlSurfaceProcessor]) vào một
 * RenderTarget có color = texture GL 2D ta tự tạo; processor composite texture đó lên camera.
 *
 * Lớp này là ORCHESTRATOR: quản lý vòng đời Filament (engine/scene/render target), nạp model, và mỗi
 * frame điều phối các collaborator —
 *  - [FaceTracker]: buffer mesh theo timestamp (khớp frame đang vẽ).
 *  - [FaceCoordinateMapper] + [FaceSurfaceModel]: quy đổi landmark + dựng bề mặt 3D (vị trí/pháp tuyến/bbox).
 *  - [BugWalker]: cho bọ bò theo đồ thị path (scrip_json app gốc).
 *  - [BugPlacer]: ốp con bọ lên bề mặt + làm mượt vị trí/hướng + dựng transform.
 *
 * Stage 3: dùng camera ORTHOGRAPHIC trong hệ toạ độ pixel của output.
 *
 * Nguồn dữ liệu: core CHỈ đọc File local. App lo việc lấy file (download URL / copy asset → cache) rồi
 * gọi [setModel] (GLB) và [setIbl] (KTX môi trường). [debug] bật nền xanh để soi vùng vẽ.
 */
class FilamentRenderer(private val debug: Boolean = false) : GlSurfaceProcessor.GlListener {

    companion object {
        private const val TAG = "FilamentRenderer"

        /** Số face mesh giữ lại (buffer) để khớp theo timestamp — như app gốc giữ 8. */
        private const val FACE_BUF = 10

        init {
            Utils.init()
        }
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var swapChain: SwapChain? = null

    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var asset: FilamentAsset? = null

    private var lightEntity = 0
    private var fillLightEntity = 0
    private var indirectLight: IndirectLight? = null

    private var renderTarget: RenderTarget? = null
    private var colorFilamentTex: Texture? = null
    private var glColorTexId = -1
    private var rtWidth = 0
    private var rtHeight = 0

    @Volatile
    private var pendingModelFile: File? = null

    @Volatile
    private var pendingScrip: List<BugScrip> = emptyList()

    @Volatile
    private var pendingYaw: Float? = null

    @Volatile
    private var pendingSize: Float? = null

    /** File KTX môi trường (IBL) do app cấp; nạp trên GL thread. */
    @Volatile
    private var pendingIblFile: File? = null

    private var loadedModelKey: String? = null
    private var loadedIblKey: String? = null
    private var scrip: List<BugScrip> = emptyList()
    private var modelYaw: Float? = null
    private var modelSize: Float? = null

    // Collaborator: tách trách nhiệm khỏi class này (xem KDoc đầu file).
    private val faceTracker = FaceTracker(FACE_BUF)
    private val mapper = FaceCoordinateMapper()
    private val surface = FaceSurfaceModel(mapper)
    private val walker = BugWalker()
    private val placer = BugPlacer()

    private val bugs = ArrayList<Bug>()

    private var lastFrameNs = 0L
    private var loggedSizes = false

    /**
     * Đổi model bọ: [glb] là File GLB local (app đã download/copy về cache). [scrip] mô tả chuyển động,
     * [yawOffset]/[sizeScale] tinh chỉnh per-model. Nạp thật sự diễn ra trên GL thread ở frame kế.
     */
    fun setModel(glb: File, scrip: List<BugScrip>, yawOffset: Float?, sizeScale: Float?) {
        pendingModelFile = glb
        pendingScrip = scrip
        pendingYaw = yawOffset
        pendingSize = sizeScale
    }

    /** Đặt IBL môi trường từ File KTX local (app cấp). Nạp trên GL thread. */
    fun setIbl(ktx: File) {
        pendingIblFile = ktx
    }

    /** Đẩy 1 mặt mới vào buffer (gọi từ luồng analysis nền). */
    fun setFace(result: FaceResult?, mirror: Boolean) = faceTracker.push(result, mirror)

    // ---- GlListener ----

    override fun onGlReady(eglCore: EglCore) {
        try {
            val eng = Engine.Builder().sharedContext(eglCore.eglContext).build()
            engine = eng
            renderer = eng.createRenderer().apply {
                clearOptions = Renderer.ClearOptions().apply {
                    clear = true
                    clearColor = if (debug) floatArrayOf(0f, 0.35f, 0f, 0.35f)
                    else floatArrayOf(0f, 0f, 0f, 0f)
                }
            }
            scene = eng.createScene()
            cameraEntity = EntityManager.get().create()
            camera = eng.createCamera(cameraEntity).apply {
                setExposure(16f, 1f / 125f, 100f)
            }
            view = eng.createView().apply {
                scene = this@FilamentRenderer.scene
                camera = this@FilamentRenderer.camera
                blendMode = View.BlendMode.TRANSLUCENT
            }
            addLights(eng)
            maybeLoadIbl(eng)
            assetLoader = AssetLoader(eng, UbershaderProvider(eng), EntityManager.get())
            resourceLoader = ResourceLoader(eng)
            Log.i(TAG, "Filament engine ready (shared context)")
        } catch (t: Throwable) {
            Log.e(TAG, "onGlReady failed", t)
        }
    }

    private fun addLights(eng: Engine) {
        val em = EntityManager.get()
        lightEntity = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(80_000f)
            .direction(0.3f, -0.4f, -1f).castShadows(false)
            .build(eng, lightEntity)
        scene?.addEntity(lightEntity)

        fillLightEntity = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(60_000f)
            .direction(0f, 0f, -1f).castShadows(false)
            .build(eng, fillLightEntity)
        scene?.addEntity(fillLightEntity)
    }

    /**
     * Image-based lighting (IBL) từ File KTX môi trường (app cấp qua [setIbl]) → bọ sáng/chi tiết tự nhiên.
     * Chạy trên GL thread (gọi từ onGlReady và đầu onDrawOverlay). Idempotent theo path file.
     */
    private fun maybeLoadIbl(eng: Engine) {
        val file = pendingIblFile ?: return
        if (file.path == loadedIblKey) return
        try {
            val bytes = file.readBytes()
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            bb.put(bytes); bb.rewind()
            val ibl = KTX1Loader.createIndirectLight(eng, bb)
            ibl.intensity = 40_000f
            indirectLight?.let { eng.destroyIndirectLight(it) }
            scene?.indirectLight = ibl
            indirectLight = ibl
            loadedIblKey = file.path
            Log.i(TAG, "IBL loaded từ ${file.path}")
        } catch (t: Throwable) {
            Log.e(TAG, "maybeLoadIbl failed", t)
        }
    }

    override fun onDrawOverlay(width: Int, height: Int, frameTimestampNs: Long): Int {
        val eng = engine ?: return -1
        try {
            maybeLoadIbl(eng)
            handleModelLoad(eng)
            if (bugs.isEmpty()) return -1
            // Chọn mesh KHỚP timestamp frame đang render → bọ đồng bộ đúng khoảnh khắc (không lệch khi rung).
            val faceNow = faceTracker.pick(frameTimestampNs) ?: return -1
            // Bỏ qua nếu mặt đã quá cũ (mất nhận diện thật sự) — nhưng giữ ~300ms để không nháy.
            if (faceTracker.isStale(System.nanoTime())) return -1
            if (!loggedSizes) {
                loggedSizes = true
                Log.i(
                    TAG,
                    "SIZES output=${width}x${height} image=${faceNow.imageWidth}x${faceNow.imageHeight} bugs=${bugs.size}"
                )
            }
            ensureRenderTarget(eng, width, height)
            setupOrthoCamera(width, height)

            val now = System.nanoTime()
            if (lastFrameNs == 0L) lastFrameNs = now
            val dt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastFrameNs = now

            // Mesh + bbox + bề ngang mặt: tính 1 lần/ frame, dùng chung cho mọi con bọ.
            mapper.mirror = faceTracker.mirror
            surface.update(faceNow, width, height, dt)
            val faceW = mapper.faceWidthPx(faceNow, width, height)
            val assetBox = asset!!.boundingBox   // chung cho mọi instance (cùng geometry)

            for (bug in bugs) {
                bug.instance.animator?.let { anim ->
                    if (anim.animationCount > 0) {
                        if (bug.animStartNs == 0L) bug.animStartNs = now
                        val seconds = (now - bug.animStartNs) / 1_000_000_000.0
                        val dur = maxOf(0.001f, anim.getAnimationDuration(0))
                        anim.applyAnimation(0, (((seconds * bug.scrip.animSpeed) % dur).toFloat()))
                        anim.updateBoneMatrices()
                    }
                }
                placer.place(eng, bug, dt, faceW, surface, walker, assetBox, modelYaw, modelSize)
            }

            val r = renderer ?: return -1
            val v = view ?: return -1
            val sc = swapChain ?: return -1
            if (r.beginFrame(sc, now)) {
                r.render(v)
                r.endFrame()
            }
            eng.flushAndWait()
            return glColorTexId
        } catch (t: Throwable) {
            Log.e(TAG, "onDrawOverlay failed", t)
            return -1
        }
    }

    /** Ortho theo pixel: world x∈[0,W], y∈[0,H], nhìn dọc -Z. */
    private fun setupOrthoCamera(width: Int, height: Int) {
        val cam = camera ?: return
        val d = maxOf(width, height).toDouble()
        cam.setProjection(
            Camera.Projection.ORTHO,
            0.0,
            width.toDouble(),
            0.0,
            height.toDouble(),
            0.1,
            2.0 * d + 1.0
        )
        cam.lookAt(0.0, 0.0, d, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }

    private fun handleModelLoad(eng: Engine) {
        val file = pendingModelFile
        val key = file?.path
        if (key == loadedModelKey) return
        asset?.let { old ->
            scene?.removeEntities(old.entities)
            assetLoader?.destroyAsset(old)
        }
        asset = null
        bugs.clear()
        loadedModelKey = key
        scrip = pendingScrip
        modelYaw = pendingYaw
        modelSize = pendingSize
        lastFrameNs = 0L
        if (file == null) return
        try {
            val bytes = file.readBytes()
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            bb.put(bytes); bb.rewind()
            // 1 asset chia sẻ geometry, mỗi scrip = 1 instance (transform + animator riêng).
            val n = maxOf(1, scrip.size)
            val instances = arrayOfNulls<FilamentInstance>(n)
            val newAsset = assetLoader!!.createInstancedAsset(bb, instances) ?: return
            resourceLoader!!.loadResources(newAsset)
            newAsset.releaseSourceData()
            scene?.addEntities(newAsset.entities)
            asset = newAsset
            for (i in 0 until n) {
                val inst = instances[i] ?: continue
                val sc = scrip.getOrNull(i) ?: BugScrip()
                val bug = Bug(sc, inst)
                walker.buildPath(bug)
                if (i > 0 && bug.segs.isNotEmpty()) {   // lệch đoạn/pha để các con không bò trùng nhịp
                    bug.curSeg = i % bug.segs.size
                    bug.segPhase = (i * 0.37f) % 1f
                    bug.walkState = 1
                    bug.delayTimer = 0f
                }
                bugs.add(bug)
            }
            Log.i(TAG, "Loaded $key với ${bugs.size} instance(s)")
        } catch (t: Throwable) {
            Log.e(TAG, "load model failed: $key", t)
        }
    }

    private fun ensureRenderTarget(eng: Engine, width: Int, height: Int) {
        if (renderTarget != null && width == rtWidth && height == rtHeight) return
        destroyRenderTarget(eng)
        rtWidth = width
        rtHeight = height

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        glColorTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glColorTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        colorFilamentTex = Texture.Builder()
            .width(width).height(height).levels(1)
            .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE)
            .format(Texture.InternalFormat.RGBA8)
            .importTexture(glColorTexId.toLong())
            .build(eng)

        renderTarget = RenderTarget.Builder()
            .texture(RenderTarget.AttachmentPoint.COLOR, colorFilamentTex!!)
            .build(eng)

        swapChain?.let { eng.destroySwapChain(it) }
        swapChain = eng.createSwapChain(width, height, 0L)

        view?.let {
            it.renderTarget = renderTarget
            it.viewport = Viewport(0, 0, width, height)
        }
    }

    private fun destroyRenderTarget(eng: Engine) {
        view?.renderTarget = null
        renderTarget?.let { eng.destroyRenderTarget(it) }
        colorFilamentTex?.let { eng.destroyTexture(it) }
        renderTarget = null
        colorFilamentTex = null
        if (glColorTexId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(glColorTexId), 0)
            glColorTexId = -1
        }
    }

    override fun onGlRelease() {
        val eng = engine ?: return
        try {
            asset?.let { assetLoader?.destroyAsset(it) }
            asset = null
            destroyRenderTarget(eng)
            resourceLoader?.destroy()
            assetLoader?.destroy()
            if (lightEntity != 0) eng.destroyEntity(lightEntity)
            if (fillLightEntity != 0) eng.destroyEntity(fillLightEntity)
            indirectLight?.let { eng.destroyIndirectLight(it) }
            renderer?.let { eng.destroyRenderer(it) }
            view?.let { eng.destroyView(it) }
            scene?.let { eng.destroyScene(it) }
            camera?.let { eng.destroyCameraComponent(cameraEntity) }
            swapChain?.let { eng.destroySwapChain(it) }
            eng.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "onGlRelease failed", t)
        }
        engine = null
    }
}
