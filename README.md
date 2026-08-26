# mill-bun-plugin

A [Mill](https://mill-build.org) plugin for first-class [Bun](https://bun.sh) workflows in Scala.js and TypeScript projects.

It keeps Mill's task graph, caching, module relationships, Scala.js linker, and TypeScript configuration while using Bun for dependency installation, execution, tests, bundling, web development, and native executables.

## Requirements

- Mill 1.1.5+
- JDK 17+

Bun does not need to be installed. The plugin downloads and checksum-verifies Bun 1.4.0 by default on macOS, Linux, and Windows x64/arm64. musl-based Linux (Alpine) is detected automatically; on x64 CPUs without AVX2, set `bunUseBaseline`. Set `MILL_BUN_USE_SYSTEM=true` to opt into the Bun on `PATH` instead.

## Scala.js quick start

```scala
//| mill-version: 1.1.5
//| mill-jvm-version: system
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.3.0

package build

import mill.*
import mill.bun.bun
import mill.scalajslib.api.*
import mill.scalajslib.bun.*

object app extends BunScalaJSModule {
  override def moduleDir = build.moduleDir
  def scalaVersion = "3.8.2"
  def scalaJSVersion = "1.22.0"

  override def moduleKind = Task { ModuleKind.ESModule }
  override def npmDeps = Task { Seq(bun"lodash@^4.17.21") }

  object test extends BunScalaJSTests, TestModule.Utest
}
```

Scala.js versions are explicit: choose the version your application tests against instead of inheriting a plugin-bundled linker.

```bash
./mill app.bunLock       # generate and commit app/bun.lock (or ./bun.lock with moduleDir above)
./mill app.run
./mill app.bundle
./mill app.compileExecutable
./mill app.test.test
```

`BunScalaJSModule` delegates `fastLinkJS`, `fullLinkJS`, and test linking to Mill's standard `ScalaJSModule` hooks. That keeps the plugin compatible with Mill's linker lifecycle and removes its former private linker-worker coupling.

## TypeScript quick start

```scala
//| mill-version: 1.1.5
//| mill-jvm-version: system
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.3.0

package build

import mill.*
import mill.javascriptlib.bun.*

object app extends BunTypeScriptModule {
  override def moduleDir = build.moduleDir
  override def npmDeps = Task { Seq("hono@^4.9.0") }

  object test extends BunTypeScriptTests
}
```

```bash
./mill app.bunLock
./mill app.run
./mill app.bundle
./mill app.compileExecutable
./mill app.test.test
```

## Web applications

Use the paired web traits when HTML and static assets are part of the application:

```scala
object frontend extends BunScalaJSWebModule {
  def scalaVersion = "3.8.2"
  def scalaJSVersion = "1.22.0"
  override def moduleKind = Task { ModuleKind.ESModule }
}

object admin extends BunTypeScriptWebModule
```

Both traits use `index.html` as the entrypoint, copy `public/`, generate a minimal page when HTML is absent, and emit an optimized `dist` from `bundle`.

```bash
./mill --watch frontend.dev   # reliable Scala.js relink + browser reload
./mill admin.dev              # Bun-native TypeScript HMR
./mill frontend.bundle
./mill admin.bundle
```

Configure `webEntryPoints`, `webPublicSources`, `webDevPort`, and `webDevArgs` for non-default layouts.

## Reproducible installs

Dependency-bearing modules require a source-controlled text `bun.lock` by default. Generate it with the module's `bunLock` command. Normal installs then use `--frozen-lockfile` and fail before resolution when the lock is missing.

For migration or intentionally ephemeral builds, set `MILL_BUN_REQUIRE_LOCKFILE=false` or override `bunRequireLockfile`. `bunInstallExtraArgs` accepts additional flags but cannot disable the plugin's lockfile safety.

The dependency model is shared across Scala.js and TypeScript:

| Setting | Meaning |
|---|---|
| `npmDeps` | Runtime dependencies |
| `npmDevDeps` | Local development/tool dependencies; never published transitively |
| `npmOptionalDeps` | Optional runtime dependencies |
| `npmPeerDeps` | Requirements supplied by the consumer |
| `npmOverrides` | Explicit resolution for otherwise conflicting declarations |
| `bunPackageJsonExtras` | Unmodeled fields such as `scripts`; typed dependency fields are rejected here |

The `bun"pkg@specifier"` interpolator is an optional compile-time validator for dependency strings. Unversioned dependencies resolve explicitly to `latest`; contradictory requirements fail unless selected by `npmOverrides`.

## Managed Bun

Resolution order is:

1. `bunExecutableOverride`
2. system `PATH` when `bunUseSystem` or `MILL_BUN_USE_SYSTEM=true`
3. checksum-verified managed Bun 1.4.0

Use `./mill app.bunDoctor` to print and validate the resolved executable, version, revision, mode, selected release asset, and linker.

To run a Bun version with no bundled checksum, set `bunVersion` and `bunArchiveSha256` — the download URL is derived from the version and the detected platform:

```scala
def bunVersion = Task { "1.4.1" }
def bunArchiveSha256 = Task { Some("<sha256 from the release SHASUMS256.txt>") }
```

Set `bunArchiveUrl` as well to download from a mirror; a mirror always requires `bunArchiveSha256` so the archive stays verified.

## Mixed Scala.js and TypeScript workspaces

`BunWorkspaceModule` gives multiple packages one install and one root lockfile:

```scala
import mill.bun.*

object scalaApp extends BunScalaJSModule {
  def scalaVersion = "3.8.2"
  def scalaJSVersion = "1.22.0"
  override def bunWorkspaceInstall = Task { Some(workspace.bunInstall()) }
}

object tsApp extends BunTypeScriptModule {
  override def bunWorkspaceInstall = Task { Some(workspace.bunInstall()) }
}

object workspace extends BunWorkspaceModule {
  def bunWorkspacePackages = Seq(scalaApp, tsApp)
}
```

Run `./mill workspace.bunLock` once, commit `workspace/bun.lock`, and use either package normally. The generated root uses Bun workspaces and both member modules link to the same installed `node_modules`.

## Publishing Scala.js libraries

Mix `BunPublishModule` into a published Scala.js library to emit `META-INF/bun/bun-dependencies.json`:

```scala
object ui extends BunScalaJSModule with BunPublishModule {
  def scalaVersion = "3.8.2"
  def scalaJSVersion = "1.22.0"
  override def npmPeerDeps = Task { Seq("react@^19.0.0") }
}
```

Manifest schema v2 publishes direct runtime, optional, and peer requirements. Development dependencies remain local. Consumers still read schema v1 manifests, but malformed metadata and contradictory requirements fail clearly instead of being ignored or resolved by order.

`bunPublishVendoredRuntime = true` can additionally embed `node_modules`; use it only for platform-independent dependency closures.

## Main modules and tasks

- `BunToolchainModule`: managed/system Bun resolution, lock policy, environment, install flags, and `bunDoctor`.
- `BunScalaJSModule`: Scala.js linking, Bun runtime, `bundle`, `bundleFast`, and executable compilation.
- `BunTypeScriptModule`: Bun-backed install, TypeScript compile, run, bundle, tests, and executables.
- `BunScalaJSWebModule` / `BunTypeScriptWebModule`: paired HTML development and production builds.
- `BunWorkspaceModule`: one install and lockfile for mixed package graphs.
- `BunPublishModule`: transitive npm metadata for published Scala.js libraries.
- `BunWorkersModule`: bundles TypeScript worker entrypoints while preserving layout.
- `BunSQLiteModule`: stages SQLite resources for compiled executables.

The older Scala.js `bunBundle*` and `bunCompile*` names remain as compatibility aliases during the 0.x migration. New code should use the idiomatic `bundle*` and `compile*` names.

## Development

```bash
./mill --no-server millbun.compile
./mill --no-server millbun.test
MILL_BUN_USE_SYSTEM=true MILL_BUN_REQUIRE_LOCKFILE=false \
  ./mill --no-server millbun.integration
```

See [the 0.3 migration guide](docs/MIGRATING-0.3.md), the runnable `example-*` projects, and [the release runbook](docs/RELEASING.md).

## License

MIT
