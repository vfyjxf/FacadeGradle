package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.TypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ClassSignatureModel(
        List<TypeParameter> typeParameters, TypeReference superClass, List<TypeReference> superInterfaces) {

    public ClassSignatureModel {
        typeParameters = List.copyOf(typeParameters);
        Objects.requireNonNull(superClass, "superClass");
        superInterfaces = List.copyOf(superInterfaces);
    }

    public ClassSignatureModel withAppendedInterfaces(List<TypeReference> refs) {
        Objects.requireNonNull(refs, "refs");
        List<TypeReference> combined = new ArrayList<>(superInterfaces);
        combined.addAll(refs);
        return new ClassSignatureModel(typeParameters, superClass, combined);
    }

    public Set<String> declaredTypeParameterNames() {
        Set<String> names = new LinkedHashSet<>();
        for (TypeParameter parameter : typeParameters) {
            names.add(parameter.name());
        }
        return Collections.unmodifiableSet(names);
    }
}
