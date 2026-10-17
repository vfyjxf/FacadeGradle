package dev.vfyjxf.gradle.facade.core.rules;

import java.util.Objects;

public record SourceLocation(String file, int line) {

    public SourceLocation {
        Objects.requireNonNull(file, "file");
    }

    @Override
    public String toString() {
        return line >= 0 ? file + ":" + line : file;
    }
}
