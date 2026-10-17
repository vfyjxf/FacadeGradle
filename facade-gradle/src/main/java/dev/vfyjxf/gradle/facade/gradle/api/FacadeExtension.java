package dev.vfyjxf.gradle.facade.gradle.api;

import org.gradle.api.Action;

public interface FacadeExtension {

    void configure(Object dependencyOrConstraint, Action<? super FacadeConfiguration> action);

    default void configure(Object dependencyOrConstraint) {
        configure(dependencyOrConstraint, configuration -> {});
    }

    FacadeOptions getOptions();

    void options(Action<? super FacadeOptions> action);
}
