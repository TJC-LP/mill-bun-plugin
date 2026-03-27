package mill.bun

import mill.*

/**
 * Mixin trait for modules that use Bun Workers requiring separate bundling.
 *
 * Worker files need to be bundled as separate entry points alongside the main
 * bundle. This trait provides a `bundleWorkers` task that bundles each worker
 * entry point independently.
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
trait BunWorkersModule extends BunToolchainModule {

  /** Worker entry point files to bundle separately. */
  def workerEntryPoints: T[Seq[PathRef]] = Task { Seq.empty }

  /** Target for worker bundles. Defaults to "bun". */
  def workerBundleTarget: T[String] = Task { "bun" }

  /** Extra raw flags for worker bundling. */
  def workerBundleArgs: T[Seq[String]] = Task { Seq.empty }

  /** Bundle all worker entry points into a workers/ output directory. */
  def bundleWorkers: T[PathRef] = Task {
    val outDir = Task.dest / "workers"
    os.makeDir.all(outDir)

    val entries = workerEntryPoints()
    if (entries.isEmpty) Task.fail("workerEntryPoints is empty. Provide worker file paths.")

    val target = workerBundleTarget()
    val extraArgs = workerBundleArgs()

    entries.foreach { entry =>
      runBun(
        bunExecutable(),
        Seq(
          "build",
          entry.path.toString,
          "--outdir",
          outDir.toString,
          "--target",
          target
        ) ++ extraArgs,
        cwd = entry.path / os.up,
        env = bunEnv()
      )
    }

    PathRef(outDir)
  }
}
