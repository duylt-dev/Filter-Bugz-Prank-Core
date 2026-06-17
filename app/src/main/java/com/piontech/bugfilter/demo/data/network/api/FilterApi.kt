package com.piontech.bugfilter.demo.data.network.api

import com.piontech.bugfilter.core.model.FilterModel
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Tầng API. Catalog đi qua Retrofit/OkHttp nhưng được [com.piontech.bugfilter.demo.data.network.AssetCatalogInterceptor] trả từ
 * assets/filters/filters.json (đóng vai "API"). Còn [downloadFile] tải thật model_zip từ CDN.
 */
interface FilterApi {

    /** Lấy danh sách filter (catalog). Thực tế interceptor trả nội dung filters.json. */
    @GET(CATALOG_PATH)
    suspend fun getCatalog(): List<FilterModel>

    /** Tải file theo URL tuyệt đối (model_zip trên CDN). @Streaming → không nạp hết vào RAM. */
    @Streaming
    @GET
    suspend fun downloadFile(@Url url: String): ResponseBody

    companion object {
        const val BASE_URL = "https://local.api/"
        const val CATALOG_PATH = "filters.json"
        const val CATALOG_URL = BASE_URL + CATALOG_PATH
    }
}
