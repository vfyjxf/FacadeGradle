pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.gradleup.shadow") version "9.6.1"
        id("com.gradle.plugin-publish") version "2.2.1"
    }
}

plugins {
    // Toolchain auto-provisioning: still the documented mechanism on Gradle 9
    // (Gradle can download JDKs, but only via a configured download repository).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "FacadeGradle"

include("facade-core", "facade-gradle")
