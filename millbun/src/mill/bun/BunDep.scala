package mill.bun

/** String interpolator for Bun package dependencies.
  *
  * Validates the `name@version` format at compile time:
  * {{{
  * import mill.bun.bun
  *
  * bun"react@^19.0.0"                          // ok
  * bun"@anthropic-ai/claude-agent-sdk@^0.2.90" // ok (scoped)
  * bun"zod@^4.0.0"                             // ok
  * bun"react"                                   // ok (latest)
  * bun""                                        // compile error
  * }}}
  *
  * Returns a plain `String` so it's fully backward compatible with
  * `npmDeps` / `bunDeps` declarations.
  */
extension (sc: StringContext)
  inline def bun(inline args: Any*): String =
    ${ BunDepMacro.validateImpl('sc, 'args) }

private object BunDepMacro:
  import scala.quoted.*

  def validateImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[String] =
    import quotes.reflect.*

    sc match
      case '{ StringContext(${ Varargs(parts) }*) } =>
        // For a no-interpolation literal like bun"react@19.0.0", parts has 1 element
        parts match
          case Seq(Expr(literal: String)) if args.matches('{ Seq() }) || args.matches('{ Nil }) =>
            validateLiteral(literal)
            Expr(literal)
          case _ =>
            // Has interpolated parts — build at runtime, skip compile-time validation
            '{ $sc.s($args*) }
      case _ =>
        // Inside another macro (e.g., utest's Tests{}) the pattern may not match.
        // Fall back to runtime string construction.
        '{ $sc.s($args*) }

  private def validateLiteral(dep: String)(using Quotes): Unit =
    import quotes.reflect.*
    if dep.isEmpty then
      report.errorAndAbort("bun dependency cannot be empty. Use bun\"package@version\" format.")
    // Validate package name format
    val name = if dep.startsWith("@") then
      // Scoped: @scope/name or @scope/name@version
      val afterScope = dep.drop(1)
      if !afterScope.contains('/') then
        report.errorAndAbort(
          s"Invalid scoped package: '$dep'. Expected @scope/name or @scope/name@version"
        )
      val slashIdx = afterScope.indexOf('/')
      val afterSlash = afterScope.drop(slashIdx + 1)
      val nameOnly = if afterSlash.contains('@') then afterSlash.take(afterSlash.indexOf('@')) else afterSlash
      if nameOnly.isEmpty then
        report.errorAndAbort(s"Invalid scoped package: '$dep'. Package name is empty after scope.")
      dep
    else
      // Unscoped: name or name@version
      val nameOnly = if dep.contains('@') then dep.take(dep.indexOf('@')) else dep
      if nameOnly.isEmpty then
        report.errorAndAbort(s"Invalid package: '$dep'. Package name cannot be empty.")
      dep
