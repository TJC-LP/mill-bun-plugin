package mill.scalajslib
package bun

import mill.*
import mill.api.BuildCtx
import mill.bun.{BunManifest, BunToolchainModule, BunVendoredNodeModules}
import mill.scalajslib.api.ModuleKind

/** Opt-in trait for Scala.js libraries that publish JARs with embedded bun dependency manifests.
  *
  * Mix this into modules whose JARs should carry `META-INF/bun/bun-dependencies.json`
  * and can optionally embed a vendored runtime `node_modules` tree for
  * downstream consumers.
  *
  * {{{
  * object myLib extends BunScalaJSModule with BunPublishModule {
  *   def bunDeps = Task { Seq(bun"react@^19.0.0") }
  * }
  * }}}
  */
trait BunPublishModule extends BunScalaJSModule {

  /** Embed resolved `node_modules` into published artifacts.
    *
    * Disabled by default because published JARs are cross-platform, while
    * Bun installs can materialize host-specific binaries or optional packages.
    */
  def bunPublishVendoredRuntime: T[Boolean] = Task { false }

  private def resolvedPublishedManifest: Task[BunManifest] = Task.Anon {
    def typed(deps: Seq[String]): Map[String, String] =
      BunToolchainModule.dependencyPairs(deps, npmOverrides()).map((k, v) => k -> v.str).toMap

    BunManifest(
      dependencies = typed(npmDeps() ++ bunDeps()),
      devDependencies = Map.empty,
      optionalDependencies = typed(npmOptionalDeps() ++ bunOptionalDeps()),
      peerDependencies = typed(npmPeerDeps()),
      schemaVersion = 2
    )
  }

  /** Generate bun dependency manifest for inclusion in published JARs.
    *
    * The manifest describes this library's direct runtime JS requirements.
    */
  def bunDependencyManifest: T[PathRef] = Task {
    val manifest = resolvedPublishedManifest()
    val metaDir = Task.dest / "META-INF" / "bun"
    os.write(metaDir / "bun-dependencies.json", BunManifest.toJson(manifest).render(indent = 2), createFolders = true)
    PathRef(Task.dest)
  }

  /** Install this module's direct runtime JS closure for vendoring in published artifacts. */
  def bunPublishedRuntimeInstall: T[PathRef] = Task {
    val dest = Task.dest
    os.makeDir.all(dest)

    val npmRc = BuildCtx.workspaceRoot / ".npmrc"
    if (os.exists(npmRc)) os.copy.over(npmRc, dest / ".npmrc", createFolders = true)
    bunfigFiles().foreach { cfg =>
      os.copy.over(cfg.path, dest / cfg.path.last, createFolders = true)
    }

    val deps = BunToolchainModule.dependencyPairs(npmDeps() ++ bunDeps(), npmOverrides())
    val optional = BunToolchainModule.dependencyPairs(npmOptionalDeps() ++ bunOptionalDeps(), npmOverrides())
    val base = ujson.Obj(
      "name" -> defaultPackageName,
      "private" -> true,
      "version" -> "0.0.0",
      "dependencies" -> ujson.Obj.from(deps)
    )
    if optional.nonEmpty then
      base("optionalDependencies") = ujson.Obj.from(optional)

    moduleKind() match
      case ModuleKind.ESModule => base("type") = "module"
      case _                   => ()

    val merged = BunToolchainModule.mergePackageJson(base, bunPackageJsonExtras())
    os.write.over(dest / "package.json", merged.render(indent = 2), createFolders = true)

    val hasRuntimeInputs = deps.nonEmpty || optional.nonEmpty || unmanagedDeps().nonEmpty
    val lockfile = bunLockfile()
    requireBunLockfile(hasRuntimeInputs, lockfile, bunRequireLockfile())
    copyBunLockfile(lockfile, dest)
    if hasRuntimeInputs then
      runBun(
        bunExecutable(),
        Seq("install") ++ resolvedBunInstallArgs(
          bunInstallArgs(),
          bunInstallExtraArgs(),
          lockfile.nonEmpty,
          updateLockfile = false
        ) ++ unmanagedDeps().map(_.path.toString),
        cwd = dest,
        env = bunEnv()
      )

    PathRef(dest)
  }

  /** Vendored runtime node_modules for deterministic downstream consumption when enabled. */
  def bunVendoredRuntimeBundle: T[PathRef] = Task {
    val metaDir = Task.dest / "META-INF" / "bun"
    val runtimeNodeModules = bunPublishedRuntimeInstall().path / "node_modules"

    if (os.exists(runtimeNodeModules)) {
      BunVendoredNodeModules.copyResolvedTree(runtimeNodeModules, metaDir / "node_modules")
    }

    PathRef(Task.dest)
  }

  /** Resource paths that include the bun dependency manifest.
    *
    * The manifest is emitted whenever this module declares publishable Bun
    * dependency metadata. Vendored runtime trees are emitted only when
    * `bunPublishVendoredRuntime` is enabled.
    */
  def bunDependencyManifestResources: T[Seq[PathRef]] = Task {
    val manifest = resolvedPublishedManifest()
    val hasManifest =
      manifest.dependencies.nonEmpty ||
        manifest.optionalDependencies.nonEmpty ||
        manifest.peerDependencies.nonEmpty
    val hasVendoredRuntime =
      bunPublishVendoredRuntime() && (
        manifest.dependencies.nonEmpty ||
          manifest.optionalDependencies.nonEmpty ||
          unmanagedDeps().nonEmpty
      )

    (if hasManifest then Seq(bunDependencyManifest()) else Seq.empty) ++
      (if hasVendoredRuntime then Seq(bunVendoredRuntimeBundle()) else Seq.empty)
  }

  override def resources: T[Seq[PathRef]] = Task {
    super.resources() ++ bunDependencyManifestResources()
  }
}
