# ============================================================
# 真正常用的 keep 规则
# Gson / Hilt / kotlinx.coroutines 均由各自 AAR 的 consumer rules
# 自动处理，这里不再重复。SDL 例外，见文件末尾。
# ============================================================

# Gson 运行时反射（字段 / 实例化 / 枚举名）。
# Signature、注解属性、TypeToken、@SerializedName 由 gson.pro 处理。
-keep class com.zyz4.gkme.model.** { *; }

# Input 类：SdlNative 的 native 方法、native/SDL 回调与 UsbPhysicalControllerBackend
# 的 javaClass.simpleName 分支都依赖原始类名与成员。必须整包完整保留，
# 否则 release 混淆后 JNI 注册/回调失配导致崩溃。
-keep class com.zyz4.gkme.input.** { *; }

# XML 中按类名 inflate 的自定义 View（LayoutInflater 反射实例化）。
# 只保留类名与 View 构造，其余成员允许裁剪/混淆。
-keep class com.zyz4.gkme.view.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# protobuf-javalite 未附带 consumer rules，lite 运行时依赖反射。
-keepclassmembers class com.zyz4.gkme.proto.** { *; }
-keep class * extends com.google.protobuf.MessageLite { *; }

# SDL：libSDL3.so 在 JNI_OnLoad 里用 RegisterNatives 动态注册
# org.libsdl.app.* 的方法（如 onNativeDropFile），静态无法推断；
# AAR 自带 proguard.txt 未覆盖本项目的原生构建，必须整包保留。
-keep class org.libsdl.app.** { *; }
