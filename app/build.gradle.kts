import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun versionCodeFrom(version: String, buildNumber: Int): Int {
    val parts = version.split(".").map { it.trim().toIntOrNull() ?: 0 }
    val base = (parts.getOrNull(0) ?: 0) * 1000000 + (parts.getOrNull(1) ?: 0) * 10000 + (parts.getOrNull(2) ?: 0) * 100
    return base + buildNumber.coerceIn(1, 99)
}

val appVersionName = rootProject.file("VERSION").readText().trim()
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

// Optional release signing from environment (CI provides these via secrets).
val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
val ksPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val ksAlias = System.getenv("ANDROID_KEY_ALIAS")
val ksKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = !ksFile.isNullOrBlank() && !ksPassword.isNullOrBlank() && !ksAlias.isNullOrBlank() && !ksKeyPassword.isNullOrBlank()

android {
    namespace = "com.localai.runtime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.localai.runtime"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeFrom(appVersionName, buildNumber)
        versionName = appVersionName
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // BouncyCastle (bcprov/bcpkix/bcutil) all ship LICENSE.md/NOTICE.md
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/NOTICE.txt"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api-server"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.documentfile)
    implementation(libs.zxing.core)
    implementation(libs.serialization.json)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
