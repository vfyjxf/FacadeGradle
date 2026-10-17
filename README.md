# FacadeGradle

Interface injection for Gradle dependencies: declare, in a small JSON rule file, which interfaces
a third-party library's classes should implement — and FacadeGradle rewrites the library's
bytecode on your compile and runtime classpaths to make it so, without touching the original
artifact. A matching `patchFacadeSources` task produces patched **sources** jars so the code you
read in your IDE shows the injected interfaces.

    MyInterface t = new Target();   // compiles & runs — Target never declared `implements MyInterface`

Inspired by the interface-injection mechanics of NeoForged's
[ModifiedBeta](https://github.com/MinecraftForge/ModifiedBeta)-era tooling and
[JST](https://github.com/neoforged/JST) (both referenced for design only; no code is derived from
either — see [License](#license)).

## What it is

A Gradle plugin (`dev.vfyjxf.facade`) plus a small core library (`facade-core`) that:

- **Injects interfaces into dependency jars at resolution time.** Bound modules (an external
  module dependency or a dependency constraint you call `facade.configure(...)` on) are
  substituted with a variant carrying a facade attribute; an artifact transform then rewrites the
  jar with [ASM](https://asm.ow2.io/) so each target class gains the interfaces named in your
  rule file. Unbound dependencies and untargeted classes/entries are left byte-for-byte intact.
- **Produces matching patched sources.** The `patchFacadeSources` task runs
  [JST](https://github.com/neoforged/JST) (`net.neoforged.jst:jst-cli-bundle:2.0.11`) over the
  original sources jars and writes, per bound module,
  `$buildDir/facade/sources/<group>/<name>/<name>-<version>-sources.jar` plus a machine-readable
  `$buildDir/facade/sources-report.json`.

## How it works

1. **Binding.** `facade.configure(dependencyOrConstraint) { config.set(...); ... }` (or
   `configs.from(...)` for several rule files) attaches rule files to a `group:name` module.
   Bindings work from any configuration — `implementation`, `api`, `compileOnly`,
   `runtimeOnly`, custom configurations, version-catalog accessors, or dependency constraints
   (a constraint binding injects without adding a graph node).
2. **Routing.** Each bound edge is substituted with a variant of the same module carrying a
   boolean attribute (`dev.vfyjxf.facade.injected.<n>`) computed from the module's merged
   rule set, so different configurations of the same build can apply different rules to the
   same module. Per-edge substitution in the edge's own requested version means Gradle's normal
   conflict resolution still picks the version winner — and the resolved winner is what gets
   patched.
3. **Binary transform.** A registered `ArtifactTransform` routes jars with the attribute to
   `facade-core`'s `FacadeJarTransformer`, which injects `implements` entries (and merges
   generic signatures so parameterized interfaces stay consistent) and writes a new jar to the
   transform cache. The original repository artifact is never modified.
4. **Sources.** `patchFacadeSources` (task group `facade`) resolves the *original* sources and
   binary jars of all bound modules, patches the binaries in-memory with facade-core to build
   JST's classpath, forks JST on a Java 21+ toolchain launcher, and reports per module:
   `PATCHED`, `MISSING_SOURCES` (fails the build only when `facade.options.failOnMissingSources`
   is true) or `SKIPPED` (invalid rules with `strictValidation` off).

## Quickstart

The plugin is `dev.vfyjxf.facade` (`dev.vfyjxf.gradle:facade-gradle`, version `0.1.0`).
Three ways to consume it — pick one:

**A. From the Gradle Plugin Portal (recommended).** Published as version `0.1.0`;
`gradlePluginPortal()` is in `pluginManagement` by default:

```kotlin
// build.gradle.kts
plugins {
    java
    id("dev.vfyjxf.facade") version "0.1.0"
}
```

The rest of the `build.gradle.kts` is identical to the mavenLocal variant below.

**B. From a locally published artifact (mavenLocal).** Publish once from this repository (see
[Publishing](#publishing)):

```console
$ ./gradlew publishToMavenLocal
```

then in your build:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    java
    id("dev.vfyjxf.facade") version "0.1.0"
}

dependencies {
    implementation("com.example:example-library:1.0.0") {
        facade.configure(this) {
            config.set(layout.projectDirectory.file("facade.json"))
        }
    }
}
```

**C. From a checkout of this repository (composite build, nothing published).**

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("/path/to/FacadeGradle")
}
```

and the same `build.gradle.kts` as above but with `id("dev.vfyjxf.facade")` — no version.

A `facade.json` naming targets and the interfaces to inject:

```json
{
  "com/example/lib/Target": ["com/example/lib/api/MyInterface"],
  "com/example/lib/GenTarget": ["java/util/function/Supplier<T>"]
}
```

Patched sources for IDE consumption:

```console
$ ./gradlew patchFacadeSources
```

Fully worked, runnable versions of the above live in [examples/](examples/) (Kotlin DSL and
Groovy DSL), each with a one-command `runExample`.

## Rule JSON format

The rule file is the JST interface-injection format: a flat JSON object mapping each target
class's internal name (slash-separated) to the list of interfaces it should implement:

```json
{
  "com/example/lib/Target": ["com/example/lib/api/MyInterface"],
  "com/example/lib/GenTarget": ["java/util/function/Supplier<T>"]
}
```

- Interfaces may carry generics: `java/util/function/Supplier<T>` binds the target's own type
  parameter `T`; `? extends X` / `? super X` wildcards are supported. A type variable used in an
  interface must be declared by the target class (`GenTarget<T>` may receive `Supplier<T>`, a
  non-generic class may not).
- **No array type arguments** (`Supplier<String[]>` is rejected) and **no dotted FQNs inside
  generics** (`java.util.function.Supplier<java.lang.String>` — use the slashed form
  `java/util/function/Supplier<java/lang/String>` instead).
- Rule files bound to the same module (via `config` + `configs.from(...)`) are **merged**;
  editing any rule file invalidates transform and task caches.
- Validation happens up front: malformed JSON fails the build with the file and line; rules
  referencing unknown type variables fail with the rule location; interfaces that do not exist
  on the transformed classpath surface as `NoClassDefFoundError` at compile/run time. With
  `facade.options.strictValidation.set(false)` a module whose rules fail validation is passed
  through unpatched (logged `UNPATCHED`) instead of failing the build.
- Version semantics: binding attaches to `group:name`; whatever version conflict resolution
  selects is the jar that gets patched. Rules bind per module per configuration — the same
  module in different configurations can use different rule files (first binding wins where
  merged within one configuration).

## DSL reference

```kotlin
facade {
    options {
        strictValidation.set(true)
        transformSources.set(true)
        failOnMissingSources.set(false)
    }
}

dependencies {
    implementation("com.example:lib:1.0.0") {
        facade.configure(this) {
            config.set(layout.projectDirectory.file("facade.json"))
            configs.from("facade-extra.json")
            enabled.set(true)
        }
    }
}
```

| Element | Type | Default | Meaning |
| --- | --- | --- | --- |
| `facade.configure(dep-or-constraint) { ... }` | — | — | Bind a module dependency or dependency constraint (works from any configuration, including version-catalog accessors) |
| `config` | `RegularFileProperty` | — | The rule file for the binding |
| `configs` | `ConfigurableFileCollection` | empty | Additional rule files, merged with `config` |
| `enabled` | `Property<Boolean>` | `true` | Toggle the binding |
| `facade.options.strictValidation` | `Property<Boolean>` | `true` | Fail the build on invalid rules; when `false`, pass the module through unpatched (logged `UNPATCHED`) |
| `facade.options.transformSources` | `Property<Boolean>` | `true` | Master switch for the `patchFacadeSources` task (`onlyIf`) |
| `facade.options.failOnMissingSources` | `Property<Boolean>` | `false` | Fail `patchFacadeSources` when a bound module has no sources jar (default: report `MISSING_SOURCES` and continue) |

Groovy DSL: `facade.configure(it) { config.set(layout.projectDirectory.file('facade.json')) }`.

Task: `patchFacadeSources` (group `facade`) → `$buildDir/facade/sources/<group>/<name>/<name>-<version>-sources.jar`,
`...-stubs.zip`, and `$buildDir/facade/sources-report.json` (per-module `result`, original/patched
sources paths, rule files, stubs).

## Runtime caveat: erasure must match

Interface injection changes assignability, not method bodies. A call through an injected
interface dispatches to the target class's method **only if the erased descriptors match**;
otherwise you get `AbstractMethodError` at runtime. `GenTarget<T>` with `T get()`
(erasure `()Ljava/lang/Object;`) can safely receive `java/util/function/Supplier<T>` because
`Supplier.get()` has the same erasure — but injecting `Supplier<String>` into a class whose
`get()` returns `String` would *erase differently* (`()Ljava/lang/String;` vs
`()Ljava/lang/Object;`) and fail at runtime. The `GenTarget` example in
[examples/](examples/) demonstrates the working case.

## Signed jars

FacadeGradle's Gradle layer **fails by design** when a bound module's jar is signed: rewriting
the jar would invalidate signatures silently. The build fails with a clear message naming the
jar. `facade-core`'s `ArchiveOptions` also exposes a `STRIP` policy (strip signature entries and
manifest digests) for API users that accept the trade-off, but the Gradle plugin does not expose
it — patches to signed artifacts are never applied quietly.

## Sources & IDE status

The Gradle side is complete: `patchFacadeSources` produces deterministic patched sources jars
and a `sources-report.json` mapping every bound module to its patched jar. Automatic attachment
in IntelliJ IDEA is **not** implemented: depending on the IDEA version, the editor either shows
the original sources or none; the current workaround is to run the task and attach the patched
jar manually via the report (Library → Attach Sources). Full status, per-version matrix, and the
design for a companion IDEA plugin: [docs/IDEA-INTEGRATION.md](docs/IDEA-INTEGRATION.md); tested
configurations: [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Known limitations

- **JST rule grammar limits** — no array type arguments; no dotted FQNs inside generics (use
  slashed names).
- **Patched classes lose non-standard class-file attributes** — the rewrite (ASM, class files
  re-serialized) does not preserve vendor/annotation-independent custom attributes.
- **`gson` and `asm` are bundled, relocated, inside the plugin jar** — the published plugin
  carries `dev.vfyjxf.facade.shaded.asm` / `dev.vfyjxf.facade.shaded.gson`, so it cannot conflict
  with a consumer's own buildscript classpath, but the embedded copies are invisible to the
  consumer's build (no shared `org.objectweb.asm`).
- **One ruleset per module per configuration** — the same module in a single configuration with
  multiple *different* rule sets is not supported; rules bind per module per configuration and
  the first binding wins where merged.
- **Signed jars fail** (by design, see above); no Gradle-level opt-out.
- **`facade-core` is a library, not a Portal artifact** — the Plugin Portal hosts plugin artifacts
  only, so only the self-contained plugin goes there (see [Publishing](#publishing)); consume
  `facade-core` directly via `publishToMavenLocal`, a repository you configure, or a composite
  build.

## Development

```console
$ ./gradlew build            # compiles + all tests (116 facade-core + 29 facade-gradle)
$ ./gradlew crossVersionTest # TestKit cross-Gradle-version suite (8.5 / 8.14.3 / 9.8.1, Java 21 launcher; downloads each distribution on first run)
$ ./gradlew -p examples/kotlin runExample
$ ./gradlew -p examples/groovy runExample
$ ./gradlew publishToMavenLocal
$ ./gradlew :facade-gradle:publishPlugins   # needs GRADLE_PUBLISH_KEY/GRADLE_PUBLISH_SECRET
```

| Module | Contents |
| --- | --- |
| `facade-core` | Rule model + JSON parsing, ASM `InterfaceInjectingClassTransformer`, jar transformer with signature merging and signed-jar policies |
| `facade-gradle` | The `dev.vfyjxf.facade` plugin: DSL, per-edge variant substitution, artifact transform, `patchFacadeSources` (JST) pipeline |
| `examples/` | Runnable Kotlin-DSL and Groovy-DSL consumer examples (separate builds, not part of the root build) |
| `docs/` | Compatibility matrix, IDEA integration status |

Requires a JDK to run Gradle (developed against Gradle 9.8.1); modules build with a Java 21
toolchain (foojay auto-provisioning configured).

### Publishing

#### What is published

| Coordinates | Artifacts | Where |
| --- | --- | --- |
| `dev.vfyjxf.gradle:facade-gradle` | self-contained plugin jar (facade-core + ASM + gson, relocated), sources, javadoc | Plugin Portal, mavenLocal, any Maven repo |
| `dev.vfyjxf.facade:dev.vfyjxf.facade.gradle.plugin` (marker) | POM only (depends on `dev.vfyjxf.gradle:facade-gradle`) | Plugin Portal, mavenLocal, any Maven repo |
| `dev.vfyjxf.gradle:facade-core` | library jar | mavenLocal / a repository you configure (not the Portal) |

The Portal only hosts **plugin** artifacts, so the plugin may not declare a dependency on a
library that lives nowhere public. `facade-gradle` therefore publishes a **single self-contained
jar**: `implementation(project(":facade-core"))` is bundled by the Shadow plugin, with ASM
relocated to `dev.vfyjxf.facade.shaded.asm` and gson to `dev.vfyjxf.facade.shaded.gson` (our own
`dev.vfyjxf.gradle.facade.*` is untouched), and the published POM declares no dependencies. The
plugin registers only `implementation` (not `api`) for facade-core, and `com.gradle.plugin-publish`
additionally enables the java plugin's sources/javadoc jars.

#### Local (`publishToMavenLocal`)

```console
$ ./gradlew publishToMavenLocal
```

installs `dev.vfyjxf.gradle:facade-core`, `dev.vfyjxf.gradle:facade-gradle` (the self-contained
plugin jar, plus sources and javadoc jars) and the marker
`dev.vfyjxf.facade:dev.vfyjxf.facade.gradle.plugin`, all `0.1.0`, into `~/.m2/repository`.
Consumers then resolve the plugin from `mavenLocal()`, as in Quickstart A:

```kotlin
// consumer settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
    }
}
```

To refresh the local repository after a change, re-run `publishToMavenLocal` (add `--rerun-tasks`
if a task is considered up-to-date but the artifacts look stale).

#### GitHub

Published at **https://github.com/vfyjxf/FacadeGradle** (public, Apache-2.0). The publishing
metadata (`repositoryUrl` in `facade-gradle/build.gradle.kts`, POM `url`/`scm`) points there —
adjust both if the repository moves. No CI workflow is included yet (the first push used a `gh`
token without the `workflow` scope); add one from the GitHub web UI if desired
(`./gradlew build --warning-mode all` on Temurin 21; the cross-version TestKit suite is
deliberately manual — it downloads three Gradle distributions).

#### Gradle Plugin Portal

**Published:** version **0.1.0** — see
[plugins.gradle.org/plugin/dev.vfyjxf.facade](https://plugins.gradle.org/plugin/dev.vfyjxf.facade).
The plugin jar is self-contained (facade-core, gson and ASM are shadow-embedded and relocated),
so the published POM has no dependencies and consumers need nothing beyond the Portal.

Releasing a new version:

1. Bump `version` in the root `build.gradle.kts` (the Portal rejects SNAPSHOTs).
2. Put the API key/secret in `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` (or as
   `gradle.publish.key` / `gradle.publish.secret` in `~/.gradle/gradle.properties`).
3. Publish:

```console
$ ./gradlew :facade-gradle:publishPlugins
```

No separate library publication is needed: the plugin jar is self-contained via shadow relocation.
Check the task graph without publishing with
`./gradlew :facade-gradle:publishPlugins --dry-run`.

#### Composite build (nothing published)

Point the consumer's `pluginManagement` at a checkout of this repository with `includeBuild`; Gradle
builds the plugin from source and resolves `id("dev.vfyjxf.facade")` with no version:

```kotlin
// consumer settings.gradle.kts
pluginManagement {
    includeBuild("/path/to/FacadeGradle")
}
```

That is how [examples/](examples/) consume the plugin (`includeBuild("../..")`). Because the plugin
is resolved from a build instead of a repository, no `version` may be given in the `plugins` block
and nothing needs publishing first.

#### Releasing a new version

The version is set once for every module in the root `build.gradle.kts` (`version = "0.1.0"`).
Bump it there, update the version strings in this README's Quickstart, then publish
(`publishToMavenLocal` for a local check, `:facade-gradle:publishPlugins` for the Portal —
the Portal rejects `-SNAPSHOT` versions).

## License

[Apache-2.0](LICENSE). FacadeGradle is original work. The interface-injection *idea* and the
sources-patching tooling it interoperates with come from the Minecraft modding ecosystem —
[ModifiedBeta](https://github.com/MinecraftForge/ModifiedBeta) (LGPL-2.1) and
[JST](https://github.com/neoforged/JST) (LGPL-2.1) — which are referenced for design and invoked
as an external tool, respectively; no code has been copied from either project.
