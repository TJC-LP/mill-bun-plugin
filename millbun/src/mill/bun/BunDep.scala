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

object BunDep:
  /**
   * Validate a dependency the macro could not check at compile time, returning it unchanged.
   *
   * Used for interpolated forms like `bun"react@$version"`, where the value only exists once the
   * build evaluates. Failing here still beats failing inside `bun install`.
   */
  def validate(dep: String): String =
    BunToolchainModule.parseDependency(dep) match
      case Right(_)     => dep
      case Left(message) => throw new IllegalArgumentException(s"Invalid bun dependency: $message")

private object BunDepMacro:
  import scala.quoted.*

  def validateImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[String] =
    literalParts(sc) match
      case Some(Seq(literal)) if isEmptyInterpolation(args) =>
        validateLiteral(literal)
        Expr(literal)
      case _ =>
        // Has interpolated parts, a non-literal StringContext, or runs inside another
        // macro-generated context, so the value is not known at compile time. Validate when
        // the build evaluates it instead, which still fails before any install runs.
        '{ BunDep.validate($sc.s($args*)) }

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

  /**
   * Validate against the same parser the build uses at task time.
   *
   * This deliberately delegates rather than re-deriving the rules: a second, weaker parser here
   * meant `bun"react@"` compiled cleanly and then threw from `parseDependency` during the install,
   * which is exactly what the interpolator exists to prevent.
   */
  private def validateLiteral(dep: String)(using Quotes): Unit =
    import quotes.reflect.*
    BunToolchainModule.parseDependency(dep).left.foreach { message =>
      report.errorAndAbort(s"Invalid bun dependency: $message")
    }
