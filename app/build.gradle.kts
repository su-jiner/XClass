import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

android {
    namespace = "cn.xiao.xyaax"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.xiao.xyaax"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0-Beta"

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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", file("$projectDir/schemas").path)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.yalantis:ucrop:2.2.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Compose / Material 3 设计体系（SleepDown 融合）
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime-tracing")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 数据层：Room + KSP
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")

    // 导入与系统能力（SleepDown 融合）
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // 性能：profileinstaller + metrics（配合 benchmark 模块 Baseline Profile）
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.metrics:metrics-performance:1.0.0")

    baselineProfile(project(":benchmark"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.3")
}
