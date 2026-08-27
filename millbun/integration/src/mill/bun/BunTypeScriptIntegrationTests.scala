package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest._

object BunTypeScriptIntegrationTests extends BunIntegrationSuite {
  def tests: Tests = Tests {

    test("compile") {
      val tester = this.tester("typescript-simple")
      val res = tester.eval("app.compile")
      assert(res.isSuccess)
    }

    test("bundle") {
      val tester = this.tester("typescript-bundle")
      val res = tester.eval("app.bundle")
      assert(res.isSuccess)

      val bundle = outputPath(tester, "app.bundle")
      assert(os.exists(bundle / os.up / "resources" / "nested" / "config.json"))

      val run = os.call(
        Seq("bun", bundle.toString),
        cwd = bundle / os.up
      )
      assert(run.out.text().trim == "Hello from bundled TypeScript resources!")
    }

    test("web bundle includes HTML CSS and JavaScript") {
      val tester = this.tester("typescript-web")
      val res = tester.eval("app.bundle")
      assert(res.isSuccess)

      val dist = outputPath(tester, "app.bundle")
      val files = os.walk(dist).filter(os.isFile)
      assert(files.exists(_.ext == "html"))
      assert(files.exists(_.ext == "css"))
      assert(files.exists(_.ext == "js"))
    }

    test("run") {
      val tester = this.tester("typescript-simple")
      val res = tester.eval("app.run")
      assert(res.isSuccess)

      val log = os.read(commandLogPath(tester, "app.run")).trim
      assert(log == "Hello from TypeScript on Bun!")
    }

    test("compile-executable") {
      val tester = this.tester("typescript-compile")
      val res = tester.eval("app.compileExecutable")
      assert(res.isSuccess)

      val executable = outputPath(tester, "app.compileExecutable")
      val run = os.call(
        Seq(executable.toString),
        cwd = executable / os.up
      )
      assert(run.out.text().trim == "Hello from compiled TypeScript executable!")

      // BunSQLiteModule feeds discovered databases into the compile workspace, alongside the
      // module's own bunCompileResources (which must chain through super to keep them).
      val workspace = tester.workspacePath / "out" / "app" / "compileExecutable.dest" / "workspace"
      assert(os.exists(workspace / "data" / "app.db"))
    }

    test("npmOverrides resolves conflicting transitive specifiers") {
      // lib pins is-odd@^3.0.0 and app pins is-odd@3.0.1 — without the override this install
      // fails with the deterministic conflict error; npmOverrides is the escape hatch.
      val tester = this.tester("typescript-overrides")
      assert(tester.eval("app.bunInstall").isSuccess)

      val packageJson = ujson.read(os.read(outputPath(tester, "app.bunInstall") / "package.json"))
      assert(packageJson("dependencies").obj("is-odd").str == "3.0.1")
      assert(packageJson("overrides").obj("is-odd").str == "3.0.1")
      assert(os.exists(outputPath(tester, "app.bunInstall") / "node_modules" / "is-odd" / "package.json"))
    }

    test("strict installs require a source lock and bunLock creates it") {
      val tester = this.tester("typescript-lock")
      val missingLock = tester.eval("app.bunInstall")
      assert(!missingLock.isSuccess)

      val lockResult = tester.eval("app.bunLock")
      assert(lockResult.isSuccess)
      assert(os.exists(tester.workspacePath / "bun.lock"))

      val installResult = tester.eval("app.bunInstall")
      assert(installResult.isSuccess)
      val args = os.read(tester.workspacePath / "out" / "app" / "bunInstall.dest" / ".bun-args")
      assert(args.contains("--frozen-lockfile"))
    }

    test("a lockfile from a newer Bun fails with regeneration guidance") {
      // bun.lock is forward- but not backward-compatible; bun's own failure is a raw
      // UnknownLockfileVersion that never mentions bunLock. The fixture's stub proves the
      // guard fires before any bun subprocess is reached.
      val tester = this.tester("typescript-stale-lock")
      val res = tester.eval("app.bunInstall")
      assert(!res.isSuccess)
      // The message text is asserted at unit level (lockfileSkewError); in-memory evals expose
      // no readable error stream, so here the contract is: fail, and never reach bun.
      assert(!os.exists(tester.workspacePath / "out" / "app" / "bunInstall.dest" / ".stub-bun-ran"))
    }

    test("bun target ambient types are pinned") {
      val tester = this.tester("typescript-simple")
      val res = tester.eval("app.bunInstall")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "app" / "bunInstall.dest" / "package.json"))
      val devDeps = packageJson("devDependencies").obj

      // Pinned, and pinned to the Bun we ship: @types/bun is published in lockstep with Bun.
      assert(devDeps("@types/bun").str == BunToolchainModule.DefaultBunVersion)
      assert(devDeps("@types/bun").str != "latest")
      assert(!devDeps.contains("@types/node"))
    }

    test("browser target does not install node or bun ambient types") {
      val tester = this.tester("typescript-browser")
      val res = tester.eval("app.compile")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "app" / "bunInstall.dest" / "package.json"))
      val devDeps = packageJson("devDependencies").obj

      assert(devDeps("typescript").str == "5.7.3")
      assert(!devDeps.contains("@types/node"))
      assert(!devDeps.contains("@types/bun"))
    }

    test("bun test module") {
      val tester = this.tester("typescript-tests")
      val res = tester.eval("app.test.testForked")
      assert(res.isSuccess)
      // Deprecated and inherited aliases must keep resolving until removal.
      assert(tester.eval("app.test.test").isSuccess)
      assert(tester.eval("app.npmInstall").isSuccess)
      assert(outputPath(tester, "app.npmInstall") == tester.workspacePath / "out" / "app" / "bunInstall.dest")
    }

    test("bundle workers") {
      val tester = this.tester("typescript-workers")
      val res = tester.eval("app.bundleWorkers")
      assert(res.isSuccess)

      val workersDir = outputPath(tester, "app.bundleWorkers")
      val alphaWorker = workersDir / "src" / "workers" / "alpha" / "worker.js"
      val betaWorker = workersDir / "src" / "workers" / "beta" / "worker.js"

      assert(os.exists(alphaWorker))
      assert(os.exists(betaWorker))

      val alphaRun = os.call(Seq("bun", alphaWorker.last), cwd = alphaWorker / os.up)
      val betaRun = os.call(Seq("bun", betaWorker.last), cwd = betaWorker / os.up)

      assert(alphaRun.out.text().trim == "Alpha worker")
      assert(betaRun.out.text().trim == "Beta worker")
    }

    test("tsx entrypoint fallback") {
      val tester = this.tester("typescript-tsx")
      val res = tester.eval("app.run")
      assert(res.isSuccess)

      val log = os.read(commandLogPath(tester, "app.run")).trim
      assert(log.contains("Hello from TSX"))
    }

    test("bunfig propagates to compile workspace without leaking .npmrc") {
      val tester = this.tester("typescript-bunfig")
      val res = tester.eval("app.compile")
      assert(res.isSuccess)

      val installDir = tester.workspacePath / "out" / "app" / "bunInstall.dest"
      val compileDir = tester.workspacePath / "out" / "app" / "compile.dest"

      // Install workspace keeps both configs.
      assert(os.exists(installDir / ".npmrc"))
      assert(os.exists(installDir / "bunfig.toml"))
      // Compile workspace should only get bunfig.
      assert(os.exists(compileDir / "bunfig.toml"))
      assert(!os.exists(compileDir / ".npmrc"))
    }

    test("test deps are devDependencies") {
      val tester = this.tester("typescript-test-deps")
      // The fixture sets bunRequireLockfile, so both modules need a lock before installing.
      assert(tester.eval("app.bunLock").isSuccess)
      assert(tester.eval("app.test.bunLock").isSuccess)

      // Outer module should have is-even in dependencies
      val outerRes = tester.eval("app.bunInstall")
      assert(outerRes.isSuccess)
      val outerPkg = ujson.read(os.read(tester.workspacePath / "out" / "app" / "bunInstall.dest" / "package.json"))
      assert(outerPkg("dependencies").obj.contains("is-even"))
      assert(!outerPkg("dependencies").obj.contains("is-odd"))

      // Test module should have is-odd in devDependencies (not dependencies)
      val testRes = tester.eval("app.test.bunInstall")
      assert(testRes.isSuccess)
      val testPkg = ujson.read(os.read(tester.workspacePath / "out" / "app" / "test" / "bunInstall.dest" / "package.json"))
      assert(testPkg("devDependencies").obj.contains("is-odd"))
      assert(!testPkg("dependencies").obj.contains("is-odd"))
      assert(!testPkg("devDependencies").obj.contains("is-even"))
      // Outer deps should also be present
      assert(testPkg("dependencies").obj.contains("is-even"))

      // Tests should actually run (both deps available)
      val runRes = tester.eval("app.test.testForked")
      assert(runRes.isSuccess)
    }

    test("test modules with extra deps own their lockfile") {
      // A test module installs a strict superset of the outer package.json. Reusing the outer
      // module's lock under --frozen-lockfile fails with "lockfile had changes, but lockfile is
      // frozen", so the test module needs its own lock and its own bunLock command.
      val tester = this.tester("typescript-test-deps")

      // Without any lock, the install refuses and names the test module's own path.
      val unlocked = tester.eval("app.test.bunInstall")
      assert(!unlocked.isSuccess)

      assert(tester.eval("app.bunLock").isSuccess)
      assert(tester.eval("app.test.bunLock").isSuccess)

      val outerLock = tester.workspacePath / "bun.lock"
      val testLock = tester.workspacePath / "test" / "bun.lock"
      assert(os.exists(outerLock))
      assert(os.exists(testLock))
      assert(os.read(outerLock) != os.read(testLock))

      // Compare the `workspaces` block, which records the root package's *declared* deps.
      // is-odd also arrives transitively through is-even, so a whole-file substring match
      // would not distinguish the two locks.
      def declaredDeps(lock: os.Path): String =
        val text = os.read(lock)
        text.slice(text.indexOf("\"workspaces\""), text.indexOf("\"packages\""))

      assert(!declaredDeps(outerLock).contains("is-odd"))
      assert(declaredDeps(testLock).contains("is-odd"))

      // The frozen install now succeeds against the test module's own lock.
      assert(tester.eval("app.test.bunInstall").isSuccess)
      assert(tester.eval("app.test.testForked").isSuccess)
    }

    test("unmanaged local packages install under a frozen lockfile") {
      // Positional install paths turned `bun install` into `bun add`, which --frozen-lockfile
      // unconditionally rejects — unmanagedDeps never worked against a lockfile at all.
      val tester = this.tester("typescript-unmanaged")
      assert(tester.eval("app.bunLock").isSuccess)
      val lock = os.read(tester.workspacePath / "bun.lock")
      assert(lock.contains("file:vendor/local-lib"))
      // The lock must not record where this repository happens to be checked out.
      assert(!lock.contains(tester.workspacePath.toString))

      assert(tester.eval("app.bunInstall").isSuccess)
      val installed = outputPath(tester, "app.bunInstall")
      assert(os.exists(installed / "node_modules" / "local-lib" / "package.json"))

      assert(tester.eval("app.bundle").isSuccess)
      val bundle = outputPath(tester, "app.bundle")
      val run = os.call(Seq("bun", bundle.toString))
      assert(run.out.text().contains("hello from local-lib"))
    }

    test("test modules adding nothing reuse the outer install") {
      // A bare test module must not demand a second lockfile.
      val tester = this.tester("typescript-tests")
      assert(tester.eval("app.test.bunInstall").isSuccess)
      assert(!os.exists(tester.workspacePath / "test" / "bun.lock"))
      // And must actually reuse the outer install — not run a second one that merely succeeds
      // because the lockfile requirement happens to be off in this suite.
      val installPath = outputPath(tester, "app.test.bunInstall")
      assert(installPath == tester.workspacePath / "out" / "app" / "bunInstall.dest")
    }

    test("bunEnv") {
      val tester = this.tester("typescript-env")
      val res = tester.eval("app.bundle")
      assert(res.isSuccess)

      val installLog = os.read(tester.workspacePath / "out" / "app" / "bunInstall.dest" / ".bun-env-log")
      val compileLog = os.read(tester.workspacePath / "out" / "app" / "compile.dest" / ".bun-env-log")
      val bundleLog = os.read(tester.workspacePath / "out" / "app" / "bundle.dest" / ".bun-env-log")

      assert(installLog.contains("install:present"))
      assert(compileLog.contains("x:present"))
      assert(bundleLog.contains("build:present"))
    }
  }
}
