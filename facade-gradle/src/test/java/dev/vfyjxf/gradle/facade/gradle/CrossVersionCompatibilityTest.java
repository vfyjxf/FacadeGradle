package dev.vfyjxf.gradle.facade.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-version acceptance tests: the same consumer build (bind a module with {@code facade.configure},
 * compile and patch sources) run through TestKit on several Gradle versions.
 *
 * <p>Runs from the dedicated {@code crossVersionTest} task, which pins a Java 21 launcher: Gradle
 * 8.5/8.14.3 cannot run on the JDK 25 this repository's wrapper daemon uses, and TestKit starts its
 * build daemons from the test JVM's Java home. Distributions are downloaded on first use into
 * Gradle's wrapper cache; each version then builds with its own TestKit gradle user home.
 */
class CrossVersionCompatibilityTest {

    private static final String RULES = "{\"com/example/library/Target\": [\"com/example/api/MyInterface\"]}";

    private static final String MAIN_USING_INJECTED = """
            package com.example.consumer;

            import com.example.api.MyInterface;
            import com.example.library.Target;

            public class Main {
                public static void main(String[] args) {
                    MyInterface value = new Target();
                }
            }
            """;

    /** Copies the resolved compileClasspath into build/classpath so the test can inspect the jar. */
    private static final String DUMP_COMPILE_CLASSPATH = "tasks.register(\"dumpCompileClasspath\") {\n"
            + "    doLast {\n"
            + "        val out = file(\"build/classpath\")\n"
            + "        out.mkdirs()\n"
            + "        configurations.getByName(\"compileClasspath\").files.forEach { f ->\n"
            + "            f.copyTo(File(out, f.name), overwrite = true)\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    private static final Path PATCHED_LIBRARY_SOURCES =
            Path.of("build/facade/sources/com.example/library/library-1.0.0-sources.jar");

    private final Fixture fixture = Fixture.shared();

    private static Path write(Path dir, String relative, String content) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private static GradleRunner runner(Path dir, String gradleVersion, String... args) {
        // TestKit's build daemons inherit the test JVM's Java home; Gradle 8.x rejects the JDK 25 the
        // wrapper daemon runs on, so the task pins a Java 21 launcher. Fail loudly if that ever breaks.
        assertEquals(
                21,
                Runtime.version().feature(),
                "the crossVersionTest task must run its test JVM on a Java 21 launcher");
        return GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withEnvironment(Map.of("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US"))
                .withArguments(args);
    }

    private static void assertSuccess(BuildResult result, String task) {
        assertNotNull(result.task(task), () -> "task " + task + " missing:\n" + result.getOutput());
        TaskOutcome outcome = result.task(task).getOutcome();
        assertTrue(
                outcome == TaskOutcome.SUCCESS || outcome == TaskOutcome.UP_TO_DATE || outcome == TaskOutcome.FROM_CACHE,
                () -> "task " + task + " did not succeed (outcome=" + outcome + "):\n" + result.getOutput());
    }

    /**
     * A Kotlin-DSL consumer whose repositories are the fixture file-maven repo (and mavenCentral).
     * The script also reports the Gradle version actually executing the build, so every test can
     * prove it ran on the version TestKit was asked for.
     */
    private void consumer(Path dir, String dependenciesBlock, boolean mavenCentral) throws IOException {
        write(dir, "settings.gradle.kts", "rootProject.name = \"consumer\"\n");
        write(dir, "facade.json", RULES);
        write(
                dir,
                "build.gradle.kts",
                ("plugins {\n"
                        + "    `java`\n"
                        + "    id(\"dev.vfyjxf.facade\")\n"
                        + "}\n"
                        + "\n"
                        + "println(\"FACADE_GRADLE_VERSION=\" + gradle.gradleVersion)\n"
                        + "\n"
                        + "repositories {\n"
                        + (mavenCentral ? "    mavenCentral()\n" : "")
                        + "    maven { url = uri(\""
                        + fixture.repo
                        + "\") }\n"
                        + "}\n"
                        + "\n"
                        + dependenciesBlock
                        + "\n"
                        + DUMP_COMPILE_CLASSPATH));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);
    }

    /** The Gradle version the TestKit build reported, asserted against the requested one. */
    private static String actualGradleVersion(BuildResult result, String requestedVersion) {
        String reported = result.getOutput()
                .lines()
                .filter(line -> line.startsWith("FACADE_GRADLE_VERSION="))
                .map(line -> line.substring("FACADE_GRADLE_VERSION=".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("build did not report its Gradle version:\n" + result.getOutput()));
        assertEquals(requestedVersion, reported, "TestKit did not run the requested Gradle version");
        return reported;
    }

    private static String configuredDependency() {
        return "dependencies {\n"
                + "    implementation(\"com.example:library:1.0.0\") {\n"
                + "        facade.configure(this) {\n"
                + "            config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
    }

    /** The interfaces the given class declares, read from the class file's interfaces table. */
    private static List<String> declaredInterfaces(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            assertNotNull(found, () -> "entry " + entry + " missing in " + jar);
            ClassReader reader = new ClassReader(zip.getInputStream(found).readAllBytes());
            return List.of(reader.getInterfaces());
        }
    }

    private static String zipEntryText(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            assertNotNull(found, () -> "entry " + entry + " missing in " + jar);
            return new String(zip.getInputStream(found).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest(name = "Gradle {0}: configured binding compiles against the patched jar")
    @ValueSource(strings = {"8.5", "8.14.3", "9.8.1"})
    void configuredBindingCompilesAndResolvesPatchedJar(String gradleVersion, @TempDir Path dir) throws IOException {
        consumer(dir, configuredDependency(), false);

        BuildResult result =
                runner(dir, gradleVersion, "compileJava", "dumpCompileClasspath", "--stacktrace").build();

        assertSuccess(result, ":compileJava");
        assertSuccess(result, ":dumpCompileClasspath");

        Path resolved = dir.resolve("build/classpath/library-1.0.0.jar");
        assertTrue(Files.exists(resolved), () -> "patched jar missing from compileClasspath:\n" + result.getOutput());
        List<String> interfaces = declaredInterfaces(resolved, "com/example/library/Target.class");
        String checksum = Fixture.sha256(resolved);
        assertEquals(
                List.of("com/example/api/MyInterface"),
                interfaces,
                () -> "Target.class on the compile classpath does not declare the injected interface:\n"
                        + result.getOutput());
        assertFalse(
                declaredInterfaces(fixture.libraryJar, "com/example/library/Target.class")
                        .contains("com/example/api/MyInterface"),
                "the fixture's repository original already declares the interface");
        assertNotEquals(
                fixture.recordedChecksum(fixture.libraryJar),
                checksum,
                "resolved compileClasspath jar is byte-identical to the repository original (not transformed)");
        System.out.println("[cross-version] Gradle " + actualGradleVersion(result, gradleVersion)
                + ": compileJava OK; compileClasspath library-1.0.0.jar sha256=" + checksum
                + " repositoryOriginal sha256=" + fixture.recordedChecksum(fixture.libraryJar)
                + " Target.class interfaces=" + interfaces);
    }

    @ParameterizedTest(name = "Gradle {0}: unconfigured dependency stays unpatched (compile fails)")
    @ValueSource(strings = {"8.5", "8.14.3", "9.8.1"})
    void withoutConfigureCompilationFails(String gradleVersion, @TempDir Path dir) throws IOException {
        consumer(dir, "dependencies {\n    implementation(\"com.example:library:1.0.0\")\n}\n", false);

        BuildResult result = runner(dir, gradleVersion, "compileJava", "--stacktrace").buildAndFail();

        // javac localizes diagnostics; the daemon serving TestKit may predate this JVM's env settings
        assertTrue(
                result.getOutput().contains("incompatible types")
                        || result.getOutput().contains("不兼容的类型"),
                () -> "expected javac type-incompatibility failure:\n" + result.getOutput());
        System.out.println("[cross-version] Gradle " + actualGradleVersion(result, gradleVersion)
                + ": unconfigured dependency fails compileJava with 'incompatible types' as expected");
    }

    @ParameterizedTest(name = "Gradle {0}: patchFacadeSources produces patched sources")
    @ValueSource(strings = {"8.5", "8.14.3", "9.8.1"})
    void patchFacadeSourcesSmoke(String gradleVersion, @TempDir Path dir) throws IOException {
        consumer(dir, configuredDependency(), true);

        // the sources pipeline needs mavenCentral (JST) but no compile: patchFacadeSources only
        BuildResult result = runner(dir, gradleVersion, "patchFacadeSources", "--stacktrace").build();

        assertSuccess(result, ":patchFacadeSources");
        Path patched = dir.resolve(PATCHED_LIBRARY_SOURCES);
        assertTrue(Files.exists(patched), () -> "patched sources jar missing:\n" + result.getOutput());
        String target = zipEntryText(patched, "com/example/library/Target.java");
        assertTrue(target.contains("implements"), () -> "Target.java not patched:\n" + target);
        assertTrue(target.contains("MyInterface"), () -> "Target.java missing injected interface:\n" + target);
        assertEquals(
                fixture.recordedChecksum(fixture.librarySourcesJar),
                Fixture.sha256(fixture.librarySourcesJar),
                "repository sources jar was modified");
        assertFalse(
                Fixture.sha256(fixture.librarySourcesJar).equals(Fixture.sha256(patched)),
                "patched sources jar is identical to the original");
        String report = Files.readString(dir.resolve("build/facade/sources-report.json"));
        assertTrue(report.contains("\"result\": \"PATCHED\""), () -> report);
        System.out.println("[cross-version] Gradle " + actualGradleVersion(result, gradleVersion)
                + ": patchFacadeSources result=PATCHED, patched=" + PATCHED_LIBRARY_SOURCES);
    }
}
