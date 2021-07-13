package aqua.parser.expr

import aqua.parser.Expr
import aqua.parser.lexer.Name
import aqua.parser.lexer.Token._
import aqua.parser.lift.LiftParser
import cats.Comonad
import cats.parse.Parser

case class CatchExpr[F[_]](name: Name[F]) extends Expr[F](CatchExpr, name)

object CatchExpr extends Expr.AndIndented {

  override def validChildren: List[Expr.Lexem] = TryExpr.validChildren

  override def p[F[_]: LiftParser: Comonad]: Parser[Expr[F]] =
    (`catch` *> ` ` *> Name.p[F]).map(CatchExpr(_))

}