package aqua.model.inline.raw

import aqua.model.{
  CallModel,
  CallServiceModel,
  CanonicalizeModel,
  FlattenModel,
  ForModel,
  FunctorModel,
  IntoFieldModel,
  IntoIndexModel,
  LiteralModel,
  MatchMismatchModel,
  NextModel,
  OpModel,
  PropertyModel,
  PushToStreamModel,
  RestrictionModel,
  SeqModel,
  ValueModel,
  VarModel,
  XorModel
}
import aqua.model.inline.Inline
import aqua.model.inline.SeqMode
import aqua.model.inline.RawValueInliner.unfold
import aqua.model.inline.state.{Arrows, Exports, Mangler}
import aqua.raw.value.{
  ApplyGateRaw,
  ApplyPropertyRaw,
  CallArrowRaw,
  FunctorRaw,
  IntoCopyRaw,
  IntoFieldRaw,
  IntoIndexRaw,
  LiteralRaw,
  PropertyRaw,
  ValueRaw,
  VarRaw
}
import aqua.types.{ArrayType, CanonStreamType, ScalarType, StreamType, Type}
import cats.Eval
import cats.data.{Chain, IndexedStateT, State}
import cats.syntax.monoid.*
import cats.instances.list.*
import scribe.Logging

object ApplyPropertiesRawInliner extends RawInliner[ApplyPropertyRaw] with Logging {

  // in perspective literals can have properties and functors (like `nil` with length)
  def flatLiteralWithProperties[S: Mangler: Exports: Arrows](
    literal: LiteralModel,
    inl: Inline,
    properties: Chain[PropertyModel]
  ): State[S, (VarModel, Inline)] = {
    for {
      apName <- Mangler[S].findAndForbidName("literal_ap")
      resultName <- Mangler[S].findAndForbidName(s"literal_props")
    } yield {
      val cleanedType = literal.`type` match {
        // literals cannot be streams, use it as an array to use properties
        case StreamType(el) => ArrayType(el)
        case tt => tt
      }
      val apVar = VarModel(apName, cleanedType, properties)
      val tree = inl |+| Inline.tree(
        SeqModel.wrap(
          FlattenModel(literal.copy(`type` = cleanedType), apVar.name).leaf,
          FlattenModel(apVar, resultName).leaf
        )
      )
      VarModel(resultName, properties.lastOption.map(_.`type`).getOrElse(cleanedType)) -> tree
    }
  }

  private def removeProperties[S: Mangler](
    varModel: VarModel
  ): State[S, (VarModel, Inline)] = {
    for {
      nn <- Mangler[S].findAndForbidName(varModel.name + "_flat")
    } yield {
      val flatten = VarModel(nn, varModel.`type`)
      flatten -> Inline.tree(FlattenModel(varModel, flatten.name).leaf)
    }
  }

  private[inline] def unfoldProperty[S: Mangler: Exports: Arrows](
    varModel: VarModel,
    p: PropertyRaw
  ): State[S, (VarModel, Inline)] =
    p match {
      case IntoFieldRaw(field, t) =>
        State.pure(
          varModel.copy(properties =
            varModel.properties :+ IntoFieldModel(field, t)
          ) -> Inline.empty
        )

      case IntoIndexRaw(LiteralRaw(value, _), t) =>
        State.pure(
          varModel.copy(properties =
            varModel.properties :+ IntoIndexModel(value, t)
          ) -> Inline.empty
        )

      case IntoIndexRaw(vr, t) =>
        unfold(vr, propertiesAllowed = false).map {
          case (VarModel(name, _, _), inline) =>
            varModel.copy(properties = varModel.properties :+ IntoIndexModel(name, t)) -> inline
          case (LiteralModel(literal, _), inline) =>
            varModel.copy(properties = varModel.properties :+ IntoIndexModel(literal, t)) -> inline
        }

      case f @ FunctorRaw(_, _) =>
        for {
          flattenVI <-
            if (varModel.properties.nonEmpty) removeProperties(varModel)
            else State.pure(varModel, Inline.empty)
          (flatten, inline) = flattenVI
          newVI <- ApplyFunctorRawInliner(flatten, f)
        } yield {
          newVI._1 -> Inline(
            inline.flattenValues ++ newVI._2.flattenValues,
            inline.predo ++ newVI._2.predo,
            mergeMode = SeqMode
          )
        }

      case ic @ IntoCopyRaw(_, _) =>
        for {
          flattenVI <-
            if (varModel.properties.nonEmpty) removeProperties(varModel)
            else State.pure(varModel, Inline.empty)
          (flatten, inline) = flattenVI
          newVI <- ApplyIntoCopyRawInliner(varModel, ic)
        } yield {
          newVI._1 -> Inline(
            inline.flattenValues ++ newVI._2.flattenValues,
            inline.predo ++ newVI._2.predo,
            mergeMode = SeqMode
          )
        }

    }

  private def unfoldProperties[S: Mangler: Exports: Arrows](
    prevInline: Inline,
    vm: VarModel,
    properties: Chain[PropertyRaw],
    propertiesAllowed: Boolean
  ): State[S, (VarModel, Inline)] = {
    properties
      .foldLeft[State[S, (VarModel, Inline)]](
        State.pure((vm, prevInline))
      ) { case (state, property) =>
        state.flatMap { case (vm, leftInline) =>
          unfoldProperty(vm, property).flatMap {
            case (v, i) if !propertiesAllowed && v.properties.nonEmpty =>
              removeProperties(v).map { case (vf, inlf) =>
                vf -> Inline(
                  leftInline.flattenValues ++ i.flattenValues ++ inlf.flattenValues,
                  leftInline.predo ++ i.predo ++ inlf.predo,
                  mergeMode = SeqMode
                )
              }
            case (v, i) =>
              State.pure(
                v -> Inline(
                  leftInline.flattenValues ++ i.flattenValues,
                  leftInline.predo ++ i.predo,
                  mergeMode = SeqMode
                )
              )
          }
        }
      }
  }

  private def unfoldRawWithProperties[S: Mangler: Exports: Arrows](
    raw: ValueRaw,
    properties: Chain[PropertyRaw],
    propertiesAllowed: Boolean
  ): State[S, (ValueModel, Inline)] = {
    ((raw, properties.headOption) match {
      case (vr @ VarRaw(_, st @ StreamType(_)), Some(IntoIndexRaw(idx, _))) =>
        unfold(vr).flatMap {
          case (vm @ VarModel(nameVM, _, _), inl) =>
            val gateRaw = ApplyGateRaw(nameVM, st, idx)
            unfold(gateRaw).flatMap {
              case (gateResVal: VarModel, gateResInline) =>
                unfoldProperties(gateResInline, gateResVal, properties, propertiesAllowed).map {
                  case (v, i) =>
                    (v: ValueModel) -> Inline(
                      inl.flattenValues ++ i.flattenValues,
                      inl.predo ++ i.predo,
                      mergeMode = SeqMode
                    )
                }
              case (v, i) =>
                // what if pass nil as stream argument?
                logger.error("Unreachable. Unfolded stream cannot be a literal")
                State.pure(v -> i)
            }
          case l =>
            logger.error("Unreachable. Unfolded stream cannot be a literal")
            State.pure(l)
        }

      case (_, _) =>
        unfold(raw).flatMap {
          case (vm: VarModel, prevInline) =>
            unfoldProperties(prevInline, vm, properties, propertiesAllowed).map { case (v, i) =>
              (v: ValueModel) -> i
            }
          case (l: LiteralModel, inline) =>
            flatLiteralWithProperties(
              l,
              inline,
              Chain.empty
            ).flatMap { (varModel, prevInline) =>
              unfoldProperties(prevInline, varModel, properties, propertiesAllowed).map {
                case (v, i) =>
                  (v: ValueModel) -> i
              }
            }
        }
    })

  }

  override def apply[S: Mangler: Exports: Arrows](
    apr: ApplyPropertyRaw,
    propertiesAllowed: Boolean
  ): State[S, (ValueModel, Inline)] = {
    val (raw, properties) = apr.unwind
    unfoldRawWithProperties(raw, properties, propertiesAllowed)
  }
}
