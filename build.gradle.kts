// Top-level build file where you should add configuration options common to all sub-projects/modules.

// KGP/KSP versions are also pinned on the buildscript classpath (AGP 9 has a
// runtime dependency on KGP and upgrades to the highest declared classpath
// version). The project opts out of AGP 9 built-in Kotlin via
// android.builtInKotlin=false and applies the classic kotlin-android plugin
// (see gradle.properties for the rationale).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
