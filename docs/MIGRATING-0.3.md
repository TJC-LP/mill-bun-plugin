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

## Renamed APIs

| 0.2 name | 0.3 name | Status |
|---|---|---|
| `bunBundle` | `bundle` | Compatibility alias retained |
| `bunBundleFast` | `bundleFast` | Compatibility alias retained |
| `bunCompileExecutable` | `compileExecutable` | Compatibility alias retained for Scala.js; Boolean TypeScript setting deprecated |
| `bunCompileExecutables` | `compileExecutables` | Compatibility alias retained |
| `bunOptionalDeps` | `npmOptionalDeps` | Deprecated compatibility setting |
| `managedBunExecutable` | `bunExecutableOverride` | Deprecated compatibility setting |

The old names are planned for removal at 1.0.

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
