package com.piontech.bugfilter.demo.data.repository

import com.piontech.bugfilter.demo.data.local.dao.FilterDao
import com.piontech.bugfilter.demo.data.mapper.toDomain
import com.piontech.bugfilter.demo.data.mapper.toEntity
import com.piontech.bugfilter.demo.data.network.api.FilterApi
import com.piontech.bugfilter.demo.domain.model.BugFilter
import com.piontech.bugfilter.demo.domain.repository.FilterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSOT = Room. Điều phối:
 * - [observeFilters]: Flow từ Room (map Entity→Domain).
 * - [seedIfEmpty]: DB rỗng thì gọi API catalog (filters.json) → insert.
 * - [ensureModel]: quyết định tải DỰA VÀO `local_path` (== "" → tải model_zip + giải nén → update DB).
 * - [ensureIbl]: copy IBL từ asset → cache.
 */
@Singleton
class FilterRepositoryImpl @Inject constructor(
    private val api: FilterApi,
    private val dao: FilterDao,
    private val cache: ModelCache,
) : FilterRepository {

    override fun observeFilters(): Flow<List<BugFilter>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.count() == 0) {
            val catalog = api.getCatalog()
            dao.insertAll(catalog.map { it.toEntity() })
        }
    }

    override suspend fun ensureModel(id: String): File = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: error("Không tìm thấy filter $id")

        // Quyết định tải dựa vào local_path (kèm guard: path có nhưng file đã mất thì tải lại).
        if (entity.localPath.isNotEmpty()) {
            val existing = File(entity.localPath)
            if (existing.exists() && existing.length() > 0L) return@withContext existing
        }

        require(entity.modelZip.isNotBlank()) { "Thiếu model_zip cho ${entity.name}" }
        val dest = cache.modelFile("model_${entity.id}.glb")
        api.downloadFile(entity.modelZip).byteStream().use { zip ->
            cache.unzipGlb(zip, entity.model, dest)
        }
        dao.updateLocalPath(entity.id, dest.absolutePath) // cập nhật SSOT
        dest
    }

    override suspend fun ensureIbl(): File = withContext(Dispatchers.IO) {
        cache.copyAsset(IBL_ASSET, cache.cacheFile("neutral_ibl.ktx"))
    }

    companion object {
        private const val IBL_ASSET = "filters/neutral_ibl.ktx"
    }
}
