# FacadeGradle examples

Runnable, self-contained consumer projects for the FacadeGradle plugin. Each example is its own
Gradle build (they are **not** part of the root build) and wires the plugin in via a composite
`includeBuild("../..")`, so nothing needs to be published first.

| Example | DSL | Entry point |
| --- | --- | --- |
| [`kotlin/`](kotlin/) | Kotlin DSL (`build.gradle.kts`) | `./gradlew -p examples/kotlin runExample` |
| [`groovy/`](groovy/) | Groovy DSL (`build.gradle`) | `./gradlew -p examples/groovy runExample` |

## Quickstart

From the repository root, run:

```console
$ ./gradlew -p examples/kotlin runExample
...
> Task :app:run
Hello from Target! from GenTarget plain

BUILD SUCCESSFUL
```

What happened: `:library:publish` published `com.example:example-library:1.0.0` (with a sources
jar) to `examples/kotlin/build/local-repo`; `:app` then resolved that artifact and the facade
plugin transformed it on the classpath according to `app/facade.json` — adding
`implements MyInterface` to `com.example.lib.Target` and `implements Supplier<T>` to
`com.example.lib.GenTarget` — so `app`'s `Main` compiles and runs even though the library sources
declare no such interfaces. The same flow works verbatim in the `groovy/` example.

See each example's README for details and for the `patchFacadeSources` sources-patching demo.
