# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep-rules cho Filament/ML Kit/Gson/model/custom-view đã chuyển sang :core (consumer-rules.pro),
# áp tự động khi app consume core. Ở đây chỉ giữ phần app-specific.

# --- Giữ số dòng cho stack trace crash bản release (dễ gỡ lỗi) ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
