package aqua.ast.expr

import aqua.ast.{Expr, Gen, Prog}
import aqua.ast.algebra.ValuesAlgebra
import aqua.ast.algebra.abilities.AbilitiesAlgebra
import aqua.ast.algebra.scope.PeerIdAlgebra
import aqua.parser.lexer.Token._
import aqua.parser.lexer.Value
import aqua.parser.lift.LiftParser
import cats.Comonad
import cats.parse.{Parser => P}
import cats.syntax.flatMap._
import cats.syntax.functor._

case class OnExpr[F[_]](peerId: Value[F]) extends Expr[F] {

  def program[Alg[_]](implicit
    P: PeerIdAlgebra[F, Alg],
    V: ValuesAlgebra[F, Alg],
    A: AbilitiesAlgebra[F, Alg]
  ): Prog[Alg, Gen] =
    Prog.around(
      V.ensureIsString(peerId) >> P.onPeerId(peerId) >> A.beginScope(peerId),
      (_: Unit) => A.endScope() >> P.erasePeerId() as Gen("OnScope finished")
    )

}

object OnExpr extends Expr.AndIndented(CoalgebraExpr, AbilityIdExpr) {

  override def p[F[_]: LiftParser: Comonad]: P[OnExpr[F]] =
    (`on` *> ` ` *> Value.`value`[F] <* ` : \n+`).map { peerId =>
      OnExpr(peerId)
    }
}