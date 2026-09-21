import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    jacoco
}

android {
    namespace = "fr.rythmo"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.rythmo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "fr.rythmo.PdfValidationInstrumentation"
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.android.gms:play-services-nearby:19.5.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// CodeQL 2.27.0 cannot extract Kotlin 2.4.20 yet; keep the patched Gradle plugin.
@OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalBuildToolsApi::class)
fun configureAnalysisCompatibleCompiler() = kotlin {
    compilerVersion.set(providers.gradleProperty("rythmo.kotlinCompilerVersion"))
}
configureAnalysisCompatibleCompiler()

// Robolectric loads Android code through its sandbox classloader without a source location.
// Instrument those classes too; keep JVM internals out of the agent's instrumentation.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

jacoco { toolVersion = "0.8.15" }
tasks.register<JacocoReport>("createDebugUnitTestCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    classDirectories.setFrom(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
    sourceDirectories.setFrom(files("src/main/java"))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/coverage/test/debug/report.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/coverage/test/debug"))
    }
}
