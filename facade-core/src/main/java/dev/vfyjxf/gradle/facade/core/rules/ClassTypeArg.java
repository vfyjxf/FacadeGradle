package dev.vfyjxf.gradle.facade.core.rules;

import java.util.List;
import java.util.Objects;

public record ClassTypeArg(String internalName, List<TypeArg> arguments, List<Suffix> suffixes)
        implements TypeArg, TypeReference {

    public ClassTypeArg {
        Objects.requireNonNull(internalName, "internalName");
        arguments = List.copyOf(arguments);
        suffixes = List.copyOf(suffixes);
    }
}
