package mill.bun

import utest._

object BunManifestTests extends TestSuite {
  def tests: Tests = Tests {

    test("empty manifest serialization") {
      val json = BunManifest.toJson(BunManifest.empty)
      val parsed = BunManifest.fromJson(json)
      assert(parsed.schemaVersion == 2)
      assert(parsed.dependencies.isEmpty)
      assert(parsed.devDependencies.isEmpty)
      assert(parsed.optionalDependencies.isEmpty)
      assert(parsed.peerDependencies.isEmpty)
    }

    test("schema v2 round-trip with publishable dependencies") {
      val manifest = BunManifest(
        dependencies = Map(
          "@anthropic-ai/claude-agent-sdk" -> "^0.2.90",
          "zod" -> "^4.0.0"
        ),
        devDependencies = Map.empty,
        optionalDependencies = Map("fsevents" -> "^2.3.3"),
        peerDependencies = Map("react" -> "^19.0.0")
      )
      val json = BunManifest.toJson(manifest)
      val parsed = BunManifest.fromJson(json)
      assert(parsed.dependencies == manifest.dependencies)
      assert(parsed.optionalDependencies == manifest.optionalDependencies)
      assert(parsed.peerDependencies == manifest.peerDependencies)
      assert(!json.obj.contains("devDependencies"))
    }

    test("schema v1 remains readable") {
      val json = ujson.Obj(
        "schemaVersion" -> 1,
        "dependencies" -> ujson.Obj("react" -> "^18.0.0"),
        "devDependencies" -> ujson.Obj("typescript" -> "^5.0.0")
      )
      val parsed = BunManifest.fromJson(json)
      assert(parsed.schemaVersion == 1)
      assert(parsed.dependencies == Map("react" -> "^18.0.0"))
      assert(parsed.devDependencies == Map("typescript" -> "^5.0.0"))
    }

    test("fromJson handles missing fields") {
      val json = ujson.Obj("dependencies" -> ujson.Obj("react" -> "19.0.0"))
      val parsed = BunManifest.fromJson(json)
      assert(parsed.dependencies == Map("react" -> "19.0.0"))
      assert(parsed.schemaVersion == 1)
      assert(parsed.devDependencies.isEmpty)
      assert(parsed.optionalDependencies.isEmpty)
    }

    test("schema v2 rejects dev dependencies") {
      val json = ujson.Obj(
        "schemaVersion" -> 2,
        "dependencies" -> ujson.Obj(),
        "devDependencies" -> ujson.Obj("typescript" -> "^5.0.0")
      )
      val error = intercept[IllegalArgumentException](BunManifest.fromJson(json))
      assert(error.getMessage.contains("does not allow devDependencies"))
    }

    test("unknown schema versions fail clearly") {
      val error = intercept[IllegalArgumentException](
        BunManifest.fromJson(ujson.Obj("schemaVersion" -> 99))
      )
      assert(error.getMessage.contains("schemaVersion 99"))
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
      assert(merged.devDependencies.isEmpty)
      assert(merged.optionalDependencies == Map("lodash" -> "^4.17.0"))
      assert(merged.schemaVersion == 2)
    }

    test("schema v2 serialization rejects development dependencies") {
      val manifest = BunManifest(
        dependencies = Map.empty,
        devDependencies = Map("typescript" -> "^5.0.0"),
        optionalDependencies = Map.empty
      )
      intercept[IllegalArgumentException](BunManifest.toJson(manifest))
    }

    test("merge rejects conflicting dependency requirements") {
      val m1 = BunManifest(Map("react" -> "^18.0.0"), Map.empty, Map.empty)
      val m2 = BunManifest(Map("react" -> "^19.0.0"), Map.empty, Map.empty)
      val error = intercept[IllegalArgumentException](BunManifest.merge(Seq(m1, m2)))
      assert(error.getMessage.contains("Conflicting runtime dependency 'react'"))
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

    test("readFromDir reports malformed manifests") {
      val dir = os.temp.dir()
      os.write(dir / os.RelPath(BunManifest.ManifestPath), "{", createFolders = true)
      intercept[Exception](BunManifest.readFromDir(dir))
    }

    test("JAR round-trip: write manifest, read back") {
      val tmpDir = os.temp.dir()

      val jarPath = tmpDir / "test-lib.jar"
      val manifestContent = BunManifest.toJson(
        BunManifest(Map("react" -> "^19.0.0"), Map.empty, Map.empty)
      ).render()

      val jarOut = new java.util.jar.JarOutputStream(
        new java.io.FileOutputStream(jarPath.toIO)
      )
      try {
        jarOut.putNextEntry(new java.util.jar.JarEntry(BunManifest.ManifestPath))
        jarOut.write(manifestContent.getBytes("UTF-8"))
        jarOut.closeEntry()
      } finally jarOut.close()

      val manifest = BunManifest.readFromJar(jarPath)
      assert(manifest.isDefined)
      assert(manifest.get.dependencies("react") == "^19.0.0")
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
    }

    test("readFromJar reports malformed manifests") {
      val jarPath = os.temp.dir() / "malformed.jar"
      val jarOut = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jarPath.toIO))
      try {
        jarOut.putNextEntry(new java.util.jar.JarEntry(BunManifest.ManifestPath))
        jarOut.write("{".getBytes("UTF-8"))
        jarOut.closeEntry()
      } finally jarOut.close()

      intercept[Exception](BunManifest.readFromJar(jarPath))
    }

    test("readFromJar returns None for nonexistent path") {
      val result = BunManifest.readFromJar(os.Path("/nonexistent/lib.jar"))
      assert(result.isEmpty)
    }
  }
}
