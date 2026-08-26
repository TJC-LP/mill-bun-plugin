package mill.bun

import mill.testkit.IntegrationTester
import utest.*

object BunManagedToolchainIntegrationTests extends TestSuite:
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  def tests: Tests = Tests:
    test("resolved toolchain runs the pinned Bun version"):
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "managed-bun",
        millExecutable = millExe,
        useInMemory = true
      )
      val result = tester.eval("app.bunExecutable")
      assert(result.isSuccess)
      val executable = tester.out("app.bunExecutable").value[String]
      val version = os.proc(executable, "--version").call(stdout = os.Pipe).out.text().trim
      assert(version == "1.4.0")

    test("an evicted download cache is repopulated, not trusted"):
      val cacheDir = os.temp.dir()
      val env = Map("MILL_BUN_CACHE_DIR" -> cacheDir.toString)
      // Forked evals, not in-memory: `Task.env` reads the Mill process's own environment, so a
      // per-eval env override only reaches a subprocess.
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "managed-bun",
        millExecutable = millExe,
        useInMemory = false
      )
      assert(tester.eval("app.bunExecutable", env = env).isSuccess)
      // Proves MILL_BUN_CACHE_DIR reached the build before the eviction step relies on it.
      assert(os.walk(cacheDir).exists(p => os.isFile(p)))

      // Users legitimately evict the cache directory; the build must recover on its own
      // rather than trust a stale task result pointing at a file that no longer exists.
      os.remove.all(cacheDir)
      os.makeDir.all(cacheDir)

      assert(tester.eval("app.bunExecutable", env = env).isSuccess)
      val executable = tester.out("app.bunExecutable").value[String]
      assert(os.isFile(os.Path(executable)))
      val version = os.proc(executable, "--version").call(stdout = os.Pipe).out.text().trim
      assert(version == "1.4.0")
