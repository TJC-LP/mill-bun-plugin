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

    test("bundles checksums for every supported Bun 1.3.14 asset"):
      val assets = Seq(
        "bun-darwin-aarch64.zip",
        "bun-darwin-x64.zip",
        "bun-linux-aarch64.zip",
        "bun-linux-x64.zip",
        "bun-windows-aarch64.zip",
        "bun-windows-x64.zip"
      )
      assets.foreach: asset =>
        val checksum = BunToolchainModule.bundledChecksum("1.3.14", asset)
        assert(checksum.exists(_.matches("[0-9a-f]{64}")))
      assert(BunToolchainModule.bundledChecksum("1.3.15", assets.head).isEmpty)

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
