package mill.bun

import mill.*
import mill.api.BuildCtx
import java.io.{BufferedInputStream, FileInputStream, FileOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object BunToolchainModule {

  private val ModeledPackageJsonFields = Set(
    "dependencies",
    "devDependencies",
    "optionalDependencies",
    "peerDependencies",
    "overrides",
    "workspaces"
  )

  private[bun] final case class NpmDependency(name: String, specifier: String)

  private[bun] final case class Distribution(
      assetName: String,
      executableName: String
  )

  private val Bun1314Checksums = Map(
    "bun-darwin-aarch64.zip" -> "d8b96221828ad6f97ac7ac0ab7e95872341af763001e8803e8267652c2652620",
    "bun-darwin-x64.zip" -> "4183df3374623e5bab315c547cfa0974533cd457d86b73b639f7a87974cd6633",
    "bun-linux-aarch64.zip" -> "a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b",
    "bun-linux-x64.zip" -> "951ee2aee855f08595aeec6225226a298d3fea83a3dcd6465c09cbccdf7e848f",
    "bun-windows-aarch64.zip" -> "89841f5a57f2348b67ec0839b718f4bf4ea7d07c371c9ba4b77b6c790f918953",
    "bun-windows-x64.zip" -> "0a0620930b6675d7ba440e81f4e0e00d3cfbe096c4b140d3fff02205e9e18922"
  )

  private[bun] def distribution(osName: String, architecture: String): Either[String, Distribution] = {
    val osPart = osName.toLowerCase match {
      case name if name.contains("mac") || name.contains("darwin") => Right("darwin")
      case name if name.contains("linux")                           => Right("linux")
      case name if name.contains("windows")                        => Right("windows")
      case other => Left(s"Unsupported operating system '$other'")
    }
    val archPart = architecture.toLowerCase match {
      case "aarch64" | "arm64" => Right("aarch64")
      case "amd64" | "x86_64" | "x64" => Right("x64")
      case other => Left(s"Unsupported architecture '$other'")
    }

    for {
      os <- osPart
      arch <- archPart
    } yield Distribution(
      assetName = s"bun-$os-$arch.zip",
      executableName = if (os == "windows") "bun.exe" else "bun"
    )
  }

  private[bun] def bundledChecksum(version: String, assetName: String): Option[String] =
    if (version == "1.3.14") Bun1314Checksums.get(assetName) else None

  private[bun] def sha256(path: os.Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = new BufferedInputStream(new FileInputStream(path.toIO))
    val buffer = new Array[Byte](64 * 1024)
    try {
      var read = stream.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = stream.read(buffer)
      }
    } finally stream.close()
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
  }

  private[bun] def download(url: String, destination: os.Path): Unit = {
    os.makeDir.all(destination / os.up)
    val client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .build()
    val request = HttpRequest.newBuilder(URI.create(url))
      .header("User-Agent", "mill-bun-plugin")
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination.toNIO))
    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(s"Unable to download Bun from $url: HTTP ${response.statusCode()}")
    }
  }

  private[bun] def extractExecutable(
      archive: os.Path,
      executableName: String,
      destination: os.Path
  ): Unit = {
    val zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive.toIO)))
    var found = false
    try {
      var entry = zip.getNextEntry
      while (entry != null) {
        val entryName = entry.getName.replace('\\', '/')
        if (!entry.isDirectory && entryName.split('/').lastOption.contains(executableName)) {
          os.makeDir.all(destination / os.up)
          val output = new FileOutputStream(destination.toIO)
          try zip.transferTo(output)
          finally output.close()
          found = true
        }
        zip.closeEntry()
        entry = zip.getNextEntry
      }
    } finally zip.close()

    if (!found) throw new RuntimeException(s"Bun archive does not contain $executableName")
    if (executableName != "bun.exe" && !destination.toIO.setExecutable(true)) {
      throw new RuntimeException(s"Unable to make downloaded Bun executable: $destination")
    }
  }

  /** Parse package.json-style `name@specifier` declarations without slicing scoped names incorrectly. */
  private[bun] def parseDependency(input: String): Either[String, NpmDependency] = {
    val trimmed = input.trim
    if (trimmed.isEmpty) Left("Dependency cannot be empty")
    else if (trimmed.startsWith("@")) {
      val slash = trimmed.indexOf('/')
      if (slash <= 1 || slash == trimmed.length - 1) Left(s"Invalid scoped dependency '$input'")
      else {
        val separator = trimmed.indexOf('@', slash + 1)
        val name = if (separator < 0) trimmed else trimmed.take(separator)
        val specifier = if (separator < 0) "latest" else trimmed.drop(separator + 1)
        if (specifier.isEmpty) Left(s"Dependency '$input' has an empty specifier")
        else Right(NpmDependency(name, specifier))
      }
    } else {
      val separator = trimmed.indexOf('@')
      val name = if (separator < 0) trimmed else trimmed.take(separator)
      val specifier = if (separator < 0) "latest" else trimmed.drop(separator + 1)
      if (name.isEmpty) Left(s"Dependency '$input' has an empty name")
      else if (specifier.isEmpty) Left(s"Dependency '$input' has an empty specifier")
      else Right(NpmDependency(name, specifier))
    }
  }

  /** Parse a dependency for compatibility with the existing public helper. */
  def splitDep(input: String): (String, ujson.Str) =
    parseDependency(input) match {
      case Right(dep) => dep.name -> ujson.Str(dep.specifier)
      case Left(message) => throw new IllegalArgumentException(message)
    }

  /** Resolve duplicate declarations deterministically and fail conflicting specs unless overridden. */
  def dependencyPairs(
      inputs: Seq[String],
      overrides: Map[String, String] = Map.empty
  ): Seq[(String, ujson.Str)] = {
    val parsed = inputs.map(input => parseDependency(input).fold(
      message => throw new IllegalArgumentException(message),
      identity
    ))
    parsed.groupBy(_.name).toSeq.sortBy(_._1).map { case (name, entries) =>
      val specifiers = entries.map(_.specifier).distinct
      val resolved = overrides.get(name).orElse(specifiers match {
        case Seq(specifier) => Some(specifier)
        case _ => None
      }).getOrElse(
        throw new IllegalArgumentException(
          s"Conflicting npm dependency '$name': ${specifiers.sorted.mkString(", ")}. " +
            "Declare npmOverrides to select one specifier."
        )
      )
      name -> ujson.Str(resolved)
    }
  }

  /** Add unmodeled package.json fields without allowing typed dependency data to be replaced. */
  def mergePackageJson(base: ujson.Obj, extras: ujson.Obj): ujson.Obj = {
    val conflicts = extras.value.keySet.intersect(ModeledPackageJsonFields).toSeq.sorted
    if (conflicts.nonEmpty) {
      throw new IllegalArgumentException(
        s"bunPackageJsonExtras cannot replace modeled fields: ${conflicts.mkString(", ")}. " +
          "Use npmDeps, npmDevDeps, npmOptionalDeps, npmPeerDeps, or npmOverrides."
      )
    }
    ujson.Obj.from(base.value.toSeq ++ extras.value.toSeq)
  }

  /** Build candidate executable names from a base name and PATHEXT extensions.
   *  PATHEXT is Windows-specific and always semicolon-delimited regardless of platform. */
  def executableCandidates(name: String, pathExt: String): Seq[String] = {
    val extensions = pathExt.split(";").filter(_.nonEmpty)
    if (extensions.nonEmpty) Seq(name) ++ extensions.map(ext => name + ext.toLowerCase)
    else Seq(name)
  }

  /** Resolve an executable name from PATH, respecting PATHEXT on Windows. */
  def findOnPath(name: String): Option[os.Path] = {
    val pathDirs = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator)
    val candidates = executableCandidates(name, sys.env.getOrElse("PATHEXT", ""))

    pathDirs.iterator
      .filter(_.nonEmpty)
      .flatMap(dir => candidates.iterator.map(c => os.Path(dir) / c))
      .find(path => os.isFile(path) && java.nio.file.Files.isExecutable(path.toNIO))
  }

  /** Copy a generated workspace into a fresh task destination, preserving layout. */
  def copyWorkspace(source: os.Path, dest: os.Path): Unit = {
    os.walk(source)
      .foreach(path => os.copy.over(path, dest / path.relativeTo(source), createFolders = true))
  }

  /**
   * Copy files or directories into a Bun workspace while preserving their relative path
   * beneath the nearest matching source root when possible.
   */
  def copyPathRefs(
      refs: Seq[PathRef],
      destRoot: os.Path,
      sourceRoots: Seq[os.Path] = Seq.empty
  ): Unit = {
    refs.foreach { ref =>
      val source = ref.path
      val target =
        sourceRoots.iterator
          .find(root => source.startsWith(root) && source != root)
          .map(root => destRoot / source.relativeTo(root))
          .getOrElse(destRoot / source.last)

      if (os.isDir(source)) {
        os.walk(source).foreach { path =>
          os.copy.over(path, target / path.relativeTo(source), createFolders = true)
        }
      } else {
        os.copy.over(source, target, createFolders = true)
      }
    }
  }
}

trait BunToolchainModule extends Module {

  /** Tested Bun release downloaded by default when no override is configured. */
  def bunVersion: T[String] = Task { "1.3.14" }

  /** Resolve Bun from PATH instead of using the managed distribution. */
  def bunUseSystem: T[Boolean] = Task {
    Task.env.get("MILL_BUN_USE_SYSTEM").exists(_.equalsIgnoreCase("true"))
  }

  /** Command name used when resolving Bun from PATH. */
  def bunExecutableName: T[String] = Task { "bun" }

  /** Explicit Bun binary override. */
  def bunExecutableOverride: T[Option[PathRef]] = Task { None }

  /** @deprecated Use [[bunExecutableOverride]]. */
  @deprecated("Use bunExecutableOverride", "0.3.0")
  def managedBunExecutable: T[Option[PathRef]] = Task { None }

  /** Optional managed distribution mirror. Must be paired with [[bunArchiveSha256]]. */
  def bunArchiveUrl: T[Option[String]] = Task { None }

  /** SHA-256 for [[bunArchiveUrl]], or for an unbundled Bun version. */
  def bunArchiveSha256: T[Option[String]] = Task { None }

  /** Verify that an override/system executable matches [[bunVersion]]. */
  def bunVerifyVersion: T[Boolean] = Task { true }

  /** Environment passed to Bun subprocesses. */
  def bunEnv: T[Map[String, String]] = Task { Map.empty }

  /** Explicit resolutions for conflicting transitive npm dependency declarations. */
  def npmOverrides: T[Map[String, String]] = Task { Map.empty }

  /** Lockfile names that Bun may produce. */
  def bunLockfiles: T[Seq[String]] = Task { Seq("bun.lock", "bun.lockb") }

  /** Source-controlled text lockfile for this module. */
  def bunLockfile: T[Option[PathRef]] = Task.Input {
    val path = moduleDir / "bun.lock"
    if (os.exists(path)) Some(PathRef(path)) else None
  }

  /** Require dependency-bearing modules to provide [[bunLockfile]]. */
  def bunRequireLockfile: T[Boolean] = Task {
    !Task.env.get("MILL_BUN_REQUIRE_LOCKFILE").exists(_.equalsIgnoreCase("false"))
  }

  /** Hoisted installs are the safest default for Node-compatible resolution. */
  def bunLinker: T[String] = Task { "hoisted" }

  def bunInstallArgs: T[Seq[String]] = Task {
    Seq("--save-text-lockfile", "--linker", bunLinker())
  }

  /** Additional install flags. Lockfile safety flags are controlled by the plugin. */
  def bunInstallExtraArgs: T[Seq[String]] = Task { Seq.empty }

  protected def copyBunLockfile(lockfile: Option[PathRef], destination: os.Path): Unit =
    lockfile.foreach(ref => os.copy.over(ref.path, destination / "bun.lock", createFolders = true))

  protected def resolvedBunInstallArgs(
      baseArgs: Seq[String],
      extraArgs: Seq[String],
      hasLockfile: Boolean,
      updateLockfile: Boolean
  ): Seq[String] = {
    val protectedPrefixes = Seq(
      "--no-save",
      "--lockfile",
      "--frozen-lockfile",
      "--save-text-lockfile"
    )
    val forbidden = extraArgs.filter(arg => protectedPrefixes.exists(arg.startsWith))
    if (forbidden.nonEmpty) {
      throw new IllegalArgumentException(
        s"bunInstallExtraArgs cannot override lockfile safety: ${forbidden.mkString(", ")}"
      )
    }
    baseArgs ++ extraArgs ++
      (if (updateLockfile) Seq("--lockfile-only")
       else if (hasLockfile) Seq("--frozen-lockfile")
       else Seq.empty)
  }

  protected def requireBunLockfile(
      hasInstallInputs: Boolean,
      lockfile: Option[PathRef],
      required: Boolean
  ): Unit = {
    if (hasInstallInputs && required && lockfile.isEmpty) {
      throw new RuntimeException(
        s"Missing ${moduleDir / "bun.lock"}. Run this module's bunLock command and commit the generated lockfile."
      )
    }
  }

  /**
   * Bun config files to copy into generated workspaces.
   *
   * Bun works without a bunfig, but copying root configs makes the generated
   * task workspaces closer to the source workspace.
   *
   * Declared as Task.Input so Mill's sandbox checker allows reading from the
   * workspace root and re-evaluates when the files change.
   */
  def bunfigFiles: T[Seq[PathRef]] = Task.Input {
    Seq(BuildCtx.workspaceRoot / "bunfig.toml", BuildCtx.workspaceRoot / ".bunfig.toml")
      .filter(os.exists)
      .map(PathRef(_))
  }

  /**
   * Cross-compilation targets for `bun build --compile`.
   * Values: "bun-linux-x64", "bun-darwin-arm64", "bun-windows-x64", etc.
   * Empty means native platform only.
   */
  def bunCompileTargets: T[Seq[String]] = Task { Seq.empty }

  /**
   * Extra files or directories to copy into the compile workspace before
   * `bun build --compile`. Useful for SQLite databases referenced via
   * `import db from "./my.db" with { type: "sqlite", embed: "true" }`.
   */
  def bunCompileResources: T[Seq[PathRef]] = Task { Seq.empty }

  private def downloadedBunExecutable: T[PathRef] = Task {
    val version = bunVersion()
    val dist = BunToolchainModule.distribution(
      System.getProperty("os.name", "unknown"),
      System.getProperty("os.arch", "unknown")
    ).fold(
      message => Task.fail(s"$message. Set bunExecutableOverride or bunUseSystem."),
      identity
    )
    val customUrl = bunArchiveUrl()
    val customChecksum = bunArchiveSha256()
    if (customUrl.isDefined != customChecksum.isDefined) {
      Task.fail("bunArchiveUrl and bunArchiveSha256 must be configured together.")
    }
    val url = customUrl.getOrElse(
      s"https://github.com/oven-sh/bun/releases/download/bun-v$version/${dist.assetName}"
    )
    val checksum = customChecksum
      .orElse(BunToolchainModule.bundledChecksum(version, dist.assetName))
      .getOrElse(Task.fail(
        s"No bundled checksum for Bun $version (${dist.assetName}). Set bunArchiveUrl and bunArchiveSha256."
      ))
      .toLowerCase

    val archive = Task.dest / dist.assetName
    val executable = Task.dest / dist.executableName
    BunToolchainModule.download(url, archive)
    val actual = BunToolchainModule.sha256(archive)
    if (actual != checksum) {
      Task.fail(s"Bun archive checksum mismatch for $url: expected $checksum, received $actual")
    }
    BunToolchainModule.extractExecutable(archive, dist.executableName, executable)
    PathRef(executable)
  }

  private def verifyBunVersion(executable: String, expected: String, verify: Boolean): Unit = {
    if (verify) {
      val result = os.proc(executable, "--version").call(
        check = false,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
      val actual = result.out.text().trim
      if (result.exitCode != 0 || actual != expected) {
        throw new RuntimeException(
          s"Bun version mismatch for '$executable': expected $expected, received " +
            (if (actual.nonEmpty) actual else s"exit code ${result.exitCode}")
        )
      }
    }
  }

  /** Resolve Bun from an explicit override, PATH opt-in, or the managed distribution. */
  def bunExecutable: T[String] = Task {
    val explicit = bunExecutableOverride().orElse(managedBunExecutable()).map(_.path.toString)
    val resolved = explicit.orElse {
      if (bunUseSystem()) {
        BunToolchainModule.findOnPath(bunExecutableName()).map(_.toString).orElse(
          Some(Task.fail(
            s"Unable to find Bun executable '${bunExecutableName()}' on PATH. " +
              "Disable bunUseSystem to use managed Bun, or set bunExecutableOverride."
          ))
        )
      } else Some(downloadedBunExecutable().path.toString)
    }.get
    verifyBunVersion(resolved, bunVersion(), bunVerifyVersion())
    resolved
  }

  /** Print and validate the resolved Bun toolchain. */
  def bunDoctor(): Command[Unit] = Task.Command {
    val executable = bunExecutable()
    val revision = os.proc(executable, "--revision").call(
      check = false,
      stdout = os.Pipe,
      stderr = os.Pipe
    ).out.text().trim
    val mode =
      if (bunExecutableOverride().orElse(managedBunExecutable()).nonEmpty) "override"
      else if (bunUseSystem()) "system"
      else "managed"
    println(s"Bun mode: $mode")
    println(s"Bun executable: $executable")
    println(s"Bun version: ${bunVersion()}")
    if (revision.nonEmpty) println(s"Bun revision: $revision")
    println(s"Bun linker: ${bunLinker()}")
  }

  /** Run a Bun command. All task values must be resolved before calling this. */
  protected def runBun(
      bunExe: String,
      args: Seq[String],
      cwd: os.Path,
      env: Map[String, String]
  ): os.CommandResult = {
    os.call(
      Seq(bunExe) ++ args,
      cwd = cwd,
      env = env,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }
}
