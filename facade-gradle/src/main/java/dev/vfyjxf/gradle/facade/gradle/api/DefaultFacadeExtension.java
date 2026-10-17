package dev.vfyjxf.gradle.facade.gradle.api;

import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class DefaultFacadeExtension implements FacadeExtension {

    public static final String CONFIGURATIONS_KEY = "__facade_configurations";

    private final ObjectFactory objects;
    private final FacadeOptions options;
    // Bindings keyed by "group:name". Version catalog accessors hand out dependency instances whose
    // identity may differ from the instance Gradle later adds to a configuration, so extra-properties
    // storage alone cannot carry those bindings; module coordinates always can.
    private final Map<String, List<FacadeConfiguration>> moduleBindings = new ConcurrentHashMap<>();
    // Best-known version per "group:name", captured at configure() time (constraint version wins over
    // dependency version); consumed by the sources pipeline when it must resolve a module by itself.
    private final Map<String, String> moduleVersions = new ConcurrentHashMap<>();

    @Inject
    public DefaultFacadeExtension(ObjectFactory objects) {
        this.objects = objects;
        this.options = objects.newInstance(DefaultFacadeOptions.class);
    }

    public final Map<String, List<FacadeConfiguration>> getModuleBindings() {
        return moduleBindings;
    }

    public final Map<String, String> getModuleVersions() {
        return moduleVersions;
    }

    @Override
    public final FacadeOptions getOptions() {
        return options;
    }

    @Override
    public final void options(Action<? super FacadeOptions> action) {
        action.execute(options);
    }

    @Override
    public final void configure(Object dependencyOrConstraint, Action<? super FacadeConfiguration> action) {
        Object target = dependencyOrConstraint;
        while (target instanceof Provider<?> provider) {
            target = provider.get();
        }
        if (target == null) {
            throw new InvalidUserDataException(
                    "facade.configure() requires a dependency or dependency constraint, got null");
        }
        FacadeConfiguration configuration = objects.newInstance(DefaultFacadeConfiguration.class);
        action.execute(configuration);
        boolean storedOnTarget = storeOnTarget(target, configuration);
        String moduleKey = storeByModule(target, configuration);
        storeVersion(target);
        if (!storedOnTarget && moduleKey == null) {
            throw new InvalidUserDataException(
                    "facade.configure() cannot bind " + target.getClass().getName()
                            + "; pass a Dependency or DependencyConstraint (e.g. `this` inside a dependency block)");
        }
    }

    private boolean storeOnTarget(Object target, FacadeConfiguration configuration) {
        if (!(target instanceof ExtensionAware aware)) {
            return false;
        }
        ExtraPropertiesExtension extra = aware.getExtensions().getExtraProperties();
        Object existing = extra.has(CONFIGURATIONS_KEY) ? extra.get(CONFIGURATIONS_KEY) : null;
        if (existing instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) existing;
            mutable.add(configuration);
        } else {
            List<FacadeConfiguration> fresh = new ArrayList<>();
            fresh.add(configuration);
            extra.set(CONFIGURATIONS_KEY, fresh);
        }
        return true;
    }

    private String storeByModule(Object target, FacadeConfiguration configuration) {
        String moduleKey = moduleKeyOf(target);
        if (moduleKey != null) {
            moduleBindings.computeIfAbsent(moduleKey, key -> new CopyOnWriteArrayList<>()).add(configuration);
        }
        return moduleKey;
    }

    private void storeVersion(Object target) {
        String moduleKey = moduleKeyOf(target);
        String version = versionOf(target);
        if (moduleKey == null || version == null) {
            return;
        }
        if (target instanceof DependencyConstraint) {
            moduleVersions.put(moduleKey, version);
        } else {
            moduleVersions.putIfAbsent(moduleKey, version);
        }
    }

    private static String versionOf(Object target) {
        if (target instanceof ExternalModuleDependency external) {
            return external.getVersion();
        }
        if (target instanceof DependencyConstraint constraint) {
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
        return null;
    }

    public static String moduleKeyOf(Object dependencyOrConstraint) {
        if (dependencyOrConstraint instanceof ExternalModuleDependency external) {
            return external.getGroup() != null ? external.getGroup() + ":" + external.getName() : null;
        }
        if (dependencyOrConstraint instanceof DependencyConstraint constraint) {
            return constraint.getGroup() != null ? constraint.getGroup() + ":" + constraint.getName() : null;
        }
        return null;
    }
}
