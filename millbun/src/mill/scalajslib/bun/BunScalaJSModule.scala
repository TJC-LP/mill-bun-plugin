package mill.scalajslib
package bun

import mill.*
import mill.api.BuildCtx
import mill.api.JsonFormatters.given
import mill.bun.{BunManifest, BunToolchainModule, BunVendoredNodeModules}
import mill.javalib.JavaModule
import mill.scalajslib.*
import mill.scalajslib.api.*
import mill.scalajslib.config.ScalaJSConfigModule

import scala.annotation.tailrec
trait BunScalaJSModule extends ScalaJSConfigModule with BunToolchainModule { outer =>

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

  /** Local tarballs / package directories. */
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
    val moduleNpm = Task.traverse(recursiveInstallBunModuleDeps)(_.npmDevDeps)().flatten
    val moduleBun = Task.traverse(recursiveInstallBunModuleDeps)(_.bunDevDeps)().flatten
    moduleNpm ++ moduleBun ++ classpathBunDevDeps() ++ npmDevDeps() ++ bunDevDeps()
  }

  def transitiveUnmanagedDeps: T[Seq[PathRef]] = Task {
    Task.traverse(recursiveInstallBunModuleDeps)(_.unmanagedDeps)().flatten ++ unmanagedDeps()
  }

  /** Optional JS packages — installed if available, not fatal if missing. */
  def bunOptionalDeps: T[Seq[String]] = Task { Seq.empty }

  // ---------------------------------------------------------------------------
  // Classpath manifest scanning — reads bun-dependencies.json from dependency JARs
  // ---------------------------------------------------------------------------

  /** Scan classpath JARs for embedded bun dependency manifests. */
  def classpathBunDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.dependencies).map { case (name, version) => s"$name@$version" }
  }

  /** Scan classpath JARs for embedded bun dev-dependency manifests. */
  def classpathBunDevDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.devDependencies).map { case (name, version) => s"$name@$version" }
  }

  /** Scan classpath JARs for embedded bun optional-dependency manifests. */
  def classpathBunOptionalDeps: T[Seq[String]] = Task {
    classpathBunManifests().flatMap(_.optionalDependencies).map { case (name, version) => s"$name@$version" }
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

  def transitiveBunOptionalDeps: T[Seq[String]] = Task {
    val moduleOptional = Task.traverse(recursiveInstallBunModuleDeps)(_.bunOptionalDeps)().flatten
    moduleOptional ++ classpathBunOptionalDeps() ++ bunOptionalDeps()
  }

  private def mkBunPackageJson: Task[Unit] = Task.Anon {
    val dest = Task.dest
    val allOptional = transitiveBunOptionalDeps().map(BunToolchainModule.splitDep)
    val base = ujson.Obj(
      "name" -> defaultPackageName,
      "private" -> true,
      "version" -> "0.0.0",
      "dependencies" -> ujson.Obj.from(transitiveNpmDeps().map(BunToolchainModule.splitDep)),
      "devDependencies" -> ujson.Obj.from(transitiveNpmDevDeps().map(BunToolchainModule.splitDep))
    )
    if allOptional.nonEmpty then
      base("optionalDependencies") = ujson.Obj.from(allOptional)

    val packageType =
      moduleKind() match {
        case ModuleKind.ESModule => Some("module")
        case _                   => None
      }

    packageType.foreach(tpe => base("type") = tpe)

    val merged = ujson.Obj.from(base.value.toSeq ++ bunPackageJsonExtras().value.toSeq)
    os.write.over(dest / "package.json", merged.render(indent = 2), createFolders = true)
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
        transitiveBunOptionalDeps().nonEmpty ||
        transitiveUnmanagedDeps().nonEmpty ||
        bunPackageJsonExtras().value.nonEmpty

    if hasInstallInputs then
      runBun(
        bunExecutable(),
        Seq("install") ++ bunInstallArgs() ++ transitiveUnmanagedDeps().map(_.path.toString),
        cwd = dest,
        env = bunEnv()
      )

    val ownResourceRoots = resources().map(_.path).toSet
    val vendoredEntries = runClasspath().map(_.path).filterNot(ownResourceRoots.contains)
    mergeVendoredNodeModules(vendoredEntries, dest / "node_modules")

    PathRef(dest)
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

  def bunBundle: T[PathRef] = Task {
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

  def bunBundleFast: T[PathRef] = Task {
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

  private def copyCompileResources(resources: Seq[PathRef], dest: os.Path): Unit =
    BunToolchainModule.copyPathRefs(resources, dest, Seq(moduleDir))

  /** Convenience task for server-side Scala.js entrypoints. */
  def bunCompileExecutable: T[PathRef] = Task {
    val linked = fullLinkJS()
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

  /**
   * Cross-compile standalone executables for each configured target.
   * Returns a map of target name to executable PathRef.
   */
  def bunCompileExecutables: T[Map[String, PathRef]] = Task {
    val targets = bunCompileTargets()
    if (targets.isEmpty) Task.fail("bunCompileTargets is empty. Set targets like Seq(\"bun-linux-x64\", \"bun-darwin-arm64\").")

    val linked = fullLinkJS()
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

  trait BunScalaJSTests extends ScalaJSConfigTests {
    override def moduleKind: T[ModuleKind] = Task {
      outer.moduleKind() match {
        // Bun rejects the temporary file:-URL importer that Scala.js' Node env
        // generates for ES module test runs, so keep test linking on CommonJS.
        case ModuleKind.ESModule => ModuleKind.CommonJSModule
        case other               => other
      }
    }

    override protected def testLinkTask: Task[Report] = Task.Anon {
      val linkConfig =
        outer.moduleKind() match {
          case ModuleKind.ESModule =>
            outer.scalaJSConfig().withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule)
          case _ =>
            outer.scalaJSConfig()
        }

      linkJs(
        worker = mill.scalajslib.config.worker.ScalaJSConfigWorkerExternalModule.scalaJSWorker(),
        toolsClasspath = scalaJSToolsClasspath(),
        runClasspath = scalaJSTestDeps() ++ runClasspath(),
        moduleInitializers = testModuleInitializers(),
        forceOutJs = false,
        testBridgeInit = true,
        importMap = scalaJSImportMap(),
        config = linkConfig
      ).map { linked =>
        outer.ensureLinkedWorkspace(linked, outer.bunInstall().path, outer.bunLockfiles(), outer.resolvedBunConfigs())
        linked
      }
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
