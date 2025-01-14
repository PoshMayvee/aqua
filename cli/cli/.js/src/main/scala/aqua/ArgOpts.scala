package aqua

import aqua.builder.ArgumentGetter
import aqua.js.VarJson
import aqua.parser.expr.func.CallArrowExpr
import aqua.parser.lexer.{CallArrowToken, CollectionToken, LiteralToken, VarToken}
import aqua.parser.lift.Span
import aqua.raw.value.{CollectionRaw, LiteralRaw, ValueRaw, VarRaw}
import aqua.types.*
import aqua.run.CliFunc
import cats.data.*
import cats.data.Validated.{invalid, invalidNec, invalidNel, valid, validNec, validNel}
import cats.effect.Concurrent
import cats.syntax.applicative.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.semigroup.*
import cats.syntax.traverse.*
import cats.{~>, Id, Semigroup}
import com.monovore.decline.Opts
import fs2.io.file.{Files, Path}

import scala.collection.immutable.SortedMap
import scala.scalajs.js
import scala.scalajs.js.JSON

case class FuncWithData(func: CliFunc, getters: Map[String, VarJson])

object ArgOpts {

  // Parses a function name and arguments from a string
  def funcOpt: Opts[CliFunc] =
    Opts
      .option[String]("func", "Function to call with args", "f", "funcName(args)")
      .mapValidated { str =>
        CliFunc.fromString(str)
      }

  // Gets data from a file or from a json string
  def dataFileOrStringOpt[F[_]: Files: Concurrent]
    : Opts[F[ValidatedNec[String, Option[js.Dynamic]]]] =
    (AppOpts.wrapWithOption(dataOpt), AppOpts.wrapWithOption(dataFromFileOpt[F])).mapN {
      case (dataFromString, dataFromFile) =>
        dataFromFile match {
          case Some(dataFromFileF) =>
            dataFromFileF.map(_.andThen(args => getData(Some(args), dataFromString)))
          case None => validNec(dataFromString).pure[F]
        }
    }

  // Creates getters based on function arguments and data, return all info
  def funcWithArgsOpt[F[_]: Files: Concurrent]: Opts[F[ValidatedNec[String, FuncWithData]]] = {
    (dataFileOrStringOpt[F], funcOpt).mapN { case (dataF, func) =>
      dataF.map { dataV =>
        dataV.andThen { data =>
          VarJson.checkDataGetServices(func.args, data).map { case (argsWithTypes, getters) =>
            FuncWithData(func.copy(args = argsWithTypes), getters)
          }
        }
      }
    }
  }

  def dataOpt: Opts[js.Dynamic] =
    Opts
      .option[String](
        "data",
        "JSON in { [argumentName]: argumentValue } format. You can call a function using these argument names",
        "d",
        "json"
      )
      .mapValidated { str =>
        Validated.catchNonFatal {
          JSON.parse(str)
        }.leftMap(t => NonEmptyList.one("Data argument isn't a valid JSON: " + t.getMessage))
      }

  def dataFromFileOpt[F[_]: Files: Concurrent]: Opts[F[ValidatedNec[String, js.Dynamic]]] = {
    jsonFromFileOpt(
      "data-path",
      "Path to a JSON file in { [argumentName]: argumentValue } format. You can call a function using these argument names",
      "p"
    )
  }

  def jsonFromFileOpt[F[_]: Files: Concurrent](
    name: String,
    help: String,
    short: String
  ): Opts[F[ValidatedNec[String, js.Dynamic]]] = {
    FileOpts.fileOpt(
      name,
      help,
      short,
      (path, str) => {
        Validated.catchNonFatal {
          JSON.parse(str)
        }.leftMap(t =>
          NonEmptyChain
            .one(s"Data in ${path.toString} isn't a valid JSON: " + t.getMessage)
        )
      }
    )
  }

  def jsonFromFileOpts[F[_]: Files: Concurrent](
    name: String,
    help: String,
    short: String
  ): Opts[F[ValidatedNec[String, NonEmptyList[(Path, js.Dynamic)]]]] = {
    FileOpts.fileOpts(
      name,
      help,
      short,
      (path, str) => {
        Validated.catchNonFatal {
          JSON.parse(str)
        }.leftMap(t =>
          NonEmptyChain
            .one(s"Data in ${path.toString} isn't a valid JSON: " + t.getMessage)
        )
      }
    )
  }

  // get data from sources, error if both sources exist
  def getData(
    dataFromArgument: Option[js.Dynamic],
    dataFromFile: Option[js.Dynamic]
  ): ValidatedNec[String, Option[js.Dynamic]] = {
    (dataFromArgument, dataFromFile) match {
      case (Some(_), Some(_)) =>
        // TODO: maybe allow to use both and simple merge with data argument having higher priority
        invalidNec("Please use either --data or --data-path. Don't use both")
      case _ => validNec(dataFromArgument.orElse(dataFromFile))
    }
  }
}
