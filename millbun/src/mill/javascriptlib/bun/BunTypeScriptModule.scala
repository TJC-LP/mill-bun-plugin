package mill.javascriptlib
package bun

import mill.*
import os.*
import mill.bun.{BunPackageModule, BunToolchainModule}

trait BunTypeScriptModule extends TypeScriptModule with BunToolchainModule with BunPackageModule { outer =>

  /** Optional packages installed when available. */
  def npmOptionalDeps: T[Seq[String]] = Task { Seq.empty }

  /** Peer requirements supplied by consuming packages. */
  def npmPeerDeps: T[Seq[String]] = Task { Seq.empty }

  /** Development dependencies are local tooling inputs, not transitive runtime requirements. */
  override def transitiveNpmDevDeps: T[Seq[String]] = Task { npmDevDeps() }

  override def bunWorkspaceUnmanagedDeps: T[Seq[PathRef]] = transitiveUnmanagedDeps

  /** Extra flags passed to `bun run`. */
  def bunRunArgs: T[Seq[String]] = Task { Seq.empty }

  /** Target used by `bun build`: browser | bun | node. */
  def bunBundleTarget: T[String] = Task { "bun" }

  /** Output format passed to `bun build --format`; `None` lets bun infer.
    *
    * `Option[String]` to match the Scala.js module's member of the same name — a `T[String]`
    * here made the two module kinds' shared vocabulary diverge on type, which no alias can
    * bridge. 0.3.0 takes the one-time break.
    */
  def bunBundleFormat: T[Option[String]] = Task { Some(if (enableEsm()) "esm" else "cjs") }

  /** Emit a standalone executable instead of a JS bundle. */
  @deprecated("Use the compileExecutable task", "0.3.0")
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

  /** Runtime environment for Bun-executed programs and tests. */
  def bunRuntimeEnv: T[Map[String, String]] = Task { bunEnv() ++ forkEnv() }

  /** TypeScript version used for `bun x tsc`. */
  def typeScriptVersion: T[String] = Task { "5.7.3" }

  /** Node ambient types used for node-targeted Bun builds. */
  def nodeTypesVersion: T[String] = Task { "22.10.9" }

  /** Bun ambient types used for bun-targeted Bun builds.
    *
    * `@types/bun` is published in lockstep with Bun itself, so this tracks [[bunVersion]] by
    * default. The one exception is the plugin's own default Bun, which pairs with
    * [[BunToolchainModule.DefaultBunTypesVersion]] — npm can lag a Bun release by a day or two,
    * and the shipped default must never 404 on release day.
    */
  def bunTypesVersion: T[String] = Task {
    if (bunVersion() == BunToolchainModule.DefaultBunVersion) BunToolchainModule.DefaultBunTypesVersion
    else bunVersion()
  }

  /** Ambient runtime types aligned to the configured Bun target. */
  protected def ambientTypeDeps: T[Seq[String]] = Task {
    bunBundleTarget() match {
      case "bun"  => Seq(s"@types/bun@${bunTypesVersion()}")
      case "node" => Seq(s"@types/node@${nodeTypesVersion()}")
      case _      => Seq.empty
    }
  }

  /** Mill's default TS deps assume ts-node/esbuild; Bun only needs TypeScript plus target-specific ambient types. */
  override def tsDeps: T[Seq[String]] = Task {
    Seq(s"typescript@${typeScriptVersion()}") ++ ambientTypeDeps()
  }

  override def bunWorkspacePackageName: T[String] = Task { moduleName }

  override def bunWorkspacePackageJson: T[ujson.Obj] = Task {
    val user = packageJson()
    val overrides = npmOverrides()

    val resolved = ujson.Obj.from(
      user.copy(
        name = if (user.name.nonEmpty) user.name else bunWorkspacePackageName(),
        version = if (user.version.nonEmpty) user.version else "1.0.0",
        `type` = if (enableEsm()) "module" else user.`type`,
        dependencies = ujson.Obj.from(BunToolchainModule.dependencyPairsWithUnmanaged(
          BunToolchainModule.dependencyPairs(transitiveNpmDeps(), overrides),
          transitiveUnmanagedDeps()
        )),
        devDependencies =
          ujson.Obj.from(BunToolchainModule.dependencyPairs(transitiveNpmDevDeps() ++ tsDeps(), overrides))
      ).cleanJson.obj.toSeq
    )

    val optional = BunToolchainModule.dependencyPairs(npmOptionalDeps(), overrides)
    val peers = BunToolchainModule.dependencyPairs(npmPeerDeps(), overrides)
    if optional.nonEmpty then resolved("optionalDependencies") = ujson.Obj.from(optional)
    if peers.nonEmpty then resolved("peerDependencies") = ujson.Obj.from(peers)
    if overrides.nonEmpty then
      resolved("overrides") =
        ujson.Obj.from(overrides.toSeq.sortBy(_._1).map((name, value) => name -> ujson.Str(value)))

    BunToolchainModule.mergePackageJson(resolved, bunPackageJsonExtras())
  }

  private def mkBunPackageJson: Task[Unit] = Task.Anon {
    os.write.over(
      Task.dest / "package.json",
      bunWorkspacePackageJson().render(indent = 2),
      createFolders = true
    )
  }

  private def resolvedBunfigs: Task[Seq[PathRef]] = Task.Anon {
    bunfigFiles()
  }

  private def copyBunWorkspaceConfigs: Task[Unit] = Task.Anon {
    // Install workspaces need both .npmrc (registry auth) and bunfig
    if (os.exists(npmRc().path)) {
      os.copy.over(npmRc().path, Task.dest / ".npmrc", createFolders = true)
    }
    BunTypeScriptModule.copyBunfigsTo(Task.dest, bunfigFiles())
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

  /** Install dependencies with Bun. The canonical name; Mill's [[npmInstall]] delegates here. */
  def bunInstall: T[PathRef] = Task {
    val dest = Task.dest
    os.makeDir.all(dest)
    mkBunPackageJson()
    copyBunWorkspaceConfigs()

    bunWorkspaceInstall() match
      case Some(workspaceInstall) =>
        val installed = workspaceInstall.path
        if os.exists(installed / "node_modules") then
          os.symlink(dest / "node_modules", installed / "node_modules")
        bunLockfiles().foreach { name =>
          val source = installed / name
          if os.exists(source) then os.symlink(dest / name, source)
        }
      case None =>
        val lockfile = bunLockfile()
        requireBunLockfile(true, lockfile, bunRequireLockfile(), bunVersion())
        copyBunLockfile(lockfile, dest)
        BunToolchainModule.stageUnmanagedDeps(transitiveUnmanagedDeps(), dest)

        runBun(
          bunExecutable(),
          Seq("install") ++ resolvedBunInstallArgs(
            bunInstallArgs(),
            bunInstallExtraArgs(),
            lockfile.nonEmpty,
            updateLockfile = false
          ),
          cwd = dest,
          env = bunToolEnv()
        )

    PathRef(dest)
  }

  /** Mill's inherited install name; delegates to [[bunInstall]]. */
  override def npmInstall: T[PathRef] = Task { bunInstall() }

  /** Resolve dependencies and update the source-controlled `bun.lock`. */
  def bunLock(): Command[PathRef] = Task.Command {
    if bunWorkspaceInstall().nonEmpty then
      Task.fail("This package uses a Bun workspace. Run the workspace module's bunLock command.")
    val dest = Task.dest
    os.makeDir.all(dest)
    mkBunPackageJson()
    copyBunWorkspaceConfigs()
    copyBunLockfile(bunLockfile(), dest)
    BunToolchainModule.stageUnmanagedDeps(transitiveUnmanagedDeps(), dest)

    runBun(
      bunExecutable(),
      Seq("install") ++ resolvedBunInstallArgs(
        bunInstallArgs(),
        bunInstallExtraArgs(),
        bunLockfile().nonEmpty,
        updateLockfile = true
      ),
      cwd = dest,
      env = bunToolEnv()
    )

    val generated = dest / "bun.lock"
    if (!os.exists(generated)) Task.fail("Bun did not generate bun.lock")
    val sourceLock = moduleDir / "bun.lock"
    os.copy.over(generated, sourceLock, createFolders = true)
    PathRef(sourceLock)
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
    BunTypeScriptModule.removeInstallOnlyConfigs(Task.dest)
    ensureInstallArtifacts(Task.dest, bunInstall().path, bunLockfiles())
    BunTypeScriptModule.copyBunfigsTo(Task.dest, resolvedBunfigs())
    mkTsconfig()

    runBun(
      bunExecutable(),
      Seq("x", "tsc", "--project", "tsconfig.json"),
      cwd = Task.dest,
      env = bunToolEnv()
    )

    PathRef(Task.dest)
  }

  override def createNodeModulesSymlink: Task[Unit] = Task.Anon {
    ensureInstallArtifacts(Task.dest, bunInstall().path, bunLockfiles())
  }

  /** Run the entrypoint directly with Bun. */
  override def run(args: mill.api.Args): Command[CommandResult] = Task.Command {
    val cwd = compile().path
    val mainFile = resolvedEntrypoint(mainFilePath(), cwd).relativeTo(cwd).toString
    os.call(
      Seq(bunExecutable(), "run") ++
        bunRunArgs() ++
        Seq(mainFile) ++
        computedArgs() ++
        args.value,
      cwd = cwd,
      env = bunRuntimeEnv(),
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }

  private def copyCompileResources(resources: Seq[PathRef], dest: os.Path): Unit =
    BunToolchainModule.copyPathRefs(resources, dest, Seq(moduleDir))

  /**
   * Fall back to Bun-style defaults when Mill's `src/<module>.ts` entrypoint
   * is not present in the prepared workspace.
   */
  private def resolvedEntrypoint(configured: os.Path, compileDir: os.Path): os.Path = {
    val candidates = Seq(
      configured,
      compileDir / "src" / "main.ts",
      compileDir / "src" / "main.tsx",
      compileDir / "src" / "index.ts",
      compileDir / "src" / "index.tsx",
      compileDir / "main.ts",
      compileDir / "main.tsx",
      compileDir / "index.ts",
      compileDir / "index.tsx"
    ).distinct

    candidates.find(os.exists).getOrElse(configured)
  }

  /** Bundle with Bun instead of Mill's esbuild wrapper. */
  override def bundle: T[PathRef] = Task {
    val compileDir = compile().path
    val buildDir = Task.dest
    val mainFile = resolvedEntrypoint(mainFilePath(), compileDir).relativeTo(compileDir).toString
    val outFile =
      if (bunCompileExecutable()) Task.dest / bunBinaryName()
      else Task.dest / s"$moduleName.js"

    // Declared explicitly: the staged tree carries a node_modules symlink into this
    // install, and Mill's filesystem checker only permits reading a dest we depend on.
    bunInstall()
    BunToolchainModule.copyWorkspace(compileDir, buildDir)
    BunTypeScriptModule.removeInstallOnlyConfigs(buildDir)
    BunTypeScriptModule.copyBunfigsTo(buildDir, resolvedBunfigs())
    if (bunCompileExecutable()) copyCompileResources(bunCompileResources(), buildDir)

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
        bunBundleTarget()
      ) ++ bunBundleFormat().toSeq.flatMap(format => Seq("--format", format))
        ++ packagesExternal ++ externalArgs ++ compileArgs ++ bunBuildArgs(),
      cwd = buildDir,
      env = bunToolEnv()
    )

    PathRef(outFile)
  }

  /** Build the configured entrypoint as a standalone Bun executable. */
  def compileExecutable: T[PathRef] = Task {
    val compileDir = compile().path
    val buildDir = Task.dest / "workspace"
    val mainFile = resolvedEntrypoint(mainFilePath(), compileDir).relativeTo(compileDir).toString
    // bun appends .exe to extensionless --compile outputs on Windows; the recorded PathRef
    // must name the file bun actually writes, or downstream copies fail and caching never
    // invalidates (a missing path's signature is constant).
    val outFile = Task.dest / (bunBinaryName() + (if (scala.util.Properties.isWin) ".exe" else ""))

    // Declared explicitly: the staged tree carries a node_modules symlink into this
    // install, and Mill's filesystem checker only permits reading a dest we depend on.
    bunInstall()
    BunToolchainModule.copyWorkspace(compileDir, buildDir)
    BunTypeScriptModule.removeInstallOnlyConfigs(buildDir)
    BunTypeScriptModule.copyBunfigsTo(buildDir, resolvedBunfigs())
    copyCompileResources(bunCompileResources(), buildDir)

    val packagesExternal = if (bunBundlePackagesExternal()) Seq("--packages", "external") else Nil
    val externalArgs = bunBundleExternal().flatMap(dep => Seq("--external", dep))
    runBun(
      bunExecutable(),
      Seq(
        "build",
        mainFile,
        "--compile",
        "--target",
        "bun",
        "--outfile",
        outFile.toString
      ) ++ packagesExternal ++ externalArgs ++ bunBuildArgs(),
      cwd = buildDir,
      env = bunToolEnv()
    )

    PathRef(outFile)
  }

  /**
   * Cross-compile standalone executables for each configured target.
   * Returns a map of target name to executable PathRef.
   * Requires `bunCompileTargets` to be non-empty.
   */
  def compileExecutables: T[Map[String, PathRef]] = Task {
    val targets = bunCompileTargets()
    if (targets.isEmpty)
      Task.fail("bunCompileTargets is empty. Set targets like Seq(\"bun-linux-x64\", \"bun-darwin-arm64\").")

    val compileDir = compile().path
    val buildDir = Task.dest / "workspace"
    val mainFile = resolvedEntrypoint(mainFilePath(), compileDir).relativeTo(compileDir).toString
    // Declared explicitly: the staged tree carries a node_modules symlink into this
    // install, and Mill's filesystem checker only permits reading a dest we depend on.
    bunInstall()
    BunToolchainModule.copyWorkspace(compileDir, buildDir)
    BunTypeScriptModule.removeInstallOnlyConfigs(buildDir)
    BunTypeScriptModule.copyBunfigsTo(buildDir, resolvedBunfigs())
    copyCompileResources(bunCompileResources(), buildDir)

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
        cwd = buildDir,
        env = bunToolEnv()
      )

      target -> PathRef(outFile)
    }.toMap
  }

  /** Compatibility alias for the canonical cross-platform executable task. */
  @deprecated("Use compileExecutables", "0.3.0")
  def bunCompileExecutables: T[Map[String, PathRef]] = Task { compileExecutables() }

  /**
   * Bun-native nested test module.
   *
   * Each test module gets its own install directory to avoid contaminating
   * the outer module's cached install.
   */
  trait BunTypeScriptTests extends TypeScriptTests {

    /**
     * The outer module's Bun-specific TS toolchain, not upstream Mill's ts-node defaults.
     *
     * This trait extends upstream `TypeScriptTests`, so an unqualified `tsDeps()` resolves to
     * the Node toolchain (`ts-node`, `tsconfig-paths`, `@types/node`) that the outer trait
     * deliberately replaced. Those names always survived the outer-name filter in
     * [[bunTestPackageJson]], so a bare test module's package.json never matched the outer's
     * and the install-reuse path in [[npmInstall]] was unreachable — with `bunRequireLockfile`
     * on, every bare test module demanded its own lockfile.
     */
    override def tsDeps: T[Seq[String]] = Task { outer.tsDeps() }

    /**
     * Runtime environment for `bun test` processes, overridable per test module.
     *
     * `override def forkEnv` on a test object compiles but is silently ignored here (only
     * upstream's Node-based runners read it), and `bunRuntimeEnv` lives on the outer module
     * where overriding it also changes `run`. This is the test-side lever, mirroring the
     * Scala.js `bunTestJsEnv`.
     */
    def bunTestEnv: T[Map[String, String]] = Task { outer.bunRuntimeEnv() }

    /** Test timeout in milliseconds. 0 means no timeout. */
    def bunTestTimeout: T[Int] = Task { 0 }

    /** Reporter format: "default", "junit", or "json". */
    def bunTestReporter: T[String] = Task { "default" }

    /** Coverage reporter formats. */
    def bunCoverageReporters: T[Seq[String]] = Task { Seq("text", "lcov") }

    /**
     * Merged outer + test-side package.json, shared by [[npmInstall]] and [[bunLock]].
     *
     * One task so the install and the lockfile can never describe different dependency sets —
     * that divergence is what made frozen installs of test modules fail.
     *
     * Upstream Mill's test `npmInstall` runs `npm install --save-dev` with the test module's
     * transitive deps; building one merged package.json achieves the same for Bun.
     */
    def bunTestPackageJson: T[ujson.Obj] = Task {
      val user = outer.packageJson()
      val overrides = outer.npmOverrides()
      val outerDeps = BunToolchainModule.dependencyPairsWithUnmanaged(
        BunToolchainModule.dependencyPairs(outer.transitiveNpmDeps(), overrides),
        (outer.transitiveUnmanagedDeps() ++ this.transitiveUnmanagedDeps()).distinct
      )
      val outerDevDeps =
        BunToolchainModule.dependencyPairs(outer.transitiveNpmDevDeps() ++ outer.tsDeps(), overrides)
      val outerPackageNames = (outerDeps.iterator ++ outerDevDeps.iterator).map(_._1).toSet
      // Test-only deps are dev dependencies — they should not appear in the
      // production dependencies field, matching Bun/npm convention.
      val testDevDeps = BunToolchainModule
        .dependencyPairs(this.transitiveNpmDeps() ++ this.npmDevDeps() ++ this.tsDeps(), overrides)
        .filterNot { case (name, _) => outerPackageNames.contains(name) }

      val resolved = ujson.Obj.from(
        user.copy(
          name = if (user.name.nonEmpty) user.name else outer.moduleName,
          version = if (user.version.nonEmpty) user.version else "1.0.0",
          `type` = if (outer.enableEsm()) "module" else user.`type`,
          dependencies = ujson.Obj.from(outerDeps),
          devDependencies = ujson.Obj.from(outerDevDeps ++ testDevDeps)
        ).cleanJson.obj.toSeq
      )

      val optional = BunToolchainModule.dependencyPairs(outer.npmOptionalDeps(), overrides)
      val peers = BunToolchainModule.dependencyPairs(outer.npmPeerDeps(), overrides)
      if optional.nonEmpty then resolved("optionalDependencies") = ujson.Obj.from(optional)
      if peers.nonEmpty then resolved("peerDependencies") = ujson.Obj.from(peers)
      if overrides.nonEmpty then
        resolved("overrides") = ujson.Obj.from(
          overrides.toSeq.sortBy(_._1).map((name, value) => name -> ujson.Str(value))
        )

      BunToolchainModule.mergePackageJson(resolved, outer.bunPackageJsonExtras())
    }

    /**
     * Source-controlled lockfile for this test module, at `<test module>/bun.lock`.
     *
     * Declared here rather than inherited from the enclosing module: a test module that adds
     * dependencies installs a strict superset of the outer package.json, which the outer module's
     * lockfile cannot satisfy under `--frozen-lockfile`.
     */
    def bunLockfile: T[Option[PathRef]] = Task.Input {
      val path = moduleDir / "bun.lock"
      if (os.exists(path)) Some(PathRef(path)) else None
    }

    /** True when the test module adds nothing the outer install does not already provide. */
    private def reusesOuterInstall: Task[Boolean] = Task.Anon {
      bunTestPackageJson().render() == outer.bunWorkspacePackageJson().render()
    }

    /** Install this test module's dependencies with Bun; reuses the outer install when equal. */
    def bunInstall: T[PathRef] = Task {
      if (reusesOuterInstall()) outer.bunInstall()
      else {
        val dest = Task.dest
        os.makeDir.all(dest)
        os.write.over(
          dest / "package.json",
          bunTestPackageJson().render(indent = 2),
          createFolders = true
        )
        outer.copyBunWorkspaceConfigs()

        val lockfile = this.bunLockfile()
        outer.requireBunLockfile(
          hasInstallInputs = true,
          lockfile = lockfile,
          required = outer.bunRequireLockfile(),
          pinnedBunVersion = outer.bunVersion(),
          lockfilePath = moduleDir / "bun.lock"
        )
        outer.copyBunLockfile(lockfile, dest)
        BunToolchainModule.stageUnmanagedDeps(
          (outer.transitiveUnmanagedDeps() ++ this.transitiveUnmanagedDeps()).distinct,
          dest
        )

        outer.runBun(
          outer.bunExecutable(),
          Seq("install") ++ outer.resolvedBunInstallArgs(
            outer.bunInstallArgs(),
            outer.bunInstallExtraArgs(),
            lockfile.nonEmpty,
            updateLockfile = false
          ),
          cwd = dest,
          env = outer.bunToolEnv()
        )

        PathRef(dest)
      }
    }

    /** Mill's inherited install name; delegates to [[bunInstall]]. */
    override def npmInstall: T[PathRef] = Task { bunInstall() }

    /**
     * Resolve this test module's dependencies and update its own `bun.lock`.
     *
     * Unlike the outer module's, this does not refuse for workspace members: a test module with
     * extra dependencies genuinely needs its own install and its own lock.
     */
    def bunLock(): Command[PathRef] = Task.Command {
      val dest = Task.dest
      os.makeDir.all(dest)
      os.write.over(
        dest / "package.json",
        bunTestPackageJson().render(indent = 2),
        createFolders = true
      )
      outer.copyBunWorkspaceConfigs()
      outer.copyBunLockfile(this.bunLockfile(), dest)
      BunToolchainModule.stageUnmanagedDeps(
        (outer.transitiveUnmanagedDeps() ++ this.transitiveUnmanagedDeps()).distinct,
        dest
      )

      outer.runBun(
        outer.bunExecutable(),
        Seq("install") ++ outer.resolvedBunInstallArgs(
          outer.bunInstallArgs(),
          outer.bunInstallExtraArgs(),
          this.bunLockfile().nonEmpty,
          updateLockfile = true
        ),
        cwd = dest,
        env = outer.bunToolEnv()
      )

      val generated = dest / "bun.lock"
      if (!os.exists(generated)) Task.fail("Bun did not generate bun.lock")
      val sourceLock = moduleDir / "bun.lock"
      os.copy.over(generated, sourceLock, createFolders = true)
      PathRef(sourceLock)
    }

    protected def preparedTestWorkspace: T[PathRef] = Task {
      val dest = Task.dest
      BunToolchainModule.copyWorkspace(this.compile().path, dest)
      BunTypeScriptModule.removeInstallOnlyConfigs(dest)
      outer.ensureInstallArtifacts(dest, bunInstall().path, bunLockfiles())
      BunTypeScriptModule.copyBunfigsTo(dest, outer.resolvedBunfigs())
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

    /** Run `bun test`. Named after Mill's standard test entrypoint, so both module kinds share it. */
    def testForked(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = bunTestEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    @deprecated("Use testForked", "0.3.0")
    def test(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = bunTestEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    /** Run tests in watch mode for interactive development. */
    def testWatch(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test", "--watch") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = bunTestEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }

    /** Run tests and update snapshots. */
    def testUpdateSnapshots(args: mill.api.Args): Command[CommandResult] = Task.Command {
      os.call(
        Seq(bunExecutable(), "test", "--update-snapshots") ++ resolvedTestFlags() ++ args.value,
        cwd = preparedTestWorkspace().path,
        env = bunTestEnv(),
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
        env = bunTestEnv(),
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
        env = bunTestEnv()
      )

      PathRef(coverageDir)
    }
  }
}

object BunTypeScriptModule {

  /** Copy bunfig files into a workspace directory. Does NOT copy .npmrc — that belongs only in install workspaces. */
  def copyBunfigsTo(dest: os.Path, bunfigConfigs: Seq[PathRef]): Unit = {
    bunfigConfigs.foreach { cfg =>
      os.copy.over(cfg.path, dest / cfg.path.last, createFolders = true)
    }
  }

  /** Generated Bun run/build/test workspaces should not retain install-only auth config. */
  def removeInstallOnlyConfigs(dest: os.Path): Unit = {
    val npmrc = dest / ".npmrc"
    if (os.exists(npmrc)) os.remove(npmrc)
  }
}
