package mill.bun

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitOption, FileVisitResult, Files, Path as NioPath, SimpleFileVisitor, StandardCopyOption}
import java.util.EnumSet
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*

/** Helpers for publishing and consuming vendored Bun runtime dependencies. */
object BunVendoredNodeModules:
  val BundleRoot = "META-INF/bun/node_modules"

  /** Copy a node_modules tree while dereferencing symlinks into plain files/directories. */
  def copyResolvedTree(source: os.Path, dest: os.Path): Unit =
    if !os.exists(source) then return

    val sourceNio = source.toNIO
    val destNio = dest.toNIO

    Files.walkFileTree(
      sourceNio,
      EnumSet.of(FileVisitOption.FOLLOW_LINKS),
      Int.MaxValue,
      new SimpleFileVisitor[NioPath]:
        override def preVisitDirectory(dir: NioPath, attrs: BasicFileAttributes): FileVisitResult =
          val rel = sourceNio.relativize(dir)
          if shouldSkip(rel) then FileVisitResult.SKIP_SUBTREE
          else
            Files.createDirectories(resolveDest(destNio, rel))
            FileVisitResult.CONTINUE

        override def visitFile(file: NioPath, attrs: BasicFileAttributes): FileVisitResult =
          val rel = sourceNio.relativize(file)
          if !shouldSkip(rel) then
            val target = resolveDest(destNio, rel)
            Option(target.getParent).foreach(Files.createDirectories(_))
            Files.copy(
              file,
              target,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES
            )
          FileVisitResult.CONTINUE
    )

  /** Merge vendored node_modules from a classpath entry into a destination. */
  def mergeFromClasspathEntry(entry: os.Path, destNodeModules: os.Path): Boolean =
    if os.isDir(entry) then mergeFromDir(entry, destNodeModules, entry.toString)
    else if os.exists(entry) && entry.ext == "jar" then mergeFromJar(entry, destNodeModules)
    else false

  private def mergeFromDir(root: os.Path, destNodeModules: os.Path, sourceLabel: String): Boolean =
    val bundleRoot = root / os.RelPath(BundleRoot)
    if !os.exists(bundleRoot) then return false

    os.walk(bundleRoot).foreach { path =>
      if path != bundleRoot then
        val rel = path.relativeTo(bundleRoot)
        if !shouldSkip(rel.toNIO) then
          val dest = destNodeModules / rel
          if os.isDir(path) then ensureDir(dest.toNIO, sourceLabel)
          else mergeFile(path, dest, s"$sourceLabel!/$rel")
    }

    true

  private def mergeFromJar(jarPath: os.Path, destNodeModules: os.Path): Boolean =
    val prefix = BundleRoot + "/"
    val jar = new JarFile(jarPath.toIO)

    try
      val entries = jar.entries().asScala.toVector.filter(_.getName.startsWith(prefix))
      if entries.isEmpty then return false

      entries.sortBy(_.getName).foreach { entry =>
        val relString = entry.getName.stripPrefix(prefix)
        if relString.nonEmpty then
          val rel = os.RelPath(relString)
          if !shouldSkip(rel.toNIO) then
            val dest = destNodeModules / rel
            val sourceLabel = s"$jarPath!/${entry.getName}"

            if entry.isDirectory then ensureDir(dest.toNIO, sourceLabel)
            else
              val input = jar.getInputStream(entry)
              try mergeBytes(input.readAllBytes(), dest, sourceLabel)
              finally input.close()
      }

      true
    finally jar.close()

  private def ensureDir(dest: NioPath, sourceLabel: String): Unit =
    if Files.exists(dest) && !Files.isDirectory(dest) then
      throw new RuntimeException(s"Vendored Bun bundle conflict at $dest from $sourceLabel: expected a directory.")
    Files.createDirectories(dest)

  private def mergeFile(source: os.Path, dest: os.Path, sourceLabel: String): Unit =
    if os.exists(dest) then
      if os.isDir(dest) then
        throw new RuntimeException(s"Vendored Bun bundle conflict at $dest from $sourceLabel: expected a file.")
      if Files.mismatch(source.toNIO, dest.toNIO) != -1L then
        throw new RuntimeException(s"Vendored Bun bundle conflict at $dest while merging $sourceLabel.")
    else
      Option(dest.toNIO.getParent).foreach(Files.createDirectories(_))
      Files.copy(
        source.toNIO,
        dest.toNIO,
        StandardCopyOption.COPY_ATTRIBUTES
      )

  private def mergeBytes(bytes: Array[Byte], dest: os.Path, sourceLabel: String): Unit =
    if os.exists(dest) then
      if os.isDir(dest) then
        throw new RuntimeException(s"Vendored Bun bundle conflict at $dest from $sourceLabel: expected a file.")
      val existing = os.read.bytes(dest)
      if existing.length != bytes.length || !java.util.Arrays.equals(existing, bytes) then
        throw new RuntimeException(s"Vendored Bun bundle conflict at $dest while merging $sourceLabel.")
    else
      os.write.over(dest, bytes, createFolders = true)

  private def resolveDest(destRoot: NioPath, rel: NioPath): NioPath =
    if rel.getNameCount == 0 then destRoot else destRoot.resolve(rel)

  /** Check whether a classpath entry contains vendored node_modules. */
  def hasVendoredNodeModules(entry: os.Path): Boolean =
    if os.isDir(entry) then os.exists(entry / os.RelPath(BundleRoot))
    else if os.exists(entry) && entry.ext == "jar" then
      val jar = new JarFile(entry.toIO)
      try jar.entries().asScala.exists(_.getName.startsWith(BundleRoot + "/"))
      finally jar.close()
    else false

  private def shouldSkip(rel: NioPath): Boolean =
    rel.iterator().asScala.exists(_.toString == ".bin")
