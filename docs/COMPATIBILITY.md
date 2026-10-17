# FacadeGradle compatibility matrix

State as of 2026-10-17. Every cell is marked **TESTED** (exercised in this repository's build or
functional-test suite) or **EXPECTED** (reasoned from documented behavior, not yet exercised).

| Dimension | Value | Status |
| --- | --- | --- |
| Gradle — plugin built & run with | 9.8.1 (this repo's wrapper) | **TESTED** |
| Gradle — supported floor | 8.5 | **TESTED** — `./gradlew crossVersionTest` (TestKit) runs the bind → substitute → transform → compile acceptance path on Gradle 8.5, 8.14.3 and 9.8.1; all three succeed, and all three resolve a patched `library-1.0.0.jar` that differs from the repository original (one run: patched `d6aa1214…` vs original `894cd42e…`) |
| Gradle versions exercised | 8.5, 8.14.3, 9.8.1 | **TESTED** — `CrossVersionCompatibilityTest` (9 invocations): configured binding compiles against the injected interface, unconfigured binding still fails with `incompatible types`, and `patchFacadeSources` reports `PATCHED`; each build asserts the Gradle version it actually ran on |
| JVM toolchain for sources patching | Java 21 (convention in `FacadeGradlePlugin`; JST requires 21+) | **TESTED** (local JDK 21; foojay resolver configured in `settings.gradle.kts` for other machines) |
| JVM running the cross-version builds | Java 21 toolchain launcher | **TESTED** — `crossVersionTest` pins the Java 21 launcher; Gradle 8.5/8.14.3 cannot run on JDK 25, which is the JVM this repo's wrapper daemon uses |
| Gradle daemon JVM | JDK 17–25 | 25 **TESTED** locally for the plugin's own build (daemon runs on the JVM that launches `gradlew`); 21 **TESTED** for the TestKit cross-version builds; 17–24 **EXPECTED** per Gradle 9 system requirements |
| Consumer build-script DSL | Kotlin DSL and Groovy DSL | **TESTED** — functional tests exercise both (`FacadePluginFunctionalTest`: `kotlinConsumer` build scripts and the `groovyDslEquivalent` test); the cross-version suite uses Kotlin DSL |
| Consumer project Java version | any | **EXPECTED** — the binary transform runs in-process on the Gradle daemon; consumer compilation uses the consumer's own toolchain |
| Sources patcher | JST `net.neoforged.jst:jst-cli-bundle:2.0.11`, forked via toolchain launcher | **TESTED** — also smoke-tested cross-version (`crossVersionTest` runs `patchFacadeSources` on 8.5, 8.14.3 and 9.8.1 and asserts a patched `Target.java` plus a `PATCHED` report) |
| Rule format | JST interface-injection JSON (flat map `target-class` → injected interfaces) | **TESTED** — consumed both by facade-core's binary rewriter and, verbatim, by JST |

## Caveats

- **Gradle 8.x floor**: 8.5 is the tested floor; both the binary path (`compileJava` resolving the
  facade-transformed jar) and the sources path (`patchFacadeSources`) run on 8.5, 8.14.3 and 9.8.1.
  Verification is TestKit-based (`CrossVersionCompatibilityTest`, only in the `crossVersionTest` task,
  not in `check`/`build`): each version downloads its distribution on first use, and the task pins a
  Java 21 launcher because TestKit's build daemons inherit the test JVM's Java home — Gradle 8.5/8.14.3
  refuse to run on JDK 25, the JVM this repository's wrapper daemon uses. The plugin's own build still
  runs on Gradle 9.8.1. Gradle < 8.5 and 8.6–8.14.2 are **NOT** tested; the variant-aware dependency
  substitution (`substitute(...).using(variant)`), registered artifact transforms, lazy lenient
  `ArtifactView` resolution (`ArtifactCollection#getResolvedArtifacts()` providers), detached
  configurations and the JVM toolchain service used by the plugin all exist on 8.5 as tested.
- **JST needs a JVM toolchain**: `patchFacadeSources` resolves a `JavaLauncher` (convention: Java 21)
  from the toolchain service once any JVM plugin is applied, and fails with an explicit
  `GradleException` otherwise. JST runs as a forked process on that launcher, so the Gradle daemon's
  JVM version is irrelevant to JST — but a discoverable or auto-provisionable JDK 21+ is required.
- **Binary vs sources**: the binary rewrite (`FacadeTransform`) applies wherever Gradle resolves the
  bound module; both paths are tested cross-version (8.5/8.14.3/9.8.1), but the patched *sources* jars
  are still produced only by the explicit `patchFacadeSources` task and are not attached by any IDE
  automatically (see `docs/IDEA-INTEGRATION.md`).
- **Examples**: `examples/{kotlin,groovy}` are runnable consumer projects (each wires the plugin via
  composite `includeBuild("../..")`, publishes a local fixture library, and proves compile + runtime
  dispatch + `patchFacadeSources`); run `./gradlew -p examples/kotlin clean runExample` (same for
  `groovy`).
