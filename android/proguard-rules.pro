-keepclassmembers class com.zyz4.gamepademu.proto.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson TypeToken - 保留匿名子类
-keep class com.google.gson.reflect.TypeToken { *; }

# Hilt - Dagger
-keep class dagger.hilt.** { *; }
-keep class jakarta.inject.** { *; }
-keep class javax.inject.** { *; }
-keep class hilt_aggregated_deps { *; }

# Hilt 注解类 - 保留 @HiltAndroidApp, @HiltViewModel, @AndroidEntryPoint
-keep,allowobfuscation @dagger.hilt.android.HiltAndroidApp class *
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *

# Hilt ViewModel 构造函数 - Hilt 通过反射调用 @Inject 构造函数
-keepclassmembers,allowobfuscation class * {
    @javax.inject.Inject <init>(...);
}

# ViewModel - 保留所有 ViewModel 子类及默认构造函数
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Repository - 保留所有 @Singleton 注入的 Repository
-keep class com.zyz4.gamepademu.data.** { *; }

# Application 类 - 保留 @HiltAndroidApp 标记的 Application
-keep class com.zyz4.gamepademu.GamepadEmuApp { *; }

# Module - 保留 Hilt @Module, @Provides
-keep class com.zyz4.gamepademu.di.** { *; }
-keepclassmembers,allowobfuscation class * {
    @dagger.Provides <methods>;
}

# kotlinx Coroutines - 协程相关
-keep class kotlinx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.android.** { *; }

# DataStore - 保留 DataStore 相关
-keep class androidx.datastore.** { *; }

# Model 类 - 所有 data class 和 enum（Gson 序列化/反序列化）
-keep class com.zyz4.gamepademu.model.** { *; }

# Service 类 - 所有服务类（Hilt 注入 + 反射调用）
-keep class com.zyz4.gamepademu.service.** { *; }

# Input 类 - 传感器和输入处理
-keep class com.zyz4.gamepademu.input.** { *; }

# MainActivity - Activity 必须保留
-keep class com.zyz4.gamepademu.MainActivity { *; }

# 保留 Proto 相关的嵌套类和 Builder
-keep class * extends com.google.protobuf.Message { *; }
-keep class * extends com.google.protobuf.MessageLite { *; }
-keep class com.google.protobuf.** { *; }

# View 相关 - 如果有自定义 View
-keep class com.zyz4.gamepademu.view.** { *; }