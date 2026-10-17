package dev.vfyjxf.gradle.facade.core.rules;

import java.util.Objects;

public record TypeVariableArg(String name) implements TypeArg, TypeReference {

    public TypeVariableArg {
        Objects.requireNonNull(name, "name");
    }
}
