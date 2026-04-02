# mill-bun-plugin: Production-Ready Plan

## Context

The mill-bun-plugin is a scaffold that adds Bun-backed workflows (install, run, bundle, test) to Mill's Scala.js and TypeScript modules. It compiles but has never been verified end-to-end. The goal is to make it production-ready: fix bugs, add tests, set up CI, and prepare for publishing under `com.tjclp`.

All Mill 1.1.5 APIs have been verified compatible with the current source.

---

## Phase 1: Build Infrastructure

### 1a. Create `.mill-version`
- New file containing `1.1.5`

### 1b. Update `build.mill`
- `millVersion` → `"1.1.5"`
- `organization` → `"com.tjclp"`
- `url` → `"https://github.com/tjc-lp/mill-bun-plugin"`
- `versionControl` → `VersionControl.github("tjc-lp", "mill-bun-plugin")`
- `developers` → TJC-LP placeholder
- Try removing explicit `upickle:4.3.2` dep (ujson comes transitively from `mill-libs`)
- Add integration test module (see Phase 4)

### 1c. Update example headers
- Both `example-scalajs/build.mill` and `example-typescript/build.mill`: `mill-version: 1.1.0` → `1.1.5`, artifact coordinates → `com.tjclp::mill-bun_mill1:0.1.0-SNAPSHOT`

### 1d. Verify compilation
- Run `./mill millbun.compile`

**Files**: `.mill-version` (new), `build.mill`, `example-scalajs/build.mill`, `example-typescript/build.mill`

---

## Phase 2: Code Correctness

### 2a. Extract shared code into `BunToolchainModule`

Move duplicated members from both `BunScalaJSModule` and `BunTypeScriptModule` into `BunToolchainModule`:
- `splitDep` → companion `object BunToolchainModule` (makes it unit-testable)
- `bunLockfiles` task
- `bunFrozenLockfile` task
- `bunLinker` task
- `bunInstallArgs` task

### 2b. Fix `runBun` return type
- Change from `Unit` to `os.CommandResult` (remove trailing `()`)

### 2c. Fix `BunTypeScriptTests.npmInstall`
Current code installs into `outer.npmInstall().path` which overwrites the outer module's install. Fix: install into `Task.dest` so the test module gets its own isolated install directory.

### 2d. Remove redundant `ensureLinkedWorkspace` calls
`bunBundle`, `bunBundleFast`, and `bunCompileExecutable` all call `ensureLinkedWorkspace` after `fullLinkJS()`/`fastLinkJS()` which already calls it. Remove the redundant calls.

### 2e. Fix `shallowMerge` in BunTypeScriptModule
`shallowMerge` is only used once; inline it since we're already simplifying.

**Files**: `src/mill/bun/BunToolchainModule.scala`, `src/mill/scalajslib/bun/BunScalaJSModule.scala`, `src/mill/javascriptlib/bun/BunTypeScriptModule.scala`

---

## Phase 3: Repository Setup

### 3a. Initialize git repo
```sh
git init
```

### 3b. Create `.gitignore`
```
out/
.bsp/
.idea/
.metals/
.vscode/
.bloop/
node_modules/
.DS_Store
```

### 3c. Create `LICENSE` (MIT)

### 3d. Initial commit

**Files**: `.gitignore` (new), `LICENSE` (new)

---

## Phase 4: Integration Tests

### 4a. Restructure `build.mill` test modules

Replace the existing `object test` with:

```scala
object test extends ScalaTests with TestModule.Utest {
  // Unit tests for pure logic (splitDep, etc.)
}

object integration extends ScalaTests with TestModule.Utest {
  // IntegrationTester-based end-to-end tests
  // Env: MILL_EXECUTABLE_PATH, MILL_TEST_RESOURCE_DIR
}
```

### 4b. Create test fixture projects

Minimal Mill projects under `millbun/integration/resources/`:

| Fixture | What it tests |
|---------|--------------|
| `scalajs-simple/` | `fastLinkJS`, `run` via Bun |
| `scalajs-bundle/` | `bunInstall` + `bunBundle` with npm dep |
| `typescript-simple/` | `compile` + `run` via Bun |
| `typescript-bundle/` | `bundle` via `bun build` |

Each fixture has a `build.mill` + minimal source file.

### 4c. Write integration tests

- `BunScalaJSIntegrationTests.scala` — tests `fastLinkJS`, `bunInstall`, `bunBundle`
- `BunTypeScriptIntegrationTests.scala` — tests `compile`, `run`, `bundle`

### 4d. Write unit tests

- `SplitDepTests.scala` — tests `BunToolchainModule.splitDep` for simple deps, scoped deps, no-version, latest tag

**New files**:
- `millbun/test/src/mill/bun/SplitDepTests.scala`
- `millbun/integration/src/mill/bun/BunScalaJSIntegrationTests.scala`
- `millbun/integration/src/mill/bun/BunTypeScriptIntegrationTests.scala`
- `millbun/integration/resources/scalajs-simple/{build.mill, src/Main.scala}`
- `millbun/integration/resources/scalajs-bundle/{build.mill, src/Main.scala}`
- `millbun/integration/resources/typescript-simple/{build.mill, src/main.ts}`
- `millbun/integration/resources/typescript-bundle/{build.mill, src/main.ts}`

---

## Phase 5: CI

### 5a. Create `.github/workflows/ci.yml`

- Triggers: push to main, PRs
- Matrix: ubuntu-latest + macos-latest
- Steps: checkout, setup-java (temurin 11), setup-bun, compile, unit test, integration test

**New file**: `.github/workflows/ci.yml`

---

## Phase 6: Documentation

### 6a. Rewrite `README.md`
- Remove "MVP/scaffold/not production-ready" language
- Add: quick start, configuration reference table, requirements (Mill 1.1.5+, Bun 1.2+, JDK 11+)
- Link to examples

**File**: `README.md`

---

## Phase 7: Verification

1. `./mill millbun.compile` — plugin compiles
2. `./mill millbun.test` — unit tests pass
3. `./mill millbun.integration` — integration tests pass (requires Bun on PATH)
4. Manually run example projects to validate end-to-end

---

## Key Architectural Decisions

- **Mill 1.1.5** target (latest stable 1.x)
- **IntegrationTester** for tests — spawns real Mill processes against fixture projects
- **`splitDep` on companion object** — enables unit testing without Mill machinery
- **Shared defaults in `BunToolchainModule`** — eliminates duplication, single override point
- **`BunTypeScriptTests` gets its own install dir** — prevents contaminating outer module's cached install
