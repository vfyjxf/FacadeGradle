package dev.vfyjxf.gradle.facade.core.rules;

import java.util.Objects;

public record WildcardTypeArg(WildcardKind kind, TypeArg bound) implements TypeArg {

    public WildcardTypeArg {
        Objects.requireNonNull(kind, "kind");
        if (kind == WildcardKind.UNBOUNDED) {
            if (bound != null) {
                throw new IllegalArgumentException("unbounded wildcard must not carry a bound");
            }
        } else if (bound == null) {
            throw new IllegalArgumentException(kind + " wildcard requires a bound");
        }
    }
}
