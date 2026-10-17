package dev.vfyjxf.gradle.facade.gradle.api;

public abstract class DefaultFacadeConfiguration implements FacadeConfiguration {

    public DefaultFacadeConfiguration() {
        getEnabled().convention(true);
    }
}
