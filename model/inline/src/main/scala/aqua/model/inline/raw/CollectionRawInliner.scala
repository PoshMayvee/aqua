package aqua.model.inline.raw

import aqua.model.{
  CallModel,
  CanonicalizeModel,
  NullModel,
  PushToStreamModel,
  RestrictionModel,
  SeqModel,
  ValueModel,
  VarModel,
  XorModel
}
import aqua.model.inline.Inline
import aqua.model.inline.RawValueInliner.valueToModel
import aqua.model.inline.state.{Arrows, Exports, Mangler}
import aqua.raw.value.CollectionRaw
import aqua.types.{ArrayType, OptionType, StreamType}
import cats.data.{Chain, State}

object CollectionRawInliner extends RawInliner[CollectionRaw] {

  override def apply[S: Mangler : Exports : Arrows](
                                                     raw: CollectionRaw,
                                                     lambdaAllowed: Boolean
                                                   ): State[S, (ValueModel, Inline)] =
    for {
      streamName <- Mangler[S].findAndForbidName((
        raw.boxType match {
          case _: StreamType => "stream"
          case _: ArrayType => "array"
          case _: OptionType => "option"
        }
        ) + "-inline")

      stream = VarModel(streamName, StreamType(raw.elementType))
      streamExp = CallModel.Export(stream.name, stream.`type`)

      vals <- raw.values
        .traverse(valueToModel(_))
        .map(_.toList)
        .map(Chain.fromSeq)
        .map(_.flatMap { case (v, t) =>
          Chain.fromOption(t) :+ PushToStreamModel(v, streamExp).leaf
        })

      canonName <-
        if (raw.boxType.isStream) State.pure(streamName)
        else Mangler[S].findAndForbidName(streamName)
      canon = CallModel.Export(canonName, raw.boxType)
    } yield VarModel(canonName, raw.boxType) -> Inline.tree(
      raw.boxType match {
        case ArrayType(_) =>
          RestrictionModel(streamName, isStream = true).wrap(
            SeqModel.wrap((vals :+ CanonicalizeModel(stream, canon).leaf).toList: _*)
          )
        case OptionType(_) =>
          RestrictionModel(streamName, isStream = true).wrap(
            SeqModel.wrap(
              XorModel.wrap((vals :+ NullModel.leaf).toList: _*),
              CanonicalizeModel(stream, canon).leaf
            )
          )
        case StreamType(_) =>
          SeqModel.wrap(vals.toList: _*)
      }
    )
}