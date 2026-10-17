package dev.vfyjxf.gradle.facade.gradle.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the JST command-line bundle (net.neoforged.jst:jst-cli-bundle) to apply interface injection
 * to a sources jar, mirroring in source form what facade-core's {@code FacadeJarTransformer} does for
 * bytecode. Plain utility class: no Gradle types beyond {@link Path}s.
 *
 * <p>The injection data is the user's original rule JSON files (flat map target &rarr; interfaces),
 * passed verbatim via repeatable {@code --interface-injection-data} options. JST requires a Java 21+
 * runtime; the caller supplies the toolchain launcher to use.
 */
public final class JstSourcesPatcher {

    private JstSourcesPatcher() {}

    /**
     * Builds the JST command line. Optional elements ({@code classpathJar}, {@code stubsOut}) are
     * omitted when {@code null}; rule JSONs are passed one {@code --interface-injection-data} option
     * each, in the given order.
     */
    public static List<String> buildCommandLine(
            Path jstJar,
            List<Path> ruleJsons,
            Path classpathJar,
            Path stubsOut,
            Path input,
            Path output,
            Path javaLauncher) {
        List<String> command = new ArrayList<>();
        command.add(javaLauncher.toString());
        command.add("-jar");
        command.add(jstJar.toString());
        command.add("--enable-interface-injection");
        for (Path ruleJson : ruleJsons) {
            command.add("--interface-injection-data=" + ruleJson.toAbsolutePath());
        }
        if (classpathJar != null) {
            command.add("--classpath=" + classpathJar.toAbsolutePath());
        }
        if (stubsOut != null) {
            command.add("--interface-injection-stubs=" + stubsOut.toAbsolutePath());
        }
        command.add("--in-format=ARCHIVE");
        command.add("--out-format=ARCHIVE");
        command.add(input.toAbsolutePath().toString());
        command.add(output.toAbsolutePath().toString());
        return List.copyOf(command);
    }

    /**
     * Runs JST over {@code input}, writing the patched sources jar to {@code output}. Captures
     * stdout and stderr combined; a non-zero exit fails with a {@link RuntimeException} carrying the
     * trimmed captured output.
     */
    public static void patch(
            Path jstJar,
            List<Path> ruleJsons,
            Path classpathJar,
            Path stubsOut,
            Path input,
            Path output,
            Path javaLauncher) {
        List<String> command = buildCommandLine(jstJar, ruleJsons, classpathJar, stubsOut, input, output, javaLauncher);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new RuntimeException("[facade] failed to launch JST: " + String.join(" ", command), e);
        }
        String captured;
        try (InputStream stdout = process.getInputStream()) {
            captured = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new RuntimeException("[facade] failed to read JST output for " + input + ": " + e.getMessage(), e);
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeException("[facade] interrupted while waiting for JST on " + input, e);
        }
        if (exitCode != 0) {
            throw new RuntimeException(
                    "[facade] JST failed with exit code " + exitCode + " while patching " + input + ":\n"
                            + captured.trim());
        }
    }
}
