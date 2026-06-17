# Keep-rules của :core — áp tự động khi app (R8/minify) consume core.

# --- Filament ---
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }
-keep class com.google.android.filament.utils.** { *; }

# --- ML Kit ---
-keep class com.google.mlkit.** { *; }

# --- Gson (parse filters.json bằng reflection) ---
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Model classes bị Gson reflection + Parcelize đọc/ghi field → KHÔNG rename/strip.
-keep class com.piontech.bugfilter.core.model.** { *; }

# --- Custom View dùng trong layout XML (LayoutInflater reflection theo tên class) ---
-keep class com.piontech.bugfilter.core.face.FaceOverlayView { *; }
