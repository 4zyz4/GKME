import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.protobuf")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// 本地签名配置（local.properties 已被 .gitignore 忽略，不会入库）。
// 配置齐全时，debug 变体复用 release 密钥签名，便于覆盖安装正式版。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.reader(Charsets.UTF_8).use { load(it) }
    }
}

fun localProperty(key: String): String? =
    localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.zyz4.gkme"
    //noinspection GradleDependency
    compileSdk = 36

    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.zyz4.gkme"
        minSdk = 26
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 4800
        versionName = "4.8.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildFeatures {
        prefab = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.4.3"
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    signingConfigs {
        val storePath = localProperty("GKME_RELEASE_STORE_FILE")
        if (storePath != null && rootProject.file(storePath).isFile) {
            create("releaseSigned") {
                storeFile = rootProject.file(storePath)
                storePassword = localProperty("GKME_RELEASE_STORE_PASSWORD")
                keyAlias = localProperty("GKME_RELEASE_KEY_ALIAS")
                keyPassword = localProperty("GKME_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 与正式版同签名，可直接覆盖安装并保留应用数据 / Shizuku 授权。
            signingConfigs.findByName("releaseSigned")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/SDL3-3.4.18.aar"))

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")

    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // 被控端：通过 Shizuku 在 shell/root 进程创建 uinput 虚拟手柄。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.code.gson:gson:2.11.0")

    // 绕过隐藏 API 限制，访问系统 Bluetooth HID Host profile 直连 DualSense
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}