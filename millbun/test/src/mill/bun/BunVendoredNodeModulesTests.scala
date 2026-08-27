package mill.bun

import java.io.FileOutputStream
import java.util.jar.{JarEntry, JarOutputStream}

import utest.*

object BunVendoredNodeModulesTests extends TestSuite {
  def tests: Tests = Tests {

    test("copyResolvedTree copies vendored files and skips .bin") {
      val source = os.temp.dir()
      val dest = os.temp.dir()

      os.makeDir.all(source / "@scope" / "pkg")
      os.write(source / "@scope" / "pkg" / "package.json", """{"name":"@scope/pkg","version":"1.0.0"}""")
      os.makeDir.all(source / ".bin")
      os.write(source / ".bin" / "tool", "#!/usr/bin/env node")

      BunVendoredNodeModules.copyResolvedTree(source, dest)

      assert(os.exists(dest / "@scope" / "pkg" / "package.json"))
      assert(!os.exists(dest / ".bin" / "tool"))
    }

    test("mergeFromClasspathEntry merges vendored node_modules from directories") {
      val classpathDir = os.temp.dir()
      val bundleRoot = classpathDir / os.RelPath(BunVendoredNodeModules.BundleRoot)
      val dest = os.temp.dir()

      os.makeDir.all(bundleRoot / "react")
      os.write(bundleRoot / "react" / "package.json", """{"name":"react","version":"19.1.1"}""")

      val merged = BunVendoredNodeModules.mergeFromClasspathEntry(classpathDir, dest / "node_modules")

      assert(merged)
      assert(os.read(dest / "node_modules" / "react" / "package.json").contains("19.1.1"))
    }

    test("mergeFromClasspathEntry merges vendored node_modules from jars") {
      val jarPath = tempJar(
        Map(
          s"${BunVendoredNodeModules.BundleRoot}/react/package.json" ->
            """{"name":"react","version":"19.1.1"}"""
        )
      )
      val dest = os.temp.dir()

      val merged = BunVendoredNodeModules.mergeFromClasspathEntry(jarPath, dest / "node_modules")

      assert(merged)
      assert(os.read(dest / "node_modules" / "react" / "package.json").contains("19.1.1"))
    }

    test("identical vendored files can be merged repeatedly") {
      val first = os.temp.dir()
      val second = os.temp.dir()
      val dest = os.temp.dir()

      writeVendoredPackage(first, "react", "19.1.1")
      writeVendoredPackage(second, "react", "19.1.1")

      assert(BunVendoredNodeModules.mergeFromClasspathEntry(first, dest / "node_modules"))
      assert(BunVendoredNodeModules.mergeFromClasspathEntry(second, dest / "node_modules"))
      assert(os.read(dest / "node_modules" / "react" / "package.json").contains("19.1.1"))
    }

    test("hasVendoredNodeModules detects vendored tree in directories") {
      val withVendor = os.temp.dir()
      writeVendoredPackage(withVendor, "react", "19.1.1")
      assert(BunVendoredNodeModules.hasVendoredNodeModules(withVendor))

      val withoutVendor = os.temp.dir()
      assert(!BunVendoredNodeModules.hasVendoredNodeModules(withoutVendor))
    }

    test("hasVendoredNodeModules detects vendored tree in jars") {
      val withVendor = tempJar(
        Map(s"${BunVendoredNodeModules.BundleRoot}/react/package.json" -> """{"name":"react"}""")
      )
      assert(BunVendoredNodeModules.hasVendoredNodeModules(withVendor))

      val withoutVendor = tempJar(Map("META-INF/bun/bun-dependencies.json" -> "{}"))
      assert(!BunVendoredNodeModules.hasVendoredNodeModules(withoutVendor))
    }

    test("conflicting vendored files fail fast") {
      val first = os.temp.dir()
      val second = os.temp.dir()
      val dest = os.temp.dir()

      writeVendoredPackage(first, "react", "19.1.1")
      writeVendoredPackage(second, "react", "19.2.0")

      BunVendoredNodeModules.mergeFromClasspathEntry(first, dest / "node_modules")

      val err = intercept[RuntimeException] {
        BunVendoredNodeModules.mergeFromClasspathEntry(second, dest / "node_modules")
      }

      assert(err.getMessage.contains("Vendored Bun bundle conflict"))
    }

    test("jar entries that climb out of the bundle root are refused") {
      // Zip-slip: a hostile dependency jar carrying the vendored marker plus a `..` entry must
      // never become a file write outside the destination — this runs during bunInstall for any
      // module whose classpath contains the jar, without executing any dependency code.
      val jarPath = tempJar(
        Map(
          s"${BunVendoredNodeModules.BundleRoot}/react/package.json" -> """{"name":"react"}""",
          s"${BunVendoredNodeModules.BundleRoot}/../../../escaped.txt" -> "outside"
        )
      )
      val destRoot = os.temp.dir()

      val err = intercept[RuntimeException] {
        BunVendoredNodeModules.mergeFromClasspathEntry(jarPath, destRoot / "node_modules")
      }
      assert(err.getMessage.contains("escapes its root"))
      assert(!os.walk(destRoot).exists(_.last == "escaped.txt"))
      assert(!os.exists(destRoot / os.up / "escaped.txt"))
    }

    test("merging through a symlinked destination is refused") {
      // In workspace mode node_modules links into the shared install's Task.dest. Merging
      // through it would silently mutate a directory every workspace member depends on.
      val source = os.temp.dir()
      writeVendoredPackage(source, "react", "19.1.1")

      val shared = os.temp.dir() / "shared-node-modules"
      os.makeDir.all(shared)
      val member = os.temp.dir()
      os.symlink(member / "node_modules", shared)

      val err = intercept[RuntimeException] {
        BunVendoredNodeModules.mergeFromClasspathEntry(source, member / "node_modules")
      }
      assert(err.getMessage.contains("belongs to another task"))
      // The critical assertion: nothing leaked into the shared directory.
      assert(os.list(shared).isEmpty)
    }
  }

  private def writeVendoredPackage(root: os.Path, name: String, version: String): Unit = {
    val bundleRoot = root / os.RelPath(BunVendoredNodeModules.BundleRoot)
    os.makeDir.all(bundleRoot / name)
    os.write(bundleRoot / name / "package.json", s"""{"name":"$name","version":"$version"}""")
  }

  private def tempJar(entries: Map[String, String]): os.Path = {
    val jarPath = os.temp.dir() / "bundle.jar"
    val jarOut = new JarOutputStream(new FileOutputStream(jarPath.toIO))
    val written = scala.collection.mutable.Set.empty[String]
    try
      entries.toSeq.sortBy(_._1).foreach { case (path, content) =>
        val parentDirs = parentDirectories(path)
        parentDirs.filter(written.add).foreach { dir =>
          jarOut.putNextEntry(new JarEntry(dir))
          jarOut.closeEntry()
        }
        jarOut.putNextEntry(new JarEntry(path))
        jarOut.write(content.getBytes("UTF-8"))
        jarOut.closeEntry()
      }
    finally jarOut.close()

    jarPath
  }

  private def parentDirectories(path: String): Seq[String] =
    path.split('/').dropRight(1).scanLeft("") {
      case ("", segment) => s"$segment/"
      case (acc, segment) => s"$acc$segment/"
    }.drop(1)
}
