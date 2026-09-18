import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
}

// CodeQL 2.27.0 cannot extract Kotlin 2.4.20 yet; keep the patched Gradle plugin.
@OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalBuildToolsApi::class)
fun configureAnalysisCompatibleCompiler() = kotlin {
    compilerVersion.set(providers.gradleProperty("rythmo.kotlinCompilerVersion"))
}
configureAnalysisCompatibleCompiler()
