package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest.TestSuite

/**
 * Shared harness for fixture-based integration suites.
 *
 * Every suite forks the same Mill executable against a fixture copied out of
 * `millbun/integration/resources`, and most assertions navigate Mill's `out/<module>/<task>`
 * layout — this trait owns both, so the layout is encoded once instead of per suite.
 */
trait BunIntegrationSuite extends TestSuite {
  val resourceDir: os.Path =
    os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  protected def tester(resource: String): IntegrationTester =
    new IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = resourceDir / resource,
      millExecutable = millExe,
      useInMemory = true
    )

  /** The path a task's `PathRef` result points at. */
  protected def outputPath(tester: IntegrationTester, selector: String): os.Path =
    tester.out(selector).value[PathRef].path

  /** Mill's per-command log file, where a forked command's console output lands. */
  protected def commandLogPath(tester: IntegrationTester, selector: String): os.Path = {
    val segments = selector.split('.')
    val rel =
      if (segments.length <= 1) os.RelPath(".")
      else os.RelPath(segments.dropRight(1).mkString("/"))
    tester.workspacePath / "out" / rel / s"${segments.last}.log"
  }
}
