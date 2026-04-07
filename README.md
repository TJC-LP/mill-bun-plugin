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
//| - com.tjclp::mill-bun_mill1:0.2.0

package build

import mill.*
import mill.scalajslib.*
import mill.scalajslib.api.*
import mill.scalajslib.bun.*

object app extends BunScalaJSModule {
  override def moduleDir = build.moduleDir
  def scalaVersion = "3.8.2"
  override def moduleKind = Task { ModuleKind.ESModule }
  override def npmDeps = Task { Seq("react@19.1.1") }
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
//| - com.tjclp::mill-bun_mill1:0.2.0

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

### `BunScalaJSModule`

Extends `ScalaJSModule` with Bun runtime and bundling.

| Task | Default | Description |
|------|---------|-------------|
| `npmDeps` | `Seq.empty` | JS packages for `@JSImport` resolution |
| `npmDevDeps` | `Seq.empty` | Dev-only JS packages |
| `transitiveNpmDeps` | — | Includes `npmDeps` from upstream `BunScalaJSModule` dependencies |
| `transitiveNpmDevDeps` | — | Includes dev deps from upstream `BunScalaJSModule` dependencies |
| `bunBundleTarget` | `"browser"` | `bun build --target` value |
| `bunBundleFormat` | `None` | Output format (`esm`, `cjs`) |
| `bunBundleSplitting` | `false` | Enable code splitting |
| `bunBundleBytecode` | `false` | Emit Bun bytecode |
| `bunInstall` | — | Runs `bun install` for linked output |
| `bunBundle` | — | Full Scala.js bundle via `bun build` |
| `bunBundleFast` | — | Fast bundle from `fastLinkJS` |
| `bunCompileExecutable` | — | Standalone Bun executable |

### `BunWorkersModule`

Mix into a `BunTypeScriptModule` to bundle worker entry points from the staged compile workspace instead of raw source files.

| Task | Default | Description |
|------|---------|-------------|
| `workerEntryPoints` | — | Worker sources to bundle |
| `workerSourceRoots` | `Seq(moduleDir)` | Roots used to preserve worker output layout |
| `workerBundleTarget` | `bunBundleTarget()` | `bun build --target` value for workers |
| `workerBundleFormat` | `Some(bunBundleFormat())` | Optional worker bundle format |
| `bundleWorkers` | — | Bundles all workers under `workers/` while preserving relative paths |

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
| `bunBuildArgs` | `Seq.empty` | Extra raw `bun build` flags |
| `bunTestArgs` | `Seq.empty` | Extra raw `bun test` flags |

Overrides: `npmInstall` (bun install), `compile` (bun x tsc), `run` (bun run), `bundle` (bun build).
Bundle outputs preserve the compiled workspace layout, including `resources/`, and `bunCompileResources` keep their relative paths beneath the module directory.
Ambient typings are selected from `bunBundleTarget`: `bun` installs pinned `@types/bun`, `node` installs pinned `@types/node`, and `browser` installs neither.

### `BunPublishModule`

Mix into a published `BunScalaJSModule` when downstream consumers should receive its runtime JS closure automatically.
Published artifacts stay manifest-only by default; opt into vendored `node_modules` only when you know the closure is safe to ship across platforms.

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
