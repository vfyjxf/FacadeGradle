package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.TypeReference;

import java.util.List;
import java.util.Objects;

/**
 * A formal type parameter of a class signature. {@code classBound} is null exactly when
 * the source signature declared an empty class bound ({@code T::Ljava/lang/Comparable;});
 * round-tripping preserves that emptiness.
 */
public record TypeParameter(String name, TypeReference classBound, List<TypeReference> interfaceBounds) {

    public TypeParameter {
        Objects.requireNonNull(name, "name");
        interfaceBounds = List.copyOf(interfaceBounds);
    }
}
