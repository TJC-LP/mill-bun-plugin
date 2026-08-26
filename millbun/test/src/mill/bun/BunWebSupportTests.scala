package mill.bun

import mill.api.PathRef
import utest.*

object BunWebSupportTests extends TestSuite:
  def tests: Tests = Tests:
    test("htmlEntries resolves without writing"):
      // dev() and bundle() call this against a staging task's already-cached dest, so it must
      // not touch the filesystem.
      val moduleDir = os.temp.dir()
      val dest = os.temp.dir()
      val entries = BunWebSupport.htmlEntries(Seq.empty, moduleDir, dest)
      assert(entries == Seq(dest / "index.html"))
      assert(!os.exists(dest / "index.html"))

    test("materializeHtmlEntries generates index.html only when none is configured"):
      val moduleDir = os.temp.dir()
      val dest = os.temp.dir()
      val entries =
        BunWebSupport.materializeHtmlEntries(Seq.empty, moduleDir, dest, "./main.js")
      assert(entries == Seq(dest / "index.html"))
      val generated = os.read(dest / "index.html")
      assert(generated.contains("""src="./main.js""""))

    test("materializing twice leaves the generated file untouched"):
      val moduleDir = os.temp.dir()
      val dest = os.temp.dir()
      BunWebSupport.materializeHtmlEntries(Seq.empty, moduleDir, dest, "./main.js")
      val before = os.read(dest / "index.html")

      // The pure form must not rewrite it — that write would land in a cached task dest.
      BunWebSupport.htmlEntries(Seq.empty, moduleDir, dest)
      assert(os.read(dest / "index.html") == before)

    test("configured entries map under moduleDir and are not overwritten"):
      val moduleDir = os.temp.dir()
      os.write(moduleDir / "pages" / "app.html", "<!-- mine -->", createFolders = true)
      val dest = os.temp.dir()
      os.write(dest / "pages" / "app.html", "<!-- staged -->", createFolders = true)

      val configured = Seq(PathRef(moduleDir / "pages" / "app.html"))
      val entries =
        BunWebSupport.materializeHtmlEntries(configured, moduleDir, dest, "./main.js")
      assert(entries == Seq(dest / "pages" / "app.html"))
      assert(!os.exists(dest / "index.html"))
      assert(os.read(dest / "pages" / "app.html") == "<!-- staged -->")
