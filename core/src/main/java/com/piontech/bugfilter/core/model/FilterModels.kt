package com.piontech.bugfilter.core.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Mô hình dữ liệu cho 1 filter "bọ bò trên mặt", parse từ filters.json (catalog).
 * Nguồn catalog do app cung cấp (asset bây giờ; API sau) — core chỉ parse + dùng.
 *
 * [model]/[thumbnail] có thể là tên file (asset) hoặc URL; app sẽ quy đổi thành File local rồi
 * đưa cho core (core không tự đọc asset/URL). [scrip] mô tả cách bọ di chuyển theo landmark.
 *
 * Parcelable để truyền qua Intent giữa các màn (Bugs → Camera) mà không cần parse lại catalog.
 */
@Parcelize
data class FilterModel(
    val id: String = "",
    val name: String = "",
    val thumbnail: String = "",
    @SerializedName("model_zip") val modelZip: String = "",
    /** Tên file glb trong assets/filters/models/ (đã giải nén sẵn). */
    val model: String = "",
    val scrip: List<BugScrip> = emptyList(),
    /** Tinh chỉnh per-model (tùy chọn, ghi trong filters.json) — null = dùng mặc định global.
     *  yawOffset: bù góc "đầu" model (độ); sizeScale: hệ số kích thước theo bề ngang mặt. */
    @SerializedName("yaw_offset") val yawOffset: Float? = null,
    @SerializedName("size_scale") val sizeScale: Float? = null
) : Parcelable

/** Kịch bản chuyển động của một con bọ (1 phần tử trong scrip_json). */
@Parcelize
data class BugScrip(
    val nameModel: String = "",
    /** Index của clip animation trong file glb sẽ phát (walk cycle). */
    val animIndex: Int = 0,
    /** Tốc độ phát animation glTF. */
    val animSpeed: Float = 1f,
    /** Tỉ lệ scale model. */
    val scale: Float = 1f,
    /** Tiến độ di chuyển dọc path mỗi giây (0..1 của 1 đoạn). */
    val progressPerSecond: Float = 0.2f,
    /** Chọn ngẫu nhiên đoạn path kế tiếp thay vì tuần tự. */
    val isRandomPath: Boolean = false,
    val position: Vec3 = Vec3(),
    val rotate: Vec3 = Vec3(),
    /** Danh sách các đoạn đường đi; mỗi đoạn là chuỗi index landmark khuôn mặt. */
    val listPath: List<BugPath> = emptyList()
) : Parcelable

@Parcelize
data class BugPath(
    val listPathIndex: List<Int> = emptyList(),
    val delayTimeStartSecond: Float = 0f,
    val delayTimeEndSecond: Float = 0f
) : Parcelable

@Parcelize
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) : Parcelable
