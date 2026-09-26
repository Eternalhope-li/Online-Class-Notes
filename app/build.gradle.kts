import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build for the Android emulator (x86_64) instead of real phones when -Pemu is given.
val emuBuild: Boolean = project.hasProperty("emu")

// 签名信息从 local.properties（已被 .gitignore 忽略）或同名环境变量读，密钥库和口令都不入库。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String = localProps.getProperty(key) ?: System.getenv(key)
    ?: error("缺少签名配置 $key：请写进 local.properties，或按 README 自己生成签名密钥")

// 没有密钥库就当没配签名：别人 clone 下来照样能 assembleRelease（只是产物未签名）
val releaseKeyStore: java.io.File? =
    rootProject.file("app/keystore/lecture-notes.jks").takeIf { it.exists() }

android {
    namespace = "com.lecture.notes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lecture.notes"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.0"
        ndk {
            abiFilters += if (emuBuild) listOf("x86_64") else listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        val ks = releaseKeyStore
        if (ks != null) {
            create("release") {
                storeFile = ks
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseKeyStore != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            if (releaseKeyStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        // 渲染层用 Robolectric 在 JVM 上真的把 View 建出来跑一遍，防止排版代码一开就崩
        unitTests.isIncludeAndroidResources = true
    }

    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
}
