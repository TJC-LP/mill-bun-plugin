package mill.bun

import mill.*
import mill.api.BuildCtx

object BunToolchainModule {

  /** Parse a dependency string like "react@19.1.1" or "@types/node@22.10.9" into (name, version). */
  def splitDep(input: String): (String, ujson.Str) = input match {
    case s if s.startsWith("@") =>
      val withoutAt = s.drop(1)
      val parts = withoutAt.split("@", 2)
      ("@" + parts(0), ujson.Str(parts.lift(1).getOrElse("")))
    case _ =>
      val parts = input.split("@", 2)
      (parts(0), ujson.Str(parts.lift(1).getOrElse("")))
  }

  /** Build candidate executable names from a base name and PATHEXT extensions.
   *  PATHEXT is Windows-specific and always semicolon-delimited regardless of platform. */
  def executableCandidates(name: String, pathExt: String): Seq[String] = {
    val extensions = pathExt.split(";").filter(_.nonEmpty)
    if (extensions.nonEmpty) Seq(name) ++ extensions.map(ext => name + ext.toLowerCase)
    else Seq(name)
  }

  /** Resolve an executable name from PATH, respecting PATHEXT on Windows. */
  def findOnPath(name: String): Option[os.Path] = {
    val pathDirs = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator)
    val candidates = executableCandidates(name, sys.env.getOrElse("PATHEXT", ""))

    pathDirs.iterator
      .flatMap(dir => candidates.iterator.map(c => os.Path(dir) / c))
      .find(os.exists(_))
  }

  /** Copy a generated workspace into a fresh task destination, preserving layout. */
  def copyWorkspace(source: os.Path, dest: os.Path): Unit = {
    os.walk(source)
      .foreach(path => os.copy.over(path, dest / path.relativeTo(source), createFolders = true))
  }

  /**
   * Copy files or directories into a Bun workspace while preserving their relative path
   * beneath the nearest matching source root when possible.
   */
  def copyPathRefs(
      refs: Seq[PathRef],
      destRoot: os.Path,
      sourceRoots: Seq[os.Path] = Seq.empty
  ): Unit = {
    refs.foreach { ref =>
      val source = ref.path
      val target =
        sourceRoots.iterator
          .find(root => source.startsWith(root) && source != root)
          .map(root => destRoot / source.relativeTo(root))
          .getOrElse(destRoot / source.last)

      if (os.isDir(source)) {
        os.walk(source).foreach { path =>
          os.copy.over(path, target / path.relativeTo(source), createFolders = true)
        }
      } else {
        os.copy.over(source, target, createFolders = true)
      }
    }
  }
}

trait BunToolchainModule extends Module {

  /** Command name used when resolving Bun from PATH. */
  def bunExecutableName: T[String] = Task { "bun" }

  /** Future hook for a managed/downloaded Bun binary. */
  def managedBunExecutable: T[Option[PathRef]] = Task { None }

  /** Environment passed to Bun subprocesses. */
  def bunEnv: T[Map[String, String]] = Task { Map.empty }

  /** Lockfile names that Bun may produce. */
  def bunLockfiles: T[Seq[String]] = Task { Seq("bun.lock", "bun.lockb") }

  /** Use Bun's text lockfile by default; optionally enforce an existing lockfile. */
  def bunFrozenLockfile: T[Boolean] = Task { false }

  /** Hoisted installs are the safest default for Node-compatible resolution. */
  def bunLinker: T[String] = Task { "hoisted" }

  def bunInstallArgs: T[Seq[String]] = Task {
    Seq("--save-text-lockfile", "--linker", bunLinker()) ++
      (if (bunFrozenLockfile()) Seq("--frozen-lockfile") else Nil)
  }

  /**
   * Bun config files to copy into generated workspaces.
   *
   * Bun works without a bunfig, but copying root configs makes the generated
   * task workspaces closer to the source workspace.
   *
   * Declared as Task.Input so Mill's sandbox checker allows reading from the
   * workspace root and re-evaluates when the files change.
   */
  def bunfigFiles: T[Seq[PathRef]] = Task.Input {
    Seq(BuildCtx.workspaceRoot / "bunfig.toml", BuildCtx.workspaceRoot / ".bunfig.toml")
      .filter(os.exists)
      .map(PathRef(_))
  }

  /**
   * Workspace-level lockfile committed by the developer.
   *
   * When present, `bunInstall` / `npmInstall` seed their install directory
   * with this lockfile so Bun resolves deterministic versions.  In CI,
   * combine with `bunFrozenLockfile = true` to reject stale lockfiles.
   *
   * Declared as `Task.Input` so Mill re-evaluates when the file changes
   * on disk and the sandbox checker allows reading from the workspace root.
   */
  def bunWorkspaceLockfile: T[Option[PathRef]] = Task.Input {
    Seq("bun.lock", "bun.lockb")
      .map(name => BuildCtx.workspaceRoot / name)
      .find(os.exists)
      .map(PathRef(_))
  }

  /**
   * Cross-compilation targets for `bun build --compile`.
   * Values: "bun-linux-x64", "bun-darwin-arm64", "bun-windows-x64", etc.
   * Empty means native platform only.
   */
  def bunCompileTargets: T[Seq[String]] = Task { Seq.empty }

  /**
   * Extra files or directories to copy into the compile workspace before
   * `bun build --compile`. Useful for SQLite databases referenced via
   * `import db from "./my.db" with { type: "sqlite", embed: "true" }`.
   */
  def bunCompileResources: T[Seq[PathRef]] = Task { Seq.empty }

  /** Resolve Bun either from a managed binary or from PATH. */
  def bunExecutable: T[String] = Task {
    managedBunExecutable()
      .map(_.path.toString)
      .orElse(BunToolchainModule.findOnPath(bunExecutableName()).map(_.toString))
      .getOrElse(Task.fail(
        s"Unable to find Bun executable '${bunExecutableName()}'. Put Bun on PATH or override managedBunExecutable."
      ))
  }

  /** Run a Bun command. All task values must be resolved before calling this. */
  protected def runBun(
      bunExe: String,
      args: Seq[String],
      cwd: os.Path,
      env: Map[String, String]
  ): os.CommandResult = {
    os.call(
      Seq(bunExe) ++ args,
      cwd = cwd,
      env = env,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }
}
