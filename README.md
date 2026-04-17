# mill-bun-plugin

A [Mill](https://mill-build.org) plugin that adds [Bun](https://bun.sh)-backed workflows for Scala.js and TypeScript projects.

Keeps Mill's task graph, module structure, caching, Scala.js linker integration, and generated `tsconfig` handling — while swapping the JS runtime/package/bundling backend from `node`/`npm`/`esbuild` to Bun.

## Requirements

- Mill 1.1.5+
- Bun 1.2+ on PATH
- JDK 17+

## Quick Start

### Scala.js

```scala
//| mill-version: 1.1.5
//| mill-jvm-version: system
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.2.1

package build

import mill.*
import mill.bun.bun
import mill.scalajslib.*
import mill.scalajslib.api.*
import mill.scalajslib.bun.*

object app extends BunScalaJSModule {
  override def moduleDir = build.moduleDir
  def scalaVersion = "3.8.2"
  override def moduleKind = Task { ModuleKind.ESModule }
  override def bunDeps = Task { Seq(bun"react@^19.0.0") }
  override def bunBundleTarget = Task { "browser" }

  object test extends BunScalaJSTests, TestModule.Utest
}
```

`BunScalaJSModule` inherits Mill's bundled current Scala.js version, so you configure `scalaVersion` on the module but do not override `scalaJSVersion`. If you keep Scala.js sources at the build root such as `src/`, override `moduleDir = build.moduleDir`; otherwise Mill will look under `<module-name>/src`.
`BunScalaJSTests` runs the Scala.js test bridge on Bun as the JS runtime. For ESM apps, the test linker falls back to CommonJS so Bun can execute the Scala.js test bridge without the temporary `file:` importer failure that affects `bun run -`.
For published Scala.js libraries that must carry JS runtime dependencies to downstream consumers, mix in `BunPublishModule`. By default it embeds `META-INF/bun/bun-dependencies.json` so consumers keep resolving transitive Bun packages via manifests. If you need to ship a vendored runtime tree as well, set `bunPublishVendoredRuntime = true` and only do so when the resolved closure is platform-independent.

### TypeScript

```scala
//| mill-version: 1.1.5
//| mill-jvm-version: system
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.2.1

package build

import mill.*
import mill.javascriptlib.bun.*

object app extends BunTypeScriptModule {
  override def moduleDir = build.moduleDir
  override def npmDeps = Task { Seq("express@4.21.2") }
  override def bunBundleTarget = Task { "bun" }

  object test extends BunTypeScriptTests
}
```

## `bun""` String Interpolator

The `bun"pkg@version"` interpolator provides compile-time validation of Bun package specifiers. Import it with `import mill.bun.bun` and use it in `bunDeps` declarations:

```scala
import mill.bun.bun

override def bunDeps = Task { Seq(
  bun"react@^19.0.0",
  bun"@anthropic-ai/claude-agent-sdk@^0.2.90",
  bun"zod@^4.0.0"
)}
```

Invalid or empty specifiers are caught at compile time. The interpolator returns a plain `String`, so it works anywhere `npmDeps` or `bunDeps` accepts strings.

## Modules

### `BunToolchainModule`

Base trait providing Bun discovery and execution helpers.

| Task | Default | Description |
|------|---------|-------------|
| `bunExecutableName` | `"bun"` | Command name for PATH lookup |
| `managedBunExecutable` | `None` | Hook for a downloaded/managed Bun binary |
| `bunEnv` | `Map.empty` | Environment variables for Bun subprocesses |
| `bunLinker` | `"hoisted"` | Bun linker strategy |
| `bunInstallArgs` | `--save-text-lockfile --linker hoisted` | Default install flags |
| `bunLockfiles` | `Seq("bun.lock", "bun.lockb")` | Lockfile names Bun may produce |
| `bunfigFiles` | auto-detected | Workspace `bunfig.toml` / `.bunfig.toml` configs |
| `bunCompileTargets` | `Seq.empty` | Cross-compilation targets (e.g. `"bun-linux-x64"`, `"bun-darwin-arm64"`) |
| `bunCompileResources` | `Seq.empty` | Extra files/directories for `bun build --compile` workspaces |

### `BunScalaJSModule`

Extends `ScalaJSModule` with Bun runtime and bundling.

| Task | Default | Description |
|------|---------|-------------|
| `npmDeps` | `Seq.empty` | JS packages for `@JSImport` resolution |
| `npmDevDeps` | `Seq.empty` | Dev-only JS packages |
| `bunDeps` | `Seq.empty` | JS packages using `bun"pkg@version"` validated syntax |
| `bunDevDeps` | `Seq.empty` | Dev-only JS packages (independent of `npmDevDeps`) |
| `bunOptionalDeps` | `Seq.empty` | Optional JS packages — installed if available, not fatal if missing |
| `unmanagedDeps` | `Seq.empty` | Local tarballs or package directories |
| `bunPackageJsonExtras` | `ujson.Obj()` | Extra fields merged into generated `package.json` |
| `transitiveNpmDeps` | — | Merged `npmDeps` + `bunDeps` from this module, upstream deps, and classpath manifests |
| `transitiveNpmDevDeps` | — | Merged `npmDevDeps` + `bunDevDeps` from this module, upstream deps, and classpath manifests |
| `classpathBunDeps` | — | Runtime deps auto-populated from dependency JAR manifests |
| `classpathBunDevDeps` | — | Dev deps auto-populated from dependency JAR manifests |
| `classpathBunOptionalDeps` | — | Optional deps auto-populated from dependency JAR manifests |
| `bunBundleTarget` | `"browser"` | `bun build --target` value |
| `bunBundleFormat` | `None` | Output format (`esm`, `cjs`) |
| `bunBundleExternal` | `Seq.empty` | Packages treated as external during bundling |
| `bunBundleSplitting` | `false` | Enable code splitting |
| `bunBundleBytecode` | `false` | Emit Bun bytecode |
| `bunBundleArgs` | `Seq.empty` | Extra raw `bun build` flags |
| `bunBinaryName` | module name | Name for compiled executables |
| `bunInstall` | — | Runs `bun install` for linked output |
| `bunBundle` | — | Full Scala.js bundle via `bun build` |
| `bunBundleFast` | — | Fast bundle from `fastLinkJS` |
| `bunCompileExecutable` | — | Standalone Bun executable |
| `bunCompileExecutables` | — | Cross-compile executables per `bunCompileTargets` |

### `BunWorkersModule`

Mix into a `BunTypeScriptModule` to bundle worker entry points from the staged compile workspace instead of raw source files.

| Task | Default | Description |
|------|---------|-------------|
| `workerEntryPoints` | — | Worker sources to bundle |
| `workerSourceRoots` | `Seq(moduleDir)` | Roots used to preserve worker output layout |
| `workerBundleTarget` | `bunBundleTarget()` | `bun build --target` value for workers |
| `workerBundleFormat` | `Some(bunBundleFormat())` | Optional worker bundle format |
| `workerBundleArgs` | `Seq.empty` | Extra raw flags for worker bundling |
| `bundleWorkers` | — | Bundles all workers under `workers/` while preserving relative paths |

### `BunSQLiteModule`

Mix into a `BunTypeScriptModule` to discover and include SQLite database files in `bun build --compile` workspaces via `bunCompileResources`.

| Task | Default | Description |
|------|---------|-------------|
| `sqliteDatabases` | `Seq.empty` | Explicit SQLite database files to include |
| `sqliteDatabaseDir` | `None` | Directory to scan for `.db`, `.sqlite`, `.sqlite3` files |

### `BunTypeScriptModule`

Extends Mill's `TypeScriptModule`, replacing npm/node/esbuild with Bun.
For top-level modules whose sources live at the workspace root, set `override def moduleDir = build.moduleDir`.
When Mill's default `src/<module>.ts` entrypoint is absent, the Bun run/bundle tasks fall back to `src/main.ts`, `src/index.ts`, `main.ts`, and `index.ts`.

| Task | Default | Description |
|------|---------|-------------|
| `bunRunArgs` | `Seq.empty` | Extra flags for `bun run` |
| `bunBundleTarget` | `"bun"` | `bun build --target` value |
| `bunBundleFormat` | `"esm"` or `"cjs"` | Based on `enableEsm` |
| `bunCompileExecutable` | `false` | Emit standalone executable |
| `bunBundlePackagesExternal` | `false` | Treat all packages as external |
| `bunBundleExternal` | `Seq.empty` | Packages treated as external during bundling |
| `bunBinaryName` | module name | Name for compiled executables |
| `bunPackageJsonExtras` | `ujson.Obj()` | Extra fields merged into generated `package.json` |
| `bunBuildArgs` | `Seq.empty` | Extra raw `bun build` flags |
| `bunTestArgs` | `Seq.empty` | Extra raw `bun test` flags |
| `bunCompileExecutables` | — | Cross-compile executables per `bunCompileTargets` |

Overrides: `npmInstall` (bun install), `compile` (bun x tsc), `run` (bun run), `bundle` (bun build).
Bundle outputs preserve the compiled workspace layout, including `resources/`, and `bunCompileResources` keep their relative paths beneath the module directory.
Ambient typings are selected from `bunBundleTarget`: `bun` installs pinned `@types/bun`, `node` installs pinned `@types/node`, and `browser` installs neither.

**`BunTypeScriptTests`** inner trait for test modules:

| Task | Default | Description |
|------|---------|-------------|
| `bunTestTimeout` | `0` | Test timeout in milliseconds (0 = no timeout) |
| `bunTestReporter` | `"default"` | Reporter format: `"default"`, `"junit"`, or `"json"` |
| `bunCoverageReporters` | `Seq("text", "lcov")` | Coverage reporter formats |

Test commands: `test`, `testWatch`, `testUpdateSnapshots`, `coverage`, `coverageReport`.

### `BunPublishModule`

Mix into a published `BunScalaJSModule` when downstream consumers should receive its runtime JS closure automatically.

Manifests (`META-INF/bun/bun-dependencies.json`) are always published when the module declares any Bun/npm dependencies. Consumer builds scan classpath JARs for these manifests and merge them into their `package.json` via `classpathBunDeps` / `classpathBunDevDeps` / `classpathBunOptionalDeps`.

Optionally, enable `bunPublishVendoredRuntime = true` to also embed a vendored `node_modules` tree in the JAR. This gives consumers the exact resolved packages without running `bun install` for those transitive deps. Only enable this when the resolved closure is platform-independent — Bun installs can materialize host-specific binaries.

| Task | Default | Description |
|------|---------|-------------|
| `bunPublishVendoredRuntime` | `false` | Embed `META-INF/bun/node_modules/**` from a local Bun install |
| `bunDependencyManifest` | — | Writes `META-INF/bun/bun-dependencies.json` for this module's direct runtime JS deps |
| `bunPublishedRuntimeInstall` | — | Resolves this module's direct runtime JS closure in an isolated install workspace |
| `bunVendoredRuntimeBundle` | — | Emits `META-INF/bun/node_modules/**` when vendored publishing is enabled |

## Examples

See `example-scalajs/` and `example-typescript/` for complete consumer projects, and `examples/build.mill` for the broader multi-module example matrix used during development.

## Development

```bash
./mill millbun.compile       # Compile the plugin
./mill millbun.test          # Unit tests
./mill millbun.integration   # Integration tests (requires Bun on PATH)
```

See `docs/RELEASING.md` for the tag-driven Maven Central release workflow.

## License

MIT
