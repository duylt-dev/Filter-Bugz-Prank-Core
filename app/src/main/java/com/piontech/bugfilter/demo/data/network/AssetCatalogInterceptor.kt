package com.piontech.bugfilter.demo.data.network

import android.content.Context
import com.piontech.bugfilter.demo.data.network.api.FilterApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Cho phép filters.json (asset) đóng vai response của API catalog: nếu request trỏ tới
 * [com.piontech.bugfilter.demo.data.network.api.FilterApi.Companion.CATALOG_URL] thì trả 200 với nội dung asset; còn lại đi tiếp ra mạng (tải CDN).
 */
class AssetCatalogInterceptor(context: Context) : Interceptor {

    private val appContext = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.toString() == FilterApi.Companion.CATALOG_URL) {
            val json = appContext.assets.open("filters/filters.json").use { it.readBytes() }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return chain.proceed(request)
    }
}