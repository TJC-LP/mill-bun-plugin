package mill.bun

import java.io.FileOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}
import utest.*

object BunToolchainTests extends TestSuite:
  def tests: Tests = Tests:
    test("maps supported platforms to official release assets"):
      assert(
        BunToolchainModule.distribution("Mac OS X", "aarch64") == Right(
          BunToolchainModule.Distribution("bun-darwin-aarch64.zip", "bun")
        )
      )
      assert(
        BunToolchainModule.distribution("Linux", "amd64") == Right(
          BunToolchainModule.Distribution("bun-linux-x64.zip", "bun")
        )
      )
      assert(
        BunToolchainModule.distribution("Windows 11", "x86_64") == Right(
          BunToolchainModule.Distribution("bun-windows-x64.zip", "bun.exe")
        )
      )

    test("rejects unsupported managed platforms"):
      assert(BunToolchainModule.distribution("Plan 9", "x64").isLeft)
      assert(BunToolchainModule.distribution("Linux", "riscv64").isLeft)

    test("composes musl and baseline asset names"):
      assert(
        BunToolchainModule.distribution("Linux", "x64", musl = true) == Right(
          BunToolchainModule.Distribution("bun-linux-x64-musl.zip", "bun")
        )
      )
      assert(
        BunToolchainModule.distribution("Linux", "x64", baseline = true) == Right(
          BunToolchainModule.Distribution("bun-linux-x64-baseline.zip", "bun")
        )
      )
      assert(
        BunToolchainModule.distribution("Linux", "x64", musl = true, baseline = true) == Right(
          BunToolchainModule.Distribution("bun-linux-x64-musl-baseline.zip", "bun")
        )
      )
      assert(
        BunToolchainModule.distribution("Windows 11", "x64", baseline = true) == Right(
          BunToolchainModule.Distribution("bun-windows-x64-baseline.zip", "bun.exe")
        )
      )

    test("rejects modifier combinations Bun does not publish"):
      // musl is Linux-only, baseline is x64-only.
      assert(BunToolchainModule.distribution("Mac OS X", "x64", musl = true).isLeft)
      assert(BunToolchainModule.distribution("Windows 11", "x64", musl = true).isLeft)
      assert(BunToolchainModule.distribution("Linux", "aarch64", baseline = true).isLeft)
      assert(BunToolchainModule.distribution("Mac OS X", "aarch64", baseline = true).isLeft)

    test("bundles a checksum for every asset of every pinned version"):
      // Every combination distribution() can produce must be downloadable, or the managed
      // toolchain fails on a platform we claim to support.
      val platforms = Seq(
        ("Mac OS X", "aarch64"),
        ("Mac OS X", "x64"),
        ("Linux", "aarch64"),
        ("Linux", "x64"),
        ("Windows 11", "aarch64"),
        ("Windows 11", "x64")
      )
      val assets =
        for
          (os, arch) <- platforms
          musl <- Seq(false, true)
          baseline <- Seq(false, true)
          dist <- BunToolchainModule.distribution(os, arch, musl, baseline).toOption
        yield dist.assetName

      assert(assets.distinct.size == 12)
      for
        version <- BunToolchainModule.bundledVersions
        asset <- assets.distinct
      do
        val checksum = BunToolchainModule.bundledChecksum(version, asset)
        assert(checksum.exists(_.matches("[0-9a-f]{64}")))

    test("pins the versions the docs and CI claim"):
      assert(BunToolchainModule.bundledVersions == Seq("1.3.14", "1.4.0"))
      // The default must be one we ship checksums for, or the managed path cannot work offline.
      assert(BunToolchainModule.bundledVersions.contains(BunToolchainModule.DefaultBunVersion))

    test("unknown versions have no bundled checksum"):
      assert(BunToolchainModule.bundledChecksum("1.3.15", "bun-linux-x64.zip").isEmpty)

    test("musl detection probes the loader and the alpine marker"):
      val glibc = os.temp.dir()
      os.makeDir.all(glibc / "lib")
      os.write(glibc / "lib" / "ld-linux-x86-64.so.2", "")
      assert(!BunToolchainModule.detectMusl(glibc))

      val musl = os.temp.dir()
      os.makeDir.all(musl / "lib")
      os.write(musl / "lib" / "ld-musl-x86_64.so.1", "")
      assert(BunToolchainModule.detectMusl(musl))

      val alpine = os.temp.dir()
      os.makeDir.all(alpine / "etc")
      os.write(alpine / "etc" / "alpine-release", "3.20.0")
      assert(BunToolchainModule.detectMusl(alpine))

      assert(!BunToolchainModule.detectMusl(os.temp.dir()))

    test("publishing to the download cache is idempotent under a race"):
      val root = os.temp.dir()
      val cached = root / "cache" / "abc123" / "bun"

      val first = root / "first" / "bun"
      os.write(first, "bun-binary", createFolders = true)
      assert(BunToolchainModule.publishToCache(first, cached) == cached)
      assert(os.read(cached) == "bun-binary")
      assert(!os.exists(first))

      // A second module extracting the same checksum concurrently must not fail, and must not
      // clobber the entry another task may already be executing.
      val second = root / "second" / "bun"
      os.write(second, "bun-binary", createFolders = true)
      assert(BunToolchainModule.publishToCache(second, cached) == cached)
      assert(os.read(cached) == "bun-binary")

    test("computes SHA-256"):
      val file = os.temp(contents = "hello")
      assert(
        BunToolchainModule.sha256(file) ==
          "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
      )

    test("extracts the Bun executable from a release-shaped zip"):
      val root = os.temp.dir()
      val archive = root / "bun.zip"
      val output = new ZipOutputStream(new FileOutputStream(archive.toIO))
      try
        output.putNextEntry(new ZipEntry("bun-linux-x64/bun"))
        output.write("fake-bun".getBytes("UTF-8"))
        output.closeEntry()
      finally output.close()

      val executable = root / "bin" / "bun"
      BunToolchainModule.extractExecutable(archive, "bun", executable)
      assert(os.read(executable) == "fake-bun")
      assert(executable.toIO.canExecute)

    test("dependency pairs are deterministic and deduplicate identical declarations"):
      val pairs = BunToolchainModule.dependencyPairs(
        Seq("zod@^4.0.0", "react@^19.0.0", "zod@^4.0.0")
      )
      assert(pairs.map((name, version) => name -> version.str) == Seq(
        "react" -> "^19.0.0",
        "zod" -> "^4.0.0"
      ))

    test("dependency conflicts fail unless explicitly overridden"):
      val error = intercept[IllegalArgumentException](
        BunToolchainModule.dependencyPairs(Seq("react@^18", "react@^19"))
      )
      assert(error.getMessage.contains("Conflicting npm dependency 'react'"))

      val pairs = BunToolchainModule.dependencyPairs(
        Seq("react@^18", "react@^19"),
        Map("react" -> "19.1.1")
      )
      assert(pairs.map((name, version) => name -> version.str) == Seq("react" -> "19.1.1"))

    test("malformed dependency declarations fail clearly"):
      Seq("", "react@", "@types", "@types/bun@").foreach: input =>
        intercept[IllegalArgumentException](BunToolchainModule.splitDep(input))

    test("package json extras cannot replace typed dependency fields"):
      val error = intercept[IllegalArgumentException](
        BunToolchainModule.mergePackageJson(
          ujson.Obj("dependencies" -> ujson.Obj("react" -> "^19")),
          ujson.Obj("dependencies" -> ujson.Obj("react" -> "latest"))
        )
      )
      assert(error.getMessage.contains("cannot replace modeled fields: dependencies"))

      val merged = BunToolchainModule.mergePackageJson(
        ujson.Obj("name" -> "app"),
        ujson.Obj("scripts" -> ujson.Obj("check" -> "bun test"))
      )
      assert(merged("scripts")("check").str == "bun test")
