package aqua.context.marker

import aqua.parser.Extract
import aqua.parser.lexer.{DataType, Token, Var}

sealed trait ArgVarMarker[F[_]] extends Marker[F]

case class FuncArgMarker[F[_]](v: Var[F], dt: DataType[F]) extends ArgVarMarker[F] {
  override def pointer: Token[F] = v
}

case class ExtractedVarMarker[F[_], L](v: Var[F], extract: Extract[F, L]) extends ArgVarMarker[F] {
  override def pointer: Token[F] = v
}

case class TypedVarMarker[F[_], L](v: Var[F], dt: DataType[F]) extends ArgVarMarker[F] {
  override def pointer: Token[F] = v
}
