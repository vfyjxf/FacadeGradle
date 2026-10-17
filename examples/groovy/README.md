# FacadeGradle example — Groovy DSL

Identical scenario to [`../kotlin/`](../kotlin/) — publish `com.example:example-library:1.0.0`
from `library/`, consume it in `app/` with interface injection — but written with Groovy DSL
build scripts (`settings.gradle`, `build.gradle`). The facade binding in Groovy reads:

```groovy
dependencies {
    implementation('com.example:example-library:1.0.0') {
        facade.configure(it) {
            config.set(layout.projectDirectory.file('facade.json'))
        }
    }
}
```

## Commands

```console
$ ./gradlew -p examples/groovy clean runExample
...
> Task :app:run
Hello from Target! from GenTarget plain

BUILD SUCCESSFUL
```

Patched sources work the same as in the Kotlin example:

```console
$ ./gradlew -p examples/groovy :app:patchFacadeSources
...
> Task :app:patchFacadeSources
[facade] JST patching sources of com.example:example-library:1.0.0

BUILD SUCCESSFUL
```

Outputs: `app/build/facade/sources/...` and `app/build/facade/sources-report.json`.

## Notes

- `settings.gradle` uses `pluginManagement { includeBuild('../..') }` (composite build, no
  published plugin required).
- Java toolchain 21; foojay resolver configured for toolchain auto-provisioning.
- First run needs network (JST from Maven Central); afterwards everything is cached.
