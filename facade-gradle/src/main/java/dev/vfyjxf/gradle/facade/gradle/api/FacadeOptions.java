package dev.vfyjxf.gradle.facade.gradle.api;

import org.gradle.api.provider.Property;

public interface FacadeOptions {

    Property<Boolean> getStrictValidation();

    Property<Boolean> getTransformSources();

    Property<Boolean> getFailOnMissingSources();
}
