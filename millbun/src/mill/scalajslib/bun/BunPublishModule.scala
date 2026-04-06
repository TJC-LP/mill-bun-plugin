package mill.scalajslib
package bun

import mill.*
import mill.bun.{BunManifest, BunToolchainModule}

/** Opt-in trait for Scala.js libraries that publish JARs with embedded bun dependency manifests.
  *
  * Mix this into modules whose JARs should carry `META-INF/bun/bun-dependencies.json`
  * so that consumers automatically resolve JS package dependencies via `classpathBunDeps`.
  *
  * {{{
  * object myLib extends BunScalaJSModule with BunPublishModule {
  *   def bunDeps = Task { Seq(bun"react@^19.0.0") }
  * }
  * }}}
  */
trait BunPublishModule extends BunScalaJSModule {

  /** Generate bun dependency manifest + lockfile for inclusion in published JARs.
    *
    * The manifest declares this library's JS package requirements so that
    * consumers automatically get them via `classpathBunDeps`. The lockfile
    * is embedded alongside for deterministic resolution seeding.
    */
  def bunDependencyManifest: T[PathRef] = Task {
    val allDeps = (npmDeps() ++ bunDeps()).map(BunToolchainModule.splitDep).map((k, v) => k -> v.str).toMap
    val allDevDeps = (npmDevDeps() ++ bunDevDeps()).map(BunToolchainModule.splitDep).map((k, v) => k -> v.str).toMap
    val optDeps = bunOptionalDeps().map(BunToolchainModule.splitDep).map((k, v) => k -> v.str).toMap
    val manifest = BunManifest(allDeps, allDevDeps, optDeps)
    val metaDir = Task.dest / "META-INF" / "bun"
    os.write(metaDir / "bun-dependencies.json", BunManifest.toJson(manifest).render(indent = 2), createFolders = true)
    PathRef(Task.dest)
  }

  /** Resource paths that include the bun dependency manifest.
    *
    * When this module declares any JS deps, the manifest and lockfile
    * are embedded in the published JAR.
    */
  def bunDependencyManifestResources: T[Seq[PathRef]] = Task {
    if npmDeps().nonEmpty || bunDeps().nonEmpty || bunOptionalDeps().nonEmpty then
      Seq(bunDependencyManifest())
    else Seq.empty
  }

  override def resources: T[Seq[PathRef]] = Task {
    super.resources() ++ bunDependencyManifestResources()
  }
}
