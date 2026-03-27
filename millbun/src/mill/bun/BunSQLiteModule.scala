package mill.bun

import mill.*

/**
 * Mixin trait for modules that use bun:sqlite with embedded databases.
 *
 * Bun supports embedding SQLite databases into compiled executables via
 * `import db from "./my.db" with { type: "sqlite", embed: "true" }`.
 * The .db file must exist on disk at `bun build --compile` time.
 *
 * This trait discovers database files and feeds them into `bunCompileResources`
 * so they are copied into the compile workspace automatically.
 *
 * Usage:
 * {{{
 * object app extends BunTypeScriptModule with BunSQLiteModule {
 *   override def sqliteDatabases = Task { Seq(PathRef(millSourcePath / "data" / "app.db")) }
 * }
 * }}}
 */
trait BunSQLiteModule extends BunToolchainModule {

  /** Explicit SQLite database files to include in the compile workspace. */
  def sqliteDatabases: T[Seq[PathRef]] = Task { Seq.empty }

  /** Optional directory to scan for .db, .sqlite, and .sqlite3 files. */
  def sqliteDatabaseDir: T[Option[PathRef]] = Task { None }

  private def resolvedSqliteDatabases: T[Seq[PathRef]] = Task {
    val explicit = sqliteDatabases()
    val discovered = sqliteDatabaseDir().toSeq.flatMap { dir =>
      os.walk(dir.path)
        .filter(p => Set("db", "sqlite", "sqlite3").contains(p.ext))
        .map(PathRef(_))
    }
    explicit ++ discovered
  }

  override def bunCompileResources: T[Seq[PathRef]] = Task {
    super.bunCompileResources() ++ resolvedSqliteDatabases()
  }
}
