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
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.1.0-SNAPSHOT

package build

import mill.*
import mill.scalajslib.*
import mill.scalajslib.bun.*

object app extends BunScalaJSModule {
  def scalaVersion = "3.3.6"
  def scalaJSVersion = "1.20.2"
  override def moduleKind = Task { ModuleKind.ESModule }
  override def npmDeps = Task { Seq("react@19.1.1") }
  override def bunBundleTarget = Task { "browser" }

  object test extends BunScalaJSTests, TestModule.Utest
}
```

### TypeScript

```scala
//| mill-version: 1.1.5
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.1.0-SNAPSHOT

package build

import mill.*
import mill.javascriptlib.bun.*

object app extends BunTypeScriptModule {
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
| `bunFrozenLockfile` | `false` | Enforce an existing lockfile |
| `bunLinker` | `"hoisted"` | Bun linker strategy |
| `bunInstallArgs` | `--save-text-lockfile --linker hoisted` | Default install flags |

### `BunScalaJSModule`

Extends `ScalaJSModule` with Bun runtime and bundling.

| Task | Default | Description |
|------|---------|-------------|
| `npmDeps` | `Seq.empty` | JS packages for `@JSImport` resolution |
| `npmDevDeps` | `Seq.empty` | Dev-only JS packages |
| `bunBundleTarget` | `"browser"` | `bun build --target` value |
| `bunBundleFormat` | `None` | Output format (`esm`, `cjs`) |
| `bunBundleSplitting` | `false` | Enable code splitting |
| `bunBundleBytecode` | `false` | Emit Bun bytecode |
| `bunInstall` | — | Runs `bun install` for linked output |
| `bunBundle` | — | Full Scala.js bundle via `bun build` |
| `bunBundleFast` | — | Fast bundle from `fastLinkJS` |
| `bunCompileExecutable` | — | Standalone Bun executable |

### `BunTypeScriptModule`

Extends Mill's `TypeScriptModule`, replacing npm/node/esbuild with Bun.

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

## Examples

See `example-scalajs/` and `example-typescript/` for complete consumer projects.

## Development

```bash
./mill millbun.compile       # Compile the plugin
./mill millbun.test          # Unit tests
./mill millbun.integration   # Integration tests (requires Bun on PATH)
```

## License

MIT
