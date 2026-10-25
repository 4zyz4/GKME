plugins {
    id("com.android.application")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.orson391.virtualgamepad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orson391.virtualgamepad"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        val gamepadProfile = providers.gradleProperty("gamepadProfile").getOrElse("xbox")
        require(gamepadProfile in setOf("xbox", "ds4", "dualsense")) {
            "gamepadProfile must be one of: xbox, ds4, dualsense"
        }
        buildConfigField("String", "GAMEPAD_PROFILE", "\"$gamepadProfile\"")

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
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Shizuku: privileged UserService binding and uinput virtual device creation
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
