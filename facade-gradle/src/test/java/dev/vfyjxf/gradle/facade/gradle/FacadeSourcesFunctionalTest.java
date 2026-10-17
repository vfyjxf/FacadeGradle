package dev.vfyjxf.gradle.facade.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadeSourcesFunctionalTest {

    private static final String STANDARD_RULES =
            "{\"com/example/library/Target\": [\"com/example/api/MyInterface\"],"
                    + " \"com/example/library/GenTarget\": [\"java/util/function/Supplier<T>\"]}";

    private static final Path PATCHED_LIBRARY_SOURCES =
            Path.of("build/facade/sources/com.example/library/library-1.0.0-sources.jar");
    private static final Path REPORT = Path.of("build/facade/sources-report.json");

    private final Fixture fixture = Fixture.shared();

    private static Path write(Path dir, String relative, String content) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private GradleRunner runner(Path dir, String... args) {
        return GradleRunner.create()
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

    /** Consumer build with mavenCentral (JST download) plus the fixture repository. */
    private Path consumer(Path dir, String dependenciesBlock, String footer) throws IOException {
        write(dir, "settings.gradle.kts", "rootProject.name = \"consumer\"\n");
        write(
                dir,
                "build.gradle.kts",
                ("plugins {\n"
                        + "    `java`\n"
                        + "    id(\"dev.vfyjxf.facade\")\n"
                        + "}\n"
                        + "\n"
                        + footer
                        + "\n"
                        + "repositories {\n"
                        + "    mavenCentral()\n"
                        + "    maven { url = uri(\""
                        + fixture.repo
                        + "\") }\n"
                        + "}\n"
                        + "\n"
                        + dependenciesBlock
                        + "\n"));
        return dir;
    }

    private static String configuredDependency(String notation, String configFile) {
        return ("dependencies {\n"
                + "    implementation(\""
                + notation
                + "\") {\n"
                + "        facade.configure(this) {\n"
                + "            config.set(layout.projectDirectory.file(\""
                + configFile
                + "\"))\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
    }

    private static String zipEntryText(Path jar, String entry) throws IOException {
        return new String(zipEntryBytes(jar, entry), StandardCharsets.UTF_8);
    }

    private static byte[] zipEntryBytes(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            assertNotNull(found, () -> "entry " + entry + " missing in " + jar);
            return zip.getInputStream(found).readAllBytes();
        }
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // 1. happy path: patched jar, injected sources, untouched originals, report
    @Test
    void patchesSourcesJarsWithJst(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        consumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"), "");

        BuildResult result = runner(dir, "patchFacadeSources").build();

        assertSuccess(result, ":patchFacadeSources");
        Path patched = dir.resolve(PATCHED_LIBRARY_SOURCES);
        assertTrue(Files.exists(patched), () -> "patched sources jar missing:\n" + result.getOutput());

        String target = zipEntryText(patched, "com/example/library/Target.java");
        assertTrue(target.contains("implements"), () -> "Target.java not patched:\n" + target);
        assertTrue(
                target.contains("com.example.api.MyInterface") || target.contains("MyInterface"),
                () -> "Target.java missing injected interface:\n" + target);
        String genTarget = zipEntryText(patched, "com/example/library/GenTarget.java");
        assertTrue(genTarget.contains("Supplier"), () -> "GenTarget.java not patched:\n" + genTarget);
        assertTrue(genTarget.contains("<T>"), () -> "GenTarget.java missing type argument:\n" + genTarget);

        // the patch is real: output differs from the original, original stays byte-identical
        assertFalse(
                Fixture.sha256(fixture.librarySourcesJar).equals(Fixture.sha256(patched)),
                "patched sources jar is identical to the original");
        assertEquals(
                fixture.recordedChecksum(fixture.librarySourcesJar),
                Fixture.sha256(fixture.librarySourcesJar),
                "repository sources jar was modified");

        String report = Files.readString(dir.resolve(REPORT));
        assertTrue(report.contains("\"result\": \"PATCHED\""), () -> report);
        assertTrue(report.contains("\"com.example:library:1.0.0\""), () -> report);
        assertTrue(report.contains(patched.toString()), () -> report);
        assertTrue(report.contains("facade.json"), () -> report);
    }

    // 2. missing sources: warning + report entry by default; failure naming the module when enabled
    @Test
    void missingSourcesDiagnosedOrFailing(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", "{\"com/example/nosrc/Nosrc\": [\"com/example/api/MyInterface\"]}");

        consumer(dir, configuredDependency("com.example:nosrc:1.0.0", "facade.json"), "");
        BuildResult lenientResult = runner(dir, "patchFacadeSources").build();
        assertSuccess(lenientResult, ":patchFacadeSources");
        assertTrue(
                lenientResult.getOutput().contains("no sources jar"),
                () -> "expected missing-sources warning:\n" + lenientResult.getOutput());
        String report = Files.readString(dir.resolve(REPORT));
        assertTrue(report.contains("MISSING_SOURCES"), () -> report);
        assertTrue(report.contains("\"com.example:nosrc:1.0.0\""), () -> report);

        consumer(
                dir,
                configuredDependency("com.example:nosrc:1.0.0", "facade.json"),
                ("facade {\n"
                        + "    options {\n"
                        + "        failOnMissingSources.set(true)\n"
                        + "    }\n"
                        + "}\n"));
        BuildResult failing = runner(dir, "patchFacadeSources").buildAndFail();
        assertTrue(
                failing.getOutput().contains("com.example:nosrc:1.0.0"),
                () -> "failure must name the module:\n" + failing.getOutput());
        assertTrue(
                failing.getOutput().contains("no sources jar"),
                () -> "failure must explain the cause:\n" + failing.getOutput());
    }

    // 3. rule change: cache invalidation, outputs change
    @Test
    void ruleChangeRepatchesSources(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        consumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"), "");
        Path patched = dir.resolve(PATCHED_LIBRARY_SOURCES);

        assertSuccess(runner(dir, "patchFacadeSources").build(), ":patchFacadeSources");
        assertFalse(
                zipEntryText(patched, "com/example/library/Plain.java").contains("MyInterface"),
                "Plain.java must not be patched before the rule exists");

        write(
                dir,
                "facade.json",
                STANDARD_RULES.substring(0, STANDARD_RULES.length() - 1)
                        + ", \"com/example/library/Plain\": [\"com/example/api/MyInterface\"]}");

        BuildResult second = runner(dir, "patchFacadeSources").build();
        assertEquals(
                TaskOutcome.SUCCESS,
                second.task(":patchFacadeSources").getOutcome(),
                () -> "rule change must invalidate the cached outputs:\n" + second.getOutput());

        String plain = zipEntryText(patched, "com/example/library/Plain.java");
        assertTrue(plain.contains("implements"), () -> plain);
        assertTrue(plain.contains("MyInterface"), () -> plain);
    }

    // 4. transformSources=false: task skipped, no outputs
    @Test
    void transformSourcesDisabledSkipsTask(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        consumer(
                dir,
                configuredDependency("com.example:library:1.0.0", "facade.json"),
                ("facade {\n"
                        + "    options {\n"
                        + "        transformSources.set(false)\n"
                        + "    }\n"
                        + "}\n"));

        BuildResult result = runner(dir, "patchFacadeSources").build();

        assertEquals(
                TaskOutcome.SKIPPED,
                result.task(":patchFacadeSources").getOutcome(),
                () -> result.getOutput());
        assertFalse(Files.exists(dir.resolve(REPORT)), () -> "skipped task must not write a report");
    }

    // 5. binary/source consistency from one build
    @Test
    void patchedBinaryAndSourcesAgree(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        consumer(
                dir,
                configuredDependency("com.example:library:1.0.0", "facade.json"),
                ("val runtimeArtifacts = configurations.runtimeClasspath.get()\n"
                        + "tasks.register(\"dumpArtifacts\") {\n"
                        + "    doLast {\n"
                        + "        val outDir = file(\"build/resolved\")\n"
                        + "        outDir.mkdirs()\n"
                        + "        runtimeArtifacts.resolve().forEach { f -> f.copyTo(File(outDir, f.name), overwrite = true) }\n"
                        + "    }\n"
                        + "}\n"));

        BuildResult result = runner(dir, "patchFacadeSources", "dumpArtifacts").build();

        assertSuccess(result, ":patchFacadeSources");
        assertSuccess(result, ":dumpArtifacts");

        // patched BINARY (compile/runtime resolution path): Target.class references the interface
        Path patchedBinary = dir.resolve("build/resolved/library-1.0.0.jar");
        byte[] targetClass = zipEntryBytes(patchedBinary, "com/example/library/Target.class");
        assertTrue(
                containsBytes(targetClass, "com/example/api/MyInterface".getBytes(StandardCharsets.UTF_8)),
                "patched binary Target.class must reference the injected interface");
        assertFalse(
                fixture.recordedChecksum(fixture.libraryJar).equals(Fixture.sha256(patchedBinary)),
                "resolved library binary is unpatched");

        // patched SOURCES from the SAME build reference the same interface
        String target = zipEntryText(dir.resolve(PATCHED_LIBRARY_SOURCES), "com/example/library/Target.java");
        assertTrue(
                target.contains("com.example.api.MyInterface") || target.contains("MyInterface"),
                () -> "patched sources and binary disagree:\n" + target);
    }

    // 6. configuration cache: second run reuses the stored configuration
    @Test
    void configurationCacheReusedForSourcesTask(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        consumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"), "");

        BuildResult first = runner(dir, "patchFacadeSources", "--configuration-cache").build();
        assertSuccess(first, ":patchFacadeSources");

        BuildResult second = runner(dir, "patchFacadeSources", "--configuration-cache").build();
        assertSuccess(second, ":patchFacadeSources");
        assertTrue(
                second.getOutput().contains("Reusing configuration cache"),
                () -> "second build should reuse configuration cache:\n" + second.getOutput());
    }

    // 7. multiple rule files: both interfaces injected
    @Test
    void multipleRuleFilesBothAppliedToSources(@TempDir Path dir) throws IOException {
        write(dir, "facade-a.json", "{\"com/example/library/Target\": [\"com/example/api/MyInterface\"]}");
        write(dir, "facade-b.json", "{\"com/example/library/GenTarget\": [\"java/util/function/Supplier<T>\"]}");
        consumer(
                dir,
                ("dependencies {\n"
                        + "    implementation(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade-a.json\"))\n"
                        + "            configs.from(\"facade-b.json\")\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"),
                "");

        BuildResult result = runner(dir, "patchFacadeSources").build();

        assertSuccess(result, ":patchFacadeSources");
        Path patched = dir.resolve(PATCHED_LIBRARY_SOURCES);
        assertTrue(
                zipEntryText(patched, "com/example/library/Target.java").contains("MyInterface"),
                () -> result.getOutput());
        assertTrue(
                zipEntryText(patched, "com/example/library/GenTarget.java").contains("Supplier"),
                () -> result.getOutput());
    }
}
