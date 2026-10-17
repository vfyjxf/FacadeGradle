pluginManagement {
    // Composite build: substitutes the plugin `dev.vfyjxf.facade` with the FacadeGradle
    // build checked out at this repository's root — no published plugin needed.
    includeBuild("../..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Toolchain auto-provisioning (a JDK 21 is required for the facade sources patcher).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "facade-example-kotlin"

include("library", "app")
