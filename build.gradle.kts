// Common conventions applied to all subprojects of FacadeGradle.

allprojects {
    group = "dev.vfyjxf.gradle"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    configure<JavaPluginExtension> {
        toolchain {
            // JDK 21 is installed locally (Oracle GraalVM 21.0.11);
            // no auto-provisioning is configured.
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
    }
}
