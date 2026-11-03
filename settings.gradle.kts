/*
 * 插件仓库来源：只依赖 Maven Central，避免企业私服干扰。
 */
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cordis-kmp"

include(":cordis-kmp")
project(":cordis-kmp").projectDir = file("cordis")

include(":sample")
