package dev.vfyjxf.gradle.facade.gradle;

import dev.vfyjxf.gradle.facade.gradle.api.DefaultFacadeExtension;
import dev.vfyjxf.gradle.facade.gradle.api.FacadeConfiguration;
import dev.vfyjxf.gradle.facade.gradle.api.FacadeExtension;
import dev.vfyjxf.gradle.facade.gradle.internal.FacadeRegistrar;
import dev.vfyjxf.gradle.facade.gradle.source.FacadeSourcesTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class FacadeGradlePlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "facade";
    public static final String SOURCES_TASK_NAME = "patchFacadeSources";
    public static final String JST_NOTATION = "net.neoforged.jst:jst-cli-bundle:2.0.11";

    @Override
    public void apply(Project project) {
        DefaultFacadeExtension extension = project.getObjects().newInstance(DefaultFacadeExtension.class);
        project.getExtensions().add(FacadeExtension.class, EXTENSION_NAME, extension);
        FacadeRegistrar registrar = new FacadeRegistrar(
                project.getGradle(), project.getDependencies(), project.getObjects(), extension);
        project.getConfigurations()
                .configureEach(configuration ->
                        configuration.withDependencies(dependencies -> registrar.applyTo(configuration)));

        registerSourcesPipeline(project, extension);
    }

    private void registerSourcesPipeline(Project project, DefaultFacadeExtension extension) {
        // JST itself: a detached configuration keeps the tool out of the user's dependency graph;
        // its single file is resolved lazily when the sources task runs, from the project repositories.
        Configuration jstBundle = project.getConfigurations()
                .detachedConfiguration(project.getDependencies().create(JST_NOTATION));

        // Original sources jars of every bound module, resolved leniently at execution time so that
        // modules without a -sources jar can be diagnosed instead of failing the build. Created via
        // create(): the role-locked resolvable() factory forbids declaring dependencies, and the
        // modules are only known lazily inside withDependencies.
        Configuration sourcesRaw = project.getConfigurations()
                .create(FacadeRegistrar.INTERNAL_CONFIGURATION_PREFIX + "Sources", configuration -> {
                    configuration.setDescription("Original sources jars of facade-bound modules (facade plugin)");
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    documentationSourcesAttributes(configuration.getAttributes(), project);
                    configuration.withDependencies(
                            dependencies -> addBoundModules(dependencies, extension, project.getDependencies()));
                });

        // Original binary jars of every bound module; the sources task patches them in-task with
        // facade-core to build the JST classpath.
        Configuration binariesRaw = project.getConfigurations()
                .create(FacadeRegistrar.INTERNAL_CONFIGURATION_PREFIX + "Binaries", configuration -> {
                    configuration.setDescription("Original binary jars of facade-bound modules (facade plugin)");
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.getAttributes()
                            .attribute(
                                    Usage.USAGE_ATTRIBUTE,
                                    project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME))
                            .attribute(
                                    Category.CATEGORY_ATTRIBUTE,
                                    project.getObjects().named(Category.class, Category.LIBRARY))
                            .attribute(
                                    Bundling.BUNDLING_ATTRIBUTE,
                                    project.getObjects().named(Bundling.class, Bundling.EXTERNAL))
                            .attribute(
                                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                                    ArtifactTypeDefinition.JAR_TYPE);
                    configuration.withDependencies(
                            dependencies -> addBoundModules(dependencies, extension, project.getDependencies()));
                });

        Provider<List<ModuleRuleBinding>> bindings = project.provider(() -> sourceBindings(extension));
        Provider<Map<String, List<String>>> moduleRules = project.provider(
                () -> moduleRulesOf(bindings.get()));
        Provider<List<String>> ruleFiles = project.provider(() -> {
            TreeSet<String> distinct = new TreeSet<>();
            for (List<String> paths : moduleRulesOf(bindings.get()).values()) {
                distinct.addAll(paths);
            }
            return new ArrayList<>(distinct);
        });

        Provider<List<ResolvedArtifactResult>> sourcesArtifacts = resolvedArtifactsOf(sourcesRaw);
        Provider<List<ResolvedArtifactResult>> binaryArtifacts = resolvedArtifactsOf(binariesRaw);

        TaskProvider<FacadeSourcesTask> sourcesTask = project.getTasks()
                .register(SOURCES_TASK_NAME, FacadeSourcesTask.class, task -> {
                    task.setGroup("facade");
                    task.setDescription(
                            "Produces JST-patched sources jars for facade-bound modules"
                                    + " (build/facade/sources + sources-report.json)");
                    task.getJstBundle().from(jstBundle);
                    task.getSourcesJars().from(sourcesArtifacts.map(FacadeGradlePlugin::filesOf));
                    task.getBinaryJars().from(binaryArtifacts.map(FacadeGradlePlugin::filesOf));
                    task.getSourcesArtifacts().set(sourcesArtifacts);
                    task.getBinaryArtifacts().set(binaryArtifacts);
                    task.getRuleFiles().from(ruleFiles);
                    task.getModuleRules().set(moduleRules);
                    task.getFailOnMissingSources().set(extension.getOptions().getFailOnMissingSources());
                    task.getStrictValidation().set(extension.getOptions().getStrictValidation());
                    task.getTransformSources().set(extension.getOptions().getTransformSources());
                    task.getOutputDirectory()
                            .set(project.getLayout().getBuildDirectory().dir("facade/sources"));
                    task.getReportFile()
                            .set(project.getLayout().getBuildDirectory().file("facade/sources-report.json"));
                    task.onlyIf(
                            "facade.options.transformSources is disabled",
                            candidate -> ((FacadeSourcesTask) candidate).getTransformSources().get());
                });

        // JST needs a Java 21+ runtime; take it from the toolchain service (present once any JVM
        // plugin is applied) without forcing a particular plugin onto the project.
        project.getPlugins().withType(JavaBasePlugin.class, javaPlugin -> {
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            sourcesTask.configure(task -> task.getJavaLauncher()
                    .convention(toolchains.launcherFor(
                            spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(21)))));
        });
    }

    private static void documentationSourcesAttributes(AttributeContainer attributes, Project project) {
        attributes.attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.getObjects().named(Category.class, Category.DOCUMENTATION))
                .attribute(DocsType.DOCS_TYPE_ATTRIBUTE, project.getObjects().named(DocsType.class, DocsType.SOURCES))
                .attribute(
                        Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.EXTERNAL))
                .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE);
    }

    private static Provider<List<ResolvedArtifactResult>> resolvedArtifactsOf(Configuration configuration) {
        ArtifactView view = configuration.getIncoming().artifactView(candidate -> candidate.lenient(true));
        // The lazy provider from ArtifactCollection#getResolvedArtifacts keeps resolution at execution
        // time and stays configuration-cache compatible; ids map artifacts back to module coordinates.
        return view.getArtifacts().getResolvedArtifacts().map(ArrayList::new);
    }

    /**
     * Adds one dependency per bound module to the internal configuration. Called from
     * {@code withDependencies}, i.e. lazily at resolution time; captures only the extension and the
     * dependency handler (both safe to serialize with the configuration cache), never the project.
     */
    private static void addBoundModules(
            DependencySet dependencies, DefaultFacadeExtension extension, DependencyHandler dependencyHandler) {
        for (ModuleRuleBinding binding : sourceBindings(extension)) {
            dependencies.add(dependencyHandler.create(binding.notation()));
        }
    }

    private static List<File> filesOf(List<ResolvedArtifactResult> artifacts) {
        List<File> files = new ArrayList<>(artifacts.size());
        for (ResolvedArtifactResult artifact : artifacts) {
            files.add(artifact.getFile());
        }
        return files;
    }

    /**
     * Enumerates the modules bound in this project (via facade.configure on external module
     * dependencies/constraints) with their rule files and best-known version, mirroring the
     * registrar's binding collection: enabled configurations only, rule files absolute and sorted,
     * bindings without rule files dropped.
     */
    private static List<ModuleRuleBinding> sourceBindings(DefaultFacadeExtension extension) {
        List<ModuleRuleBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, List<FacadeConfiguration>> entry : extension.getModuleBindings().entrySet()) {
            String key = entry.getKey();
            int separator = key.indexOf(':');
            String group = key.substring(0, separator);
            String name = key.substring(separator + 1);
            TreeSet<String> ruleFiles = new TreeSet<>();
            boolean anyEnabled = false;
            for (FacadeConfiguration configuration : entry.getValue()) {
                if (!Boolean.TRUE.equals(configuration.getEnabled().get())) {
                    continue;
                }
                anyEnabled = true;
                RegularFile single = configuration.getConfig().getOrNull();
                if (single != null) {
                    ruleFiles.add(single.getAsFile().getAbsolutePath());
                }
                for (File file : configuration.getConfigs()) {
                    ruleFiles.add(file.getAbsolutePath());
                }
            }
            if (!anyEnabled || ruleFiles.isEmpty()) {
                continue;
            }
            bindings.add(new ModuleRuleBinding(group, name, extension.getModuleVersions().get(key), ruleFiles));
        }
        bindings.sort((left, right) -> (left.group() + ":" + left.name()).compareTo(right.group() + ":" + right.name()));
        return bindings;
    }

    private static Map<String, List<String>> moduleRulesOf(List<ModuleRuleBinding> bindings) {
        Map<String, List<String>> moduleRules = new LinkedHashMap<>();
        for (ModuleRuleBinding binding : bindings) {
            moduleRules.put(binding.ruleKey(), new ArrayList<>(binding.ruleFiles()));
        }
        return moduleRules;
    }

    /**
     * A facade binding relevant to the sources pipeline: module coordinates, best-known (possibly
     * null) version, and the module's sorted absolute rule file paths.
     */
    private record ModuleRuleBinding(String group, String name, String version, TreeSet<String> ruleFiles) {

        String notation() {
            return version == null ? group + ":" + name : group + ":" + name + ":" + version;
        }

        String ruleKey() {
            return group + ":" + name + ":" + (version == null ? "" : version);
        }
    }
}
