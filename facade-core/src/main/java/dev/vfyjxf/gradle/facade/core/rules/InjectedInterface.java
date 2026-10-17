package dev.vfyjxf.gradle.facade.core.rules;

import java.util.List;
import java.util.Objects;

public record InjectedInterface(String rawText, String internalName, List<TypeArg> arguments, SourceLocation source) {

    public InjectedInterface {
        Objects.requireNonNull(rawText, "rawText");
        Objects.requireNonNull(internalName, "internalName");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
