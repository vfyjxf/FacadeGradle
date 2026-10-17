# FacadeGradle example — Kotlin DSL

A two-project build demonstrating the FacadeGradle plugin (`dev.vfyjxf.facade`) with Kotlin DSL
build scripts:

- **`library/`** — a `java-library` published as `com.example:example-library:1.0.0` (jar +
  sources jar) to `build/local-repo` at the example root.
  - `com.example.lib.Target` — plain class with `String greet()`; declares **no** interfaces.
  - `com.example.lib.GenTarget<T>` — generic class with `T get()` (erasure `()Ljava/lang/Object;`).
  - `com.example.lib.Plain` — untouched by any rule.
  - `com.example.lib.api.MyInterface` / `OtherInterface` — interfaces without implementations.
- **`app/`** — applies `dev.vfyjxf.facade`, consumes the published artifact with
  `facade.configure(this) { config.set(...) }` and binds `app/facade.json`:

```json
{
  "com/example/lib/Target": ["com/example/lib/api/MyInterface"],
  "com/example/lib/GenTarget": ["java/util/function/Supplier<T>"]
}
```

The plugin substitutes the bound module with a variant carrying an injection attribute and
transforms the jar at resolution time, so `MyInterface t = new Target();` and
`Supplier<String> s = new GenTarget<>(...);` compile **and** dispatch at runtime.

## Commands

Run everything from a clean state (the local Maven repo lives under `build/`, so `clean` also
removes it):

```console
$ ./gradlew -p examples/kotlin clean runExample
...
> Task :library:publish
...
> Task :app:run
Hello from Target! from GenTarget plain

BUILD SUCCESSFUL
```

Patched sources (JST) for the bound module:

```console
$ ./gradlew -p examples/kotlin :app:patchFacadeSources
...
> Task :app:patchFacadeSources
[facade] JST patching sources of com.example:example-library:1.0.0

BUILD SUCCESSFUL
```

Outputs land in deterministic locations:

- `app/build/facade/sources/com.example/example-library/example-library-1.0.0-sources.jar`
- `app/build/facade/sources/com.example/example-library/example-library-1.0.0-stubs.zip`
- `app/build/facade/sources-report.json` — one entry per bound module
  (`result: PATCHED`, original/patched sources paths, rule files).

## Notes

- `settings.gradle.kts` uses `pluginManagement { includeBuild("../..") }`, so the plugin is
  consumed from this repository checkout via a composite build — no publication needed.
- The app sets `compileJava { dependsOn(":library:publish") }` so the local repo exists before
  the classpath resolves.
- Java toolchain 21 (JST requires a 21+ JVM; the foojay resolver is configured in
  `settings.gradle.kts` for machines without a local JDK 21).
- First run needs network (JST `net.neoforged.jst:jst-cli-bundle:2.0.11` from Maven Central);
  afterwards everything resolves from Gradle's caches.
