package dev.vfyjxf.gradle.facade.gradle.transform;

import dev.vfyjxf.gradle.facade.core.archive.ArchiveOptions;
import dev.vfyjxf.gradle.facade.core.archive.FacadeJarTransformer;
import dev.vfyjxf.gradle.facade.core.archive.SignedJarPolicy;
import dev.vfyjxf.gradle.facade.core.bytecode.FacadeTransformException;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@CacheableTransform
public abstract class FacadeTransform implements TransformAction<FacadeTransform.Parameters> {

    public interface Parameters extends TransformParameters {
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        ConfigurableFileCollection getRuleFiles();

        @Input
        Property<Boolean> getStrictValidation();
    }

    private static final Logger LOG = Logging.getLogger(FacadeTransform.class);

    @InputArtifact
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(TransformOutputs outputs) {
        Path input = getInputArtifact().get().getAsFile().toPath();
        List<Path> ruleFiles = new ArrayList<>();
        for (File file : getParameters().getRuleFiles()) {
            ruleFiles.add(file.toPath());
        }
        Collections.sort(ruleFiles);
        InterfaceInjectionRules rules;
        try {
            List<InterfaceInjectionRules> parsed = new ArrayList<>(ruleFiles.size());
            for (Path ruleFile : ruleFiles) {
                parsed.add(InterfaceInjectionRules.parse(ruleFile));
            }
            rules = InterfaceInjectionRules.merge(parsed.toArray(InterfaceInjectionRules[]::new));
        } catch (IOException | RuntimeException e) {
            if (getParameters().getStrictValidation().get()) {
                throw new GradleException(
                        "[facade] failed to parse rule files " + ruleFiles + " while transforming " + input
                                + ": " + e.getMessage(),
                        e);
            }
            LOG.warn(
                    "[facade] rule validation failed; strict validation is disabled and the artifact is passed"
                            + " through UNPATCHED: " + input + " (" + e.getMessage() + ")");
            passThrough(outputs, input);
            return;
        }
        if (rules.isEmpty()) {
            passThrough(outputs, input);
            return;
        }
        File output = outputs.file(input.getFileName().toString());
        try {
            FacadeJarTransformer.transformJar(
                    input,
                    output.toPath(),
                    rules,
                    ArchiveOptions.defaults().withSignedJarPolicy(SignedJarPolicy.FAIL));
        } catch (FacadeTransformException e) {
            throw new FacadeTransformException(
                    "[facade] failed to transform " + input + " with rule files " + ruleFiles + ": " + e.getMessage(),
                    e);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "[facade] failed to transform " + input + " with rule files " + ruleFiles, e);
        }
    }

    // an unchanged copy must still be registered as the output: the resolution graph must stay complete
    private static void passThrough(TransformOutputs outputs, Path input) {
        File output = outputs.file(input.getFileName().toString());
        try {
            Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("[facade] failed to copy " + input + " unchanged", e);
        }
    }
}
