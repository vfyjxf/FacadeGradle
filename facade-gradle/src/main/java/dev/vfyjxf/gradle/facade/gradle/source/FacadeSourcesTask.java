package dev.vfyjxf.gradle.facade.gradle.source;

import dev.vfyjxf.gradle.facade.core.archive.ArchiveOptions;
import dev.vfyjxf.gradle.facade.core.archive.FacadeJarTransformer;
import dev.vfyjxf.gradle.facade.core.archive.SignedJarPolicy;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Patches the original sources jars of every facade-bound module in this project with JST interface
 * injection, producing patched sources jars under {@code $buildDir/facade/sources/<group>/<name>/}
 * and a JSON report at {@code $buildDir/facade/sources-report.json}.
 *
 * <p>All inputs are captured at task-graph time (resolved artifact providers, rule files, the JST
 * bundle, a toolchain launcher); execution performs no project access.
 */
@CacheableTask
public abstract class FacadeSourcesTask extends DefaultTask {

    @Classpath
    public abstract ConfigurableFileCollection getJstBundle();

    /** Original sources jars of the bound modules, resolved leniently. */
    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getSourcesJars();

    /** Original binary jars of the bound modules (JST classpath source, patched in-task). */
    @Classpath
    public abstract ConfigurableFileCollection getBinaryJars();

    /** All rule files across bound modules. */
    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getRuleFiles();

    /**
     * Bound modules in serializable form: key {@code "group:name:version"} (version may be empty when
     * unknown at binding time), value the module's sorted absolute rule file paths.
     */
    @Input
    public abstract MapProperty<String, List<String>> getModuleRules();

    @Input
    public abstract Property<Boolean> getFailOnMissingSources();

    @Input
    public abstract Property<Boolean> getStrictValidation();

    /** Behavior gate (facade.options.transformSources); checked via {@code onlyIf}. */
    @Internal
    public abstract Property<Boolean> getTransformSources();

    /** Resolved sources artifacts with component identifiers, for module-coordinate mapping. */
    @Internal
    public abstract ListProperty<ResolvedArtifactResult> getSourcesArtifacts();

    /** Resolved binary artifacts with component identifiers, for module-coordinate mapping. */
    @Internal
    public abstract ListProperty<ResolvedArtifactResult> getBinaryArtifacts();

    @Internal
    public abstract Property<JavaLauncher> getJavaLauncher();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void patchSources() {
        if (!getJavaLauncher().isPresent()) {
            throw new GradleException(
                    "[facade] patchFacadeSources needs a JVM toolchain; apply the java plugin to this project");
        }
        Path launcher = getJavaLauncher().get().getExecutablePath().getAsFile().toPath();
        Path jstJar = getJstBundle().getSingleFile().toPath();
        boolean strict = getStrictValidation().get();
        Map<String, List<String>> moduleRules = new TreeMap<>(getModuleRules().get());
        Map<String, ResolvedModule> sources = indexByModule(getSourcesArtifacts().get());
        Map<String, ResolvedModule> binaries = indexByModule(getBinaryArtifacts().get());

        List<String> missing = new ArrayList<>();
        for (String key : moduleRules.keySet()) {
            if (!sources.containsKey(moduleKey(key))) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty() && getFailOnMissingSources().get()) {
            throw new GradleException(
                    "[facade] no sources jar for facade-bound module(s) (facade.options.failOnMissingSources"
                            + "=true): " + String.join(", ", missing));
        }

        Path outputRoot = getOutputDirectory().get().getAsFile().toPath();
        List<ReportEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> binding : moduleRules.entrySet()) {
            try {
                entries.add(patchModule(binding, sources, binaries, strict, launcher, jstJar, outputRoot));
            } catch (IOException e) {
                throw new UncheckedIOException("[facade] failed to patch sources for " + binding.getKey(), e);
            }
        }
        try {
            writeReport(getReportFile().get().getAsFile().toPath(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException("[facade] failed to write sources report", e);
        }
    }

    private ReportEntry patchModule(
            Map.Entry<String, List<String>> binding,
            Map<String, ResolvedModule> sources,
            Map<String, ResolvedModule> binaries,
            boolean strict,
            Path launcher,
            Path jstJar,
            Path outputRoot)
            throws IOException {
        String[] coordinates = parseKey(binding.getKey());
        String group = coordinates[0];
        String name = coordinates[1];
        String knownVersion = coordinates[2];
        String moduleKey = group + ":" + name;
        List<Path> ruleFiles = new ArrayList<>();
        for (String path : binding.getValue()) {
            ruleFiles.add(Path.of(path));
        }
        ResolvedModule source = sources.get(moduleKey);
        if (source == null) {
            ResolvedModule binary = binaries.get(moduleKey);
            String version = !knownVersion.isEmpty()
                    ? knownVersion
                    : (binary != null ? binary.version() : "unknown");
            getLogger().warn(
                    "[facade] no sources jar for facade-bound module {}:{}:{} — patched sources not produced",
                    group,
                    name,
                    version);
            return new ReportEntry(
                    group + ":" + name + ":" + version, "MISSING_SOURCES", null, null, ruleFileStrings(ruleFiles),
                    null, null);
        }
        String version = source.version();
        InterfaceInjectionRules rules;
        try {
            List<InterfaceInjectionRules> parsed = new ArrayList<>(ruleFiles.size());
            for (Path ruleFile : ruleFiles) {
                parsed.add(InterfaceInjectionRules.parse(ruleFile));
            }
            rules = InterfaceInjectionRules.merge(parsed.toArray(InterfaceInjectionRules[]::new));
        } catch (IOException | RuntimeException e) {
            if (strict) {
                throw new GradleException(
                        "[facade] failed to parse rule files " + ruleFiles + " for " + moduleKey + ": "
                                + e.getMessage(),
                        e);
            }
            getLogger().warn(
                    "[facade] rule validation failed for {}; strict validation is disabled and its sources are"
                            + " left UNPATCHED: {}",
                    moduleKey,
                    e.getMessage());
            return new ReportEntry(
                    moduleKey + ":" + version, "SKIPPED", source.file().toString(), null,
                    ruleFileStrings(ruleFiles), null, null);
        }
        // JST classpath: facade-core patch of the module's ORIGINAL binary jar, produced in-task to a
        // temp file (deterministic, never modifies the original artifact).
        Path patchedBinary = null;
        String classpathSource = "none";
        ResolvedModule binary = binaries.get(moduleKey);
        if (binary != null) {
            Path patchedBinaryPath =
                    getTemporaryDir().toPath().resolve(group + "." + name + "-patched-binary.jar");
            try {
                FacadeJarTransformer.transformJar(
                        binary.file(),
                        patchedBinaryPath,
                        rules,
                        ArchiveOptions.defaults().withSignedJarPolicy(SignedJarPolicy.FAIL));
                patchedBinary = patchedBinaryPath;
                classpathSource = "facade-core transformJar of original binary";
            } catch (Exception e) {
                if (strict) {
                    throw new GradleException(
                            "[facade] failed to build patched binary classpath for " + moduleKey + ": "
                                    + e.getMessage(),
                            e);
                }
                getLogger().warn(
                        "[facade] could not build patched binary classpath for {} ({}); running JST without a"
                                + " classpath, unresolved interfaces will be stubbed",
                        moduleKey,
                        e.getMessage());
            }
        } else {
            getLogger().warn(
                    "[facade] no binary jar for facade-bound module {}; running JST without a classpath,"
                            + " unresolved interfaces will be stubbed",
                    moduleKey);
        }
        try {
            Path moduleOutput = outputRoot.resolve(group).resolve(name);
            Files.createDirectories(moduleOutput);
            Path patchedSources = moduleOutput.resolve(name + "-" + version + "-sources.jar");
            Path stubs = moduleOutput.resolve(name + "-" + version + "-stubs.zip");
            Files.deleteIfExists(stubs);
            getLogger().lifecycle("[facade] JST patching sources of {}:{}:{}", group, name, version);
            JstSourcesPatcher.patch(jstJar, ruleFiles, patchedBinary, stubs, source.file(), patchedSources, launcher);
            return new ReportEntry(
                    moduleKey + ":" + version,
                    "PATCHED",
                    source.file().toString(),
                    patchedSources.toString(),
                    ruleFileStrings(ruleFiles),
                    Files.exists(stubs) ? stubs.toString() : null,
                    classpathSource);
        } catch (RuntimeException e) {
            throw e instanceof GradleException ? e : new GradleException(e.getMessage(), e);
        }
    }

    private static List<String> ruleFileStrings(List<Path> ruleFiles) {
        List<String> paths = new ArrayList<>(ruleFiles.size());
        for (Path ruleFile : ruleFiles) {
            paths.add(ruleFile.toString());
        }
        return paths;
    }

    /** Indexes artifacts by {@code group:name}; coordinates come from the component identifier. */
    private static Map<String, ResolvedModule> indexByModule(Collection<ResolvedArtifactResult> artifacts) {
        Map<String, ResolvedModule> index = new TreeMap<>();
        for (ResolvedArtifactResult artifact : artifacts) {
            ComponentIdentifier identifier = artifact.getId().getComponentIdentifier();
            if (identifier instanceof ModuleComponentIdentifier module) {
                index.putIfAbsent(
                        module.getGroup() + ":" + module.getModule(),
                        new ResolvedModule(module.getVersion(), artifact.getFile().toPath()));
            }
        }
        return index;
    }

    /** Strips the (possibly empty) version from a {@code "group:name[:version]"} rule key. */
    private static String moduleKey(String ruleKey) {
        int first = ruleKey.indexOf(':');
        int second = ruleKey.indexOf(':', first + 1);
        return second < 0 ? ruleKey : ruleKey.substring(0, second);
    }

    private static String[] parseKey(String ruleKey) {
        int first = ruleKey.indexOf(':');
        int second = ruleKey.indexOf(':', first + 1);
        if (second < 0) {
            return new String[] {ruleKey.substring(0, first), ruleKey.substring(first + 1), ""};
        }
        return new String[] {
            ruleKey.substring(0, first), ruleKey.substring(first + 1, second), ruleKey.substring(second + 1)
        };
    }

    private static void writeReport(Path reportPath, List<ReportEntry> entries) throws IOException {
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"modules\": [");
        for (int i = 0; i < entries.size(); i++) {
            ReportEntry entry = entries.get(i);
            json.append(i == 0 ? "\n" : ",\n");
            json.append("    {\n");
            appendField(json, "module", entry.module());
            json.append(",\n");
            appendField(json, "result", entry.result());
            json.append(",\n");
            appendField(json, "originalSources", entry.originalSources());
            json.append(",\n");
            appendField(json, "patchedSources", entry.patchedSources());
            json.append(",\n      \"ruleFiles\": [");
            for (int r = 0; r < entry.ruleFiles().size(); r++) {
                if (r > 0) {
                    json.append(", ");
                }
                appendString(json, entry.ruleFiles().get(r));
            }
            json.append("],\n");
            appendField(json, "stubs", entry.stubs());
            json.append(",\n");
            appendField(json, "classpathSource", entry.classpathSource());
            json.append("\n    }");
        }
        json.append(entries.isEmpty() ? "]\n" : "\n  ]\n");
        json.append("}\n");
        Files.writeString(reportPath, json.toString(), StandardCharsets.UTF_8);
    }

    private static void appendField(StringBuilder json, String name, String value) {
        json.append("      \"").append(name).append("\": ");
        if (value == null) {
            json.append("null");
        } else {
            appendString(json, value);
        }
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    private record ResolvedModule(String version, Path file) {}

    private record ReportEntry(
            String module,
            String result,
            String originalSources,
            String patchedSources,
            List<String> ruleFiles,
            String stubs,
            String classpathSource) {}
}
