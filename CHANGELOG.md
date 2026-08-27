# Changelog

All notable changes to `mill-bun-plugin` will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - Managed toolchain, strict lockfiles, canonical vocabulary (2026-08-27)

### Added

- Tag-driven release workflow for Maven Central publishing and GitHub releases.
- Release runbook covering secrets, version sweep, annotated tags, and verification.
- Checksum-verified managed Bun 1.4.0 for macOS, Linux, and Windows on x64 and arm64, including
  musl (auto-detected) and `-baseline` (via `bunUseBaseline`) builds.
- `bunArchiveSha256` may be set on its own to run a Bun version with no bundled checksum; the
  download URL is derived from `bunVersion` and the detected platform.
- Strict text-lockfile workflow with `bunLock`, frozen installs, and actionable missing-lock failures.
- Canonical `bundle`, `bundleFast`, `compileExecutable`, and `compileExecutables` task names.
- Paired `BunScalaJSWebModule` and `BunTypeScriptWebModule` HTML workflows.
- `npmOptionalDeps`, `npmPeerDeps`, `npmOverrides`, and deterministic dependency conflict detection.
- `BunWorkspaceModule` for one install and lockfile across mixed Scala.js/TypeScript packages.
- Published dependency manifest schema v2 with runtime, optional, and peer requirements.
- `bunDoctor` diagnostics and managed-toolchain CI smoke coverage.
- A committed lockfile written by a newer Bun fails frozen installs with regeneration guidance,
  instead of surfacing bun's raw `UnknownLockfileVersion`.

### Changed

- Scala.js linking delegates to Mill's standard linker hooks; applications now choose `scalaJSVersion` explicitly.
- Development dependencies are local tooling inputs and are no longer published transitively.
- `bunPackageJsonExtras` rejects dependency fields now represented by typed settings.
- Missing dependency versions are represented as `latest` instead of an empty package.json value,
  and an explicit specifier wins over `latest` when the same package is declared both ways —
  two different explicit specifiers still fail deterministically.
- TypeScript `bunBundleFormat` is `Option[String]`, matching Scala.js; `None` lets `bun build` infer.
- `unmanagedDeps` entries are staged into `vendor/` and declared as `file:./vendor/<name>`
  dependencies, so local packages install under frozen lockfiles and locks stay portable.
- The TypeScript install task is canonically `bunInstall`; Mill's inherited `npmInstall` delegates to it.
- `bunToolEnv` is defined once on `BunToolchainModule` for all toolchain subprocesses, and the
  TypeScript `bunRuntimeEnv` is public.

### Deprecated

- Scala.js `bunBundle*` and `bunCompile*` task names in favor of their canonical aliases.
- `bunOptionalDeps` in favor of `npmOptionalDeps`.
- `managedBunExecutable` in favor of `bunExecutableOverride`.
- The TypeScript `bunCompileExecutable: Boolean` switch in favor of the `compileExecutable` task.
- The TypeScript test command `test` and the Scala.js test command `bunTest`, both in favor of `testForked`.

## [0.2.1] - Overridable test-time JS env (2026-04-17)

### Added

- `BunScalaJSTests.bunTestJsEnv` — a test-scoped override for the Scala.js
  test process environment. Defaults to the outer module's `bunJsEnv`
  unchanged (no behavior change for existing users).
- `BunScalaJSTests` now owns its own `jsEnvConfig` that sources its env
  from `bunTestJsEnv`, so overriding that map is enough to diverge test
  env from production `bunRun`.

### Why

Tests that spin up an in-process `Bun.serve({...})` typically want
`NODE_ENV=production`, which flips Bun's `development` default to
`false`. Otherwise a fetch-handler Promise rejection is rewritten into
a ~100 KB HTML `BunError` React-overlay `Response` — the test then
asserts against the overlay HTML instead of the real error, which
hides bugs.

Downstream libraries can now opt in with one override:

```scala
object test extends BunScalaJSTests:
  override def bunTestJsEnv = Task {
    super.bunTestJsEnv() + ("NODE_ENV" -> "production")
  }
```
