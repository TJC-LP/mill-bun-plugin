package mill.scalajslib
package bun

import mill.*
import mill.api.BuildCtx
import mill.api.JsonFormatters.given
import mill.bun.{BunManifest, BunPackageModule, BunToolchainModule, BunVendoredNodeModules}
import mill.javalib.JavaModule
import mill.scalajslib.*
import mill.scalajslib.api.*

import scala.annotation.tailrec
trait BunScalaJSModule extends ScalaJSModule with BunToolchainModule with BunPackageModule { outer =>

  /** JS packages needed by linked Scala.js output, e.g. packages referenced by @JSImport. */
  def npmDeps: T[Seq[String]] = Task { Seq.empty }

  /** Dev-only JS packages for bundling or local tooling. */
  def npmDevDeps: T[Seq[String]] = Task { Seq.empty }

  /** JS packages needed by linked Scala.js output.
    *
    * Use the `bun"pkg@version"` string interpolator for compile-time validation:
    * {{{
    * def bunDeps = Task { Seq(
    *   bun"@anthropic-ai/claude-agent-sdk@^0.2.90",
    *   bun"zod@^4.0.0"
    * )}
    * }}}
    *
    * Both `bunDeps` and `npmDeps` are merged into `transitiveNpmDeps` —
    * use whichever you prefer. They are independent (no delegation).
    */
  def bunDeps: T[Seq[String]] = Task { Seq.empty }

  /** Dev-only JS packages for bundling or local tooling.
    *
    * Independent of `npmDevDeps` — both are merged into `transitiveNpmDevDeps`.
    */
  def bunDevDeps: T[Seq[String]] = Task { Seq.empty }

  /**
   * Local package directories, each containing a `package.json` with a name.
   *
   * Every entry is staged into `vendor/` beside the generated package.json and declared as a
   * `file:./vendor/<name>` dependency, so the recorded lockfile entry stays independent of the
   * checkout path and frozen installs work. Tarballs are not supported — unpack them.
   */
  def unmanagedDeps: T[Seq[PathRef]] = Task { Seq.empty }

  private def npmRc = Task.Source(BuildCtx.workspaceRoot / ".npmrc")

  private def recursiveBunModuleDeps: Seq[BunScalaJSModule] = {
    @tailrec
    def loop(
        pending: List[JavaModule],
        seen: Set[JavaModule],
        acc: Vector[BunScalaJSModule]
    ): Vector[BunScalaJSModule] = pending match {
      case Nil => acc
      case head :: tail if seen(head) =>
        loop(tail, seen, acc)
      case head :: tail =>
        val next = head.moduleDepsChecked.toList ++ head.runModuleDepsChecked.toList ++ tail
        val nextAcc = head match {
          case bunModule: BunScalaJSModule => acc :+ bunModule
          case _                           => acc
        }
        loop(next, seen + head, nextAcc)
    }

    loop(moduleDepsChecked.toList ++ runModuleDepsChecked.toList, Set.empty, Vector.empty)
  }

  private def recursiveInstallBunModuleDeps: Seq[BunScalaJSModule] =
    recursiveBunModuleDeps.filterNot(_.isInstanceOf[BunPublishModule])

  def transitiveNpmDeps: T[Seq[String]] = Task {
    val moduleNpm = Task.traverse(recursiveInstallBunModuleDeps)(_.npmDeps)().flatten
    val moduleBun = Task.traverse(recursiveInstallBunModuleDeps)(_.bunDeps)().flatten
    moduleNpm ++ moduleBun ++ classpathBunDeps() ++ npmDeps() ++ bunDeps()
  }

  def transitiveNpmDevDeps: T[Seq[String]] = Task {
    npmDevDeps() ++ bunDevDeps()
  }

  def transitiveUnmanagedDeps: T[Seq[PathRef]] = Task {
    Task.traverse(recursiveInstallBunModuleDeps)(_.unmanagedDeps)().flatten ++ unmanagedDeps()
  }

  override def bunWorkspaceUnmanagedDeps: T[Seq[PathRef]] = transitiveUnmanagedDeps

  /** Optional JS packages — installed if available, not fatal if missing. */
  def npmOptionalDeps: T[Seq[String]] = Task { Seq.empty }

  /** Peer JS packages that must be supplied by the consuming application. */
  def npmPeerDeps: T[Seq[String]] = Task { Seq.empty }

  /** @deprecated Use [[npmOptionalDeps]]. */
  @deprecated("Use npmOptionalDeps", "0.3.0")
  def bunOptionalDeps: T[Seq[String]] = Task { Seq.empty }

  // ---------------------------------------------------------------------------
  // Classpath manifest scanning — reads bun-dependencies.json from dependency JARs
  // ---------------------------------------------------------------------------

  /** Scan classpath JARs for embedded bun dependency manifests. */
  def classpathBunDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.dependencies).map { case (name, version) => s"$name@$version" }
  }

  /** Read legacy schema v1 development metadata for diagnostics.
    * @deprecated Development dependencies are not transitive in schema v2.
    */
  @deprecated("Development dependencies are local and no longer transitive", "0.3.0")
  def classpathBunDevDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.devDependencies).map { case (name, version) => s"$name@$version" }
  }

  /** Scan classpath JARs for embedded bun optional-dependency manifests. */
  def classpathBunOptionalDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.optionalDependencies).map { case (name, version) => s"$name@$version" }
  }

  /** Peer packages declared by published Scala.js libraries. */
  def classpathBunPeerDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.peerDependencies).map { case (name, version) => s"$name@$version" }
  }

  /** Manifests from classpath entries that do NOT carry vendored node_modules.
    * Entries with a vendored tree are handled by `mergeVendoredNodeModules` instead.
    */
  private def classpathBunManifests: Task[Seq[BunManifest]] = Task.Anon {
    runClasspath().flatMap { ref =>
      val path = ref.path
      if BunVendoredNodeModules.hasVendoredNodeModules(path) then Nil
      else if os.exists(path) && path.ext == "jar" then BunManifest.readFromJar(path).toSeq
      else if os.isDir(path) then BunManifest.readFromDir(path).toSeq
      else Nil
    }
  }

  /** Extra package.json fields not modeled by this scaffold. */
  def bunPackageJsonExtras: T[ujson.Obj] = Task { ujson.Obj() }

  /**
   * Critical trick: Scala.js' NodeJSEnv writes a bootstrap program to stdin.
   * `bun run -` reads JS/TS from stdin, which makes Bun a plausible drop-in
   * runtime for Mill's existing Node-based Scala.js env.
   */
  def bunJsEnvArgs: T[Seq[String]] = Task { Seq("run", "-") }

  /** Separate hook in case runtime env vars should differ from install/build env vars. */
  def bunJsEnv: T[Map[String, String]] = Task { bunEnv() }

  /** Target used by the convenience `bunBundle*` tasks. */
  def bunBundleTarget: T[String] = Task { "browser" }

  def bunBundleFormat: T[Option[String]] = Task { None }

  def bunBundleSourcemap: T[Option[String]] = Task {
    if (scalaJSSourceMap()) Some("linked") else None
  }

  def bunBundleExternal: T[Seq[String]] = Task { Seq.empty }

  def bunBundleSplitting: T[Boolean] = Task { false }

  def bunBundleBytecode: T[Boolean] = Task { false }

  def bunBundleArgs: T[Seq[String]] = Task { Seq.empty }

  def bunBinaryName: T[String] = Task {
    val name = toString
    if (name.nonEmpty) name.split('.').last else "app"
  }

  protected def defaultPackageName: String = {
    val name = toString
    if (name.nonEmpty) name.split('.').last.replace('.', '-') else "app"
  }

  @deprecated("Use transitiveNpmOptionalDeps", "0.3.0")
  def transitiveBunOptionalDeps: T[Seq[String]] = Task {
    val moduleOptional = Task.traverse(recursiveInstallBunModuleDeps)(module => Task.Anon {
      module.npmOptionalDeps() ++ module.bunOptionalDeps()
    })().flatten
    moduleOptional ++ classpathBunOptionalDeps() ++ npmOptionalDeps() ++ bunOptionalDeps()
  }

  def transitiveNpmOptionalDeps: T[Seq[String]] = Task { transitiveBunOptionalDeps() }

  def transitiveNpmPeerDeps: T[Seq[String]] = Task {
    val modulePeers = Task.traverse(recursiveInstallBunModuleDeps)(_.npmPeerDeps)().flatten
    modulePeers ++ classpathBunPeerDeps() ++ npmPeerDeps()
  }

  override def bunWorkspacePackageName: T[String] = Task { defaultPackageName }

  override def bunWorkspacePackageJson: T[ujson.Obj] = Task {
    val overrides = npmOverrides()
    val allOptional = BunToolchainModule.dependencyPairs(transitiveNpmOptionalDeps(), overrides)
    val allPeers = BunToolchainModule.dependencyPairs(transitiveNpmPeerDeps(), overrides)
    val base = ujson.Obj(
      "name" -> defaultPackageName,
      "private" -> true,
      "version" -> "0.0.0",
      "dependencies" -> ujson.Obj.from(BunToolchainModule.dependencyPairsWithUnmanaged(
        BunToolchainModule.dependencyPairs(transitiveNpmDeps(), overrides),
        transitiveUnmanagedDeps()
      )),
      "devDependencies" -> ujson.Obj.from(BunToolchainModule.dependencyPairs(transitiveNpmDevDeps(), overrides))
    )
    if allOptional.nonEmpty then
      base("optionalDependencies") = ujson.Obj.from(allOptional)
    if allPeers.nonEmpty then
      base("peerDependencies") = ujson.Obj.from(allPeers)
    if overrides.nonEmpty then
      base("overrides") = ujson.Obj.from(overrides.toSeq.sortBy(_._1).map((name, value) => name -> ujson.Str(value)))

    val packageType =
      moduleKind() match {
        case ModuleKind.ESModule => Some("module")
        case _                   => None
      }

    packageType.foreach(tpe => base("type") = tpe)

    BunToolchainModule.mergePackageJson(base, bunPackageJsonExtras())
  }

  private def mkBunPackageJson: Task[Unit] = Task.Anon {
    os.write.over(
      Task.dest / "package.json",
      bunWorkspacePackageJson().render(indent = 2),
      createFolders = true
    )
  }

  private def mergeVendoredNodeModules(entries: Seq[os.Path], destNodeModules: os.Path): Unit =
    entries.foreach { entry =>
      BunVendoredNodeModules.mergeFromClasspathEntry(entry, destNodeModules)
    }

  def bunInstall: T[PathRef] = Task {
    val dest = Task.dest
    os.makeDir.all(dest)

    if (os.exists(npmRc().path)) os.copy.over(npmRc().path, dest / ".npmrc", createFolders = true)

    bunfigFiles().foreach { cfg =>
      os.copy.over(cfg.path, dest / cfg.path.last, createFolders = true)
    }

    mkBunPackageJson()

    val hasInstallInputs =
      transitiveNpmDeps().nonEmpty ||
        transitiveNpmDevDeps().nonEmpty ||
        transitiveNpmOptionalDeps().nonEmpty ||
        transitiveNpmPeerDeps().nonEmpty ||
        transitiveUnmanagedDeps().nonEmpty ||
        bunPackageJsonExtras().value.nonEmpty

    val ownResourceRoots = resources().map(_.path).toSet
    val vendoredEntries = runClasspath().map(_.path).filterNot(ownResourceRoots.contains)

    bunWorkspaceInstall() match
      case Some(workspaceInstall) =>
        // Vendored trees must not be merged here: node_modules is a link into the workspace
        // install's dest, so merging would mutate a directory shared by every workspace member.
        val vendored = vendoredEntries.filter(BunVendoredNodeModules.hasVendoredNodeModules)
        if vendored.nonEmpty then
          Task.fail(
            s"Bun workspace members cannot consume vendored runtime dependencies: " +
              s"${vendored.map(_.last).mkString(", ")}. Depend on the manifest-only artifact, or " +
              "install this module outside the workspace."
          )

        val installed = workspaceInstall.path
        if os.exists(installed / "node_modules") then
          os.symlink(dest / "node_modules", installed / "node_modules")
        bunLockfiles().foreach { name =>
          val source = installed / name
          if os.exists(source) then os.symlink(dest / name, source)
        }
      case None =>
        val lockfile = bunLockfile()
        requireBunLockfile(hasInstallInputs, lockfile, bunRequireLockfile())
        copyBunLockfile(lockfile, dest)

        if hasInstallInputs then
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
            env = bunEnv()
          )

        mergeVendoredNodeModules(vendoredEntries, dest / "node_modules")

    PathRef(dest)
  }

  /** Resolve dependencies and update the source-controlled `bun.lock`. */
  def bunLock(): Command[PathRef] = Task.Command {
    if bunWorkspaceInstall().nonEmpty then
      Task.fail("This package uses a Bun workspace. Run the workspace module's bunLock command.")
    val dest = Task.dest
    os.makeDir.all(dest)
    if (os.exists(npmRc().path)) os.copy.over(npmRc().path, dest / ".npmrc", createFolders = true)
    bunfigFiles().foreach { cfg =>
      os.copy.over(cfg.path, dest / cfg.path.last, createFolders = true)
    }
    mkBunPackageJson()
    BunToolchainModule.stageUnmanagedDeps(transitiveUnmanagedDeps(), dest)
    copyBunLockfile(bunLockfile(), dest)

    runBun(
      bunExecutable(),
      Seq("install") ++ resolvedBunInstallArgs(
        bunInstallArgs(),
        bunInstallExtraArgs(),
        bunLockfile().nonEmpty,
        updateLockfile = true
      ),
      cwd = dest,
      env = bunEnv()
    )

    val generated = dest / "bun.lock"
    if (!os.exists(generated)) Task.fail("Bun did not generate bun.lock")
    val sourceLock = moduleDir / "bun.lock"
    os.copy.over(generated, sourceLock, createFolders = true)
    PathRef(sourceLock)
  }

  private def resolvedBunConfigs: Task[Seq[PathRef]] = Task.Anon {
    bunfigFiles()
  }

  private def ensureLinkedWorkspace(report: Report, installDir: os.Path, lockfiles: Seq[String], bunConfigs: Seq[PathRef]): Unit = {
    val linkedDir = report.dest.path

    os.copy.over(installDir / "package.json", linkedDir / "package.json", createFolders = true)

    if (!os.exists(linkedDir / "node_modules") && os.exists(installDir / "node_modules")) {
      os.symlink(linkedDir / "node_modules", installDir / "node_modules")
    }

    lockfiles.foreach { name =>
      val src = installDir / name
      if (os.exists(src) && !os.exists(linkedDir / name)) {
        os.symlink(linkedDir / name, src)
      }
    }

    bunConfigs.foreach { cfg =>
      os.copy.over(cfg.path, linkedDir / cfg.path.last, createFolders = true)
    }
  }

  override def jsEnvConfig: T[JsEnvConfig] = Task {
    JsEnvConfig.NodeJs(
      executable = bunExecutable(),
      args = bunJsEnvArgs().toList,
      env = bunJsEnv(),
      sourceMap = scalaJSSourceMap()
    )
  }

  /** Run the linked Scala.js output directly with Bun.
    *
    * Overrides the inherited `run` which uses NodeJSEnv's stdin-pipe mechanism.
    * NodeJSEnv creates a temp bootstrap script that `import()`s the linked output
    * via `file:` URLs, but Bun rejects cross-sandbox `file:` URL imports.
    * This override invokes `bun run <entrypoint>` directly in the linked output
    * directory, which already has `node_modules` symlinked from `bunInstall`.
    */
  override def run(args: Task[mill.api.Args] = Task.Anon(mill.api.Args(Nil))): Command[Unit] = Task.Command {
    val linked = fastLinkJS()
    val entry = primaryEntrypoint(linked)
    runBun(
      bunExecutable(),
      Seq("run", entry.toString) ++ args().value,
      cwd = linked.dest.path,
      env = bunJsEnv()
    )
    ()
  }

  override protected def linkTask(isFullLinkJS: Boolean, forceOutJs: Boolean): Task[Report] = Task.Anon {
    val linked = super.linkTask(isFullLinkJS, forceOutJs)()
    ensureLinkedWorkspace(linked, bunInstall().path, bunLockfiles(), resolvedBunConfigs())
    linked
  }

  protected def bundleEntrypoints(report: Report): Seq[os.Path] =
    report.publicModules.toSeq.map(m => report.dest.path / m.jsFileName) match {
      case Nil =>
        throw new RuntimeException(
          "No Scala.js public modules found in link output. bunBundle requires a public JS entrypoint, such as a main module initializer or JSExportTopLevel export."
        )
      case modules => modules
    }

  protected def primaryEntrypoint(report: Report): os.Path = {
    report.publicModules.find(_.moduleID == "main").map(m => report.dest.path / m.jsFileName)
      .orElse(report.publicModules.toSeq match {
        case Seq(module) => Some(report.dest.path / module.jsFileName)
        case _           => None
      })
      .getOrElse(
        throw new RuntimeException(
          "No unambiguous Scala.js entrypoint found. Configure a main module initializer or expose exactly one public JS module."
        )
      )
  }

  /** Canonical production bundle task. */
  def bundle: T[PathRef] = Task {
    val linked = fullLinkJS()

    val outDir = Task.dest / "dist"
    os.makeDir.all(outDir)

    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.map(mode => s"--sourcemap=$mode")
    val externalArgs = bunBundleExternal().flatMap(dep => Seq("--external", dep))
    val splittingArgs = if (bunBundleSplitting()) Seq("--splitting") else Nil
    val bytecodeArgs = if (bunBundleBytecode()) Seq("--bytecode") else Nil

    runBun(
      bunExecutable(),
      Seq("build") ++
        bundleEntrypoints(linked).map(_.toString) ++
        Seq("--outdir", outDir.toString, "--target", bunBundleTarget()) ++
        formatArgs ++
        sourcemapArgs ++
        externalArgs ++
        splittingArgs ++
        bytecodeArgs ++
        bunBundleArgs(),
      cwd = linked.dest.path,
      env = bunEnv()
    )

    PathRef(outDir)
  }

  @deprecated("Use bundle", "0.3.0")
  def bunBundle: T[PathRef] = Task { bundle() }

  /** Canonical fast-development bundle task. */
  def bundleFast: T[PathRef] = Task {
    val linked = fastLinkJS()

    val outDir = Task.dest / "dist"
    os.makeDir.all(outDir)

    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.map(mode => s"--sourcemap=$mode")
    val externalArgs = bunBundleExternal().flatMap(dep => Seq("--external", dep))
    val splittingArgs = if (bunBundleSplitting()) Seq("--splitting") else Nil

    runBun(
      bunExecutable(),
      Seq("build") ++
        bundleEntrypoints(linked).map(_.toString) ++
        Seq("--outdir", outDir.toString, "--target", bunBundleTarget()) ++
        formatArgs ++
        sourcemapArgs ++
        externalArgs ++
        splittingArgs ++
        bunBundleArgs(),
      cwd = linked.dest.path,
      env = bunEnv()
    )

    PathRef(outDir)
  }

  @deprecated("Use bundleFast", "0.3.0")
  def bunBundleFast: T[PathRef] = Task { bundleFast() }

  private def copyCompileResources(resources: Seq[PathRef], dest: os.Path): Unit =
    BunToolchainModule.copyPathRefs(resources, dest, Seq(moduleDir))

  /** Build a server-side Scala.js entrypoint as a standalone executable. */
  def compileExecutable: T[PathRef] = Task {
    val linked = fullLinkJS()
    // Declared explicitly: the staged workspace carries a node_modules symlink into this
    // install, and Mill's filesystem checker only permits reading a dest we depend on.
    bunInstall()
    val buildDir = Task.dest / "workspace"
    BunToolchainModule.copyWorkspace(linked.dest.path, buildDir)
    resolvedBunConfigs().foreach(cfg => os.copy.over(cfg.path, buildDir / cfg.path.last, createFolders = true))
    copyCompileResources(bunCompileResources(), buildDir)

    val outFile = Task.dest / bunBinaryName()
    val entry = primaryEntrypoint(linked).relativeTo(linked.dest.path).toString
    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.map(mode => s"--sourcemap=$mode")
    val bytecodeArgs = if (bunBundleBytecode()) Seq("--bytecode") else Nil

    runBun(
      bunExecutable(),
      Seq("build", entry, "--outfile", outFile.toString, "--compile", "--target", "bun") ++
        formatArgs ++
        sourcemapArgs ++
        bytecodeArgs ++
        bunBundleArgs(),
      cwd = buildDir,
      env = bunEnv()
    )

    PathRef(outFile)
  }

  @deprecated("Use compileExecutable", "0.3.0")
  def bunCompileExecutable: T[PathRef] = Task { compileExecutable() }

  /**
   * Cross-compile standalone executables for each configured target.
   * Returns a map of target name to executable PathRef.
   */
  def compileExecutables: T[Map[String, PathRef]] = Task {
    val targets = bunCompileTargets()
    if (targets.isEmpty) Task.fail("bunCompileTargets is empty. Set targets like Seq(\"bun-linux-x64\", \"bun-darwin-arm64\").")

    val linked = fullLinkJS()
    bunInstall()
    val buildDir = Task.dest / "workspace"
    BunToolchainModule.copyWorkspace(linked.dest.path, buildDir)
    resolvedBunConfigs().foreach(cfg => os.copy.over(cfg.path, buildDir / cfg.path.last, createFolders = true))
    copyCompileResources(bunCompileResources(), buildDir)

    val entry = primaryEntrypoint(linked).relativeTo(linked.dest.path).toString
    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.map(mode => s"--sourcemap=$mode")
    val bytecodeArgs = if (bunBundleBytecode()) Seq("--bytecode") else Nil
    val binaryName = bunBinaryName()

    targets.map { target =>
      val suffix = if (target.contains("windows")) ".exe" else ""
      val outFile = Task.dest / s"$binaryName-$target$suffix"

      runBun(
        bunExecutable(),
        Seq("build", entry, "--outfile", outFile.toString, "--compile", "--target", target) ++
          formatArgs ++
          sourcemapArgs ++
          bytecodeArgs ++
          bunBundleArgs(),
        cwd = buildDir,
        env = bunEnv()
      )

      target -> PathRef(outFile)
    }.toMap
  }

  @deprecated("Use compileExecutables", "0.3.0")
  def bunCompileExecutables: T[Map[String, PathRef]] = Task { compileExecutables() }

  trait BunScalaJSTests extends ScalaJSTests {
    override def moduleKind: T[ModuleKind] = Task {
      outer.moduleKind() match {
        // Bun rejects the temporary file:-URL importer that Scala.js' Node env
        // generates for ES module test runs, so keep test linking on CommonJS.
        case ModuleKind.ESModule => ModuleKind.CommonJSModule
        case other               => other
      }
    }

    /**
     * Runtime environment for the JS process that drives the Scala.js test
     * framework. Defaults to the outer module's [[bunJsEnv]] unchanged, so
     * plain test runs behave exactly like before.
     *
     * Override this when your tests need environment variables that differ
     * from production `bunRun` invocations. A common case: set
     * `NODE_ENV=production` so that in-process [[https://bun.sh/docs/api/http
     * `Bun.serve({...})`]] calls default `development: false`. Otherwise
     * Bun's dev-mode error overlay rewrites any fetch-handler Promise
     * rejection into a ~100 KB HTML `Response` (the `BunError` React
     * bundle), which masks the real error inside a running test and makes
     * HTTP-level assertions impossible. Example:
     *
     * {{{
     *   object test extends BunScalaJSTests:
     *     override def bunTestJsEnv = Task {
     *       super.bunTestJsEnv() + ("NODE_ENV" -> "production")
     *     }
     * }}}
     */
    def bunTestJsEnv: T[Map[String, String]] = Task { outer.bunJsEnv() }

    /**
     * Scala.js environment used specifically for the test framework process.
     * Mirrors [[outer.jsEnvConfig]] but sources its `env` from
     * [[bunTestJsEnv]] so tests can diverge (e.g. override `NODE_ENV`)
     * without affecting `bunRun` on the outer module.
     */
    override def jsEnvConfig: T[JsEnvConfig] = Task {
      JsEnvConfig.NodeJs(
        executable = outer.bunExecutable(),
        args = outer.bunJsEnvArgs().toList,
        env = bunTestJsEnv(),
        sourceMap = outer.scalaJSSourceMap()
      )
    }

    override protected def testLinkTask: Task[Report] = Task.Anon {
      val linked = super.testLinkTask()
      outer.ensureLinkedWorkspace(linked, outer.bunInstall().path, outer.bunLockfiles(), outer.resolvedBunConfigs())
      linked
    }

    /** Run Scala.js tests through Mill's test bridge with Bun as the JS runtime. */
    def bunTest(args: mill.api.Args): Command[(msg: String, results: Seq[mill.javalib.testrunner.TestResult])] =
      Task.Command {
        testTask(
          Task.Anon { testArgsDefault() ++ args.value },
          Task.Anon { Seq.empty[String] }
        )()
      }
  }
}
