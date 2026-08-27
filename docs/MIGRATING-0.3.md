# Migrating to 0.3

Version 0.3 makes dependency installation reproducible by default and aligns Scala.js and TypeScript around the same public vocabulary.

## Required changes

### Choose a Scala.js version

Every `BunScalaJSModule` now defines its own tested Scala.js version:

```scala
def scalaJSVersion = "1.22.0"
```

The plugin no longer ships or invokes its own Scala.js linker worker. Linking delegates to Mill's `ScalaJSModule` implementation.

### Generate lockfiles

Run `bunLock` for every dependency-bearing module and commit the resulting `bun.lock` beside that module's sources:

```bash
./mill app.bunLock
./mill frontend.bunLock
```

Subsequent installs are frozen. During a staged migration only, set `MILL_BUN_REQUIRE_LOCKFILE=false`.

### Move typed dependency fields out of `bunPackageJsonExtras`

Use `npmDeps`, `npmDevDeps`, `npmOptionalDeps`, `npmPeerDeps`, and `npmOverrides`. `bunPackageJsonExtras` remains available for unmodeled fields such as `scripts`, but now rejects dependency sections so task invalidation and published metadata remain correct.

### `bunBundleFormat` is `Option[String]` on TypeScript modules

Scala.js and TypeScript previously disagreed on this member's type — `Option[String]` versus
`String` — which no deprecation alias can bridge, so 0.3 takes the one-time break. Both are now
`T[Option[String]]`; `None` lets `bun build` infer the format.

```scala
// 0.2
override def bunBundleFormat = Task { "esm" }
// 0.3
override def bunBundleFormat = Task { Some("esm") }
```

### Contradictory npm declarations fail every install, not just publishing

0.2 resolved the same package declared with different specifiers last-wins. 0.3 fails
deterministically for **all** generated package.json files — direct declarations, module-graph
aggregation, and classpath manifests alike. Resolve with `npmOverrides`:

```scala
override def npmOverrides = Task { Map("react" -> "^19.0.0") }
```

### Local packages via `unmanagedDeps` must be directories with a `package.json`

Entries are staged into `vendor/` beside the generated package.json and declared as
`file:./vendor/<name>` dependencies, so they now work under frozen lockfile installs and the
lockfile stays independent of the checkout path. Tarballs are no longer accepted — unpack them.

## Renamed APIs

| 0.2 name | 0.3 name | Status |
|---|---|---|
| `bunBundle` | `bundle` | Compatibility alias retained |
| `bunBundleFast` | `bundleFast` | Compatibility alias retained |
| `bunCompileExecutable` | `compileExecutable` | Compatibility alias retained for Scala.js; Boolean TypeScript setting deprecated |
| `bunCompileExecutables` | `compileExecutables` | Compatibility alias retained |
| `bunOptionalDeps` | `npmOptionalDeps` | Deprecated compatibility setting |
| `managedBunExecutable` | `bunExecutableOverride` | Deprecated compatibility setting |
| `npmInstall` (TypeScript) | `bunInstall` | Mill's inherited name delegates to `bunInstall` and stays usable |
| `test` (TypeScript test modules) | `testForked` | Deprecated compatibility command |
| `bunTest` (Scala.js test modules) | `testForked` (inherited from Mill) | Deprecated compatibility command |

The old names are planned for removal at 1.0.

Environment hooks share one vocabulary: `bunToolEnv` (toolchain subprocesses — install, lock,
build) is defined on `BunToolchainModule` for every module kind, and the TypeScript
`bunRuntimeEnv` (program and test processes) is now public. Scala.js keeps `bunJsEnv` /
`bunJsEnvArgs` / `bunTestJsEnv` for its Scala.js-test JS environment, unchanged.

## Toolchain behavior

The default is now checksum-verified managed Bun 1.4.0. To preserve the old PATH behavior:

```bash
export MILL_BUN_USE_SYSTEM=true
```

The selected executable must report the configured `bunVersion` unless `bunVerifyVersion` is explicitly disabled. Run `./mill app.bunDoctor` when diagnosing toolchain selection.

## Published dependency manifests

New JARs use schema v2:

- runtime, optional, and peer dependencies are published;
- development dependencies are not transitive;
- contradictory requirements fail unless resolved with `npmOverrides`;
- malformed manifests fail rather than disappearing silently.

Schema v1 remains readable for backward compatibility.

## Optional workspace migration

For repositories with several Scala.js or TypeScript modules, introduce a `BunWorkspaceModule`, list the packages in `bunWorkspacePackages`, and point each member's `bunWorkspaceInstall` at `workspace.bunInstall()`. Then replace per-package locks with `workspace/bun.lock`.
