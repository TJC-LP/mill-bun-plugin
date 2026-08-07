package mill.scalajslib
package bun

import mill.*
import mill.bun.{BunToolchainModule, BunWebSupport}

/** Scala.js web application served and bundled through Bun's HTML pipeline. */
trait BunScalaJSWebModule extends BunScalaJSModule:

  /** HTML entrypoints. A minimal index.html is generated when none exist. */
  def webEntryPoints: T[Seq[PathRef]] = Task.Sources(moduleDir / "index.html")

  /** Static web files copied beneath `public/`. */
  def webPublicSources: T[Seq[PathRef]] = Task.Sources(moduleDir / "public")

  def webDevPort: T[Int] = Task { 3000 }

  def webDevArgs: T[Seq[String]] = Task { Seq.empty }

  private def webDevelopmentStage: T[PathRef] = Task {
    val linked = fastLinkJS()
    val destination = Task.dest
    BunWebSupport.copyContents(linked.dest.path, destination)
    BunWebSupport.copyPreservingModuleDir(webEntryPoints(), moduleDir, destination)
    BunWebSupport.copyPreservingModuleDir(webPublicSources(), moduleDir, destination)

    val entrypoint = primaryEntrypoint(linked)
    val stableEntrypoint = destination / "main.js"
    if entrypoint != stableEntrypoint then os.copy.over(entrypoint, stableEntrypoint, createFolders = true)
    BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, destination, "./main.js")
    PathRef(destination)
  }

  private def webProductionStage: T[PathRef] = Task {
    val linked = fullLinkJS()
    val destination = Task.dest
    BunWebSupport.copyContents(linked.dest.path, destination)
    BunWebSupport.copyPreservingModuleDir(webEntryPoints(), moduleDir, destination)
    BunWebSupport.copyPreservingModuleDir(webPublicSources(), moduleDir, destination)

    val entrypoint = primaryEntrypoint(linked)
    val stableEntrypoint = destination / "main.js"
    if entrypoint != stableEntrypoint then os.copy.over(entrypoint, stableEntrypoint, createFolders = true)
    BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, destination, "./main.js")
    PathRef(destination)
  }

  /** Start Bun's HTML development server. Use `mill --watch app.dev` for Scala relinking. */
  def dev(): Command[Unit] = Task.Command {
    val stage = webDevelopmentStage().path
    val entries = BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, stage, "./main.js")
    BunWebSupport.runDevelopmentServer(
      bunExecutable(),
      entries,
      stage,
      webDevPort(),
      webDevArgs(),
      bunEnv()
    )
  }

  /** Build complete optimized HTML/CSS/JavaScript assets under `dist`. */
  override def bundle: T[PathRef] = Task {
    val stage = webProductionStage().path
    val entries = BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, stage, "./main.js")
    val destination = Task.dest / "dist"
    runBun(
      bunExecutable(),
      Seq("build") ++ entries.map(_.toString) ++ Seq("--minify", "--outdir", destination.toString) ++ bunBundleArgs(),
      cwd = stage,
      env = bunEnv()
    )
    PathRef(destination)
  }
