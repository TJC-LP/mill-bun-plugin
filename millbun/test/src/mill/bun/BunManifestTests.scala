package mill.bun

import utest._

object BunManifestTests extends TestSuite {
  def tests: Tests = Tests {

    test("empty manifest serialization") {
      val json = BunManifest.toJson(BunManifest.empty)
      val parsed = BunManifest.fromJson(json)
      assert(parsed.dependencies.isEmpty)
      assert(parsed.devDependencies.isEmpty)
      assert(parsed.optionalDependencies.isEmpty)
    }

    test("round-trip with dependencies") {
      val manifest = BunManifest(
        dependencies = Map(
          "@anthropic-ai/claude-agent-sdk" -> "^0.2.90",
          "zod" -> "^4.0.0"
        ),
        devDependencies = Map("@types/bun" -> "^1.3.5"),
        optionalDependencies = Map.empty
      )
      val json = BunManifest.toJson(manifest)
      val parsed = BunManifest.fromJson(json)
      assert(parsed.dependencies == manifest.dependencies)
      assert(parsed.devDependencies == manifest.devDependencies)
    }

    test("round-trip with optional dependencies") {
      val manifest = BunManifest(
        dependencies = Map("react" -> "^19.0.0"),
        devDependencies = Map.empty,
        optionalDependencies = Map("@openai/codex-sdk" -> "^0.118.0")
      )
      val json = BunManifest.toJson(manifest)
      val parsed = BunManifest.fromJson(json)
      assert(parsed.optionalDependencies == manifest.optionalDependencies)
    }

    test("fromJson handles missing fields") {
      val json = ujson.Obj("dependencies" -> ujson.Obj("react" -> "19.0.0"))
      val parsed = BunManifest.fromJson(json)
      assert(parsed.dependencies == Map("react" -> "19.0.0"))
      assert(parsed.devDependencies.isEmpty)
      assert(parsed.optionalDependencies.isEmpty)
    }

    test("merge combines manifests") {
      val m1 = BunManifest(
        Map("react" -> "^19.0.0"),
        Map("typescript" -> "^5.0.0"),
        Map.empty
      )
      val m2 = BunManifest(
        Map("zod" -> "^4.0.0"),
        Map.empty,
        Map("lodash" -> "^4.17.0")
      )
      val merged = BunManifest.merge(Seq(m1, m2))
      assert(merged.dependencies == Map("react" -> "^19.0.0", "zod" -> "^4.0.0"))
      assert(merged.devDependencies == Map("typescript" -> "^5.0.0"))
      assert(merged.optionalDependencies == Map("lodash" -> "^4.17.0"))
    }

    test("merge later entries override earlier") {
      val m1 = BunManifest(Map("react" -> "^18.0.0"), Map.empty, Map.empty)
      val m2 = BunManifest(Map("react" -> "^19.0.0"), Map.empty, Map.empty)
      val merged = BunManifest.merge(Seq(m1, m2))
      assert(merged.dependencies("react") == "^19.0.0")
    }

    test("readFromDir returns None for missing directory") {
      val result = BunManifest.readFromDir(os.temp.dir() / "nonexistent")
      assert(result.isEmpty)
    }

    test("readFromDir reads manifest from unpacked classes") {
      val dir = os.temp.dir()
      val metaDir = dir / "META-INF" / "bun"
      os.makeDir.all(metaDir)
      val manifest = BunManifest(Map("react" -> "^19.0.0"), Map.empty, Map.empty)
      os.write(metaDir / "bun-dependencies.json", BunManifest.toJson(manifest).render())
      val result = BunManifest.readFromDir(dir)
      assert(result.isDefined)
      assert(result.get.dependencies("react") == "^19.0.0")
    }

    test("JAR round-trip: write manifest and lockfile, read back") {
      val tmpDir = os.temp.dir()

      // Create a JAR with manifest and lockfile
      val jarPath = tmpDir / "test-lib.jar"
      val manifestContent = BunManifest.toJson(
        BunManifest(Map("react" -> "^19.0.0"), Map.empty, Map.empty)
      ).render()
      val lockContent = "# bun lockfile\nreact@^19.0.0: resolved=19.1.0"

      val jarOut = new java.util.jar.JarOutputStream(
        new java.io.FileOutputStream(jarPath.toIO)
      )
      try {
        // Write manifest entry
        jarOut.putNextEntry(new java.util.jar.JarEntry(BunManifest.ManifestPath))
        jarOut.write(manifestContent.getBytes("UTF-8"))
        jarOut.closeEntry()

        // Write lockfile entry
        jarOut.putNextEntry(new java.util.jar.JarEntry(BunManifest.LockfilePath))
        jarOut.write(lockContent.getBytes("UTF-8"))
        jarOut.closeEntry()
      } finally jarOut.close()

      // Read manifest back
      val manifest = BunManifest.readFromJar(jarPath)
      assert(manifest.isDefined)
      assert(manifest.get.dependencies("react") == "^19.0.0")

      // Extract lockfile
      val extractDir = tmpDir / "extract"
      os.makeDir.all(extractDir)
      val lockfile = BunManifest.extractLockfile(jarPath, extractDir)
      assert(lockfile.isDefined)
      assert(os.exists(lockfile.get))
      val content = os.read(lockfile.get)
      assert(content.contains("react"))
    }

    test("readFromJar returns None for JAR without manifest") {
      val tmpDir = os.temp.dir()
      val jarPath = tmpDir / "empty-lib.jar"

      val jarOut = new java.util.jar.JarOutputStream(
        new java.io.FileOutputStream(jarPath.toIO)
      )
      try {
        jarOut.putNextEntry(new java.util.jar.JarEntry("com/example/Foo.class"))
        jarOut.write("fake class".getBytes("UTF-8"))
        jarOut.closeEntry()
      } finally jarOut.close()

      val manifest = BunManifest.readFromJar(jarPath)
      assert(manifest.isEmpty)

      val lockfile = BunManifest.extractLockfile(jarPath, tmpDir / "extract")
      assert(lockfile.isEmpty)
    }

    test("readFromJar returns None for nonexistent path") {
      val result = BunManifest.readFromJar(os.Path("/nonexistent/lib.jar"))
      assert(result.isEmpty)
    }
  }
}
