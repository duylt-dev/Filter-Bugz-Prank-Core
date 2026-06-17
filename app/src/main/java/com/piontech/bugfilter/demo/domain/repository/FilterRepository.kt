package com.piontech.bugfilter.demo.domain.repository

import com.piontech.bugfilter.demo.domain.model.BugFilter
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Nguồn dữ liệu filter (SSOT = Room). App quy nguồn (API/asset) về DB + File local cho core.
 */
interface FilterRepository {

    /** Luồng danh sách filter từ Room (SSOT) — phát lại khi DB đổi (vd cập nhật local_path). */
    fun observeFilters(): Flow<List<BugFilter>>

    /** Lần đầu (DB rỗng): tải catalog (filters.json) và insert. Đã có data → bỏ qua. */
    suspend fun seedIfEmpty()

    /**
     * Đảm bảo GLB của filter [id] có sẵn local: nếu local_path == "" thì tải model_zip + giải nén,
     * lưu file rồi update local_path vào Room. Trả File local cho core.
     */
    suspend fun ensureModel(id: String): File

    /** Đảm bảo IBL môi trường có trong cache → File local cho core. */
    suspend fun ensureIbl(): File
}
