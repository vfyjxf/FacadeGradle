package dev.vfyjxf.gradle.facade.gradle.api;

public abstract class DefaultFacadeOptions implements FacadeOptions {

    public DefaultFacadeOptions() {
        getStrictValidation().convention(true);
        getTransformSources().convention(true);
        getFailOnMissingSources().convention(false);
    }
}
