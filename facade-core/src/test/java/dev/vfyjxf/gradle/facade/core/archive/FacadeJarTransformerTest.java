package dev.vfyjxf.gradle.facade.core.archive;

import dev.vfyjxf.gradle.facade.core.bytecode.FacadeTransformException;
import dev.vfyjxf.gradle.facade.core.fixtures.ClassInspect;
import dev.vfyjxf.gradle.facade.core.fixtures.FixtureCompiler;
import dev.vfyjxf.gradle.facade.core.fixtures.FixtureSources;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FacadeJarTransformerTest {

    @TempDir
    Path workDir;

    private static InterfaceInjectionRules rules(String json) {
        return InterfaceInjectionRules.parse(json, "archive-test");
    }

    private Path compilePkg() throws IOException {
        Path dir = workDir.resolve("pkg-classes");
        Files.createDirectories(dir);
        FixtureCompiler.compile(dir, FixtureSources.pkg());
        return dir;
    }

    private static byte[] pkgClass(Path libDir, String internalName) throws IOException {
        return FixtureCompiler.classBytes(libDir, internalName);
    }

    private static List<String> entryNames(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
            return names;
        }
    }

    private static byte[] entryBytes(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, "missing entry " + name + " in " + jar);
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static void assertNoTempFiles(Path dir) throws IOException {
        List<Path> leftovers = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "*.facade-tmp-*")) {
            stream.forEach(leftovers::add);
        }
        assertTrue(leftovers.isEmpty(), () -> "leftover temp files: " + leftovers);
    }

    @Test
    @DisplayName("16. full jar: order, non-class content and unmatched classes preserved, target patched")
    void fullJarPreservesEverythingElse() throws IOException {
        Path libDir = compilePkg();
        byte[] manifest = TestJars.manifest("Created-By", "facade-test", "Custom-Attr", "v1");
        byte[] services = "pkg.Impl\n".getBytes(StandardCharsets.UTF_8);
        byte[] resource = "hello resource".getBytes(StandardCharsets.UTF_8);
        byte[] stored = new byte[64];
        new Random(42).nextBytes(stored);
        Path input = TestJars.jar(workDir.resolve("in.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", manifest),
                TestJars.Spec.dir("META-INF/"),
                TestJars.Spec.deflated("META-INF/services/pkg.Service", services),
                TestJars.Spec.dir("pkg/"),
                TestJars.Spec.deflated("pkg/Target.class", pkgClass(libDir, "pkg/Target")),
                TestJars.Spec.deflated("pkg/Other.class", pkgClass(libDir, "pkg/Other")),
                TestJars.Spec.deflated("pkg/package-info.class", pkgClass(libDir, "pkg/package-info")),
                TestJars.Spec.deflated("resource.txt", resource),
                TestJars.Spec.stored("stored.bin", stored)));
        Path output = workDir.resolve("out.jar");

        JarTransformResult result = FacadeJarTransformer.transformJar(input, output,
                rules("{\"pkg/Target\": \"java.io.Serializable\"}"), ArchiveOptions.defaults());

        assertEquals(9, result.totalEntries());
        assertEquals(1, result.transformedClasses());
        assertEquals(List.of("pkg/Target"), result.matchedTargets());
        assertFalse(result.signaturesStripped());

        assertEquals(entryNames(input), entryNames(output));
        assertArrayEquals(manifest, entryBytes(output, "META-INF/MANIFEST.MF"));
        assertArrayEquals(services, entryBytes(output, "META-INF/services/pkg.Service"));
        assertArrayEquals(resource, entryBytes(output, "resource.txt"));
        assertArrayEquals(stored, entryBytes(output, "stored.bin"));
        assertArrayEquals(pkgClass(libDir, "pkg/Other"), entryBytes(output, "pkg/Other.class"));
        assertArrayEquals(pkgClass(libDir, "pkg/package-info"), entryBytes(output, "pkg/package-info.class"));

        byte[] patched = entryBytes(output, "pkg/Target.class");
        ClassInspect.verify(patched);
        ClassInspect.Info info = ClassInspect.info(patched);
        assertArrayEquals(new String[]{"java/io/Serializable"}, info.interfaces);
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/io/Serializable;", info.signature);
    }

    @Test
    @DisplayName("17. multi-release: base and versioned layer both patched, other versions untouched")
    void multiReleaseLayers() throws IOException {
        Path libDir = compilePkg();
        byte[] manifest = TestJars.manifest("Multi-Release", "true");
        Path input = TestJars.jar(workDir.resolve("mr.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", manifest),
                TestJars.Spec.deflated("pkg/Target.class", pkgClass(libDir, "pkg/Target")),
                TestJars.Spec.deflated("META-INF/versions/9/pkg/Other.class", pkgClass(libDir, "pkg/Other")),
                TestJars.Spec.deflated("META-INF/versions/21/pkg/Target.class", pkgClass(libDir, "pkg/Target"))));
        Path output = workDir.resolve("mr-out.jar");

        JarTransformResult result = FacadeJarTransformer.transformJar(input, output,
                rules("{\"pkg/Target\": \"java.io.Serializable\"}"), ArchiveOptions.defaults());

        assertEquals(4, result.totalEntries());
        assertEquals(2, result.transformedClasses());
        assertEquals(List.of("pkg/Target", "pkg/Target"), result.matchedTargets());
        assertArrayEquals(manifest, entryBytes(output, "META-INF/MANIFEST.MF"));
        assertArrayEquals(pkgClass(libDir, "pkg/Other"),
                entryBytes(output, "META-INF/versions/9/pkg/Other.class"));
        for (String layer : List.of("pkg/Target.class", "META-INF/versions/21/pkg/Target.class")) {
            ClassInspect.Info info = ClassInspect.info(entryBytes(output, layer));
            assertArrayEquals(new String[]{"java/io/Serializable"}, info.interfaces, layer);
        }
    }

    @Test
    @DisplayName("18. signed jars: FAIL names the detection, STRIP removes signatures and keeps the rest")
    void signedJarHandling() throws IOException {
        Path libDir = compilePkg();
        byte[] targetBytes = pkgClass(libDir, "pkg/Target");
        byte[] signedManifest = TestJars.manifest(
                "Application-Name", "Facade Test", "SHA-256-Digest", "BASE64PAYLOAD=");
        Path withSignatureEntries = TestJars.jar(workDir.resolve("signed.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", signedManifest),
                TestJars.Spec.deflated("META-INF/TEST.SF", "Signature-Version: 1.0".getBytes(StandardCharsets.UTF_8)),
                TestJars.Spec.deflated("META-INF/SIG-INFO", "sig".getBytes(StandardCharsets.UTF_8)),
                TestJars.Spec.deflated("pkg/Target.class", targetBytes)));

        FacadeTransformException failure = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(withSignatureEntries, workDir.resolve("fail.jar"),
                        rules("{\"pkg/Target\": \"java.io.Serializable\"}"), ArchiveOptions.defaults()));
        assertTrue(failure.getMessage().contains("TEST.SF"), failure.getMessage());
        assertTrue(failure.getMessage().contains("STRIP"), failure.getMessage());
        assertFalse(Files.exists(workDir.resolve("fail.jar")));

        Path digestOnly = TestJars.jar(workDir.resolve("digest-only.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", signedManifest),
                TestJars.Spec.deflated("pkg/Target.class", targetBytes)));
        FacadeTransformException digestFailure = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(digestOnly, workDir.resolve("fail2.jar"),
                        rules("{\"pkg/Target\": \"java.io.Serializable\"}"), ArchiveOptions.defaults()));
        assertTrue(digestFailure.getMessage().contains("SHA-256-Digest"), digestFailure.getMessage());

        Path stripped = workDir.resolve("stripped.jar");
        JarTransformResult result = FacadeJarTransformer.transformJar(withSignatureEntries, stripped,
                rules("{\"pkg/Target\": \"java.io.Serializable\"}"),
                ArchiveOptions.defaults().withSignedJarPolicy(SignedJarPolicy.STRIP));
        assertTrue(result.signaturesStripped());
        List<String> names = entryNames(stripped);
        assertTrue(names.contains("META-INF/MANIFEST.MF"));
        assertFalse(names.contains("META-INF/TEST.SF"));
        assertFalse(names.contains("META-INF/SIG-INFO"));
        Manifest outManifest = new Manifest(new ByteArrayInputStream(entryBytes(stripped, "META-INF/MANIFEST.MF")));
        assertEquals("Facade Test", outManifest.getMainAttributes().getValue("Application-Name"));
        for (Object key : outManifest.getMainAttributes().keySet()) {
            assertFalse(key.toString().toUpperCase(Locale.ROOT).contains("DIGEST"), key.toString());
        }
        ClassInspect.Info info = ClassInspect.info(entryBytes(stripped, "pkg/Target.class"));
        assertArrayEquals(new String[]{"java/io/Serializable"}, info.interfaces);
    }

    @Test
    @DisplayName("19. duplicate entries rejected")
    void duplicateEntriesRejected() throws IOException {
        Path dup = duplicateEntryJar(workDir.resolve("dup.jar"));
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(dup, workDir.resolve("out.jar"),
                        InterfaceInjectionRules.empty(), ArchiveOptions.defaults()));
        assertTrue(e.getMessage().contains("dup.txt"), e.getMessage());
    }

    // ZipOutputStream refuses duplicate names, so a raw zip is assembled by hand
    private static Path duplicateEntryJar(Path file) throws IOException {
        byte[] nameBytes = "dup.txt".getBytes(StandardCharsets.UTF_8);
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int firstOffset = buf.size();
        writeStoredLocalHeader(buf, nameBytes, first);
        int secondOffset = buf.size();
        writeStoredLocalHeader(buf, nameBytes, second);
        int centralStart = buf.size();
        writeCentralHeader(buf, nameBytes, first, firstOffset);
        writeCentralHeader(buf, nameBytes, second, secondOffset);
        int centralSize = buf.size() - centralStart;
        leInt(buf, 0x06054b50);
        leShort(buf, 0);
        leShort(buf, 0);
        leShort(buf, 2);
        leShort(buf, 2);
        leInt(buf, centralSize);
        leInt(buf, centralStart);
        leShort(buf, 0);
        Files.write(file, buf.toByteArray());
        return file;
    }

    private static void writeStoredLocalHeader(ByteArrayOutputStream buf, byte[] nameBytes, byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        leInt(buf, 0x04034b50);
        leShort(buf, 20);
        leShort(buf, 0);
        leShort(buf, ZipEntry.STORED);
        leShort(buf, 0);
        leShort(buf, 0x21);
        leInt(buf, (int) crc.getValue());
        leInt(buf, content.length);
        leInt(buf, content.length);
        leShort(buf, nameBytes.length);
        leShort(buf, 0);
        buf.write(nameBytes, 0, nameBytes.length);
        buf.write(content, 0, content.length);
    }

    private static void writeCentralHeader(
            ByteArrayOutputStream buf, byte[] nameBytes, byte[] content, int localHeaderOffset) {
        CRC32 crc = new CRC32();
        crc.update(content);
        leInt(buf, 0x02014b50);
        leShort(buf, 20);
        leShort(buf, 20);
        leShort(buf, 0);
        leShort(buf, ZipEntry.STORED);
        leShort(buf, 0);
        leShort(buf, 0x21);
        leInt(buf, (int) crc.getValue());
        leInt(buf, content.length);
        leInt(buf, content.length);
        leShort(buf, nameBytes.length);
        leShort(buf, 0);
        leShort(buf, 0);
        leShort(buf, 0);
        leShort(buf, 0);
        leInt(buf, 0);
        leInt(buf, localHeaderOffset);
        buf.write(nameBytes, 0, nameBytes.length);
    }

    private static void leShort(ByteArrayOutputStream buf, int value) {
        buf.write(value & 0xFF);
        buf.write((value >>> 8) & 0xFF);
    }

    private static void leInt(ByteArrayOutputStream buf, int value) {
        leShort(buf, value);
        leShort(buf, value >>> 16);
    }

    @Test
    @DisplayName("20. corrupt input rejected, no temp files or partial output left behind")
    void corruptInputAndCleanup() throws IOException {
        Path corrupt = workDir.resolve("corrupt.jar");
        Files.write(corrupt, "definitely not a zip file, just text padding padding".getBytes(StandardCharsets.UTF_8));
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(corrupt, workDir.resolve("out.jar"),
                        InterfaceInjectionRules.empty(), ArchiveOptions.defaults()));
        assertTrue(e.getMessage().contains("not a valid zip/jar archive"), e.getMessage());
        assertNoTempFiles(workDir);

        Path libDir = compilePkg();
        Path input = TestJars.jar(workDir.resolve("conflict.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", TestJars.manifest()),
                TestJars.Spec.deflated("pkg/Library.class", pkgClass(libDir, "pkg/Library"))));
        Path output = workDir.resolve("conflict-out.jar");
        FacadeTransformException midWrite = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(input, output,
                        rules("{\"pkg/Library\": \"java.util.function.Supplier<T>\"}"), ArchiveOptions.defaults()));
        assertNotNull(midWrite.getCause());
        assertFalse(Files.exists(output));
        assertNoTempFiles(workDir);
    }

    @Test
    @DisplayName("21. reproducibility: same input transformed twice is byte-identical")
    void reproducibleOutput() throws IOException {
        Path libDir = compilePkg();
        Path input = TestJars.jar(workDir.resolve("in.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", TestJars.manifest("Created-By", "repro")),
                TestJars.Spec.dir("pkg/"),
                TestJars.Spec.deflated("pkg/Target.class", pkgClass(libDir, "pkg/Target")),
                TestJars.Spec.deflated("pkg/Other.class", pkgClass(libDir, "pkg/Other")),
                TestJars.Spec.stored("stored.bin", new byte[]{9, 8, 7, 6, 5})));
        Path first = workDir.resolve("r1.jar");
        Path second = workDir.resolve("r2.jar");
        InterfaceInjectionRules rules = rules("{\"pkg/Target\": \"java.io.Serializable\"}");
        FacadeJarTransformer.transformJar(input, first, rules, ArchiveOptions.defaults());
        FacadeJarTransformer.transformJar(input, second, rules, ArchiveOptions.defaults());
        assertEquals(-1L, Files.mismatch(first, second));
    }

    @Test
    @DisplayName("22. bomb guard: maxUncompressedBytes exceeded")
    void bombGuard() throws IOException {
        Path libDir = compilePkg();
        Path bomb = TestJars.jar(workDir.resolve("bomb.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", TestJars.manifest()),
                TestJars.Spec.deflated("big.bin", new byte[100_000]),
                TestJars.Spec.deflated("pkg/Target.class", pkgClass(libDir, "pkg/Target"))));
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(bomb, workDir.resolve("out.jar"),
                        rules("{\"pkg/Target\": \"java.io.Serializable\"}"),
                        ArchiveOptions.defaults().withMaxUncompressedBytes(2048)));
        assertTrue(e.getMessage().contains("maxUncompressedBytes"), e.getMessage());
        assertFalse(Files.exists(workDir.resolve("out.jar")));
        assertNoTempFiles(workDir);
    }

    @Test
    @DisplayName("23. large jar: 500 synthetic classes, 100 targeted")
    void largeJar() throws IOException {
        List<TestJars.Spec> specs = new ArrayList<>();
        specs.add(TestJars.Spec.deflated("META-INF/MANIFEST.MF", TestJars.manifest()));
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("\"synth/Tgt").append(String.format("%03d", i)).append("\": \"java.io.Serializable\"");
            specs.add(TestJars.Spec.deflated(
                    "synth/Tgt" + String.format("%03d", i) + ".class", syntheticClass("synth/Tgt" + String.format("%03d", i))));
        }
        json.append("}");
        for (int i = 100; i < 500; i++) {
            specs.add(TestJars.Spec.deflated("synth/Cls" + i + ".class", syntheticClass("synth/Cls" + i)));
        }
        Path input = TestJars.jar(workDir.resolve("big.jar"), specs);
        Path output = workDir.resolve("big-out.jar");

        JarTransformResult result = FacadeJarTransformer.transformJar(input, output, rules(json.toString()),
                ArchiveOptions.defaults());

        assertEquals(501, result.totalEntries());
        assertEquals(100, result.transformedClasses());
        assertEquals(100, result.matchedTargets().size());
        assertEquals(501, entryNames(output).size());
        ClassInspect.Info info = ClassInspect.info(entryBytes(output, "synth/Tgt000.class"));
        assertArrayEquals(new String[]{"java/io/Serializable"}, info.interfaces);
        assertArrayEquals(new String[0], ClassInspect.info(entryBytes(output, "synth/Cls499.class")).interfaces);
    }

    @Test
    @DisplayName("24. input == output rejected")
    void inputEqualsOutputRejected() throws IOException {
        Path input = TestJars.jar(workDir.resolve("in.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", TestJars.manifest())));
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> FacadeJarTransformer.transformJar(input, input, InterfaceInjectionRules.empty(),
                        ArchiveOptions.defaults()));
        assertTrue(e.getMessage().contains("must differ"), e.getMessage());
    }

    @Test
    @DisplayName("25. javap integration: patched jar shows the injected interface and signature")
    void javapShowsInjectedInterface() throws Exception {
        Path libDir = compilePkg();
        Path input = TestJars.jar(workDir.resolve("accept.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF",
                        TestJars.manifest("Implementation-Title", "acceptance")),
                TestJars.Spec.dir("pkg/"),
                TestJars.Spec.deflated("pkg/Target.class", pkgClass(libDir, "pkg/Target"))));
        Path patched = workDir.resolve("accept-patched.jar");
        FacadeJarTransformer.transformJar(input, patched,
                rules("{\"pkg/Target\": \"java.util.function.Supplier<T>\"}"), ArchiveOptions.defaults());

        Path javap = Path.of(System.getProperty("java.home"), "bin", "javap");
        Process process = new ProcessBuilder(
                javap.toString(), "-v", "-p", "-cp", patched.toString(), "pkg.Target")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("java.util.function.Supplier"), output);
        assertTrue(output.contains("<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/function/Supplier<TT;>;"),
                output);
    }

    @Test
    @DisplayName("26. compile acceptance: pre-patch javac fails, post-patch javac succeeds")
    void compileAcceptance() throws IOException {
        Path libDir = compilePkg();
        byte[] manifest = TestJars.manifest("Implementation-Title", "facade-lib");
        Path originalJar = TestJars.jar(workDir.resolve("lib.jar"), List.of(
                TestJars.Spec.deflated("META-INF/MANIFEST.MF", manifest),
                TestJars.Spec.deflated("pkg/Library.class", pkgClass(libDir, "pkg/Library")),
                TestJars.Spec.deflated("pkg/GenericLibrary.class", pkgClass(libDir, "pkg/GenericLibrary")),
                TestJars.Spec.deflated("pkg/api/MyInterface.class", pkgClass(libDir, "pkg/api/MyInterface"))));
        Path patchedJar = workDir.resolve("lib-patched.jar");
        FacadeJarTransformer.transformJar(originalJar, patchedJar,
                rules("{\"pkg/Library\": \"pkg.api.MyInterface\","
                        + " \"pkg/GenericLibrary\": \"java.util.function.Supplier<java.lang.String>\"}"),
                ArchiveOptions.defaults());

        Path preOut = workDir.resolve("pre-classes");
        Files.createDirectories(preOut);
        FixtureCompiler.CompileResult pre = FixtureCompiler.compile(preOut, originalJar.toString(),
                Map.of("Consumer", FixtureSources.CONSUMER));
        assertFalse(pre.success(), () -> String.join("\n", pre.messages()));
        assertTrue(pre.messages().stream().anyMatch(m -> m.contains("incompatible types")),
                () -> String.join("\n", pre.messages()));
        FixtureCompiler.CompileResult preGeneric = FixtureCompiler.compile(preOut, originalJar.toString(),
                Map.of("GenericConsumer", FixtureSources.GENERIC_CONSUMER));
        assertFalse(preGeneric.success(), () -> String.join("\n", preGeneric.messages()));
        assertTrue(preGeneric.messages().stream().anyMatch(m -> m.contains("incompatible types")),
                () -> String.join("\n", preGeneric.messages()));

        Path postOut = workDir.resolve("post-classes");
        Files.createDirectories(postOut);
        FixtureCompiler.CompileResult post = FixtureCompiler.compile(postOut, patchedJar.toString(),
                Map.of("Consumer", FixtureSources.CONSUMER));
        assertTrue(post.success(), () -> String.join("\n", post.messages()));
        assertTrue(Files.exists(postOut.resolve("Consumer.class")));
        FixtureCompiler.CompileResult postGeneric = FixtureCompiler.compile(postOut, patchedJar.toString(),
                Map.of("GenericConsumer", FixtureSources.GENERIC_CONSUMER));
        assertTrue(postGeneric.success(), () -> String.join("\n", postGeneric.messages()));
        assertTrue(Files.exists(postOut.resolve("GenericConsumer.class")));
    }

    private static byte[] syntheticClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    static final class TestJars {

        private static final long FIXED_TIME = 1_767_225_600L;

        record Spec(String name, byte[] data, int method) {

            static Spec deflated(String name, byte[] data) {
                return new Spec(name, data, ZipEntry.DEFLATED);
            }

            static Spec stored(String name, byte[] data) {
                return new Spec(name, data, ZipEntry.STORED);
            }

            static Spec dir(String name) {
                return new Spec(name, new byte[0], ZipEntry.STORED);
            }
        }

        static Path jar(Path file, List<Spec> specs) throws IOException {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                for (Spec spec : specs) {
                    ZipEntry entry = new ZipEntry(spec.name());
                    entry.setTime(FIXED_TIME);
                    if (spec.method() == ZipEntry.STORED) {
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(spec.data().length);
                        CRC32 crc = new CRC32();
                        crc.update(spec.data());
                        entry.setCrc(crc.getValue());
                    }
                    zos.putNextEntry(entry);
                    zos.write(spec.data());
                    zos.closeEntry();
                }
            }
            return file;
        }

        static byte[] manifest(String... pairs) throws IOException {
            Manifest mf = new Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                mf.getMainAttributes().putValue(pairs[i], pairs[i + 1]);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            mf.write(buffer);
            return buffer.toByteArray();
        }
    }
}
