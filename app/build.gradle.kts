import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cn.xiao.xyaax"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.xiao.xyaax"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0-Beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 离线 OCR 依赖体积较大：只保留主流手机 ABI
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 签名凭据来源（按优先级）：CI 环境变量（GitHub Actions Secrets → env）> 本地 local-signing.properties（已废弃，将删除）
    // 仓库不再提交任何密码文件；CI 通过 ANDROID_KEYSTORE_BASE64/STORE_PASSWORD/KEY_PASSWORD/KEY_ALIAS Secret 注入。
    val localSigningFile = rootProject.file("local-signing.properties")
    val localSigningProperties = Properties()
    if (localSigningFile.isFile) {
        localSigningFile.inputStream().use(localSigningProperties::load)
    }
    val signingStorePassword = System.getenv("STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
    val signingKeyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
    val signingKeyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: "XiaoXiao"
    val keystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: "../apk-key/key.jks"
    val hasLocalSigning = signingStorePassword != null && signingKeyPassword != null && rootProject.file(keystorePath).isFile
    signingConfigs {
        if (hasLocalSigning) {
            create("official") {
                storeFile = rootProject.file(keystorePath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasLocalSigning) signingConfig = signingConfigs.getByName("official")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.yalantis:ucrop:2.2.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
