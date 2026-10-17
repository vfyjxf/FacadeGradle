package dev.vfyjxf.gradle.facade.gradle.api;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public interface FacadeConfiguration {

    RegularFileProperty getConfig();

    ConfigurableFileCollection getConfigs();

    Property<Boolean> getEnabled();
}
