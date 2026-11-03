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

        // WASM 运行时按平台各自引入：Chicory 是纯 JVM 字节码实现，Android 直接复用；
        // JS 走引擎内置的 WebAssembly，不需要任何依赖。
        jvmMain.dependencies {
            implementation("com.dylibso.chicory:runtime:1.4.0")
            implementation("com.dylibso.chicory:wasm:1.4.0")
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}

