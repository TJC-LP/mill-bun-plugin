package mill.bun

import mill.api.PathRef

private[mill] object BunWebSupport:

  def copyPreservingModuleDir(refs: Seq[PathRef], moduleDir: os.Path, destination: os.Path): Unit =
    refs.filter(ref => os.exists(ref.path)).foreach { ref =>
      val source = ref.path
      val target =
        if source.startsWith(moduleDir) then destination / source.relativeTo(moduleDir)
        else destination / source.last
      copyPath(source, target)
    }

  def copyContents(source: os.Path, destination: os.Path): Unit =
    if os.exists(source) then
      if os.isDir(source) then
        os.walk(source).foreach { path =>
          val relative = path.relativeTo(source)
          if relative.segments.nonEmpty then
            val target = destination / relative
            if os.isDir(path) then os.makeDir.all(target)
            else os.copy.over(path, target, createFolders = true)
        }
      else copyPath(source, destination / source.last)

  private def copyPath(source: os.Path, target: os.Path): Unit =
    if os.isDir(source) then
      os.walk(source).foreach { path =>
        val destination = target / path.relativeTo(source)
        if os.isDir(path) then os.makeDir.all(destination)
        else os.copy.over(path, destination, createFolders = true)
      }
    else os.copy.over(source, target, createFolders = true)

  def htmlEntries(
      configured: Seq[PathRef],
      moduleDir: os.Path,
      destination: os.Path,
      generatedScript: String
  ): Seq[os.Path] =
    val copied = configured
      .map(_.path)
      .filter(path => os.exists(path) && os.isFile(path))
      .map(path =>
        if path.startsWith(moduleDir) then destination / path.relativeTo(moduleDir)
        else destination / path.last
      )

    if copied.nonEmpty then copied
    else
      val index = destination / "index.html"
      os.write.over(
        index,
        s"""<!doctype html>
           |<html>
           |  <head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
           |  <body><div id="app"></div><script type="module" src="$generatedScript"></script></body>
           |</html>
           |""".stripMargin,
        createFolders = true
      )
      Seq(index)

  def syncRoots(roots: Seq[(os.Path, os.Path)]): Unit =
    roots.foreach { case (source, target) =>
      if os.exists(source) then copyPath(source, target)
    }

  def runDevelopmentServer(
      bunExecutable: String,
      htmlEntries: Seq[os.Path],
      workingDirectory: os.Path,
      port: Int,
      extraArgs: Seq[String],
      environment: Map[String, String],
      syncRoots: Seq[(os.Path, os.Path)] = Seq.empty
  ): Unit =
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    val syncThread =
      if syncRoots.nonEmpty then
        val thread = new Thread(
          () =>
            while running.get() do
              try
                syncRoots.foreach { case (source, target) =>
                  if os.exists(source) then copyChangedFiles(source, target)
                }
                Thread.sleep(200)
              catch
                case _: InterruptedException => ()
          ,
          "mill-bun-web-sync"
        )
        thread.setDaemon(true)
        thread.start()
        Some(thread)
      else None

    val relativeEntries = htmlEntries.map(_.relativeTo(workingDirectory).toString)
    val process = os.proc(
      Seq(bunExecutable) ++ relativeEntries ++ Seq(s"--port=$port") ++ extraArgs
    ).spawn(
      cwd = workingDirectory,
      env = environment,
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit
    )

    try
      process.join()
      if process.exitCode() != 0 then
        throw new RuntimeException(s"Bun development server exited with ${process.exitCode()}")
    finally
      running.set(false)
      syncThread.foreach(_.interrupt())
      if process.isAlive() then process.destroy()

  private def copyChangedFiles(source: os.Path, target: os.Path): Unit =
    if os.isDir(source) then
      os.walk(source).foreach { path =>
        val destination = target / path.relativeTo(source)
        if os.isDir(path) then os.makeDir.all(destination)
        else if !os.exists(destination) || os.mtime(path) != os.mtime(destination) || os.size(path) != os.size(destination) then
          os.copy.over(path, destination, createFolders = true)
      }
    else if !os.exists(target) || os.mtime(source) != os.mtime(target) || os.size(source) != os.size(target) then
      os.copy.over(source, target, createFolders = true)
