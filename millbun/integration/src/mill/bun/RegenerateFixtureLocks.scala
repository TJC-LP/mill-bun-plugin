package mill.bun

import mill.testkit.IntegrationTester
import utest.*

/**
 * Maintenance entry point, not a test: regenerates every committed fixture lockfile with the
 * pinned Bun, so a dependency-default change or a `bunVersion` bump is one command instead of a
 * hunt across fixtures. Without the env guard it reports what would run and does nothing.
 *
 * {{{
 * MILL_BUN_REGENERATE_LOCKS=1 ./mill millbun.integration.testOnly mill.bun.RegenerateFixtureLocks
 * }}}
 */
object RegenerateFixtureLocks extends TestSuite:
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  /** Fixture -> the bunLock commands that produce its committed lockfiles. */
  val lockedFixtures: Seq[(String, Seq[String])] = Seq(
    "scalajs-bundle" -> Seq("app.bunLock"),
    "scalajs-transitive" -> Seq("app.bunLock"),
    "scalajs-web" -> Seq("app.bunLock"),
    "typescript-browser" -> Seq("app.bunLock"),
    "typescript-bundle" -> Seq("app.bunLock"),
    "typescript-bunfig" -> Seq("app.bunLock"),
    "typescript-compile" -> Seq("app.bunLock"),
    "typescript-env" -> Seq("app.bunLock"),
    "typescript-simple" -> Seq("app.bunLock"),
    "typescript-tests" -> Seq("app.bunLock"),
    "typescript-tsx" -> Seq("app.bunLock"),
    "typescript-web" -> Seq("app.bunLock"),
    "typescript-workers" -> Seq("app.bunLock")
  )

  def tests: Tests = Tests:
    test("regenerate"):
      if !sys.env.get("MILL_BUN_REGENERATE_LOCKS").exists(_.nonEmpty) then
        println(
          s"MILL_BUN_REGENERATE_LOCKS is not set; would regenerate locks for " +
            s"${lockedFixtures.size} fixtures. Set it to 1 to actually rewrite them."
        )
      else
        lockedFixtures.foreach { case (fixture, commands) =>
          val tester = new IntegrationTester(
            daemonMode = false,
            workspaceSourcePath = resourceDir / fixture,
            millExecutable = millExe,
            useInMemory = true
          )
          commands.foreach { command =>
            val result = tester.eval(command)
            Predef.assert(result.isSuccess, s"$fixture: $command failed")
          }
          val generated = os
            .walk(tester.workspacePath, skip = p => p.last == "out" || p.last == "node_modules")
            .filter(_.last == "bun.lock")
          Predef.assert(generated.nonEmpty, s"$fixture: no bun.lock produced")
          generated.foreach { lock =>
            val target = resourceDir / fixture / lock.relativeTo(tester.workspacePath)
            os.copy.over(lock, target, createFolders = true)
            println(s"regenerated ${target.relativeTo(resourceDir)}")
          }
        }
