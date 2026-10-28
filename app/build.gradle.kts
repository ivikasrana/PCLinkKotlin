import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

fun props(name: String) = Properties().apply { rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use(::load) }
val signing = props("keystore.properties")

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

android {
    namespace = "com.vik.pclink"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.vik.pclink"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        create("lite") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "MIRROR", "false")
        }
        all { if (name != "lite") buildConfigField("boolean", "MIRROR", "true") }
    }
    lint {
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.nanohttpd)
    implementation(libs.mlkit.barcode)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.coroutines.android)
}
