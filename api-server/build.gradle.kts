import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    // ktor-server-cio pulls host-common with runtime scope only; we need it at
    // compile time for io.ktor.server.plugins.origin.origin (client IP lookup).
    api(libs.ktor.server.host.common)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
}
