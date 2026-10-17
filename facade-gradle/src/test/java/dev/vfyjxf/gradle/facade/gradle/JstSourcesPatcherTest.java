package dev.vfyjxf.gradle.facade.gradle;

import dev.vfyjxf.gradle.facade.gradle.source.JstSourcesPatcher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JstSourcesPatcherTest {

    private static final Path LAUNCHER = Path.of("/jdks/21/bin/java");
    private static final Path JST = Path.of("/cache/jst-cli-bundle-2.0.11.jar");
    private static final Path RULE_A = Path.of("/rules/facade-common.json");
    private static final Path RULE_B = Path.of("/rules/facade-extra.json");
    private static final Path CLASSPATH = Path.of("/tmp/library-patched-binary.jar");
    private static final Path STUBS = Path.of("/out/library-1.0.0-stubs.zip");
    private static final Path INPUT = Path.of("/cache/library-1.0.0-sources.jar");
    private static final Path OUTPUT = Path.of("/out/library-1.0.0-sources.jar");

    @Test
    void buildsFullCommandLine() {
        List<String> command = JstSourcesPatcher.buildCommandLine(
                JST, List.of(RULE_A, RULE_B), CLASSPATH, STUBS, INPUT, OUTPUT, LAUNCHER);

        assertEquals(
                List.of(
                        LAUNCHER.toString(),
                        "-jar",
                        JST.toString(),
                        "--enable-interface-injection",
                        "--interface-injection-data=" + RULE_A,
                        "--interface-injection-data=" + RULE_B,
                        "--classpath=" + CLASSPATH,
                        "--interface-injection-stubs=" + STUBS,
                        "--in-format=ARCHIVE",
                        "--out-format=ARCHIVE",
                        INPUT.toString(),
                        OUTPUT.toString()),
                command);
    }

    @Test
    void omitsOptionalArguments() {
        List<String> command = JstSourcesPatcher.buildCommandLine(
                JST, List.of(RULE_A), null, null, INPUT, OUTPUT, LAUNCHER);

        assertEquals(
                List.of(
                        LAUNCHER.toString(),
                        "-jar",
                        JST.toString(),
                        "--enable-interface-injection",
                        "--interface-injection-data=" + RULE_A,
                        "--in-format=ARCHIVE",
                        "--out-format=ARCHIVE",
                        INPUT.toString(),
                        OUTPUT.toString()),
                command);
    }
}
