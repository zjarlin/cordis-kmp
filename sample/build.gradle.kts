plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":cordis-kmp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // 演示脚本用 WAT 现场生成 wasm 制品，省掉外部工具链依赖。
    implementation("com.dylibso.chicory:wabt:1.4.0")
}

application {
    mainClass.set("site.addzero.cordis.sample.MainKt")
}

// 第二个入口：运行时 wasm 插件演示
tasks.register<JavaExec>("wasmDemo") {
    group = "application"
    description = "演示运行时装载外部 wasm 插件"
    mainClass.set("site.addzero.cordis.sample.WasmPluginDemoKt")
    classpath = sourceSets["main"].runtimeClasspath
}
