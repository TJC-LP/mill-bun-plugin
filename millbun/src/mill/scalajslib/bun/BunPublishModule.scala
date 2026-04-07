package mill.scalajslib
package bun

import mill.*
import mill.api.BuildCtx
import mill.bun.{BunManifest, BunToolchainModule, BunVendoredNodeModules}
import mill.scalajslib.api.ModuleKind

/** Opt-in trait for Scala.js libraries that publish JARs with embedded bun dependency manifests.
  *
  * Mix this into modules whose JARs should carry `META-INF/bun/bun-dependencies.json`
  * plus vendored runtime `node_modules` so consumers automatically receive the
  * exact JS packages required by the published library.
  *
  * {{{
  * object myLib extends BunScalaJSModule with BunPublishModule {
  *   def bunDeps = Task { Seq(bun"react@^19.0.0") }
  * }
  * }}}
  */
trait BunPublishModule extends BunScalaJSModule {

  /** Generate bun dependency manifest for inclusion in published JARs.
    *
    * The manifest describes this library's direct runtime JS requirements.
    */
  def bunDependencyManifest: T[PathRef] = Task {
    val allDeps = (npmDeps() ++ bunDeps()).map(BunToolchainModule.splitDep).map((k, v) => k -> v.str).toMap
    val optDeps = bunOptionalDeps().map(BunToolchainModule.splitDep).map((k, v) => k -> v.str).toMap
    val manifest = BunManifest(allDeps, Map.empty, optDeps)
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

    val deps = (npmDeps() ++ bunDeps()).map(BunToolchainModule.splitDep)
    val optional = bunOptionalDeps().map(BunToolchainModule.splitDep)
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

    val merged = ujson.Obj.from(base.value.toSeq ++ bunPackageJsonExtras().value.toSeq)
    os.write.over(dest / "package.json", merged.render(indent = 2), createFolders = true)

    val hasRuntimeInputs = deps.nonEmpty || optional.nonEmpty || unmanagedDeps().nonEmpty
    if hasRuntimeInputs then
      runBun(
        bunExecutable(),
        Seq("install") ++ bunInstallArgs() ++ unmanagedDeps().map(_.path.toString),
        cwd = dest,
        env = bunEnv()
      )

    PathRef(dest)
  }

  /** Vendored runtime node_modules for deterministic downstream consumption. */
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
    * When this module declares any runtime JS deps, the manifest and vendored
    * runtime tree are embedded in the published JAR.
    */
  def bunDependencyManifestResources: T[Seq[PathRef]] = Task {
    val hasManifest = npmDeps().nonEmpty || bunDeps().nonEmpty || bunOptionalDeps().nonEmpty
    val hasVendoredRuntime = hasManifest || unmanagedDeps().nonEmpty

    (if hasManifest then Seq(bunDependencyManifest()) else Seq.empty) ++
      (if hasVendoredRuntime then Seq(bunVendoredRuntimeBundle()) else Seq.empty)
  }

  override def resources: T[Seq[PathRef]] = Task {
    super.resources() ++ bunDependencyManifestResources()
  }
}
