package com.piontech.bugfilter.demo.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo File local của app (nơi core đọc model). Model GLB lưu ở **filesDir** (bền) để `local_path` trong
 * Room không bị vô hiệu khi hệ thống dọn cache; IBL/tạm để ở **cacheDir**.
 */
@Singleton
class ModelCache @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }
    private val cacheDir: File = File(appContext.cacheDir, "bugfilter").apply { mkdirs() }

    /** File model (bền) trong filesDir. */
    fun modelFile(name: String): File = File(modelsDir, name)

    /** File tạm/ephemeral trong cacheDir. */
    fun cacheFile(name: String): File = File(cacheDir, name)

    /**
     * Giải nén [zip] (stream), ghi entry .glb khớp [modelName] (fallback .glb đầu tiên) ra [dest].
     * GLB là self-contained nên chỉ cần lấy đúng file glb trong zip.
     */
    fun unzipGlb(zip: InputStream, modelName: String, dest: File) {
        val target = modelName.substringAfterLast('/')
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var chosen: ByteArray? = null
        var firstGlb: ByteArray? = null
        ZipInputStream(zip).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name.endsWith(".glb", ignoreCase = true)) {
                    val bytes = zis.readBytes()
                    if (name.equals(target, ignoreCase = true)) {
                        chosen = bytes
                        break
                    } else if (firstGlb == null) {
                        firstGlb = bytes
                    }
                }
                entry = zis.nextEntry
            }
        }
        val data = chosen ?: firstGlb ?: error("Không tìm thấy .glb trong zip cho $modelName")
        tmp.writeBytes(data)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** Copy asset → [dest] nếu chưa có; trả File local. */
    fun copyAsset(assetPath: String, dest: File): File {
        if (dest.exists() && dest.length() > 0L) return dest
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        appContext.assets.open(assetPath).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest
    }
}
