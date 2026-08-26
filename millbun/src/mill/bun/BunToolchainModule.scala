package mill.bun

import mill.*
import mill.api.BuildCtx
import java.io.{BufferedInputStream, FileInputStream, FileOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object BunToolchainModule {

  /**
   * The Bun release this plugin is tested against and downloads by default.
   *
   * Referenced by `bunVersion`, by `bunTypesVersion` (`@types/bun` is published in lockstep), and
   * by the tests, so a bump is a one-line change here rather than a hunt for literals.
   */
  val DefaultBunVersion = "1.4.0"

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

  /**
   * SHA-256 for every Bun release asset this plugin can download, keyed by version then asset.
   *
   * Sourced from the `SHASUMS256.txt` published with each Bun release. To add a version, append a
   * complete entry — [[bundledChecksum]] deliberately has no fallback, so a partial table fails
   * loudly on the missing platform rather than silently skipping verification.
   */
  private val BundledChecksums: Map[String, Map[String, String]] = Map(
    "1.3.14" -> Map(
      "bun-darwin-aarch64.zip" -> "d8b96221828ad6f97ac7ac0ab7e95872341af763001e8803e8267652c2652620",
      "bun-darwin-x64.zip" -> "4183df3374623e5bab315c547cfa0974533cd457d86b73b639f7a87974cd6633",
      "bun-darwin-x64-baseline.zip" -> "3e35ad6f53971a9834bf9e6786e2adf72b5f1921cc9a9c5fde073d2972944076",
      "bun-linux-aarch64.zip" -> "a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b",
      "bun-linux-aarch64-musl.zip" -> "b98e0ad3625c5c00d1d5b5ff55605c7adddbfae151861e68ade57b2d3b8703bb",
      "bun-linux-x64.zip" -> "951ee2aee855f08595aeec6225226a298d3fea83a3dcd6465c09cbccdf7e848f",
      "bun-linux-x64-baseline.zip" -> "a063908ae08b7852ca10939bbdc6ceed3ddabce8fb9402dce83d65d73b36e6c7",
      "bun-linux-x64-musl.zip" -> "14bd9aedeebf1dba67e8def9531c89bc989ecfdf1de42e5bfcaf1b8cd9294719",
      "bun-linux-x64-musl-baseline.zip" -> "56a7d6806cf155536c0178f0ea5fbd098e684fa509ebdb4fc0a7e19fb65382dc",
      "bun-windows-aarch64.zip" -> "89841f5a57f2348b67ec0839b718f4bf4ea7d07c371c9ba4b77b6c790f918953",
      "bun-windows-x64.zip" -> "0a0620930b6675d7ba440e81f4e0e00d3cfbe096c4b140d3fff02205e9e18922",
      "bun-windows-x64-baseline.zip" -> "538f9c846355d9e847b2671bc00c47da4229a0befb24df3282b739770f3b475f"
    ),
    "1.4.0" -> Map(
      "bun-darwin-aarch64.zip" -> "c669e97f6164e1c96e0701748db98dfa77492908cbd8394c7557134a735de381",
      "bun-darwin-x64.zip" -> "1d0211b8f1dc991182344687ad15e72ee86f154845a5f7fa477994cd341dd9b0",
      "bun-darwin-x64-baseline.zip" -> "da9b9f1b4ba766c6f299711f38dfaa98623e1ed9c40896aa53db803c52ec1fa0",
      "bun-linux-aarch64.zip" -> "4b1a332ee861983eb93bcfe6f770fff94e3e31b2c388bdaea3c8ed35e58eed0e",
      "bun-linux-aarch64-musl.zip" -> "576300ce33ff16ffcd455bf178c2f095f9df845c6cc3d0284ba1c96ca0e80473",
      "bun-linux-x64.zip" -> "2d03fb5fb83ac8b567aca0a281b2ce1a1a19d488f56c2968d88c3f25e92fe452",
      "bun-linux-x64-baseline.zip" -> "184fb4595f0d401a217cf7c78c1bc430ba83314dab7a8b94805babbf7fa7097f",
      "bun-linux-x64-musl.zip" -> "83b5f12fd258dd8d4fdcaea65ede954366aa717dab399e20093ecab280d54e7a",
      "bun-linux-x64-musl-baseline.zip" -> "618c4bc1f94b02337ee210003c0b7c066f11548a8cdc5109df10db043dc47ca2",
      "bun-windows-aarch64.zip" -> "f473bfe2df73ee770548c93dd5d380aea7120c218ec2aa1afdd0bbba7bf18c47",
      "bun-windows-x64.zip" -> "e6f093d39da486b20262ca8cdd5ed6a9e8bc9c2f275b78e6d3a0c5b28cc95901",
      "bun-windows-x64-baseline.zip" -> "b929c54a9badb104a16dedd23aab6152c86793ae653d4e6b13983ffd0c882a66"
    )
  )

  /** Versions with a complete bundled checksum table, for diagnostics. */
  private[bun] def bundledVersions: Seq[String] = BundledChecksums.keys.toSeq.sorted

  /**
   * Compose a Bun release asset name from the platform axes.
   *
   * Bun names assets `bun-{os}-{arch}[-musl][-baseline].zip`. The two modifiers are constrained:
   * `-musl` exists only for Linux, and `-baseline` (for x64 CPUs without AVX2) only for x64. This
   * returns `Left` for combinations Bun does not publish rather than constructing a 404 URL.
   */
  private[bun] def distribution(
      osName: String,
      architecture: String,
      musl: Boolean = false,
      baseline: Boolean = false
  ): Either[String, Distribution] = {
    val osPart = osName.toLowerCase match {
      case name if name.contains("mac") || name.contains("darwin") => Right("darwin")
      case name if name.contains("linux")                          => Right("linux")
      case name if name.contains("windows")                        => Right("windows")
      case other => Left(s"Unsupported operating system '$other'")
    }
    val archPart = architecture.toLowerCase match {
      case "aarch64" | "arm64"        => Right("aarch64")
      case "amd64" | "x86_64" | "x64" => Right("x64")
      case other                      => Left(s"Unsupported architecture '$other'")
    }

    for {
      os <- osPart
      arch <- archPart
      _ <-
        if (musl && os != "linux") Left(s"Bun publishes no musl build for '$os'")
        else Right(())
      _ <-
        if (baseline && arch != "x64") Left(s"Bun publishes no baseline build for '$arch'")
        else Right(())
    } yield Distribution(
      assetName = s"bun-$os-$arch${if (musl) "-musl" else ""}${if (baseline) "-baseline" else ""}.zip",
      executableName = if (os == "windows") "bun.exe" else "bun"
    )
  }

  /**
   * Detect a musl-based Linux (Alpine and friends), where the glibc build cannot run.
   *
   * Probing for the musl dynamic loader is more reliable than parsing `ldd --version`, which musl
   * writes to stderr and glibc to stdout.
   */
  private[bun] def detectMusl(root: os.Path = os.root): Boolean =
    try
      val libDir = root / "lib"
      (os.exists(libDir) && os.list(libDir).exists(p => p.last.startsWith("ld-musl-"))) ||
      os.exists(root / "etc" / "alpine-release")
    catch case _: Exception => false

  private[bun] def bundledChecksum(version: String, assetName: String): Option[String] =
    BundledChecksums.get(version).flatMap(_.get(assetName))

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

  /**
   * Move a verified executable into the shared cache, tolerating a concurrent writer.
   *
   * Mill evaluates modules in parallel, so two tasks can extract the same archive at once. Both
   * move into the same destination; whoever loses the race simply uses the winner's file, which is
   * byte-identical because the path is keyed by checksum.
   */
  private[bun] def publishToCache(staged: os.Path, cached: os.Path): os.Path = {
    os.makeDir.all(cached / os.up)
    try
      java.nio.file.Files.move(
        staged.toNIO,
        cached.toNIO,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE
      )
      cached
    catch
      case _: java.nio.file.FileAlreadyExistsException => cached
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        // Cache on a different filesystem than the task dest: fall back to a plain copy.
        os.copy.over(staged, cached, createFolders = true)
        cached
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
  def bunVersion: T[String] = Task { BunToolchainModule.DefaultBunVersion }

  /**
   * Download the `-baseline` build, for x64 CPUs without AVX2.
   *
   * Not auto-detected: the JVM cannot see CPU feature flags, and guessing wrong surfaces as an
   * illegal-instruction crash rather than a diagnosable error. Set this when Bun aborts on startup
   * with `SIGILL` on older x64 hardware.
   */
  def bunUseBaseline: T[Boolean] = Task { false }

  /**
   * Download the musl build, required on Alpine and other musl-based Linux distributions.
   *
   * Auto-detected from the presence of the musl dynamic loader; override to force either build.
   */
  def bunUseMusl: T[Boolean] = Task.Input { BunToolchainModule.detectMusl() }

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

  /**
   * SHA-256 of the Bun archive to download.
   *
   * Set this alone to run a [[bunVersion]] with no bundled checksum — the URL is derived from the
   * version and the detected platform. Set it together with [[bunArchiveUrl]] to use a mirror.
   */
  def bunArchiveSha256: T[Option[String]] = Task { None }

  /**
   * Directory holding managed Bun downloads, shared by every module in the build.
   *
   * `downloadedBunExecutable` is a task on this trait, so without a shared cache a build with N
   * Bun modules downloads the ~35 MB archive N times into N separate `Task.dest` directories.
   * Entries are keyed by the archive's verified SHA-256, so a partial or tampered download can
   * never be reused and two modules on the same pin share one file.
   *
   * Lives outside the workspace, so Mill's filesystem checker does not restrict it.
   */
  def bunDownloadCacheDir: T[os.Path] = Task.Input {
    Task.env.get("MILL_BUN_CACHE_DIR").filter(_.nonEmpty).map(os.Path(_))
      .getOrElse(os.home / ".cache" / "mill-bun")
  }

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
      System.getProperty("os.arch", "unknown"),
      musl = bunUseMusl(),
      baseline = bunUseBaseline()
    ).fold(
      message => Task.fail(s"$message. Set bunExecutableOverride or bunUseSystem."),
      identity
    )
    val customUrl = bunArchiveUrl()
    val customChecksum = bunArchiveSha256()
    if (customUrl.isDefined && customChecksum.isEmpty) {
      Task.fail("bunArchiveUrl requires bunArchiveSha256 so the mirrored archive stays verified.")
    }
    val url = customUrl.getOrElse(
      s"https://github.com/oven-sh/bun/releases/download/bun-v$version/${dist.assetName}"
    )
    val checksum = customChecksum
      .orElse(BunToolchainModule.bundledChecksum(version, dist.assetName))
      .getOrElse(Task.fail(
        s"No bundled checksum for Bun $version (${dist.assetName}). " +
          s"Bundled versions are ${BunToolchainModule.bundledVersions.mkString(", ")}. " +
          "Set bunArchiveSha256 to the archive's SHA-256 to use this version anyway."
      ))
      .toLowerCase

    if (!checksum.matches("[0-9a-f]{64}")) {
      Task.fail(s"bunArchiveSha256 must be 64 hexadecimal characters, received '$checksum'.")
    }

    // Keyed by the verified checksum, so a cache hit is proof of the right bytes.
    val cached = bunDownloadCacheDir() / checksum / dist.executableName
    if (os.exists(cached)) PathRef(cached)
    else {
      val archive = Task.dest / dist.assetName
      val staged = Task.dest / dist.executableName
      BunToolchainModule.download(url, archive)
      val actual = BunToolchainModule.sha256(archive)
      if (actual != checksum) {
        Task.fail(s"Bun archive checksum mismatch for $url: expected $checksum, received $actual")
      }
      BunToolchainModule.extractExecutable(archive, dist.executableName, staged)
      PathRef(BunToolchainModule.publishToCache(staged, cached))
    }
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
    if (mode == "managed") {
      val dist = BunToolchainModule.distribution(
        System.getProperty("os.name", "unknown"),
        System.getProperty("os.arch", "unknown"),
        musl = bunUseMusl(),
        baseline = bunUseBaseline()
      )
      println(s"Bun asset: ${dist.fold(identity, _.assetName)}")
      println(s"Bun libc: ${if (bunUseMusl()) "musl" else "glibc"}")
      println(s"Bun baseline: ${bunUseBaseline()}")
    }
    println(s"Bun lockfile: ${bunLockfile().fold("none")(_.path.toString)}")
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
