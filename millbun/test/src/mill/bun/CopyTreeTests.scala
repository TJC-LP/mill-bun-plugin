package mill.bun

import utest.*

object CopyTreeTests extends TestSuite:

  /** `linked/` as the Scala.js linker leaves it: output files plus a node_modules symlink. */
  private def stagedLink(): (os.Path, os.Path) =
    val root = os.temp.dir()
    val installed = root / "installed"
    os.write(installed / "node_modules" / "lodash" / "index.js", "lodash", createFolders = true)
    val linked = root / "linked"
    os.write(linked / "main.js", "app", createFolders = true)
    os.symlink(linked / "node_modules", installed / "node_modules")
    (linked, installed)

  def tests: Tests = Tests:
    test("symlinked directories are recreated, not flattened or deep-copied"):
      val (linked, installed) = stagedLink()
      val dest = os.temp.dir()
      BunToolchainModule.copyTree(linked, dest)

      // The whole point: a link stays a link pointing at the install, so `bun build` in the
      // staged directory resolves npm imports without copying node_modules.
      assert(os.isLink(dest / "node_modules"))
      assert(os.readLink.absolute(dest / "node_modules") == installed / "node_modules")
      assert(os.exists(dest / "node_modules" / "lodash" / "index.js"))
      assert(os.read(dest / "main.js") == "app")

    test("broken symlinks do not abort the copy"):
      // Routine inside node_modules: .bin shims for skipped optional dependencies.
      val source = os.temp.dir()
      os.write(source / "main.js", "app")
      os.symlink(source / "dangling", source / "does-not-exist")

      val dest = os.temp.dir()
      BunToolchainModule.copyTree(source, dest)
      assert(os.isLink(dest / "dangling"))
      assert(os.read(dest / "main.js") == "app")

    test("excluded top-level entries are skipped entirely"):
      val (linked, _) = stagedLink()
      val dest = os.temp.dir()
      BunToolchainModule.copyTree(linked, dest, exclude = Set("node_modules"))
      assert(!os.exists(dest / "node_modules", followLinks = false))
      assert(os.exists(dest / "main.js"))

    test("symlinked files are recreated as links"):
      val source = os.temp.dir()
      val target = os.temp.dir() / "real.js"
      os.write(target, "real")
      os.symlink(source / "alias.js", target)

      val dest = os.temp.dir()
      BunToolchainModule.copyTree(source, dest)
      assert(os.isLink(dest / "alias.js"))
      assert(os.read(dest / "alias.js") == "real")

    test("nested directories and empty directories are preserved"):
      val source = os.temp.dir()
      os.makeDir.all(source / "empty")
      os.write(source / "a" / "b" / "c.txt", "deep", createFolders = true)

      val dest = os.temp.dir()
      BunToolchainModule.copyTree(source, dest)
      assert(os.isDir(dest / "empty"))
      assert(os.read(dest / "a" / "b" / "c.txt") == "deep")

    test("copying a missing source is a no-op"):
      val dest = os.temp.dir()
      BunToolchainModule.copyTree(os.temp.dir() / "absent", dest)
      assert(os.list(dest).isEmpty)
