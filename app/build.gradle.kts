import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 凭据不进源码。
 *
 * 开源仓库里的 LocalConfig 全是空值，能编译、能录音，只是不往上传。
 * 本机真实凭据写在 local.properties（已被 .gitignore 忽略），
 * 构建时注入成 BuildConfig 常量，APK 里才有值。
 *
 * 需要填的键：
 *   baidu.bduss / baidu.stoken / baidu.bdstoken
 *   dashscope.key
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String): String =
    (localProps.getProperty(key) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.daluo.alwaysrec"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.daluo.alwaysrec"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("String", "BAIDU_BDUSS", "\"${secret("baidu.bduss")}\"")
        buildConfigField("String", "BAIDU_STOKEN", "\"${secret("baidu.stoken")}\"")
        buildConfigField("String", "BAIDU_BDSTOKEN", "\"${secret("baidu.bdstoken")}\"")
        buildConfigField("String", "DASHSCOPE_KEY", "\"${secret("dashscope.key")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 可选：把自带的能量 VAD 换成精度更高的 WebRTC VAD 时启用
    // 注意 24 小时常驻场景优先用 webrtc（GMM/纯 C），不要用 silero（DNN 推理耗电）
    // implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")
}
