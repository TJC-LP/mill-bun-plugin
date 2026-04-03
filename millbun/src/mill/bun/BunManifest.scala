package mill.bun

import java.util.jar.JarFile

/** Bun dependency manifest embedded in published JARs.
  *
  * When a Scala.js library declares JS package dependencies via `npmDeps`,
  * this manifest is generated and included in the JAR at publish time.
  * Consumer builds scan classpath JARs for these manifests and merge them
  * into their `package.json`, making JS deps transitive — just like
  * Coursier resolves JVM deps from POMs.
  *
  * Layout inside JAR:
  * {{{
  * META-INF/bun/bun-dependencies.json   — dependency manifest
  * META-INF/bun/bun.lock                — lockfile (optional, for deterministic resolution)
  * }}}
  */
final case class BunManifest(
    dependencies: Map[String, String],
    devDependencies: Map[String, String],
    optionalDependencies: Map[String, String]
)

object BunManifest:
  val ManifestPath = "META-INF/bun/bun-dependencies.json"
  val LockfilePath = "META-INF/bun/bun.lock"

  val empty: BunManifest = BunManifest(Map.empty, Map.empty, Map.empty)

  /** Serialize manifest to JSON. */
  def toJson(manifest: BunManifest): ujson.Obj =
    val obj = ujson.Obj(
      "dependencies" -> ujson.Obj.from(manifest.dependencies.map((k, v) => k -> ujson.Str(v))),
      "devDependencies" -> ujson.Obj.from(manifest.devDependencies.map((k, v) => k -> ujson.Str(v)))
    )
    if manifest.optionalDependencies.nonEmpty then
      obj("optionalDependencies") = ujson.Obj.from(
        manifest.optionalDependencies.map((k, v) => k -> ujson.Str(v))
      )
    obj

  /** Deserialize manifest from JSON. */
  def fromJson(json: ujson.Value): BunManifest =
    val obj = json.obj
    def readDeps(key: String): Map[String, String] =
      obj.get(key).map(_.obj.map((k, v) => k -> v.str).toMap).getOrElse(Map.empty)
    BunManifest(
      dependencies = readDeps("dependencies"),
      devDependencies = readDeps("devDependencies"),
      optionalDependencies = readDeps("optionalDependencies")
    )

  /** Read a manifest from inside a JAR file. Returns None if no manifest is present. */
  def readFromJar(jarPath: os.Path): Option[BunManifest] =
    if !os.exists(jarPath) then return None
    val jar = new JarFile(jarPath.toIO)
    try
      val entry = jar.getEntry(ManifestPath)
      if entry == null then None
      else
        val is = jar.getInputStream(entry)
        try Some(fromJson(ujson.read(is)))
        finally is.close()
    catch case _: Exception => None
    finally jar.close()

  /** Read a manifest from an unpacked directory (e.g., classes output). */
  def readFromDir(dirPath: os.Path): Option[BunManifest] =
    val manifestFile = dirPath / os.RelPath(ManifestPath)
    if os.exists(manifestFile) then
      try Some(fromJson(ujson.read(os.read(manifestFile))))
      catch case _: Exception => None
    else None

  /** Extract the embedded lockfile from a JAR to a destination directory.
    * Returns the path to the extracted lockfile, or None if no lockfile is embedded.
    */
  def extractLockfile(jarPath: os.Path, destDir: os.Path): Option[os.Path] =
    if !os.exists(jarPath) then return None
    val jar = new JarFile(jarPath.toIO)
    try
      val entry = jar.getEntry(LockfilePath)
      if entry == null then None
      else
        // Use JAR filename to namespace lockfiles from different libraries
        val namespace = jarPath.last.stripSuffix(".jar")
        val dest = destDir / namespace / "bun.lock"
        os.makeDir.all(dest / os.up)
        val is = jar.getInputStream(entry)
        try
          os.write.over(dest, is)
          Some(dest)
        finally is.close()
    catch case _: Exception => None
    finally jar.close()

  /** Merge multiple manifests into one. Later entries override earlier ones for the same package. */
  def merge(manifests: Seq[BunManifest]): BunManifest =
    manifests.foldLeft(empty) { (acc, m) =>
      BunManifest(
        dependencies = acc.dependencies ++ m.dependencies,
        devDependencies = acc.devDependencies ++ m.devDependencies,
        optionalDependencies = acc.optionalDependencies ++ m.optionalDependencies
      )
    }
