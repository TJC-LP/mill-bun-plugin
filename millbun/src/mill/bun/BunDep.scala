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
    literalParts(sc) match
      case Some(Seq(literal)) if isEmptyInterpolation(args) =>
        validateLiteral(literal)
        Expr(literal)
      case _ =>
        // Has interpolated parts, a non-literal StringContext, or runs inside another
        // macro-generated context — build at runtime and skip compile-time validation.
        '{ $sc.s($args*) }

  private def literalParts(sc: Expr[StringContext])(using Quotes): Option[Seq[String]] =
    import quotes.reflect.*

    def extractRepeatedStrings(term: Term): Option[Seq[String]] =
      term match
        case Typed(Repeated(partTerms, _), _) => extractStringTerms(partTerms)
        case Repeated(partTerms, _)           => extractStringTerms(partTerms)
        case Inlined(_, _, inner)             => extractRepeatedStrings(inner)
        case _                                => None

    def extractStringTerms(partTerms: Seq[Term]): Option[Seq[String]] =
      partTerms.foldRight(Option(List.empty[String])) { (term, acc) =>
        val part = term match
          case Literal(StringConstant(value)) => Some(value)
          case Inlined(_, _, inner) =>
            inner match
              case Literal(StringConstant(value)) => Some(value)
              case _                              => None
          case _ => None
        for
          values <- acc
          value <- part
        yield value :: values
      }

    sc.asTerm.underlyingArgument match
      case Apply(_, List(repeatedParts)) => extractRepeatedStrings(repeatedParts)
      case _                             => None

  private def isEmptyInterpolation(args: Expr[Seq[Any]])(using Quotes): Boolean =
    import quotes.reflect.*

    def extractRepeatedArgs(term: Term): Option[Seq[Term]] =
      term match
        case Typed(Repeated(argTerms, _), _) => Some(argTerms)
        case Repeated(argTerms, _)           => Some(argTerms)
        case Inlined(_, _, inner)            => extractRepeatedArgs(inner)
        case _                               => None

    extractRepeatedArgs(args.asTerm.underlyingArgument).contains(Nil)

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
      val scopeName = afterScope.take(slashIdx)
      if scopeName.isEmpty then
        report.errorAndAbort(s"Invalid scoped package: '$dep'. Scope name is empty.")
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
