// Top-level build file where you should add configuration options common to all sub-projects/modules.
// Note: the project opts out of AGP 9 built-in Kotlin via
// android.builtInKotlin=false (see gradle.properties for the rationale) and
// applies the classic kotlin-android plugin, so KGP/KSP resolve through the
// normal plugin DSL (version catalog).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
