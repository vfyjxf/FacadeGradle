package dev.vfyjxf.gradle.facade.core.rules;

import java.util.List;
import java.util.Objects;

public record Suffix(String simpleName, List<TypeArg> arguments) {

    public Suffix {
        Objects.requireNonNull(simpleName, "simpleName");
        arguments = List.copyOf(arguments);
    }
}
