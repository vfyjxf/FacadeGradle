package dev.vfyjxf.gradle.facade.gradle;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

final class Fixture {

    private static final Fixture INSTANCE = build();

    final Path repo;
    final Path libraryJar;
    final Path otherJar;
    final Path signedJar;
    final Path librarySourcesJar;
    final Path nosrcJar;
    final Path library2Jar;
    final Path otherlibJar;

    private final Map<Path, String> checksums = new TreeMap<>();

    static Fixture shared() {
        return INSTANCE;
    }

    private Fixture(
            Path repo,
            Path libraryJar,
            Path otherJar,
            Path signedJar,
            Path librarySourcesJar,
            Path nosrcJar,
            Path library2Jar,
            Path otherlibJar) {
        this.repo = repo;
        this.libraryJar = libraryJar;
        this.otherJar = otherJar;
        this.signedJar = signedJar;
        this.librarySourcesJar = librarySourcesJar;
        this.nosrcJar = nosrcJar;
        this.library2Jar = library2Jar;
        this.otherlibJar = otherlibJar;
    }

    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash " + file, e);
        }
    }

    String recordedChecksum(Path file) {
        return checksums.computeIfAbsent(file, Fixture::sha256);
    }

    private static Fixture build() {
        try {
            Path root = Files.createTempDirectory("facade-fixture");
            Path sources = root.resolve("src");
            Path classes = root.resolve("classes");
            Files.createDirectories(classes);
            writeSources(sources, "1.0.0");
            compile(sources, classes);
            Path sources2 = root.resolve("src2");
            Path classes2 = root.resolve("classes2");
            Files.createDirectories(classes2);
            writeLibrarySources(sources2, "2.0.0");
            compile(sources2, classes2);
            Path repo = root.resolve("repo");
            Path library = mavenArtifact(repo, "library", classes, List.of(
                    "com/example/api/MyInterface.class",
                    "com/example/api/OtherInterface.class",
                    "com/example/library/Target.class",
                    "com/example/library/GenTarget.class",
                    "com/example/library/Plain.class"), Map.of());
            Path other = mavenArtifact(repo, "other", classes, List.of(
                    "com/example/api/OtherInterface.class",
                    "com/example/other/Other.class"), Map.of());
            Path signed = mavenArtifact(repo, "signedlib", classes, List.of(
                    "com/example/api/MyInterface.class",
                    "com/example/signedlib/SignedTarget.class"), Map.of("META-INF/X.SF", "Signature File"));
            // real sources jar for library: the .java files of the classes it ships
            Path librarySources = mavenSourcesJar(repo, "library", "1.0.0", sources, List.of(
                    "com/example/api/MyInterface.java",
                    "com/example/api/OtherInterface.java",
                    "com/example/library/Target.java",
                    "com/example/library/GenTarget.java",
                    "com/example/library/Plain.java"));
            // a module published WITHOUT a sources jar
            Path nosrc = mavenArtifact(repo, "nosrc", classes, List.of(
                    "com/example/api/MyInterface.class",
                    "com/example/nosrc/Nosrc.class"), Map.of());
            // a second version of library with identical classes but its own Target.version()
            Path library2 = mavenArtifact(repo, "library", "2.0.0", classes2, List.of(
                    "com/example/api/MyInterface.class",
                    "com/example/api/OtherInterface.class",
                    "com/example/library/Target.class",
                    "com/example/library/GenTarget.class",
                    "com/example/library/Plain.class"), Map.of(), List.of());
            mavenSourcesJar(repo, "library", "2.0.0", sources2, List.of(
                    "com/example/api/MyInterface.java",
                    "com/example/api/OtherInterface.java",
                    "com/example/library/Target.java",
                    "com/example/library/GenTarget.java",
                    "com/example/library/Plain.java"));
            // a module pulling in library:2.0.0 transitively via its POM
            Path otherlib = mavenArtifact(repo, "otherlib", "1.0.0", classes, List.of(
                    "com/example/otherlib/Other.class"), Map.of(), List.of("com.example:library:2.0.0"));
            return new Fixture(repo, library, other, signed, librarySources, nosrc, library2, otherlib);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> librarySources(String version) {
        Map<String, String> files = new TreeMap<>();
        files.put("com/example/api/MyInterface.java", """
                package com.example.api;

                public interface MyInterface {
                }
                """);
        files.put("com/example/api/OtherInterface.java", """
                package com.example.api;

                public interface OtherInterface {
                }
                """);
        files.put("com/example/library/Target.java", """
                package com.example.library;

                public class Target {
                    private String value = "target";

                    public String value() {
                        return value;
                    }

                    public String version() {
                        return "$VERSION";
                    }
                }
                """.replace("$VERSION", version));
        files.put("com/example/library/GenTarget.java", """
                package com.example.library;

                public class GenTarget<T> {
                    public T get() {
                        return null;
                    }
                }
                """);
        files.put("com/example/library/Plain.java", """
                package com.example.library;

                public class Plain {
                }
                """);
        return files;
    }

    private static void writeSources(Path sources, String version) throws IOException {
        Map<String, String> files = new TreeMap<>(librarySources(version));
        files.put("com/example/other/Other.java", """
                package com.example.other;

                public class Other {
                }
                """);
        files.put("com/example/otherlib/Other.java", """
                package com.example.otherlib;

                public class Other {
                    public String use() {
                        return new com.example.library.Target().version();
                    }
                }
                """);
        files.put("com/example/signedlib/SignedTarget.java", """
                package com.example.signedlib;

                public class SignedTarget {
                }
                """);
        files.put("com/example/nosrc/Nosrc.java", """
                package com.example.nosrc;

                public class Nosrc {
                    public String name() {
                        return "nosrc";
                    }
                }
                """);
        writeAll(sources, files);
    }

    private static void writeLibrarySources(Path sources, String version) throws IOException {
        writeAll(sources, librarySources(version));
    }

    private static void writeAll(Path sources, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = sources.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void compile(Path sources, Path classes) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("system javac unavailable; run tests on a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.SOURCE_PATH, List.of(sources.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            for (JavaFileObject unit :
                    fileManager.list(StandardLocation.SOURCE_PATH, "", EnumSet.of(JavaFileObject.Kind.SOURCE), true)) {
                units.add(unit);
            }
            JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fileManager, diagnostics, List.of("--release", "11"), null, units);
            if (!task.call()) {
                throw new IllegalStateException("fixture compilation failed: " + diagnostics.getDiagnostics());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path mavenArtifact(
            Path repo, String artifact, Path classes, List<String> entries, Map<String, String> extraEntries)
            throws IOException {
        return mavenArtifact(repo, artifact, "1.0.0", classes, entries, extraEntries, List.of());
    }

    private static Path mavenArtifact(
            Path repo,
            String artifact,
            String version,
            Path classes,
            List<String> entries,
            Map<String, String> extraEntries,
            List<String> pomDependencies)
            throws IOException {
        Path dir = repo.resolve("com/example/" + artifact + "/" + version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifact + "-" + version + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (!extraEntries.isEmpty()) {
            manifest.getMainAttributes().putValue("SHA-256-DIGEST", "AA==");
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String entry : entries) {
                Path classFile = classes.resolve(entry);
                writeEntry(out, entry, Files.readAllBytes(classFile));
            }
            for (Map.Entry<String, String> extra : extraEntries.entrySet()) {
                writeEntry(out, extra.getKey(), extra.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        writePom(dir.resolve(artifact + "-" + version + ".pom"), artifact, version, pomDependencies);
        return jar;
    }

    private static Path mavenSourcesJar(
            Path repo, String artifact, String version, Path sourcesRoot, List<String> entries) throws IOException {
        Path dir = repo.resolve("com/example/" + artifact + "/" + version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifact + "-" + version + "-sources.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String entry : entries) {
                writeEntry(out, entry, Files.readAllBytes(sourcesRoot.resolve(entry)));
            }
        }
        return jar;
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static void writePom(Path target, String artifact, String version, List<String> dependencies)
            throws IOException {
        StringBuilder dependencyXml = new StringBuilder();
        if (!dependencies.isEmpty()) {
            dependencyXml.append("  <dependencies>\n");
            for (String notation : dependencies) {
                String[] coordinates = notation.split(":");
                dependencyXml
                        .append("    <dependency>\n")
                        .append("      <groupId>")
                        .append(coordinates[0])
                        .append("</groupId>\n")
                        .append("      <artifactId>")
                        .append(coordinates[1])
                        .append("</artifactId>\n")
                        .append("      <version>")
                        .append(coordinates[2])
                        .append("</version>\n")
                        .append("    </dependency>\n");
            }
            dependencyXml.append("  </dependencies>\n");
        }
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>" + artifact + "</artifactId>\n"
                + "  <version>" + version + "</version>\n"
                + dependencyXml
                + "</project>\n";
        Files.writeString(target, pom, StandardCharsets.UTF_8);
    }

    static byte[] readAll(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
