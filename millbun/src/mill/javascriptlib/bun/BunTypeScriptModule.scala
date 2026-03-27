package mill.javascriptlib
package bun

import mill.*
import os.*
import mill.api.BuildCtx
import mill.bun.BunToolchainModule

trait BunTypeScriptModule extends TypeScriptModule with BunToolchainModule { outer =>

  /** Extra flags passed to `bun run`. */
  def bunRunArgs: T[Seq[String]] = Task { Seq.empty }

  /** Target used by `bun build`: browser | bun | node. */
  def bunBundleTarget: T[String] = Task { "bun" }

  /** Output format used by `bun build`. */
  def bunBundleFormat: T[String] = Task { if (enableEsm()) "esm" else "cjs" }

  /** Emit a standalone executable instead of a JS bundle. */
  def bunCompileExecutable: T[Boolean] = Task { false }

  /** Treat all packages as external during bundling. */
  def bunBundlePackagesExternal: T[Boolean] = Task { false }

  /** Additional externals passed via repeated `--external`. */
  def bunBundleExternal: T[Seq[String]] = Task { Seq.empty }

  /** Name used for compiled executables. Defaults to the Mill module name. */
  def bunBinaryName: T[String] = Task { moduleName }

  /** Extra raw flags for `bun build`. */
  def bunBuildArgs: T[Seq[String]] = Task { Seq.empty }

  /** Extra raw flags for `bun test`. */
  def bunTestArgs: T[Seq[String]] = Task { Seq.empty }

  /** Bun-only package.json fields not modeled by Mill's typed PackageJson. */
  def bunPackageJsonExtras: T[ujson.Obj] = Task { ujson.Obj() }

  /** Mill's default TS deps assume ts-node/esbuild; Bun only needs TypeScript plus ambient types. */
  override def tsDeps: T[Seq[String]] = Task {
    Seq("typescript@5.7.3") ++
      (if (bunBundleTarget() == "bun") Seq("@types/bun@latest") else Seq("@types/node@22.10.9"))
  }

  private def mkBunPackageJson: Task[Unit] = Task.Anon {
    val dest = Task.dest
    val user = packageJson()

    val resolved = ujson.Obj.from(
      user.copy(
        name = if (user.name.nonEmpty) user.name else moduleName,
        version = if (user.version.nonEmpty) user.version else "1.0.0",
        `type` = if (enableEsm()) "module" else user.`type`,
        dependencies = ujson.Obj.from(transitiveNpmDeps().map(BunToolchainModule.splitDep)),
        devDependencies = ujson.Obj.from((transitiveNpmDevDeps() ++ tsDeps()).map(BunToolchainModule.splitDep))
      ).cleanJson.obj.toSeq
    )

    val merged = ujson.Obj.from(resolved.value.toSeq ++ bunPackageJsonExtras().value.toSeq)
    os.write.over(dest / "package.json", merged.render(indent = 2), createFolders = true)
  }

  private def copyBunWorkspaceConfigs: Task[Unit] = Task.Anon {
    val dest = Task.dest
    if (os.exists(npmRc().path)) {
      os.copy.over(npmRc().path, dest / ".npmrc", createFolders = true)
    }

    bunfigFiles().foreach { cfg =>
      os.copy.over(cfg.path, dest / cfg.path.last, createFolders = true)
    }
  }

  private def ensureInstallArtifacts(dest: os.Path, installRoot: os.Path, lockfiles: Seq[String]): Unit = {
    os.copy.over(installRoot / "package.json", dest / "package.json", createFolders = true)

    if (!os.exists(dest / "node_modules") && os.exists(installRoot / "node_modules")) {
      os.symlink(dest / "node_modules", installRoot / "node_modules")
    }

    lockfiles.foreach { name =>
      val src = installRoot / name
      if (os.exists(src) && !os.exists(dest / name)) {
        os.symlink(dest / name, src)
      }
    }
  }

  /** Replace npm install with bun install. */
  override def npmInstall: T[PathRef] = Task {
    val dest = Task.dest
    os.makeDir.all(dest)
    mkBunPackageJson()
    copyBunWorkspaceConfigs()

    runBun(
      bunExecutable(),
      Seq("install") ++ bunInstallArgs() ++ transitiveUnmanagedDeps().map(_.path.toString),
      cwd = dest,
      env = forkEnv()
    )

    PathRef(dest)
  }

  /**
   * Preserve Mill's compile sandbox preparation, but invoke TypeScript through
   * Bun instead of a Node-shebang script.
   */
  override def compile: T[PathRef] = Task {
    tscCopySources()
    tscCopyModDeps()
    tscCopyGenSources()
    tscLinkResources()
    ensureInstallArtifacts(Task.dest, npmInstall().path, bunLockfiles())
    mkTsconfig()

    runBun(
      bunExecutable(),
      Seq("x", "tsc", "--project", "tsconfig.json"),
      cwd = Task.dest,
      env = forkEnv()
    )

    PathRef(Task.dest)
  }

  override def createNodeModulesSymlink: Task[Unit] = Task.Anon {
    ensureInstallArtifacts(Task.dest, npmInstall().path, bunLockfiles())
  }

  /** Run the entrypoint directly with Bun. */
  override def run(args: mill.api.Args): Command[CommandResult] = Task.Command {
    val cwd = compile().path
    val mainFile = mainFilePath().relativeTo(cwd).toString
    os.call(
      Seq(bunExecutable(), "run") ++
        bunRunArgs() ++
        Seq(mainFile) ++
        computedArgs() ++
        args.value,
      cwd = cwd,
      env = forkEnv(),
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }

  private def copyCompileResources(resources: Seq[PathRef], dest: os.Path): Unit =
    resources.foreach { ref =>
      val p = ref.path
      os.copy.over(p, dest / p.last, createFolders = true)
    }

  /** Bundle with Bun instead of Mill's esbuild wrapper. */
  override def bundle: T[PathRef] = Task {
    val cwd = compile().path
    val mainFile = mainFilePath().relativeTo(cwd).toString
    val outFile =
      if (bunCompileExecutable()) Task.dest / bunBinaryName()
      else Task.dest / s"$moduleName.js"

    if (bunCompileExecutable()) copyCompileResources(bunCompileResources(), cwd)

    val packagesExternal = if (bunBundlePackagesExternal()) Seq("--packages", "external") else Nil
    val externalArgs = bunBundleExternal().flatMap(dep => Seq("--external", dep))
    val compileArgs = if (bunCompileExecutable()) Seq("--compile") else Nil

    runBun(
      bunExecutable(),
      Seq(
        "build",
        mainFile,
        "--outfile",
        outFile.toString,
        "--target",
        bunBundleTarget(),
        "--format",
        bunBundleFormat()
      ) ++ packagesExternal ++ externalArgs ++ compileArgs ++ bunBuildArgs(),
      cwd = cwd,
      env = forkEnv()
    )

    PathRef(outFile)
  }

  /**
   * Cross-compile standalone executables for each configured target.
   * Returns a map of target name to executable PathRef.
   * Requires `bunCompileTargets` to be non-empty.
   */
  def bunCompileExecutables: T[Map[String, PathRef]] = Task {
    val targets = bunCompileTargets()
    if (targets.isEmpty) Task.fail("bunCompileTargets is empty. Set targets like Seq(\"bun-linux-x64\", \"bun-darwin-arm64\").")

    val cwd = compile().path
    val mainFile = mainFilePath().relativeTo(cwd).toString
    copyCompileResources(bunCompileResources(), cwd)

    val packagesExternal = if (bunBundlePackagesExternal()) Seq("--packages", "external") else Nil
    val externalArgs = bunBundleExternal().flatMap(dep => Seq("--external", dep))
    val binaryName = bunBinaryName()

    targets.map { target =>
      val suffix = if (target.contains("windows")) ".exe" else ""
      val outFile = Task.dest / s"$binaryName-$target$suffix"

      runBun(
        bunExecutable(),
        Seq(
          "build",
          mainFile,
          "--compile",
          "--target",
          target,
          "--outfile",
          outFile.toString
        ) ++ packagesExternal ++ externalArgs ++ bunBuildArgs(),
        cwd = cwd,
        env = forkEnv()
      )

      target -> PathRef(outFile)
    }.toMap
  }

  /**
   * Bun-native nested test module.
   *
   * Each test module gets its own install directory to avoid contaminating
   * the outer module's cached install.
   */
  trait BunTypeScriptTests extends TypeScriptTests {

    /** Test timeout in milliseconds. 0 means no timeout. */
    def bunTestTimeout: T[Int] = Task { 0 }

    /** Reporter format: "default", "junit", or "json". */
    def bunTestReporter: T[String] = Task { "default" }

    /** Coverage reporter formats. */
    def bunCoverageReporters: T[Seq[String]] = Task { Seq("text", "lcov") }

    override def npmInstall: T[PathRef] = Task {
      val dest = Task.dest
      os.makeDir.all(dest)
      outer.mkBunPackageJson()
      outer.copyBunWorkspaceConfigs()

      runBun(
        bunExecutable(),
        Seq("install") ++ bunInstallArgs() ++ transitiveUnmanagedDeps().map(_.path.toString),
        cwd = dest,
        env = forkEnv()
      )

      PathRef(dest)
    }

    protected def preparedTestWorkspace: T[PathRef] = Task {
      val dest = this.compile().path
      outer.ensureInstallArtifacts(dest, npmInstall().path, bunLockfiles())
      PathRef(dest)
    }

    private def resolvedTestFlags: T[Seq[String]] = Task {
      val timeoutArgs = {
        val t = bunTestTimeout()
        if (t > 0) Seq("--timeout", t.toString) else Nil
      }
      val reporterArgs = {
        val r = bunTestReporter()
        if (r != "default") Seq("--reporter", r) else Nil
      }
      bunTestArgs() ++ timeoutArgs ++ reporterArgs
    }

    def test(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = forkEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    /** Run tests in watch mode for interactive development. */
    def testWatch(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test", "--watch") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = forkEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    /** Run tests and update snapshots. */
    def testUpdateSnapshots(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test", "--update-snapshots") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = forkEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    def coverage(args: mill.api.Args): Command[CommandResult] = Task.Command {
      val coverageDir = Task.dest / "coverage"
      os.makeDir.all(coverageDir)

      val coverageReporterArgs = bunCoverageReporters().flatMap(r => Seq("--coverage-reporter", r))

      os.call(
        Seq(
          bunExecutable(),
          "test",
          "--coverage",
          "--coverage-dir",
          coverageDir.toString
        ) ++ coverageReporterArgs ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = forkEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    /** Produce coverage artifacts as a cacheable task. */
    def coverageReport: T[PathRef] = Task {
      val coverageDir = Task.dest / "coverage"
      os.makeDir.all(coverageDir)

      val coverageReporterArgs = bunCoverageReporters().flatMap(r => Seq("--coverage-reporter", r))

      runBun(
        bunExecutable(),
        Seq(
          "test",
          "--coverage",
          "--coverage-dir",
          coverageDir.toString
        ) ++ coverageReporterArgs ++ resolvedTestFlags(),
        cwd = preparedTestWorkspace().path,
        env = forkEnv()
      )

      PathRef(coverageDir)
    }
  }
}
