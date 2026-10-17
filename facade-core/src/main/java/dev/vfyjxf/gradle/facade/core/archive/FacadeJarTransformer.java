package dev.vfyjxf.gradle.facade.core.archive;

import dev.vfyjxf.gradle.facade.core.bytecode.FacadeTransformException;
import dev.vfyjxf.gradle.facade.core.bytecode.InterfaceInjectingClassTransformer;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class FacadeJarTransformer {

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String MODULE_INFO_LOOKUP = "module-info.class";
    private static final String CLASS_SUFFIX = ".class";
    private static final String TEMP_INFIX = ".facade-tmp-";
    private static final long BOMB_MIN_UNCOMPRESSED_BYTES = 1024L * 1024L;
    private static final long BOMB_COMPRESSION_RATIO = 1000L;
    private static final Pattern VERSIONED_LAYER = Pattern.compile("META-INF/versions/(\\d+)/(.+)");
    private static final Pattern SIGNATURE_ENTRY =
            Pattern.compile("META-INF/.*\\.(SF|RSA|DSA)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNATURE_PREFIX_ENTRY = Pattern.compile("META-INF/SIG-.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGEST_ATTRIBUTE =
            Pattern.compile("(SHA1|SHA-1|SHA-256|SHA-512|MD5)-DIGEST(-.+)?", Pattern.CASE_INSENSITIVE);

    private FacadeJarTransformer() {}

    public static JarTransformResult transformJar(
            Path input, Path output, InterfaceInjectionRules rules, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(options, "options");
        if (!Files.exists(input)) {
            throw new FacadeTransformException("input does not exist: " + input);
        }
        if (!Files.isReadable(input)) {
            throw new FacadeTransformException("input is not readable: " + input);
        }
        Path absoluteOutput = output.toAbsolutePath();
        if (absoluteOutput.equals(input.toAbsolutePath())) {
            throw new FacadeTransformException("output must differ from input: " + output);
        }
        Path outputDir = absoluteOutput.getParent();
        if (outputDir != null) {
            Files.createDirectories(outputDir);
        }
        Plan plan = planInput(input, rules, options);
        if (!plan.anyTargeted()) {
            Files.copy(input, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
            return new JarTransformResult(plan.totalEntries(), 0, List.of(), false);
        }
        return writeTransformed(input, absoluteOutput, rules, options, plan);
    }

    private record Plan(
            int totalEntries,
            boolean anyTargeted,
            List<String> matchedTargets,
            Set<String> droppedEntries,
            boolean rewriteManifest,
            boolean signaturesStripped,
            int expectedWrittenEntries) {}

    private static Plan planInput(Path input, InterfaceInjectionRules rules, ArchiveOptions options)
            throws IOException {
        try (ZipFile zip = openZip(input)) {
            int total = 0;
            long cumulative = 0;
            Set<String> seen = new LinkedHashSet<>();
            List<String> matchedTargets = new ArrayList<>();
            Set<String> signatureEntries = new LinkedHashSet<>();
            boolean anyTargeted = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                total++;
                if (!seen.add(name)) {
                    throw new FacadeTransformException("duplicate entry name in archive: " + name);
                }
                validateEntryName(name, input);
                long uncompressed = Math.max(entry.getSize(), 0L);
                cumulative += uncompressed;
                if (cumulative > options.maxUncompressedBytes()) {
                    throw new FacadeTransformException(
                            "archive " + input + " exceeds maxUncompressedBytes=" + options.maxUncompressedBytes()
                                    + " (cumulative uncompressed size " + cumulative + " after entry '" + name + "')");
                }
                long compressed = entry.getCompressedSize();
                if (uncompressed > BOMB_MIN_UNCOMPRESSED_BYTES && compressed > 0
                        && uncompressed / compressed > BOMB_COMPRESSION_RATIO) {
                    throw new FacadeTransformException(
                            "entry '" + name + "' in " + input + " looks like a zip bomb: " + uncompressed
                                    + " uncompressed bytes vs " + compressed + " compressed");
                }
                String lookup = lookupName(name);
                if (isClassEntry(lookup) && rules.targetsClass(internalName(lookup))) {
                    anyTargeted = true;
                    matchedTargets.add(internalName(lookup));
                }
                if (SIGNATURE_ENTRY.matcher(name).matches() || SIGNATURE_PREFIX_ENTRY.matcher(name).matches()) {
                    signatureEntries.add(name);
                }
            }
            List<String> manifestDigests = List.of();
            boolean detected = !signatureEntries.isEmpty();
            if (anyTargeted && !detected) {
                manifestDigests = scanManifestDigestAttributes(zip);
                detected = !manifestDigests.isEmpty();
            }
            if (anyTargeted && detected && options.signedJarPolicy() == SignedJarPolicy.FAIL) {
                throw new FacadeTransformException(
                        "input jar " + input + " is signed and at least one class would be modified, which would"
                                + " invalidate its signatures; detected: " + describeDetection(signatureEntries, manifestDigests)
                                + "; opt in with ArchiveOptions.withSignedJarPolicy(SignedJarPolicy.STRIP)"
                                + " to strip signatures");
            }
            boolean stripping = anyTargeted && detected && options.signedJarPolicy() == SignedJarPolicy.STRIP;
            Set<String> dropped = stripping ? signatureEntries : Set.of();
            // when stripping, the manifest is always run through the digest-attribute remover
            // (it returns the original bytes untouched when no digest attribute is present)
            return new Plan(
                    total,
                    anyTargeted,
                    List.copyOf(matchedTargets),
                    dropped,
                    stripping,
                    stripping,
                    total - dropped.size());
        } catch (ZipException e) {
            throw notValidArchive(input, e);
        }
    }

    private static JarTransformResult writeTransformed(
            Path input, Path absoluteOutput, InterfaceInjectionRules rules, ArchiveOptions options, Plan plan)
            throws IOException {
        Path temp = Files.createTempFile(
                absoluteOutput.getParent(), absoluteOutput.getFileName().toString() + TEMP_INFIX, "");
        boolean moved = false;
        int transformedClasses = 0;
        try {
            InterfaceInjectingClassTransformer transformer = InterfaceInjectingClassTransformer.of(rules);
            long actualTotal = 0;
            try (ZipFile zip = openZip(input);
                    ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (plan.droppedEntries().contains(entry.getName())) {
                        continue;
                    }
                    byte[] data = readEntry(zip, entry);
                    actualTotal += data.length;
                    if (actualTotal > options.maxUncompressedBytes()) {
                        throw new FacadeTransformException(
                                "archive " + input + " exceeds maxUncompressedBytes="
                                        + options.maxUncompressedBytes() + " while reading entry '" + entry.getName()
                                        + "'");
                    }
                    String lookup = lookupName(entry.getName());
                    if (isClassEntry(lookup)) {
                        String internalName = internalName(lookup);
                        if (rules.targetsClass(internalName)) {
                            byte[] transformed = transformer.transform(internalName, data);
                            if (transformed != data) {
                                transformedClasses++;
                            }
                            data = transformed;
                        }
                    }
                    if (plan.rewriteManifest() && MANIFEST_ENTRY.equals(entry.getName())) {
                        data = removeManifestDigestAttributes(data);
                    }
                    writeEntry(out, entry, data);
                }
            }
            validateEntryCount(temp, plan.expectedWrittenEntries());
            try {
                Files.move(temp, absoluteOutput, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (ZipException e) {
            throw notValidArchive(input, e);
        } catch (IOException e) {
            throw new FacadeTransformException(
                    "failed to transform " + input + " -> " + absoluteOutput + ": " + e, e);
        } finally {
            // the output must never be a partial file: the temp file is the only partial artifact
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
        return new JarTransformResult(
                plan.totalEntries(), transformedClasses, plan.matchedTargets(), plan.signaturesStripped());
    }

    static String lookupName(String entryName) {
        if (entryName.startsWith("META-INF/versions/")) {
            Matcher matcher = VERSIONED_LAYER.matcher(entryName);
            if (matcher.matches()) {
                return matcher.group(2);
            }
        }
        return entryName;
    }

    private static boolean isClassEntry(String lookup) {
        return lookup.endsWith(CLASS_SUFFIX) && !lookup.equals(MODULE_INFO_LOOKUP);
    }

    private static String internalName(String lookup) {
        return lookup.substring(0, lookup.length() - CLASS_SUFFIX.length());
    }

    private static void validateEntryName(String name, Path input) {
        if (name.startsWith("/")) {
            throw new FacadeTransformException("absolute entry name in " + input + ": " + name);
        }
        for (String segment : name.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new FacadeTransformException("entry name with '..' segment in " + input + ": " + name);
            }
        }
    }

    private static ZipFile openZip(Path input) throws IOException {
        try {
            return new ZipFile(input.toFile());
        } catch (ZipException e) {
            throw notValidArchive(input, e);
        }
    }

    private static FacadeTransformException notValidArchive(Path input, ZipException cause) {
        String detail = cause.getMessage() == null ? "" : ": " + cause.getMessage();
        return new FacadeTransformException("not a valid zip/jar archive: " + input + detail, cause);
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            if (in == null) {
                throw new FacadeTransformException("cannot read entry '" + entry.getName() + "'");
            }
            return in.readAllBytes();
        }
    }

    private static void writeEntry(ZipOutputStream out, ZipEntry source, byte[] data) throws IOException {
        ZipEntry target = new ZipEntry(source.getName());
        if (source.getLastModifiedTime() != null) {
            target.setLastModifiedTime(source.getLastModifiedTime());
        }
        if (source.getMethod() == ZipEntry.STORED) {
            // STORED entries need size and crc set before putNextEntry
            target.setMethod(ZipEntry.STORED);
            CRC32 crc = new CRC32();
            crc.update(data);
            target.setCrc(crc.getValue());
            target.setSize(data.length);
        }
        out.putNextEntry(target);
        out.write(data);
        out.closeEntry();
    }

    private static void validateEntryCount(Path temp, int expected) throws IOException {
        try (ZipFile check = new ZipFile(temp.toFile())) {
            if (check.size() != expected) {
                throw new FacadeTransformException(
                        "output validation failed: expected " + expected + " entries but wrote " + check.size());
            }
        }
    }

    private static List<String> scanManifestDigestAttributes(ZipFile zip) throws IOException {
        ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
        if (manifestEntry == null) {
            return List.of();
        }
        try {
            Manifest manifest = new Manifest(zip.getInputStream(manifestEntry));
            List<String> found = new ArrayList<>();
            collectDigestAttributes(manifest.getMainAttributes(), "main section", found);
            for (java.util.Map.Entry<String, Attributes> section : manifest.getEntries().entrySet()) {
                collectDigestAttributes(section.getValue(), "section '" + section.getKey() + "'", found);
            }
            return found;
        } catch (IOException e) {
            throw new FacadeTransformException("failed to read " + MANIFEST_ENTRY + ": " + e, e);
        }
    }

    private static void collectDigestAttributes(Attributes attributes, String where, List<String> found) {
        for (Object key : attributes.keySet()) {
            String name = key.toString();
            if (DIGEST_ATTRIBUTE.matcher(name).matches()) {
                found.add(name + " (" + where + ")");
            }
        }
    }

    private static String describeDetection(Set<String> signatureEntries, List<String> manifestDigests) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String name : signatureEntries) {
            joiner.add("signature entry " + name);
        }
        for (String attribute : manifestDigests) {
            joiner.add("manifest attribute " + attribute);
        }
        return joiner.toString();
    }

    private static byte[] removeManifestDigestAttributes(byte[] manifestBytes) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
        boolean changed = removeDigestAttributes(manifest.getMainAttributes());
        for (Attributes attributes : manifest.getEntries().values()) {
            changed |= removeDigestAttributes(attributes);
        }
        if (!changed) {
            return manifestBytes;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        manifest.write(buffer);
        return buffer.toByteArray();
    }

    private static boolean removeDigestAttributes(Attributes attributes) {
        boolean changed = false;
        for (Iterator<Object> it = attributes.keySet().iterator(); it.hasNext(); ) {
            if (DIGEST_ATTRIBUTE.matcher(it.next().toString()).matches()) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }
}
