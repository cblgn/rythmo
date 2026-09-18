import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")) }
application { mainClass.set("fr.rythmo.server.MainKt") }

// CodeQL 2.27.0 cannot extract Kotlin 2.4.20 yet; keep the patched Gradle plugin.
@OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalBuildToolsApi::class)
fun configureAnalysisCompatibleCompiler() = kotlin {
    compilerVersion.set(providers.gradleProperty("rythmo.kotlinCompilerVersion"))
}
configureAnalysisCompatibleCompiler()
