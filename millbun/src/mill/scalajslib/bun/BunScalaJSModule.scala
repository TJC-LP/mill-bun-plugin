package mill.scalajslib
package bun

import mill.*
import mill.api.BuildCtx
import mill.bun.BunToolchainModule
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

  /** Local tarballs / package directories. */
  def unmanagedDeps: T[Seq[PathRef]] = Task { Seq.empty }

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

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(recursiveBunModuleDeps)(_.npmDeps)().flatten ++ npmDeps()
  }

  def transitiveNpmDevDeps: T[Seq[String]] = Task {
    Task.traverse(recursiveBunModuleDeps)(_.npmDevDeps)().flatten ++ npmDevDeps()
  }

  def transitiveUnmanagedDeps: T[Seq[PathRef]] = Task {
    Task.traverse(recursiveBunModuleDeps)(_.unmanagedDeps)().flatten ++ unmanagedDeps()
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

  private def mkBunPackageJson: Task[Unit] = Task.Anon {
    val dest = Task.dest
    val base = ujson.Obj(
      "name" -> defaultPackageName,
      "private" -> true,
      "version" -> "0.0.0",
      "dependencies" -> ujson.Obj.from(transitiveNpmDeps().map(BunToolchainModule.splitDep)),
      "devDependencies" -> ujson.Obj.from(transitiveNpmDevDeps().map(BunToolchainModule.splitDep))
    )

    val packageType =
      moduleKind() match {
        case ModuleKind.ESModule => Some("module")
        case _                   => None
      }

    packageType.foreach(tpe => base("type") = tpe)

    val merged = ujson.Obj.from(base.value.toSeq ++ bunPackageJsonExtras().value.toSeq)
    os.write.over(dest / "package.json", merged.render(indent = 2), createFolders = true)
  }

  def bunInstall: T[PathRef] = Task {
    val dest = Task.dest
    os.makeDir.all(dest)

    val npmrc = BuildCtx.workspaceRoot / ".npmrc"
    if (os.exists(npmrc)) os.copy.over(npmrc, dest / ".npmrc", createFolders = true)

    bunfigFiles().foreach { cfg =>
      os.copy.over(cfg.path, dest / cfg.path.last, createFolders = true)
    }

    mkBunPackageJson()

    runBun(
      bunExecutable(),
      Seq("install") ++ bunInstallArgs() ++ transitiveUnmanagedDeps().map(_.path.toString),
      cwd = dest,
      env = bunEnv()
    )

    PathRef(dest)
  }

  private def ensureLinkedWorkspace(report: Report, installDir: os.Path, lockfiles: Seq[String]): Unit = {
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
    ensureLinkedWorkspace(linked, bunInstall().path, bunLockfiles())
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
    val cwd = linked.dest.path
    copyCompileResources(bunCompileResources(), cwd)

    val outFile = Task.dest / bunBinaryName()
    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.map(mode => s"--sourcemap=$mode")
    val bytecodeArgs = if (bunBundleBytecode()) Seq("--bytecode") else Nil

    runBun(
      bunExecutable(),
      Seq("build", primaryEntrypoint(linked).toString, "--outfile", outFile.toString, "--compile", "--target", "bun") ++
        formatArgs ++
        sourcemapArgs ++
        bytecodeArgs ++
        bunBundleArgs(),
      cwd = cwd,
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
    val cwd = linked.dest.path
    copyCompileResources(bunCompileResources(), cwd)

    val entry = primaryEntrypoint(linked).toString
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
        cwd = cwd,
        env = bunEnv()
      )

      target -> PathRef(outFile)
    }.toMap
  }

  trait BunScalaJSTests extends ScalaJSConfigTests {
    override protected def testLinkTask: Task[Report] = Task.Anon {
      val linked = super.testLinkTask()
      outer.ensureLinkedWorkspace(linked, outer.bunInstall().path, outer.bunLockfiles())
      linked
    }

    /** Run Bun tests in the linked Scala.js workspace. */
    def bunTest(args: mill.api.Args): Command[os.CommandResult] = Task.Command {
      val report = fastLinkJSTest()
      os.call(
        Seq(outer.bunExecutable(), "test") ++ args.value,
        cwd = report.dest.path,
        env = outer.bunEnv(),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }
  }
}
