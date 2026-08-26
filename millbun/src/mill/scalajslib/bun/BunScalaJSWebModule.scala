package mill.scalajslib
package bun

import mill.*
import mill.bun.{BunToolchainModule, BunWebSupport}
import mill.scalajslib.api.Report

/** Scala.js web application served and bundled through Bun's HTML pipeline. */
trait BunScalaJSWebModule extends BunScalaJSModule:

  /** HTML entrypoints. A minimal index.html is generated when none exist. */
  def webEntryPoints: T[Seq[PathRef]] = Task.Sources(moduleDir / "index.html")

  /** Static web files copied beneath `public/`. */
  def webPublicSources: T[Seq[PathRef]] = Task.Sources(moduleDir / "public")

  def webDevPort: T[Int] = Task { 3000 }

  def webDevArgs: T[Seq[String]] = Task { Seq.empty }

  /**
   * Stage linked output, HTML, and static files into a directory Bun can build from.
   *
   * `node_modules` is linked explicitly rather than carried over from the link report, mirroring
   * [[mill.javascriptlib.bun.BunTypeScriptWebModule]]: the staged tree is where `bun build`
   * resolves npm imports emitted by `@JSImport`, so it has to reach the install.
   */
  private def prepareWebStage(
      linked: Report,
      destination: os.Path,
      install: os.Path,
      htmlRefs: Seq[PathRef],
      publicRefs: Seq[PathRef],
      configs: Seq[PathRef]
  ): Unit =
    BunWebSupport.copyContents(linked.dest.path, destination, exclude = Set("node_modules"))
    if os.exists(install / "node_modules") then
      os.symlink(destination / "node_modules", install / "node_modules")
    os.copy.over(install / "package.json", destination / "package.json", createFolders = true)
    configs.foreach(cfg => os.copy.over(cfg.path, destination / cfg.path.last, createFolders = true))

    BunWebSupport.copyPreservingModuleDir(htmlRefs, moduleDir, destination)
    BunWebSupport.copyPreservingModuleDir(publicRefs, moduleDir, destination)

    val entrypoint = primaryEntrypoint(linked)
    val stableEntrypoint = destination / "main.js"
    if entrypoint != stableEntrypoint then os.copy.over(entrypoint, stableEntrypoint, createFolders = true)
    BunWebSupport.materializeHtmlEntries(htmlRefs, moduleDir, destination, "./main.js")

  private def webDevelopmentStage: T[PathRef] = Task {
    prepareWebStage(
      fastLinkJS(),
      Task.dest,
      bunInstall().path,
      webEntryPoints(),
      webPublicSources(),
      bunfigFiles()
    )
    PathRef(Task.dest)
  }

  private def webProductionStage: T[PathRef] = Task {
    prepareWebStage(
      fullLinkJS(),
      Task.dest,
      bunInstall().path,
      webEntryPoints(),
      webPublicSources(),
      bunfigFiles()
    )
    PathRef(Task.dest)
  }

  /** Start Bun's HTML development server. Use `mill --watch app.dev` for Scala relinking. */
  def dev(): Command[Unit] = Task.Command {
    val stage = webDevelopmentStage().path
    val entries = BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, stage)
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
    val entries = BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, stage)
    val destination = Task.dest / "dist"
    runBun(
      bunExecutable(),
      Seq("build") ++ entries.map(_.toString) ++ Seq("--minify", "--outdir", destination.toString) ++ bunBundleArgs(),
      cwd = stage,
      env = bunEnv()
    )
    PathRef(destination)
  }
