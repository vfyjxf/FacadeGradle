package dev.vfyjxf.gradle.facade.core.rules;

public record ArrayTypeArg(int dimensions, TypeArg component) implements TypeArg, TypeReference {

    public ArrayTypeArg {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (!(component instanceof ClassTypeArg) && !(component instanceof TypeVariableArg)) {
            throw new IllegalArgumentException("array component must be a class type or a type variable");
        }
    }
}
