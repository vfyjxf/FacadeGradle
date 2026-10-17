package dev.vfyjxf.gradle.facade.core.archive;

import java.util.List;

public record JarTransformResult(
        int totalEntries, int transformedClasses, List<String> matchedTargets, boolean signaturesStripped) {

    public JarTransformResult {
        matchedTargets = List.copyOf(matchedTargets);
    }
}
