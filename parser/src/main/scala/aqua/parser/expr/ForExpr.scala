package aqua.parser.expr

import aqua.parser.Expr
import aqua.parser.lexer.{Name, Value}
import aqua.parser.lift.LiftParser
import cats.Comonad
import cats.parse.{Parser => P}
import aqua.parser.lexer.Token._
import LiftParser._

case class ForExpr[F[_]](item: Name[F], iterable: Value[F], par: Option[F[Unit]]) extends Expr[F]

object ForExpr extends Expr.AndIndented(Expr.defer(OnExpr), ParExpr, CallArrowExpr, AbilityIdExpr) {

  override def p[F[_]: LiftParser: Comonad]: P[ForExpr[F]] =
    ((`for` *> ` ` *> Name.p[F] <* ` <- `) ~ Value.`value`[F] ~ (` ` *> `par`.lift).?).map {
      case ((item, iterable), par) =>
        ForExpr(item, iterable, par)
    }
}