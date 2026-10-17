# FacadeGradle × IntelliJ IDEA — sources integration

State as of 2026-10-09. Gradle side (`patchFacadeSources`) is implemented; IDE side is not. §4–5 are
designs only — **NOT IMPLEMENTED**.

## 1. Status summary

| Capability | Environment | Status |
| --- | --- | --- |
| Patched binaries on compile/runtime classpath | Any Gradle resolution | **WORKS** — plain artifact transform (`transform/FacadeTransform.java`, routed via attributes `dev.vfyjxf.facade.injected.<n>`) |
| Patched binaries visible to IDEA | Stable IDEA | **WORKS** — IDEA imports classpaths resolved on the Gradle daemon, where the transform runs |
| Patched sources in editor | IDEA ≤ 2025.1 | **NO** — `ArtifactResolutionQuery` path has no attributes/transforms ([gradle/gradle#35065]); original sources shown |
| Patched sources in editor | IDEA 2025.2 – 2026.2 | **NO** — transforms run on the daemon, but outputs are discarded in `AuxiliaryArtifactResolverImpl.classify()` (fixes on master 2026-09-23, unshipped) |
| Patched sources in editor | IDEA ≥ 2026.3 | **PLAUSIBLE-BUT-UNVERIFIED** — conditional on the sources *transform* of §5 (today's patching is a task, not on IDEA's resolution path); end-to-end untested |
| Patched sources attached automatically | Any IDEA | **NEEDS-PLUGIN** — companion plugin `facade-idea` (§4), not implemented |
| Gradle-level fix | Gradle 10.x | Tracked by [gradle/gradle#35065] — open, milestone 10.x |
[gradle/gradle#35065]: https://github.com/gradle/gradle/issues/35065

## 2. How IDEA resolves Gradle sources today ([VERIFIED] 2026-10-09)

**Legacy / classifier path (default through IDEA 2025.1).** `IdeDependencySet` resolves sources and
javadoc via `ArtifactResolutionQuery` — no `ArtifactView`, hence no configuration attributes and no
project-registered transforms. Only repository `-sources` jars are found; transform-produced sources
are invisible. Root cause of [gradle/gradle#35065] (open, milestone 10.x). Fix attempt
[gradle/gradle#35131] closed **unmerged** 2026-07-01 (perf regressions: Tooling API ~25%, IDE task
execution ~10%) — no Gradle-side fix is imminent. https://github.com/gradle/gradle/pull/35131

**Modern path (IDEA 2025.2+, commit 11adacf43d1, 2025-05-13)** resolves sources per resolvable
configuration via

```text
configuration.incoming.artifactView {
    withVariantReselection(); lenient
    attributes: Usage=java-runtime, Category=documentation, Bundling=external, DocsType=sources
}
```

running **on the Gradle daemon**, so project-registered `ArtifactTransform`s do execute — but
`AuxiliaryArtifactResolverImpl.classify()` (tooling-extension-impl plugin, file
.../model/dependencyModel/auxiliary/AuxiliaryArtifactResolverImpl.java) dropped the results: only
`ModuleComponentArtifactIdentifier` (repository artifacts) was accepted;
`TransformedComponentFileArtifactIdentifier` (transform outputs) was discarded. Commits 90ffef33f8e
("accept transformed auxiliary artifacts") and f05d1338511 ("request archive artifact types jar then
zip"), both 2026-09-23, fix that; expected — not confirmed — to ship in IDEA 2026.3 [INFERRED].
https://github.com/JetBrains/intellij-community/commit/11adacf43d19200f04a200353177c66f023e577d
https://github.com/JetBrains/intellij-community/commit/90ffef33f8e21d665620d4ab372a6ecef3d079cd
https://github.com/JetBrains/intellij-community/commit/f05d133851131b87e419da2a327c59751e7a8bd5

**Post-sync attach fallback.** `GradleProjectResolverUtil.attachSourcesAndJavadocFromGradleCacheIfNeeded`
scans `~/.gradle/caches/modules-2` and `caches/transforms-` and attaches whatever it finds. Fragile —
it can attach the *original* sources of a bound module — and not a strategy.

**No metadata-rule shortcut:** metadata rules only select among a component's *repository* artifacts;
they cannot substitute a locally-produced patched sources jar.

[INFERRED] Facade substitution puts `dev.vfyjxf.facade.injected.<n>=true` on the bound module's
*selector*, which IDEA's auxiliary view does not request; the lenient outcome today is original
sources at best — never patched ones.

## 3. What FacadeGradle already provides (Gradle side, in-repo)

All under `facade-gradle/src/main/java/dev/vfyjxf/gradle/facade/gradle/`:

- Task `patchFacadeSources` (group `facade`; registered in `FacadeGradlePlugin`, implemented by the
  `@CacheableTask` `source/FacadeSourcesTask.java`), gated by `onlyIf` on `facade.options.transformSources`.
  Missing sources fail the build only when `facade.options.failOnMissingSources` is true; rule-validation
  failures skip the module unless `facade.options.strictValidation` is true (DSL: `api/FacadeExtension.java`).
- Inputs come from internal configurations `facadeInternalSources` / `facadeInternalBinaries`
  (`FacadeRegistrar.INTERNAL_CONFIGURATION_PREFIX`), which resolve the **original** artifacts on purpose
  and never receive substitution. JST: detached `net.neoforged.jst:jst-cli-bundle:2.0.11`, run with a
  JVM toolchain launcher (Java 21 convention).

**Report** at `$buildDir/facade/sources-report.json`, written by `FacadeSourcesTask.writeReport`:

```json
{
  "modules": [
    {
      "module": "com.example:library:1.0.0",
      "result": "PATCHED",
      "originalSources": "/home/u/.gradle/caches/modules-2/.../library-1.0.0-sources.jar",
      "patchedSources": "/home/u/proj/build/facade/sources/com.example/library/library-1.0.0-sources.jar",
      "ruleFiles": ["/home/u/proj/facade.json"],
      "stubs": "/home/u/proj/build/facade/sources/com.example/library/library-1.0.0-stubs.zip",
      "classpathSource": "facade-core transformJar of original binary"
    }
  ]
}
```

Exact field semantics (one entry per bound module):

| Field | Meaning |
| --- | --- |
| `module` | `group:name:version` of the bound module (version from the resolved artifact; `"unknown"` if nothing resolvable) |
| `result` | `PATCHED` \| `MISSING_SOURCES` \| `SKIPPED` (validation failed, strict off) |
| `originalSources` | absolute path of the repository sources jar (`null` for `MISSING_SOURCES`) |
| `patchedSources` | absolute path of the patched sources jar (`null` unless `PATCHED`) |
| `ruleFiles` | the module's sorted absolute rule-file paths |
| `stubs` | JST interface-injection stubs zip if produced, else `null` |
| `classpathSource` | `"facade-core transformJar of original binary"` or `"none"` (JST ran classpath-less → unresolved interfaces stubbed); `null` unless `PATCHED` |

**Deterministic layout** (no timestamps or hashes; safe to reference from an IDE):

```text
$buildDir/facade/sources/<group>/<name>/<name>-<version>-sources.jar
$buildDir/facade/sources/<group>/<name>/<name>-<version>-stubs.zip
```

This report *is* the ComponentIdentity → PatchedSources mapping any IDE adapter needs — no scanning
of `~/.gradle/caches` required. The design in §4 consumes exactly this file.

## 4. Design: optional companion plugin `facade-idea` — DESIGN ONLY, NOT IMPLEMENTED

Goal: after sync, `LibraryData` for facade-bound modules points its SOURCE roots at the patched jars
from §3 instead of the originals. Integration points (intellij-community, [VERIFIED]):

- Extension point `org.jetbrains.plugins.gradle.projectResolve` with a `GradleProjectResolverExtension`
  (plugins/gradle/src/org/jetbrains/plugins/gradle/service/project/GradleProjectResolverExtension.java).
- `getExtraProjectModelClasses()` / `getModelProviders()` — expose a custom tooling model carrying the
  patched-sources mapping. Recommended: a `FacadeSourcesModel` mirroring `sources-report.json` (per
  module: `module`, `result`, `patchedSources`, `ruleFiles`), built by a `ToolingModelBuilder` that
  reads the report file — same data users see, no re-resolution, no coupling to plugin internals.
- `getToolingExtensionsClasses()` — classes the Tooling API must load on the daemon side.
- `resolveFinished(DataNode<ProjectData>)` — walk `ProjectKeys.LIBRARY_DEPENDENCY` nodes and, for bound
  modules only, call `libraryData.addPath(LibraryPathType.SOURCE, patchedPath)` (normally set earlier
  in `GradleProjectResolverUtil.createDependencyDataNode`).

Semantics:

- Rewrite only libraries whose `group:name` is in the model with `result == "PATCHED"` and an existing
  file; log a warning for `MISSING_SOURCES`/`SKIPPED`; never touch anything else.
- Ordering: IDEA downloads sources during sync via `AuxiliaryArtifactResolver` (on the daemon);
  `resolveFinished` runs after dependency data nodes are created — the override must run there and
  win. Initial sync and re-sync behave identically; a stale/missing patched jar means leave IDEA's
  value rather than dangling paths.
- "Download Sources" flow: there is no separate generic post-sync Gradle download task; IDEA's
  per-project download-sources setting gates its own auxiliary resolution. Honor it (skip attaching
  when the user disabled source downloads).
- Version changes are self-cleaning: patched jar names embed the version; re-sync rebuilds `LibraryData`.

Wiring sketch for the IDEA-plugin project (not compiled or tested; check the
intellij-platform-gradle-plugin docs for your plugin version):

```kotlin
plugins { id("org.jetbrains.intellij.platform") }

dependencies {
    intellijPlatform { intellijIdeaCommunity("2026.2") } // pin the IDEA version you target
}

intellijPlatform {
    // ships FacadeSourcesModel etc. to the Gradle daemon via the tooling-extension classpath
    gradleToolingExtension(mainGroup = "dev.vfyjxf.facade.idea", useClassPathVersion = IJVersion.IJ_262)
}
```

Precedent for a Gradle-plugin + IDEA-plugin combo that delivers sources into LibraryData:
https://github.com/jonnyzzz/gradle-lib-with-sources

## 5. Design note (deferred): type-changing sources transform — DESIGN ONLY

Ride IDEA ≥ 2026.3's acceptance of transformed auxiliary artifacts with a *sources* artifact
transform (in addition to today's binary `FacadeTransform`): from `artifactType=jar,
Category=documentation, DocsType=sources, dev.vfyjxf.facade.injected.<n>=false` to `artifactType=zip`
with the same documentation attributes and injected attribute `true`, producing the patched sources.
Commit f05d1338511 ("request archive artifact types jar then zip") is what makes the zip reachable.

Risky before IDEA 2026.3: on 2025.2–2026.2 the transform *runs on the daemon* (cost: JST per
resolution) but `classify()` discards the outputs — at best a no-op, at worst wasted work; on
≤ 2025.1 it is never invoked at all. Parked until 2026.3 behavior is confirmed on a real build.

## 6. Risks & watch list

- [gradle/gradle#35065] (open, milestone 10.x): a Gradle-side fix changes the legacy path;
  re-evaluate §4–§5 when it lands. PR #35131 was closed unmerged — fix not imminent.
- IDEA 2026.3: verify 90ffef33f8e and f05d1338511 shipped, then prototype §5 and re-test end-to-end
  (sync a consumer, open a bound module's class, confirm the injected interface appears).
- `AuxiliaryArtifactResolverImpl` is internal IDEA API and may change without notice.
- `attachSourcesAndJavadocFromGradleCacheIfNeeded` may attach *original* sources from the Gradle
  caches; after a manual attach, verify the patched root wins in the library editor.

**Manual workaround today (no plugin needed):**

1. Run `./gradlew patchFacadeSources`.
2. Open `$buildDir/facade/sources-report.json`, find your module, copy its `patchedSources` path.
3. IDEA: Project Structure (⌘;) → Project Settings → Libraries → select the bound module's library → attach the patched jar as a source root ("＋" / *Attach Sources*) → OK.
4. Repeat after changing rules or dependency versions (paths embed the version).
