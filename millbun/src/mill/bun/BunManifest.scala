package mill.bun

import java.util.jar.JarFile

/** Bun dependency manifest embedded in published JARs.
  *
  * When a Scala.js library declares direct runtime JS package dependencies via
  * `npmDeps` / `bunDeps`, this manifest is generated and included in the JAR
  * and may optionally be accompanied by vendored runtime `node_modules`.
  *
  * Layout inside JAR:
  * {{{
  * META-INF/bun/bun-dependencies.json   — dependency manifest
  * }}}
  */
final case class BunManifest(
    dependencies: Map[String, String],
    devDependencies: Map[String, String],
    optionalDependencies: Map[String, String],
    peerDependencies: Map[String, String] = Map.empty,
    schemaVersion: Int = 2
)

object BunManifest:
  val ManifestPath = "META-INF/bun/bun-dependencies.json"

  val empty: BunManifest = BunManifest(Map.empty, Map.empty, Map.empty)

  /** Serialize manifest to JSON. */
  def toJson(manifest: BunManifest): ujson.Obj =
    if manifest.schemaVersion != 1 && manifest.schemaVersion != 2 then
      throw new IllegalArgumentException(s"Unsupported Bun manifest schemaVersion ${manifest.schemaVersion}")
    if manifest.schemaVersion == 2 && manifest.devDependencies.nonEmpty then
      throw new IllegalArgumentException("Bun manifest schema v2 does not allow devDependencies")
    val obj = ujson.Obj(
      "schemaVersion" -> manifest.schemaVersion,
      "dependencies" -> dependencyJson(manifest.dependencies)
    )
    if manifest.schemaVersion == 1 && manifest.devDependencies.nonEmpty then
      obj("devDependencies") = dependencyJson(manifest.devDependencies)
    if manifest.optionalDependencies.nonEmpty then
      obj("optionalDependencies") = dependencyJson(manifest.optionalDependencies)
    if manifest.peerDependencies.nonEmpty then
      obj("peerDependencies") = dependencyJson(manifest.peerDependencies)
    obj

  private def dependencyJson(dependencies: Map[String, String]): ujson.Obj =
    ujson.Obj.from(dependencies.toSeq.sortBy(_._1).map((name, version) => name -> ujson.Str(version)))

  /** Deserialize manifest from JSON. */
  def fromJson(json: ujson.Value): BunManifest =
    val obj = json.obj
    val schemaVersion = obj.get("schemaVersion").map(_.num.toInt).getOrElse(1)
    if schemaVersion != 1 && schemaVersion != 2 then
      throw new IllegalArgumentException(s"Unsupported Bun manifest schemaVersion $schemaVersion")
    if schemaVersion == 2 && obj.contains("devDependencies") then
      throw new IllegalArgumentException("Bun manifest schema v2 does not allow devDependencies")

    def readDeps(key: String): Map[String, String] =
      obj.get(key).map { value =>
        value.obj.map { case (name, specifier) =>
          name -> specifier.str
        }.toMap
      }.getOrElse(Map.empty)
    BunManifest(
      dependencies = readDeps("dependencies"),
      devDependencies = readDeps("devDependencies"),
      optionalDependencies = readDeps("optionalDependencies"),
      peerDependencies = readDeps("peerDependencies"),
      schemaVersion = schemaVersion
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
    finally jar.close()

  /** Read a manifest from an unpacked directory (e.g., classes output). */
  def readFromDir(dirPath: os.Path): Option[BunManifest] =
    val manifestFile = dirPath / os.RelPath(ManifestPath)
    if os.exists(manifestFile) then
      Some(fromJson(ujson.read(os.read(manifestFile))))
    else None

  /** Merge publishable fields, rejecting contradictions and discarding legacy v1 development metadata. */
  def merge(manifests: Seq[BunManifest]): BunManifest =
    def mergeField(field: String, values: Seq[Map[String, String]]): Map[String, String] =
      values.flatMap(_.toSeq).groupBy(_._1).map { case (name, entries) =>
        val specifiers = entries.map(_._2).distinct
        if specifiers.size > 1 then
          throw new IllegalArgumentException(
            s"Conflicting $field dependency '$name': ${specifiers.sorted.mkString(", ")}"
          )
        name -> specifiers.head
      }

    BunManifest(
      dependencies = mergeField("runtime", manifests.map(_.dependencies)),
      devDependencies = Map.empty,
      optionalDependencies = mergeField("optional", manifests.map(_.optionalDependencies)),
      peerDependencies = mergeField("peer", manifests.map(_.peerDependencies)),
      schemaVersion = 2
    )
