import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradleup.shadow")
    id("com.gradle.plugin-publish")
}

val repositoryUrl = "https://github.com/vfyjxf/FacadeGradle"

gradlePlugin {
    website.set(repositoryUrl)
    vcsUrl.set(repositoryUrl)
    plugins {
        create("facade") {
            id = "dev.vfyjxf.facade"
            implementationClass = "dev.vfyjxf.gradle.facade.gradle.FacadeGradlePlugin"
            displayName = "FacadeGradle — interface injection for Gradle dependencies"
            description = "Injects interfaces into dependency jars at resolution time, with matching patched sources."
            tags.set(setOf("bytecode", "asm", "interface-injection", "minecraft"))
        }
    }
}

dependencies {
    // Bundled (and relocated) into the plugin jar: the Gradle Plugin Portal hosts plugin artifacts
    // only, so the published plugin may not depend on a library that is not on a public repository.
    implementation(project(":facade-core"))

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `com.gradle.plugin-publish` detects the shadow plugin, requires this jar to carry no classifier,
// and publishes it as the plugin's main artifact (it reclassifies the plain jar as
// `facade-gradle-<version>-main.jar`, which is not published). `pluginUnderTestMetadata` keeps
// pointing at the unshadowed `sourceSets.main.output`, so TestKit still runs against facade-core.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    relocate("org.objectweb.asm", "dev.vfyjxf.facade.shaded.asm")
    relocate("com.google.gson", "dev.vfyjxf.facade.shaded.gson")
    // Annotation-only, compile-time dependency of gson; nothing in it is needed at runtime.
    exclude("com/google/errorprone/**")
    // The bundled libraries' own Maven metadata and JPMS descriptors have no meaning here.
    exclude("META-INF/maven/**")
    exclude("module-info.class")
}

// `com.gradle.plugin-publish` enables the java plugin's sources/javadoc jars for each plugin
// publication. The java and resources source dirs each carry a marker that maps to the same archive
// root entry (`/.gitkeep`), which the sources jar rejects as a duplicate.
tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("${project.group}:${project.name}")
            description.set("Gradle plugin of FacadeGradle: rule-driven interface injection into resolved dependency jars.")
            url.set(repositoryUrl)
            scm {
                url.set(repositoryUrl)
                connection.set("scm:git:$repositoryUrl.git")
                developerConnection.set("scm:git:$repositoryUrl.git")
            }
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("vfyjxf")
                    name.set("vfyjxf")
                }
            }
        }
    }

    // The shadow integration above already keeps the component's library dependencies out of this
    // POM; pinning it explicitly means no wiring change can leak `dev.vfyjxf.gradle:facade-core`
    // (unresolvable for a Plugin Portal consumer) back into the published metadata. The plugin
    // *marker* publication is untouched: its dependency on this plugin's coordinates is what makes
    // `plugins { id("dev.vfyjxf.facade") version "..." }` resolvable.
    publications.withType<MavenPublication>().matching { it.name == "pluginMaven" }.configureEach {
        pom.withXml {
            val root = asNode()
            root.children().filterIsInstance<groovy.util.Node>()
                .filter { it.name() == "dependencies" }
                .forEach { root.remove(it) }
        }
    }

    repositories {
        // Local file repository used by `publishAllPublicationsToTestRepoRepository`: resolving the
        // plugin from it exercises exactly what a Plugin Portal consumer gets (marker POM + the
        // self-contained plugin jar, no library dependencies).
        maven {
            name = "testRepo"
            url = uri(rootDir.resolve("build/test-repo"))
        }
    }
}

// Gradle 8.5/8.14.3 cannot run on JDK 25 (the wrapper's daemon JVM), and TestKit starts its build
// daemons from the test JVM's Java home: pin a Java 21 launcher instead of inheriting whatever JVM
// runs the test task. Resolved from the project's toolchain service (registered by the java plugin,
// which `java-gradle-plugin` applies).
val java21Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

// Cross-version (Gradle 8.5 / 8.14.3 / 9.8.1) TestKit tests. Deliberately NOT wired into `test`/
// `check`/`build`: each version downloads its own distribution (~100-150 MB) on first run and starts
// its own builds. Run explicitly with `crossVersionTest`.
val crossVersionTest = tasks.register<Test>("crossVersionTest") {
    description = "Runs the TestKit cross-Gradle-version compatibility tests (see docs/COMPATIBILITY.md)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("dev.vfyjxf.gradle.facade.gradle.CrossVersionCompatibilityTest") }
    javaLauncher.set(java21Launcher)
    shouldRunAfter(tasks.test)
}

tasks.test {
    // the cross-version class is exercised by crossVersionTest (Java 21 launcher) only
    filter { excludeTestsMatching("dev.vfyjxf.gradle.facade.gradle.CrossVersionCompatibilityTest") }
}
