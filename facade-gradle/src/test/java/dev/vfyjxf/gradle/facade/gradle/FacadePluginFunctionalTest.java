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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadePluginFunctionalTest {

    private static final String STANDARD_RULES =
            "{\"com/example/library/Target\": [\"com/example/api/MyInterface\"],"
                    + " \"com/example/library/GenTarget\": [\"java/util/function/Supplier<T>\"]}";

    private static final String MAIN_USING_INJECTED = """
            package com.example.consumer;

            import com.example.api.MyInterface;
            import com.example.library.GenTarget;
            import com.example.library.Target;
            import java.util.function.Supplier;

            public class Main {
                public static void main(String[] args) {
                    MyInterface value = new Target();
                    Supplier<String> supplier = new GenTarget();
                }
            }
            """;

    private static final String MAIN_USING_PLAIN = """
            package com.example.consumer;

            import com.example.library.Plain;

            public class Main {
                public static void main(String[] args) {
                    Plain plain = new Plain();
                }
            }
            """;

    private final Fixture fixture = Fixture.shared();

    private static Path write(Path dir, String relative, String content) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private GradleRunner runner(Path dir, String... args) {
        // TestKit pins its own Gradle user home (build/tmp/test/work/.gradle-test-kit) and shares it
        // across the whole suite and across suite re-runs; tests must not rely on it staying cold.
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

    private Path kotlinConsumer(Path dir, String dependenciesBlock) throws IOException {
        return kotlinConsumer(dir, dependenciesBlock, "", "");
    }

    private Path kotlinConsumer(Path dir, String dependenciesBlock, String header, String footer)
            throws IOException {
        write(dir, "settings.gradle.kts", "rootProject.name = \"consumer\"\n");
        write(
                dir,
                "build.gradle.kts",
                ("plugins {\n"
                        + "    `java`\n"
                        + "    id(\"dev.vfyjxf.facade\")\n"
                        + "}\n"
                        + "\n"
                        + header
                        + "\n"
                        + "repositories {\n"
                        + "    maven { url = uri(\""
                        + fixture.repo
                        + "\") }\n"
                        + "}\n"
                        + "\n"
                        + dependenciesBlock
                        + "\n"
                        + footer));
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

    @Test
    void acceptanceDslInjectsInterfaces(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava").build();

        assertSuccess(result, ":compileJava");
    }

    @Test
    void withoutConfigureCompilationFails(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(
                dir,
                "dependencies {\n    implementation(\"com.example:library:1.0.0\")\n}\n");
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava").buildAndFail();

        // javac localizes diagnostics; the daemon serving TestKit may predate this JVM's env settings
        assertTrue(
                result.getOutput().contains("incompatible types")
                        || result.getOutput().contains("不兼容的类型"),
                () -> "expected javac type-incompatibility failure:\n" + result.getOutput());
    }

    @Test
    void unrelatedDependencyUntouchedAndBoundDependencyTransformed(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(
                dir,
                ("dependencies {\n"
                        + "    implementation(\"com.example:other:1.0.0\")\n"
                        + "    implementation(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"
                        + "\n"
                        + "val runtimeArtifacts = configurations.runtimeClasspath.get()\n"
                        + "tasks.register(\"dumpArtifacts\") {\n"
                        + "    doLast {\n"
                        + "        val outDir = file(\"build/resolved\")\n"
                        + "        outDir.mkdirs()\n"
                        + "        runtimeArtifacts.resolve().forEach { f -> f.copyTo(File(outDir, f.name), overwrite = true) }\n"
                        + "    }\n"
                        + "}\n"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_PLAIN);

        BuildResult result = runner(dir, "dumpArtifacts").build();

        assertSuccess(result, ":dumpArtifacts");
        Path resolvedOther = dir.resolve("build/resolved/other-1.0.0.jar");
        Path resolvedLibrary = dir.resolve("build/resolved/library-1.0.0.jar");
        assertTrue(Files.exists(resolvedOther), () -> result.getOutput());
        assertTrue(Files.exists(resolvedLibrary), () -> result.getOutput());
        assertEquals(fixture.recordedChecksum(fixture.otherJar), Fixture.sha256(resolvedOther), "other jar changed");
        assertNotEquals(fixture.recordedChecksum(fixture.libraryJar), Fixture.sha256(resolvedLibrary), "library jar unchanged");
    }

    @Test
    void originalArtifactImmutable(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        String before = fixture.recordedChecksum(fixture.libraryJar);
        runner(dir, "compileJava").build();
        String after = Fixture.sha256(fixture.libraryJar);

        assertEquals(before, after, "repository artifact was modified");
    }

    @Test
    void configurationMatrixApiCompileOnlyRuntimeOnly(@TempDir Path dir) throws IOException {
        write(dir, "settings.gradle.kts", "include(\":apiUse\", \":compileOnlyUse\", \":runtimeOnlyUse\")\n");
        subproject(dir, "apiUse", "api", "`java-library`", MAIN_USING_INJECTED);
        subproject(dir, "compileOnlyUse", "compileOnly", "`java`", MAIN_USING_INJECTED);
        subproject(
                dir,
                "runtimeOnlyUse",
                "runtimeOnly",
                "`java`",
                MAIN_USING_PLAIN,
                ("val runtimeArtifacts = configurations.runtimeClasspath.get()\n"
                        + "tasks.register(\"dumpArtifacts\") {\n"
                        + "    doLast {\n"
                        + "        val outDir = file(\"build/resolved\")\n"
                        + "        outDir.mkdirs()\n"
                        + "        runtimeArtifacts.resolve().forEach { f -> f.copyTo(File(outDir, f.name), overwrite = true) }\n"
                        + "    }\n"
                        + "}\n"));

        BuildResult result = runner(dir, ":apiUse:compileJava", ":compileOnlyUse:compileJava", ":runtimeOnlyUse:dumpArtifacts")
                .build();

        assertSuccess(result, ":apiUse:compileJava");
        assertSuccess(result, ":compileOnlyUse:compileJava");
        assertSuccess(result, ":runtimeOnlyUse:dumpArtifacts");
        Path resolved = dir.resolve("runtimeOnlyUse/build/resolved/library-1.0.0.jar");
        assertTrue(Files.exists(resolved), () -> result.getOutput());
        assertNotEquals(fixture.recordedChecksum(fixture.libraryJar), Fixture.sha256(resolved), "runtime artifact unchanged");
    }

    private void subproject(Path root, String name, String configuration, String javaPlugin, String mainSource)
            throws IOException {
        subproject(root, name, configuration, javaPlugin, mainSource, "");
    }

    private void subproject(
            Path root, String name, String configuration, String javaPlugin, String mainSource, String footer)
            throws IOException {
        Path sub = root.resolve(name);
        write(
                sub,
                "build.gradle.kts",
                ("plugins {\n"
                        + "    "
                        + javaPlugin
                        + "\n"
                        + "    id(\"dev.vfyjxf.facade\")\n"
                        + "}\n"
                        + "\n"
                        + "repositories {\n"
                        + "    maven { url = uri(\""
                        + fixture.repo
                        + "\") }\n"
                        + "}\n"
                        + "\n"
                        + "dependencies {\n"
                        + "    "
                        + configuration
                        + "(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"
                        + "\n"
                        + footer));
        write(sub, "facade.json", STANDARD_RULES);
        write(sub, "src/main/java/com/example/consumer/Main.java", mainSource);
    }

    @Test
    void transitiveBindingViaDependencyConstraint(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(
                dir,
                ("dependencies {\n"
                        + "    constraints {\n"
                        + "        implementation(\"com.example:library:1.0.0\") {\n"
                        + "            facade.configure(this) {\n"
                        + "                config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n"
                        + "    implementation(\"com.example:library\")\n"
                        + "}\n"
                        + "\n"
                        + "val runtimeArtifacts = configurations.runtimeClasspath.get()\n"
                        + "tasks.register(\"printModules\") {\n"
                        + "    doLast {\n"
                        + "        runtimeArtifacts.resolve().forEach { println(\"RESOLVED:\" + it.name) }\n"
                        + "    }\n"
                        + "}\n"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava", "printModules").build();

        assertSuccess(result, ":compileJava");
        assertSuccess(result, ":printModules");
        long resolvedCount = result.getOutput()
                .lines()
                .filter(line -> line.startsWith("RESOLVED:"))
                .count();
        assertEquals(
                1,
                resolvedCount,
                () -> "constraint must not add graph nodes:\n" + result.getOutput());
        assertTrue(
                result.getOutput().lines().anyMatch(line -> line.equals("RESOLVED:library-1.0.0.jar")),
                () -> result.getOutput());
    }

    @Test
    void multipleRuleFilesAreMerged(@TempDir Path dir) throws IOException {
        write(dir, "facade-common.json", "{\"com/example/library/Target\": [\"com/example/api/MyInterface\"]}");
        write(dir, "facade-extra.json", "{\"com/example/library/GenTarget\": [\"java/util/function/Supplier<T>\"]}");
        kotlinConsumer(
                dir,
                ("dependencies {\n"
                        + "    implementation(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade-common.json\"))\n"
                        + "            configs.from(\"facade-extra.json\")\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava").build();

        assertSuccess(result, ":compileJava");
    }

    @Test
    void ruleChangeInvalidatesCachedTransform(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        assertSuccess(runner(dir, "compileJava").build(), ":compileJava");

        write(
                dir,
                "facade.json",
                STANDARD_RULES.substring(0, STANDARD_RULES.length() - 1)
                        + ", \"com/example/library/Plain\": [\"com/example/api/MyInterface\"]}");
        write(
                dir,
                "src/main/java/com/example/consumer/Extra.java",
                ("package com.example.consumer;\n"
                        + "\n"
                        + "import com.example.api.MyInterface;\n"
                        + "import com.example.library.Plain;\n"
                        + "\n"
                        + "public class Extra {\n"
                        + "    MyInterface plain = new Plain();\n"
                        + "}\n"));

        BuildResult result = runner(dir, "compileJava").build();

        assertSuccess(result, ":compileJava");
    }

    @Test
    void malformedRuleJsonFailsBuildWithFileAndLine(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", "{ \"com/example/library/Target\": }");
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_PLAIN);

        BuildResult result = runner(dir, "compileJava").buildAndFail();

        assertTrue(
                result.getOutput().contains("malformed JSON in"),
                () -> "expected core malformed-JSON message:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("facade.json"), () -> result.getOutput());
        assertTrue(result.getOutput().contains("at line 1"), () -> result.getOutput());
    }

    @Test
    void undeclaredTypeVariableFailsBuildWithRuleLocation(@TempDir Path dir) throws IOException {
        write(dir, "facade-bad-tv.json", "{\"com/example/library/Plain\": [\"java/util/function/Supplier<T>\"]}");
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade-bad-tv.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_PLAIN);

        BuildResult result = runner(dir, "compileJava").buildAndFail();

        assertTrue(
                result.getOutput().contains("declares no type parameters"),
                () -> "expected core signature error:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("facade-bad-tv.json:1"), () -> result.getOutput());
    }

    @Test
    void strictValidationDisabledPassesArtifactThrough(@TempDir Path dir) throws IOException {
        write(dir, "bad.json", "{ \"com/example/library/Target\": }");
        kotlinConsumer(
                dir,
                configuredDependency("com.example:library:1.0.0", "bad.json"),
                ("facade {\n"
                        + "    options {\n"
                        + "        strictValidation.set(false)\n"
                        + "    }\n"
                        + "}\n"),
                "");
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_PLAIN);

        BuildResult result = runner(dir, "compileJava").build();

        assertSuccess(result, ":compileJava");
        assertTrue(
                result.getOutput().contains("UNPATCHED"),
                () -> "expected pass-through warning:\n" + result.getOutput());
    }

    @Test
    void configurationCacheReused(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult first = runner(dir, "compileJava", "--configuration-cache").build();
        assertSuccess(first, ":compileJava");

        BuildResult second = runner(dir, "compileJava", "--configuration-cache").build();
        assertSuccess(second, ":compileJava");
        assertTrue(
                second.getOutput().contains("Reusing configuration cache"),
                () -> "second build should reuse configuration cache:\n" + second.getOutput());
    }

    @Test
    void buildCacheAndParallelReplayTransform(@TempDir Path dir) throws IOException {
        // TestKit shares its caches across tests AND suite re-runs; a per-run source marker keeps
        // this build's compileJava cache key cold so the first build genuinely executes
        String probe = "cache-probe-" + UUID.randomUUID();
        write(dir, "facade.json", STANDARD_RULES);
        write(dir, "gradle.properties", "org.gradle.caching=true\norg.gradle.parallel=true\n");
        kotlinConsumer(dir, configuredDependency("com.example:library:1.0.0", "facade.json"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);
        write(
                dir,
                "src/main/java/com/example/consumer/Probe.java",
                "package com.example.consumer;\n\npublic final class Probe {\n    public static final String ID = \"" + probe
                        + "\";\n}\n");

        BuildResult first = runner(dir, "compileJava").build();
        assertSuccess(first, ":compileJava");
        assertEquals(
                TaskOutcome.SUCCESS,
                first.task(":compileJava").getOutcome(),
                () -> "first build must actually compile:\n" + first.getOutput());

        BuildResult second = runner(dir, "clean", "compileJava").build();
        assertSuccess(second, ":compileJava");
        assertEquals(
                TaskOutcome.FROM_CACHE,
                second.task(":compileJava").getOutcome(),
                () -> "clean rebuild should restore compileJava from the build cache:\n" + second.getOutput());
    }

    @Test
    void groovyDslEquivalent(@TempDir Path dir) throws IOException {
        write(dir, "settings.gradle", "rootProject.name = 'consumer'\n");
        write(
                dir,
                "build.gradle",
                ("plugins {\n"
                        + "    id 'java'\n"
                        + "    id 'dev.vfyjxf.facade'\n"
                        + "}\n"
                        + "\n"
                        + "repositories {\n"
                        + "    maven { url = uri('"
                        + fixture.repo
                        + "') }\n"
                        + "}\n"
                        + "\n"
                        + "dependencies {\n"
                        + "    implementation('com.example:library:1.0.0') {\n"
                        + "        facade.configure(it) {\n"
                        + "            config.set(layout.projectDirectory.file('facade.json'))\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"));
        write(dir, "facade.json", STANDARD_RULES);
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava").build();

        assertSuccess(result, ":compileJava");
    }

    @Test
    void sharedAndIsolatedRegistrationsAcrossModules(@TempDir Path dir) throws IOException {
        write(dir, "settings.gradle.kts", "include(\":a\", \":b\")\n");
        writeSharedRuleSubproject(dir, "a", "facade-a.json", "{\"com/example/other/Other\": [\"com/example/api/MyInterface\"]}", true);
        writeSharedRuleSubproject(dir, "b", "facade-b.json", "{\"com/example/other/Other\": [\"com/example/api/OtherInterface\"]}", false);

        BuildResult result = runner(dir, ":a:compileJava", ":b:compileJava").build();

        assertSuccess(result, ":a:compileJava");
        assertSuccess(result, ":b:compileJava");
    }

    private void writeSharedRuleSubproject(
            Path root, String name, String otherRulesFile, String otherRules, boolean useMyInterface)
            throws IOException {
        Path sub = root.resolve(name);
        write(
                sub,
                "build.gradle.kts",
                ("plugins {\n"
                        + "    `java`\n"
                        + "    id(\"dev.vfyjxf.facade\")\n"
                        + "}\n"
                        + "\n"
                        + "repositories {\n"
                        + "    maven { url = uri(\""
                        + fixture.repo
                        + "\") }\n"
                        + "}\n"
                        + "\n"
                        + "dependencies {\n"
                        + "    implementation(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "    implementation(\"com.example:other:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\""
                        + otherRulesFile
                        + "\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"));
        write(sub, "facade.json", STANDARD_RULES);
        write(sub, otherRulesFile, otherRules);
        String injection = useMyInterface
                ? "        com.example.api.MyInterface fromOther = new com.example.other.Other();\n"
                : "        com.example.api.OtherInterface fromOther = new com.example.other.Other();\n";
        write(
                sub,
                "src/main/java/com/example/consumer/Main.java",
                ("package com.example.consumer;\n"
                        + "\n"
                        + "import com.example.api.MyInterface;\n"
                        + "import com.example.library.Target;\n"
                        + "\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        MyInterface fromLibrary = new Target();\n"
                        + injection
                        + "    }\n"
                        + "}\n"));
    }

    @Test
    void versionCatalogBinding(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        write(dir, "gradle/libs.versions.toml", "[libraries]\nlibrary = \"com.example:library:1.0.0\"\n");
        kotlinConsumer(
                dir,
                ("dependencies {\n"
                        + "    implementation(libs.library)\n"
                        + "    facade.configure(libs.library) {\n"
                        + "        config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "    }\n"
                        + "}\n"));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava").build();

        assertSuccess(result, ":compileJava");
    }

    @Test
    void signedJarFailsWithClearMessage(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", "{\"com/example/signedlib/SignedTarget\": [\"com/example/api/MyInterface\"]}");
        kotlinConsumer(dir, configuredDependency("com.example:signedlib:1.0.0", "facade.json"));
        write(
                dir,
                "src/main/java/com/example/consumer/Main.java",
                ("package com.example.consumer;\n"
                        + "\n"
                        + "import com.example.api.MyInterface;\n"
                        + "\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        MyInterface signed = new com.example.signedlib.SignedTarget();\n"
                        + "    }\n"
                        + "}\n"));

        BuildResult result = runner(dir, "compileJava").buildAndFail();

        assertTrue(
                result.getOutput().contains("is signed"),
                () -> "expected signed-jar failure:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("signedlib-1.0.0.jar"), () -> result.getOutput());
    }

    private static final String DUMP_RUNTIME_ARTIFACTS = ("val runtimeArtifacts = configurations.runtimeClasspath.get()\n"
            + "tasks.register(\"dumpArtifacts\") {\n"
            + "    doLast {\n"
            + "        val outDir = file(\"build/resolved\")\n"
            + "        outDir.mkdirs()\n"
            + "        runtimeArtifacts.resolve().forEach { f -> f.copyTo(File(outDir, f.name), overwrite = true) }\n"
            + "    }\n"
            + "}\n");

    private static final String MAIN_USING_INJECTED_AND_OTHERLIB = """
            package com.example.consumer;

            import com.example.api.MyInterface;
            import com.example.library.Target;

            public class Main {
                public static void main(String[] args) {
                    MyInterface value = new Target();
                    String version = new com.example.otherlib.Other().use();
                }
            }
            """;

    private void assertPatchedSingleLibrary(Path dir, String expectedJar, String buildOutput) throws IOException {
        Path resolved = dir.resolve("build/resolved");
        List<String> libraryJars;
        try (var files = Files.list(resolved)) {
            libraryJars = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("library-"))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(expectedJar), libraryJars, () -> buildOutput);
        Path repoLibrary = expectedJar.equals("library-2.0.0.jar") ? fixture.library2Jar : fixture.libraryJar;
        assertNotEquals(
                fixture.recordedChecksum(repoLibrary),
                Fixture.sha256(resolved.resolve(expectedJar)),
                () -> expectedJar + " resolved unchanged (not patched)");
    }

    @Test
    void boundVersionLosesConflictStillPatchesResolvedVersion(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(
                dir,
                ("dependencies {\n"
                        + "    implementation(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "    implementation(\"com.example:otherlib:1.0.0\")\n"
                        + "}\n"
                        + "\n"
                        + DUMP_RUNTIME_ARTIFACTS));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED_AND_OTHERLIB);

        BuildResult result = runner(dir, "compileJava", "dumpArtifacts").build();

        assertSuccess(result, ":compileJava");
        assertSuccess(result, ":dumpArtifacts");
        assertPatchedSingleLibrary(dir, "library-2.0.0.jar", result.getOutput());
        assertEquals(
                fixture.recordedChecksum(fixture.otherlibJar),
                Fixture.sha256(dir.resolve("build/resolved/otherlib-1.0.0.jar")),
                "otherlib jar changed");
    }

    @Test
    void boundVersionWinsConflictStillPatchesResolvedVersion(@TempDir Path dir) throws IOException {
        write(dir, "facade.json", STANDARD_RULES);
        kotlinConsumer(
                dir,
                ("dependencies {\n"
                        + "    implementation(\"com.example:library:2.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"facade.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "    implementation(\"com.example:library:1.0.0\")\n"
                        + "}\n"
                        + "\n"
                        + DUMP_RUNTIME_ARTIFACTS));
        write(dir, "src/main/java/com/example/consumer/Main.java", MAIN_USING_INJECTED);

        BuildResult result = runner(dir, "compileJava", "dumpArtifacts").build();

        assertSuccess(result, ":compileJava");
        assertSuccess(result, ":dumpArtifacts");
        assertPatchedSingleLibrary(dir, "library-2.0.0.jar", result.getOutput());
    }

    @Test
    void sameModuleDifferentRulesPerConfiguration(@TempDir Path dir) throws IOException {
        write(dir, "rules-a.json", "{\"com/example/library/Target\": [\"com/example/api/MyInterface\"]}");
        write(dir, "rules-b.json", "{\"com/example/library/Target\": [\"com/example/api/OtherInterface\"]}");
        write(dir, "settings.gradle.kts", "rootProject.name = \"consumer\"\n");
        write(
                dir,
                "build.gradle.kts",
                ("import org.gradle.api.attributes.Category\n"
                        + "import org.gradle.api.attributes.Usage\n"
                        + "\n"
                        + "plugins {\n"
                        + "    `java`\n"
                        + "    id(\"dev.vfyjxf.facade\")\n"
                        + "}\n"
                        + "\n"
                        + "fun resolvableConf(name: String): Configuration = configurations.create(name) {\n"
                        + "    isCanBeConsumed = false\n"
                        + "    isCanBeResolved = true\n"
                        + "    attributes {\n"
                        + "        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))\n"
                        + "        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))\n"
                        + "    }\n"
                        + "}\n"
                        + "\n"
                        + "val confA = resolvableConf(\"confA\")\n"
                        + "val confB = resolvableConf(\"confB\")\n"
                        + "\n"
                        + "repositories {\n"
                        + "    maven { url = uri(\""
                        + fixture.repo
                        + "\") }\n"
                        + "}\n"
                        + "\n"
                        + "dependencies {\n"
                        + "    confA(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"rules-a.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "    confB(\"com.example:library:1.0.0\") {\n"
                        + "        facade.configure(this) {\n"
                        + "            config.set(layout.projectDirectory.file(\"rules-b.json\"))\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"
                        + "\n"
                        + "tasks.register(\"dumpA\") {\n"
                        + "    doLast {\n"
                        + "        val out = file(\"build/resolvedA\")\n"
                        + "        out.mkdirs()\n"
                        + "        confA.resolve().forEach { f -> f.copyTo(File(out, f.name), overwrite = true) }\n"
                        + "    }\n"
                        + "}\n"
                        + "tasks.register(\"dumpB\") {\n"
                        + "    doLast {\n"
                        + "        val out = file(\"build/resolvedB\")\n"
                        + "        out.mkdirs()\n"
                        + "        confB.resolve().forEach { f -> f.copyTo(File(out, f.name), overwrite = true) }\n"
                        + "    }\n"
                        + "}\n"));

        BuildResult result = runner(dir, "dumpA", "dumpB").build();

        assertSuccess(result, ":dumpA");
        assertSuccess(result, ":dumpB");
        byte[] targetA = zipEntryBytes(
                dir.resolve("build/resolvedA/library-1.0.0.jar"), "com/example/library/Target.class");
        byte[] targetB = zipEntryBytes(
                dir.resolve("build/resolvedB/library-1.0.0.jar"), "com/example/library/Target.class");
        assertTrue(
                containsBytes(targetA, "com/example/api/MyInterface".getBytes(StandardCharsets.UTF_8)),
                () -> "confA Target lacks MyInterface:\n" + result.getOutput());
        assertFalse(
                containsBytes(targetA, "com/example/api/OtherInterface".getBytes(StandardCharsets.UTF_8)),
                "confA Target carries confB's OtherInterface");
        assertTrue(
                containsBytes(targetB, "com/example/api/OtherInterface".getBytes(StandardCharsets.UTF_8)),
                () -> "confB Target lacks OtherInterface:\n" + result.getOutput());
        assertFalse(
                containsBytes(targetB, "com/example/api/MyInterface".getBytes(StandardCharsets.UTF_8)),
                "confB Target carries confA's MyInterface");
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
}
