package aqua.js

import aqua.types.*
import cats.data.Validated.{invalid, invalidNec, invalidNel, valid, validNec, validNel}
import cats.data.{NonEmptyMap, Validated, ValidatedNec}
import cats.syntax.applicative.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.semigroup.*
import cats.syntax.traverse.*

import scala.collection.immutable.SortedMap
import scala.scalajs.js

object JsonEncoder {

  /* Get widest possible type from JSON arrays. For example:
  JSON: {
          field1: [
                  {
                    a: "a",
                    b: [1,2,3],
                    c: 4
                  },
                  {
                    c: 3
                  }
                  ]
        }
  There type in array must be { a: ?string, b: []number, c: number
   */
  private def compareAndGetWidestType(
    name: String,
    ltV: ValidatedNec[String, Type],
    rtV: ValidatedNec[String, Type]
  ): ValidatedNec[String, Type] = {
    (ltV, rtV) match {
      case (Validated.Valid(lt), Validated.Valid(rt)) =>
        (lt, rt) match {
          case (lt, rt) if lt == rt => validNec(lt)
          case (BottomType, ra @ ArrayType(_)) => validNec(ra)
          case (la @ ArrayType(_), BottomType) => validNec(la)
          case (lo @ OptionType(lel), rtt) if lel == rtt => validNec(lo)
          case (ltt, ro @ OptionType(rel)) if ltt == rel => validNec(ro)
          case (BottomType, rb) => validNec(OptionType(rb))
          case (lb, BottomType) => validNec(OptionType(lb))
          case (lst: StructType, rst: StructType) =>
            val lFieldsSM: SortedMap[String, Type] = lst.fields.toSortedMap
            val rFieldsSM: SortedMap[String, Type] = rst.fields.toSortedMap
            (lFieldsSM.toList ++ rFieldsSM.toList)
              .groupBy(_._1)
              .view
              .mapValues(_.map(_._2))
              .map {
                case (name, t :: Nil) =>
                  compareAndGetWidestType(name, validNec(t), validNec(BottomType)).map(t =>
                    (name, t)
                  )
                case (name, lt :: rt :: Nil) =>
                  compareAndGetWidestType(name, validNec(lt), validNec(rt)).map(t => (name, t))
                case _ =>
                  // this is internal error.This Can't happen
                  invalidNec("Unexpected. The list can only have 1 or 2 arguments.")
              }
              .toList
              .sequence
              .map(processedFields => NonEmptyMap.fromMap(SortedMap(processedFields: _*)).get)
              .map(mt => StructType("", mt))
          case (_, _) =>
            invalidNec(s"Items in '$name' array should be of the same type")
        }
      case (Validated.Invalid(lerr), Validated.Invalid(rerr)) =>
        Validated.Invalid(lerr ++ rerr)
      case (l @ Validated.Invalid(_), _) =>
        l
      case (_, r @ Validated.Invalid(_)) =>
        r
    }
  }

  // Gather all information about all fields in JSON and create Aqua type.
  def aquaTypeFromJson(name: String, arg: js.Dynamic): ValidatedNec[String, Type] = {
    val t = js.typeOf(arg)
    arg match {
      case a if t == "string" => validNec(LiteralType.string)
      case a if t == "number" => validNec(LiteralType.number)
      case a if t == "boolean" => validNec(LiteralType.bool)
      case a if js.Array.isArray(a) =>
        // if all types are similar it will be array array with this type
        // otherwise array with bottom type
        val elementsTypesV: ValidatedNec[String, List[Type]] =
          a.asInstanceOf[js.Array[js.Dynamic]].map(ar => aquaTypeFromJson(name, ar)).toList.sequence

        elementsTypesV.andThen { elementsTypes =>
          if (elementsTypes.isEmpty) validNec(ArrayType(BottomType))
          else {
            elementsTypes
              .map(el => validNec(el))
              .reduce[ValidatedNec[String, Type]] { case (l, t) =>
                compareAndGetWidestType(name, l, t)
              }
              .map(t => ArrayType(t))
          }
        }
      case a if t == "object" && !js.isUndefined(arg) && arg != null =>
        val dict = arg.asInstanceOf[js.Dictionary[js.Dynamic]]
        val keys = dict.keys
        keys
          .map(k => aquaTypeFromJson(k, arg.selectDynamic(k)).map(t => k -> t))
          .toList
          .sequence
          .map { fields =>
            // HACK: JSON can have empty object and it is possible if there is only optional fields
            val fs =
              if (fields.isEmpty) List(("some_random_field_that_does_not_even_exists", BottomType))
              else fields
            StructType("", NonEmptyMap.fromMap(SortedMap(fs: _*)).get)
          }

      case _ => validNec(BottomType)
    }
  }
}
