package mill.bun

import mill.*
import mill.api.BuildCtx

/** A Scala.js or TypeScript package participating in a shared Bun workspace. */
trait BunPackageModule extends Module:

  /** Optional shared workspace install. Override with `Task { Some(workspace.bunInstall()) }`. */
  def bunWorkspaceInstall: T[Option[PathRef]] = Task { None }

  /** npm package name used in the generated workspace. */
  def bunWorkspacePackageName: T[String]

  /** Complete typed package.json used by [[BunWorkspaceModule]]. */
  def bunWorkspacePackageJson: T[ujson.Obj]

  /** Local package archives/directories passed to the workspace install. */
  def bunWorkspaceUnmanagedDeps: T[Seq[PathRef]] = Task { Seq.empty }

/** Root module that gives Scala.js and TypeScript packages one Bun install and lockfile.
  *
  * Member modules opt in by returning this module's install from `bunWorkspaceInstall`.
  */
trait BunWorkspaceModule extends BunToolchainModule:

  /** Packages included in this workspace. */
  def bunWorkspacePackages: Seq[BunPackageModule]

  /** Root package name. */
  def bunWorkspaceName: T[String] = Task {
    val name = toString
    if name.nonEmpty then name.replace('.', '-') else "mill-bun-workspace"
  }

  /** Unmodeled root package.json fields such as scripts. */
  def bunPackageJsonExtras: T[ujson.Obj] = Task { ujson.Obj() }

  private def npmRc = Task.Source(BuildCtx.workspaceRoot / ".npmrc")

  private def packageDirectory(name: String): String =
    name.stripPrefix("@").replace('/', '+')

  private def resolvedPackages: Task[Seq[(String, ujson.Obj, Seq[PathRef])]] = Task.Anon {
    Task.traverse(bunWorkspacePackages) { module =>
      Task.Anon {
        (
          module.bunWorkspacePackageName(),
          module.bunWorkspacePackageJson(),
          module.bunWorkspaceUnmanagedDeps()
        )
      }
    }()
  }

  /** Generated root and member package.json files before installation. */
  def bunWorkspaceLayout: T[PathRef] = Task {
    val packages = resolvedPackages()
    val duplicateNames = packages.groupBy(_._1).collect { case (name, entries) if entries.size > 1 => name }.toSeq.sorted
    if duplicateNames.nonEmpty then
      Task.fail(s"Duplicate Bun workspace package names: ${duplicateNames.mkString(", ")}")

    val directories = packages.map((name, _, _) => name -> packageDirectory(name))
    val duplicateDirectories = directories.groupBy(_._2).collect {
      case (directory, entries) if entries.size > 1 => directory
    }.toSeq.sorted
    if duplicateDirectories.nonEmpty then
      Task.fail(s"Bun workspace package names map to duplicate directories: ${duplicateDirectories.mkString(", ")}")

    packages.foreach { case (name, json, unmanaged) =>
      val directory = packageDirectory(name)
      os.write.over(
        Task.dest / "packages" / directory / "package.json",
        json.render(indent = 2),
        createFolders = true
      )
      // Members declare local packages as `file:./vendor/<name>` relative to their own
      // package.json, so their vendor trees live beside it in the layout.
      BunToolchainModule.stageUnmanagedDeps(unmanaged, Task.dest / "packages" / directory)
    }

    val root = ujson.Obj(
      "name" -> bunWorkspaceName(),
      "private" -> true,
      "version" -> "0.0.0",
      "packageManager" -> s"bun@${bunVersion()}",
      "workspaces" -> ujson.Arr.from(directories.map((_, directory) => ujson.Str(s"packages/$directory")))
    )
    if npmOverrides().nonEmpty then
      root("overrides") = ujson.Obj.from(
        npmOverrides().toSeq.sortBy(_._1).map((name, specifier) => name -> ujson.Str(specifier))
      )
    val merged = BunToolchainModule.mergePackageJson(root, bunPackageJsonExtras())
    os.write.over(Task.dest / "package.json", merged.render(indent = 2), createFolders = true)
    PathRef(Task.dest)
  }

  private def copyConfigs(destination: os.Path, npmRcPath: os.Path, bunfigs: Seq[PathRef]): Unit =
    if os.exists(npmRcPath) then
      os.copy.over(npmRcPath, destination / ".npmrc", createFolders = true)
    bunfigs.foreach(ref =>
      os.copy.over(ref.path, destination / ref.path.last, createFolders = true)
    )

  private def hasDependencyInputs(packages: Seq[(String, ujson.Obj, Seq[PathRef])]): Boolean =
    val dependencyFields = Seq("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
    packages.exists { case (_, json, unmanaged) =>
      unmanaged.nonEmpty || dependencyFields.exists(field => json.value.get(field).exists(_.obj.nonEmpty))
    }

  /** Install every member from one source-controlled root `bun.lock`. */
  def bunInstall: T[PathRef] = Task {
    val packages = resolvedPackages()
    BunToolchainModule.copyWorkspace(bunWorkspaceLayout().path, Task.dest)
    copyConfigs(Task.dest, npmRc().path, bunfigFiles())

    val lockfile = bunLockfile()
    requireBunLockfile(hasDependencyInputs(packages), lockfile, bunRequireLockfile(), bunVersion())
    copyBunLockfile(lockfile, Task.dest)

    runBun(
      bunExecutable(),
      Seq("install") ++ resolvedBunInstallArgs(
        bunInstallArgs(),
        bunInstallExtraArgs(),
        lockfile.nonEmpty,
        updateLockfile = false
      ),
      cwd = Task.dest,
      env = bunToolEnv()
    )
    PathRef(Task.dest)
  }

  /** Resolve the full workspace and update its source-controlled `bun.lock`. */
  def bunLock(): Command[PathRef] = Task.Command {
    BunToolchainModule.copyWorkspace(bunWorkspaceLayout().path, Task.dest)
    copyConfigs(Task.dest, npmRc().path, bunfigFiles())
    copyBunLockfile(bunLockfile(), Task.dest)

    runBun(
      bunExecutable(),
      Seq("install") ++ resolvedBunInstallArgs(
        bunInstallArgs(),
        bunInstallExtraArgs(),
        bunLockfile().nonEmpty,
        updateLockfile = true
      ),
      cwd = Task.dest,
      env = bunToolEnv()
    )

    val generated = Task.dest / "bun.lock"
    if !os.exists(generated) then Task.fail("Bun did not generate bun.lock")
    val sourceLock = moduleDir / "bun.lock"
    os.copy.over(generated, sourceLock, createFolders = true)
    PathRef(sourceLock)
  }
