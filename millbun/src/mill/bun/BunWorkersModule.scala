package mill.bun

import mill.*
import mill.javascriptlib.bun.BunTypeScriptModule

/**
 * Mixin trait for Bun-backed TypeScript modules that use Bun Workers requiring
 * separate bundling.
 *
 * Worker files need to be bundled as separate entry points alongside the main
 * bundle. This trait bundles each worker from the module's prepared compile
 * workspace so it sees the same staged sources, resources, tsconfig, and
 * installed packages as the main module.
 *
 * Usage:
 * {{{
 * object app extends BunTypeScriptModule with BunWorkersModule {
 *   override def workerEntryPoints = Task {
 *     Seq(PathRef(millSourcePath / "src" / "worker.ts"))
 *   }
 * }
 * }}}
 */
trait BunWorkersModule extends BunToolchainModule { this: BunTypeScriptModule =>

  /** Worker entry point files to bundle separately. */
  def workerEntryPoints: T[Seq[PathRef]] = Task { Seq.empty }

  /** Source roots used to locate worker files inside the staged compile workspace. */
  def workerSourceRoots: T[Seq[os.Path]] = Task { Seq(moduleDir) }

  /** Target for worker bundles. Defaults to the module target. */
  def workerBundleTarget: T[String] = Task { bunBundleTarget() }

  /** Output format for worker bundles. Defaults to the module format. */
  def workerBundleFormat: T[Option[String]] = Task { Some(bunBundleFormat()) }

  /** Extra raw flags for worker bundling. */
  def workerBundleArgs: T[Seq[String]] = Task { Seq.empty }

  private def workerRelativePath(entry: os.Path, roots: Seq[os.Path]): os.RelPath =
    roots.iterator
      .find(root => entry.startsWith(root) && entry != root)
      .map(entry.relativeTo(_))
      .orElse {
        Option.when(entry.startsWith(moduleDir) && entry != moduleDir)(entry.relativeTo(moduleDir))
      }
      .getOrElse {
        throw new RuntimeException(
          s"Worker entry point '$entry' is not under any configured workerSourceRoots: ${roots.mkString(", ")}"
        )
      }

  private def workerOutputDir(outRoot: os.Path, relative: os.RelPath): os.Path =
    if (relative.segments.size <= 1) outRoot
    else outRoot / os.RelPath(relative.segments.dropRight(1).mkString("/"))

  /** Bundle all worker entry points into a workers/ output directory. */
  def bundleWorkers: T[PathRef] = Task {
    val workspace = Task.dest / "workspace"
    val outDir = Task.dest / "workers"
    BunToolchainModule.copyWorkspace(compile().path, workspace)
    os.makeDir.all(outDir)

    val entries = workerEntryPoints()
    if (entries.isEmpty) Task.fail("workerEntryPoints is empty. Provide worker file paths.")

    val sourceRoots = workerSourceRoots()
    val target = workerBundleTarget()
    val formatArgs = workerBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val extraArgs = workerBundleArgs()

    entries.foreach { entry =>
      val relative = workerRelativePath(entry.path, sourceRoots)
      val stagedEntry = workspace / relative
      if (!os.exists(stagedEntry)) {
        Task.fail(s"Worker entry point '$relative' was not staged into $workspace.")
      }

      val entryOutDir = workerOutputDir(outDir, relative)
      os.makeDir.all(entryOutDir)

      runBun(
        bunExecutable(),
        Seq(
          "build",
          stagedEntry.relativeTo(workspace).toString,
          "--outdir",
          entryOutDir.toString,
          "--target",
          target
        ) ++ formatArgs ++ extraArgs,
        cwd = workspace,
        env = bunEnv()
      )
    }

    PathRef(outDir)
  }
}
