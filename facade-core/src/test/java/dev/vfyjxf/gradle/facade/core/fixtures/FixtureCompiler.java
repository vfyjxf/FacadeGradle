package dev.vfyjxf.gradle.facade.core.fixtures;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FixtureCompiler {

    private FixtureCompiler() {}

    public record CompileResult(boolean success, List<String> messages) {}

    public static void compile(Path outputDir, Map<String, String> sources) {
        CompileResult result = compile(outputDir, null, sources);
        if (!result.success()) {
            throw new AssertionError("fixture compilation failed:\n" + String.join("\n", result.messages()));
        }
    }

    public static CompileResult compile(Path outputDir, String classpath, Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("system java compiler unavailable");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // javac localizes diagnostics with the default locale; pin it so assertions are stable
        Locale previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, Set.of(outputDir.toFile()));
            List<String> options = classpath == null ? List.of() : List.of("-classpath", classpath);
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                units.add(new SourceUnit(source.getKey(), source.getValue()));
            }
            boolean success = compiler.getTask(null, files, diagnostics, options, null, units).call();
            List<String> messages = new ArrayList<>();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                messages.add(diagnostic.toString());
            }
            return new CompileResult(success, List.copyOf(messages));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    public static byte[] classBytes(Path classesDir, String internalName) throws IOException {
        return Files.readAllBytes(classesDir.resolve(internalName + ".class"));
    }

    private static final class SourceUnit extends SimpleJavaFileObject {

        private final String source;

        SourceUnit(String fullyQualifiedName, String source) {
            super(URI.create("string:///" + fullyQualifiedName.replace('.', '/') + ".java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
