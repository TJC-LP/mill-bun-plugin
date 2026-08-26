package mill.javascriptlib
package bun

import mill.*
import mill.bun.BunWebSupport

/** TypeScript web application served and bundled through Bun's HTML pipeline. */
trait BunTypeScriptWebModule extends BunTypeScriptModule:

  /** HTML entrypoints. A minimal index.html is generated when none exist. */
  def webEntryPoints: T[Seq[PathRef]] = Task.Sources(moduleDir / "index.html")

  /** Static web files copied beneath `public/`. */
  def webPublicSources: T[Seq[PathRef]] = Task.Sources(moduleDir / "public")

  /** Browser entrypoint used when the plugin generates index.html. */
  def webScriptEntryPoint: T[PathRef] = Task.Input {
    val candidates = Seq(
      moduleDir / "src" / "main.ts",
      moduleDir / "src" / "main.tsx",
      moduleDir / "src" / "index.ts",
      moduleDir / "src" / "index.tsx"
    )
    PathRef(candidates.find(os.exists).getOrElse(
      throw new RuntimeException(
        s"No TypeScript web entrypoint found beneath ${moduleDir / "src"}. Override webScriptEntryPoint."
      )
    ))
  }

  def webDevPort: T[Int] = Task { 3000 }

  def webDevArgs: T[Seq[String]] = Task { Seq.empty }

  private def prepareWebStage(
      destination: os.Path,
      sourceRefs: Seq[PathRef],
      htmlRefs: Seq[PathRef],
      publicRefs: Seq[PathRef],
      install: os.Path,
      configs: Seq[PathRef],
      scriptEntryPoint: os.Path
  ): Seq[os.Path] = {
    BunWebSupport.copyPreservingModuleDir(sourceRefs, moduleDir, destination)
    BunWebSupport.copyPreservingModuleDir(htmlRefs, moduleDir, destination)
    BunWebSupport.copyPreservingModuleDir(publicRefs, moduleDir, destination)
    if os.exists(install / "node_modules") then
      os.symlink(destination / "node_modules", install / "node_modules")
    os.copy.over(install / "package.json", destination / "package.json", createFolders = true)
    configs.foreach(ref => os.copy.over(ref.path, destination / ref.path.last, createFolders = true))

    val script = "./" + scriptEntryPoint.relativeTo(moduleDir).toString.replace('\\', '/')
    BunWebSupport.materializeHtmlEntries(htmlRefs, moduleDir, destination, script)
  }

  /**
   * Staged sources, HTML, static files, and `node_modules` that both `dev` and `bundle` build from.
   *
   * A single task: development and production stage identically, and differ only in the flags
   * `bundle` passes to `bun build`.
   */
  private def webStage: T[PathRef] = Task {
    prepareWebStage(
      Task.dest,
      sources() ++ generatedSources() ++ resources(),
      webEntryPoints(),
      webPublicSources(),
      npmInstall().path,
      bunfigFiles(),
      webScriptEntryPoint().path
    )
    PathRef(Task.dest)
  }

  /** Start Bun's HTML development server with source mirroring for native HMR. */
  def dev(): Command[Unit] = Task.Command {
    // Serve from a private copy: the sync thread mirrors live edits (but never deletions) into
    // the serving root, and `bundle` builds from the same cached stage — mutating it in place
    // would let a file created and deleted during a dev session ship in the production bundle.
    val stage = Task.dest / "stage"
    mill.bun.BunToolchainModule.copyTree(webStage().path, stage)
    val entries = BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, stage)
    val syncRoots = (sources() ++ generatedSources() ++ resources() ++ webEntryPoints() ++ webPublicSources())
      .filter(ref => os.exists(ref.path) && ref.path.startsWith(moduleDir))
      .map(ref => ref.path -> (stage / ref.path.relativeTo(moduleDir)))
    BunWebSupport.runDevelopmentServer(
      bunExecutable(),
      entries,
      stage,
      webDevPort(),
      webDevArgs(),
      bunRuntimeEnv(),
      syncRoots
    )
  }

  /** Build complete optimized HTML/CSS/JavaScript assets under `dist`. */
  override def bundle: T[PathRef] = Task {
    val stage = webStage().path
    val entries = BunWebSupport.htmlEntries(webEntryPoints(), moduleDir, stage)
    val destination = Task.dest / "dist"
    runBun(
      bunExecutable(),
      Seq("build") ++ entries.map(_.toString) ++ Seq("--minify", "--outdir", destination.toString) ++ bunBuildArgs(),
      cwd = stage,
      env = bunToolEnv()
    )
    PathRef(destination)
  }
