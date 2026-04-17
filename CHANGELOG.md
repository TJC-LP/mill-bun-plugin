# Changelog

All notable changes to `mill-bun-plugin` will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Tag-driven release workflow for Maven Central publishing and GitHub releases.
- Release runbook covering secrets, version sweep, annotated tags, and verification.

## [0.2.1] - Overridable test-time JS env

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

