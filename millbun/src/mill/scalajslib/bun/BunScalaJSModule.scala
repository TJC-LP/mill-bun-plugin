package mill.scalajslib
package bun

import mill.*
import mill.api.BuildCtx
import mill.bun.BunToolchainModule
import mill.scalajslib.*
import mill.scalajslib.api.*

trait BunScalaJSModule extends ScalaJSModule with BunToolchainModule { outer =>

  /** JS packages needed by linked Scala.js output, e.g. packages referenced by @JSImport. */
  def npmDeps: T[Seq[String]] = Task { Seq.empty }

  /** Dev-only JS packages for bundling or local tooling. */
  def npmDevDeps: T[Seq[String]] = Task { Seq.empty }

  /** Local tarballs / package directories. */
  def unmanagedDeps: T[Seq[PathRef]] = Task { Seq.empty }

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
      "dependencies" -> ujson.Obj.from(npmDeps().map(BunToolchainModule.splitDep)),
      "devDependencies" -> ujson.Obj.from(npmDevDeps().map(BunToolchainModule.splitDep))
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
      Seq("install") ++ bunInstallArgs() ++ unmanagedDeps().map(_.path.toString),
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

  override def fastLinkJS: T[Report] = Task(persistent = true) {
    val report = super.fastLinkJS()
    ensureLinkedWorkspace(report, bunInstall().path, bunLockfiles())
    report
  }

  override def fullLinkJS: T[Report] = Task(persistent = true) {
    val report = super.fullLinkJS()
    ensureLinkedWorkspace(report, bunInstall().path, bunLockfiles())
    report
  }

  protected def bundleEntrypoints(report: Report): Seq[os.Path] =
    report.publicModules.toSeq.map(m => report.dest.path / m.jsFileName)

  protected def primaryEntrypoint(report: Report): os.Path = {
    val module =
      report.publicModules.find(_.moduleID == "main").orElse(report.publicModules.headOption).getOrElse {
        throw new RuntimeException("No public Scala.js module found in link report.")
      }
    report.dest.path / module.jsFileName
  }

  def bunBundle: T[PathRef] = Task {
    val linked = fullLinkJS()

    val outDir = Task.dest / "dist"
    os.makeDir.all(outDir)

    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.flatMap(mode => Seq("--sourcemap", mode))
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
    val sourcemapArgs = bunBundleSourcemap().toSeq.flatMap(mode => Seq("--sourcemap", mode))
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
    resources.foreach { ref =>
      val p = ref.path
      os.copy.over(p, dest / p.last, createFolders = true)
    }

  /** Convenience task for server-side Scala.js entrypoints. */
  def bunCompileExecutable: T[PathRef] = Task {
    val linked = fullLinkJS()
    val cwd = linked.dest.path
    copyCompileResources(bunCompileResources(), cwd)

    val outFile = Task.dest / bunBinaryName()
    val formatArgs = bunBundleFormat().toSeq.flatMap(fmt => Seq("--format", fmt))
    val sourcemapArgs = bunBundleSourcemap().toSeq.flatMap(mode => Seq("--sourcemap", mode))
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
    val sourcemapArgs = bunBundleSourcemap().toSeq.flatMap(mode => Seq("--sourcemap", mode))
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

  trait BunScalaJSTests extends ScalaJSTests {
    override def fastLinkJSTest: T[Report] = Task(persistent = true) {
      val report = super.fastLinkJSTest()
      outer.ensureLinkedWorkspace(report, outer.bunInstall().path, outer.bunLockfiles())
      report
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
