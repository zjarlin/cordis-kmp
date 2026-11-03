/*
 * cordis-kmp：Kotlin Multiplatform 版 cordis 运行时。
 */
plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "site.addzero"
version = "0.1.0"

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    js(IR) {
        nodejs()
        browser()
    }

    linuxX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
