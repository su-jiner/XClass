plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "cn.xiao.xyaax.benchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.xiao.xyaax.benchmark"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.0-Beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        if (variant.buildType == "benchmark") {
            variant.enable = true
        } else {
            variant.enable = false
        }
    }
}
