buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Security fixes for AGP's transitive build tools, not app dependencies.
        constraints {
            add("classpath", "org.apache.commons:commons-lang3:3.18.0")
            add("classpath", "org.apache.httpcomponents:httpclient:4.5.14")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.84")
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.84")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// AGP also resolves lint and SDK tools outside its own plugin classpath.
// Minimum-version constraints do not add these tools to the application.
allprojects {
    val toolSecurity = configurations.create("buildToolSecurityConstraints") {
        isCanBeResolved = false
        isCanBeConsumed = false
    }
    dependencies {
        constraints {
            add(toolSecurity.name, "org.apache.commons:commons-lang3:3.18.0")
            add(toolSecurity.name, "org.apache.httpcomponents:httpclient:4.5.14")
            add(toolSecurity.name, "org.bitbucket.b_c:jose4j:0.9.6")
            add(toolSecurity.name, "org.bouncycastle:bcpkix-jdk18on:1.84")
            add(toolSecurity.name, "org.bouncycastle:bcprov-jdk18on:1.84")
            add(toolSecurity.name, "org.jdom:jdom2:2.0.6.1")
        }
    }
    configurations.configureEach {
        if (isCanBeResolved) extendsFrom(toolSecurity)
        // Compiler plugins must match the compiler selected in each module.
        if (name.startsWith("kotlinCompilerPluginClasspath")) {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") useVersion(providers.gradleProperty("rythmo.kotlinCompilerVersion").get())
            }
        }
    }
}
