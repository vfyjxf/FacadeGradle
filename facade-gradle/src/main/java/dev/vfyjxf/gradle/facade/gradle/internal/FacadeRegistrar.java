package dev.vfyjxf.gradle.facade.gradle.internal;

import dev.vfyjxf.gradle.facade.gradle.api.DefaultFacadeExtension;
import dev.vfyjxf.gradle.facade.gradle.api.FacadeConfiguration;
import dev.vfyjxf.gradle.facade.gradle.transform.FacadeTransform;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.DependencySubstitution;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class FacadeRegistrar {

    private static final String STATE_KEY = "__facade_registration_state";
    private static final String ATTRIBUTE_PREFIX = "dev.vfyjxf.facade.injected.";
    private static final Object GLOBAL_LOCK = new Object();

    /**
     * Configurations whose names start with this prefix are internal to the plugin (e.g. the sources
     * pipeline's raw resolution configurations). They resolve the ORIGINAL artifacts on purpose and
     * must never receive facade substitution.
     */
    public static final String INTERNAL_CONFIGURATION_PREFIX = "facadeInternal";

    private final Gradle gradle;
    private final DependencyHandler dependencies;
    private final ObjectFactory objects;
    private final DefaultFacadeExtension extension;
    // the attribute is shared build-wide, but transforms/schema/artifact-type defaults are per project
    private final Set<String> locallyRegistered = ConcurrentHashMap.newKeySet();

    public FacadeRegistrar(
            Gradle gradle, DependencyHandler dependencies, ObjectFactory objects, DefaultFacadeExtension extension) {
        this.gradle = gradle;
        this.dependencies = dependencies;
        this.objects = objects;
        this.extension = extension;
    }

    public void applyTo(Configuration configuration) {
        if (configuration.getName().startsWith(INTERNAL_CONFIGURATION_PREFIX)) {
            return;
        }
        List<Binding> bindings = collectBindings(configuration);
        if (bindings.isEmpty()) {
            return;
        }
        List<Attribute<Boolean>> attributes = new ArrayList<>(bindings.size());
        for (Binding binding : bindings) {
            attributes.add(attributeFor(binding));
        }
        // Each edge of the bound module must carry the injection attribute in its OWN requested
        // version: pinning one version would override transitive version requirements instead of
        // letting conflict resolution pick the winner, and Gradle rejects versionless substitution
        // targets ("Must specify version for target of dependency substitution").
        configuration.getResolutionStrategy().dependencySubstitution(subs -> {
            for (int i = 0; i < bindings.size(); i++) {
                Binding binding = bindings.get(i);
                Attribute<Boolean> attribute = attributes.get(i);
                subs.all(dep -> attachInjectionVariant(subs, dep, binding, attribute));
            }
        });
    }

    private static void attachInjectionVariant(
            DependencySubstitutions subs, DependencySubstitution dep, Binding binding, Attribute<Boolean> attribute) {
        ComponentSelector requested = dep.getRequested();
        ComponentSelector base;
        if (binding.project()) {
            if (!(requested instanceof ProjectComponentSelector project)
                    || !project.getProjectPath().equals(binding.projectPath())) {
                return;
            }
            base = requested;
        } else {
            if (!(requested instanceof ModuleComponentSelector module)
                    || !module.getGroup().equals(binding.group())
                    || !module.getModule().equals(binding.name())) {
                return;
            }
            String version = module.getVersion();
            if (version == null || version.isEmpty()) {
                version = binding.version();
            }
            if (version == null) {
                return;
            }
            base = subs.module(binding.group() + ":" + binding.name() + ":" + version);
        }
        ComponentSelector target = subs.variant(base, details -> details.attributes(
                attrs -> attrs.attribute(attribute, Boolean.TRUE)));
        dep.useTarget(target, "facade interface injection rules");
    }

    private List<Binding> collectBindings(Configuration configuration) {
        Set<Configuration> hierarchy = hierarchyOf(configuration);
        Map<String, String> observedVersions = new LinkedHashMap<>();
        for (Configuration cfg : hierarchy) {
            for (Dependency dep : cfg.getDependencies()) {
                if (dep instanceof ExternalModuleDependency external
                        && external.getGroup() != null
                        && external.getVersion() != null) {
                    mergeVersion(
                            observedVersions, external.getGroup() + ":" + external.getName(), external.getVersion());
                }
            }
        }
        Map<String, BindingBuilder> builders = new LinkedHashMap<>();
        for (Configuration cfg : hierarchy) {
            for (Dependency dep : cfg.getDependencies()) {
                Set<FacadeConfiguration> configs = configurationsFor(dep);
                if (configs.isEmpty()) {
                    continue;
                }
                if (dep instanceof ProjectDependency project) {
                    builders.computeIfAbsent(
                                    "project:" + project.getPath(),
                                    key -> new BindingBuilder(true, null, null, project.getPath()))
                            .configs.addAll(configs);
                } else if (dep instanceof ExternalModuleDependency external && external.getGroup() != null) {
                    BindingBuilder builder = builders.computeIfAbsent(
                            "module:" + external.getGroup() + ":" + external.getName(),
                            key -> new BindingBuilder(false, external.getGroup(), external.getName(), null));
                    builder.configs.addAll(configs);
                    if (external.getVersion() != null) {
                        builder.depVersion = external.getVersion();
                    }
                }
            }
            for (DependencyConstraint constraint : cfg.getDependencyConstraints()) {
                Set<FacadeConfiguration> configs = configurationsFor(constraint);
                if (configs.isEmpty() || constraint.getGroup() == null) {
                    continue;
                }
                BindingBuilder builder = builders.computeIfAbsent(
                        "module:" + constraint.getGroup() + ":" + constraint.getName(),
                        key -> new BindingBuilder(false, constraint.getGroup(), constraint.getName(), null));
                builder.configs.addAll(configs);
                String version = constraintVersion(constraint);
                if (version != null) {
                    builder.constraintVersion = version;
                }
            }
        }
        List<Binding> bindings = new ArrayList<>();
        for (Map.Entry<String, BindingBuilder> entry : builders.entrySet()) {
            BindingBuilder builder = entry.getValue();
            Set<Path> ruleFiles = new TreeSet<>();
            boolean anyEnabled = false;
            for (FacadeConfiguration config : builder.configs) {
                if (!Boolean.TRUE.equals(config.getEnabled().get())) {
                    continue;
                }
                anyEnabled = true;
                org.gradle.api.file.RegularFile single = config.getConfig().getOrNull();
                if (single != null) {
                    ruleFiles.add(single.getAsFile().toPath().toAbsolutePath());
                }
                for (java.io.File file : config.getConfigs()) {
                    ruleFiles.add(file.toPath().toAbsolutePath());
                }
            }
            // an enabled binding with no rule files has nothing to inject: skip it entirely
            if (!anyEnabled || ruleFiles.isEmpty()) {
                continue;
            }
            boolean strict = Boolean.TRUE.equals(extension.getOptions().getStrictValidation().get());
            String version = builder.constraintVersion != null
                    ? builder.constraintVersion
                    : (builder.depVersion != null
                            ? builder.depVersion
                            : observedVersions.get(entry.getKey().substring("module:".length())));
            bindings.add(new Binding(
                    builder.project, builder.group, builder.name, builder.projectPath, version,
                    List.copyOf(ruleFiles), strict));
        }
        return bindings;
    }

    private Set<FacadeConfiguration> configurationsFor(Object dependencyOrConstraint) {
        Set<FacadeConfiguration> result = Collections.newSetFromMap(new IdentityHashMap<>());
        if (dependencyOrConstraint instanceof ExtensionAware aware) {
            ExtraPropertiesExtension extra = aware.getExtensions().getExtraProperties();
            if (extra.has(DefaultFacadeExtension.CONFIGURATIONS_KEY)) {
                Object stored = extra.get(DefaultFacadeExtension.CONFIGURATIONS_KEY);
                if (stored instanceof List<?> list) {
                    for (Object element : list) {
                        if (element instanceof FacadeConfiguration configuration) {
                            result.add(configuration);
                        }
                    }
                }
            }
        }
        // Bindings attached to this very declaration are authoritative: the same module bound in
        // another configuration must not leak its rule files here. Module-key bindings only serve
        // declarations whose instance Gradle did not carry the attachment to (version catalogs).
        if (!result.isEmpty()) {
            return result;
        }
        String moduleKey = DefaultFacadeExtension.moduleKeyOf(dependencyOrConstraint);
        if (moduleKey != null) {
            List<FacadeConfiguration> byModule = extension.getModuleBindings().get(moduleKey);
            if (byModule != null) {
                result.addAll(byModule);
            }
        }
        return result;
    }

    private Attribute<Boolean> attributeFor(Binding binding) {
        String key = binding.registrationKey();
        RegistrationState state = registrationState();
        Attribute<Boolean> attribute = state.index.get(key);
        if (attribute == null) {
            synchronized (GLOBAL_LOCK) {
                attribute = state.index.get(key);
                if (attribute == null) {
                    attribute = Attribute.of(ATTRIBUTE_PREFIX + state.counter.incrementAndGet(), Boolean.class);
                    state.index.put(key, attribute);
                }
            }
        }
        if (locallyRegistered.add(key)) {
            registerTransform(binding, attribute);
        }
        return attribute;
    }

    private void registerTransform(Binding binding, Attribute<Boolean> attribute) {
        dependencies.getAttributesSchema().attribute(attribute);
        dependencies.getArtifactTypes().named(ArtifactTypeDefinition.JAR_TYPE, type ->
                type.getAttributes().attribute(attribute, Boolean.FALSE));
        dependencies.registerTransform(FacadeTransform.class, spec -> {
            configureEndpoint(spec.getFrom(), attribute, Boolean.FALSE);
            configureEndpoint(spec.getTo(), attribute, Boolean.TRUE);
            spec.getParameters().getRuleFiles().from(binding.ruleFiles().toArray(Path[]::new));
            spec.getParameters().getStrictValidation().set(binding.strictValidation());
        });
    }

    private void configureEndpoint(AttributeContainer container, Attribute<Boolean> attribute, Boolean value) {
        container.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE);
        container.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
        container.attribute(attribute, value);
    }

    private RegistrationState registrationState() {
        ExtraPropertiesExtension extra = gradle.getExtensions().getExtraProperties();
        synchronized (GLOBAL_LOCK) {
            if (extra.has(STATE_KEY)) {
                return (RegistrationState) extra.get(STATE_KEY);
            }
            RegistrationState state = new RegistrationState();
            extra.set(STATE_KEY, state);
            return state;
        }
    }

    private static void mergeVersion(Map<String, String> versions, String module, String version) {
        versions.merge(module, version, (a, b) -> a.compareTo(b) >= 0 ? a : b);
    }

    private static String constraintVersion(DependencyConstraint constraint) {
        VersionConstraint versionConstraint = constraint.getVersionConstraint();
        if (versionConstraint == null) {
            return null;
        }
        String preferred = versionConstraint.getPreferredVersion();
        if (!preferred.isEmpty()) {
            return preferred;
        }
        String required = versionConstraint.getRequiredVersion();
        return required.isEmpty() ? null : required;
    }

    private static Set<Configuration> hierarchyOf(Configuration configuration) {
        Set<Configuration> seen = new LinkedHashSet<>();
        collectHierarchy(configuration, seen);
        return seen;
    }

    private static void collectHierarchy(Configuration configuration, Set<Configuration> seen) {
        if (!seen.add(configuration)) {
            return;
        }
        for (Configuration parent : configuration.getExtendsFrom()) {
            collectHierarchy(parent, seen);
        }
    }

    private static final class RegistrationState {
        final AtomicInteger counter = new AtomicInteger();
        final Map<String, Attribute<Boolean>> index = new ConcurrentHashMap<>();
    }

    private static final class BindingBuilder {
        final boolean project;
        final String group;
        final String name;
        final String projectPath;
        final Set<FacadeConfiguration> configs = Collections.newSetFromMap(new IdentityHashMap<>());
        String depVersion;
        String constraintVersion;

        BindingBuilder(boolean project, String group, String name, String projectPath) {
            this.project = project;
            this.group = group;
            this.name = name;
            this.projectPath = projectPath;
        }
    }

    private record Binding(
            boolean project,
            String group,
            String name,
            String projectPath,
            String version,
            List<Path> ruleFiles,
            boolean strictValidation) {

        String registrationKey() {
            StringBuilder key = new StringBuilder(strictValidation ? "strict|" : "lenient|");
            for (Path ruleFile : ruleFiles) {
                key.append(ruleFile).append('|');
            }
            return key.toString();
        }
    }
}
