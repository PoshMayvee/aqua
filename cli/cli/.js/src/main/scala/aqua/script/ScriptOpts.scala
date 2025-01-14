package aqua.script

import aqua.*
import aqua.ArgOpts.{dataFileOrStringOpt, funcOpt, funcWithArgsOpt}
import aqua.backend.Generated
import aqua.backend.air.{AirBackend, AirGen, FuncAirGen}
import aqua.builder.ArgumentGetter
import aqua.compiler.AquaCompiler
import aqua.js.VarJson
import aqua.io.{PackagePath, Prelude, RelativePath}
import aqua.ipfs.js.IpfsApi
import aqua.keypair.KeyPairShow.show
import aqua.model.transform.{Transform, TransformConfig}
import aqua.model.{AquaContext, FuncArrow, LiteralModel}
import aqua.parser.lift.FileSpan
import aqua.raw.ops.{Call, CallArrowRawTag}
import aqua.raw.value.{LiteralRaw, ValueRaw, VarRaw}
import aqua.res.{AquaRes, FuncRes}
import aqua.run.RunOpts.logger
import aqua.run.{
  CliFunc,
  FuncCompiler,
  GeneralOptions,
  GeneralOpts,
  RunCommand,
  RunConfig,
  RunOpts
}
import aqua.types.{ArrowType, LiteralType, NilType, ScalarType}
import cats.data.*
import cats.data.Validated.{invalid, invalidNec, valid, validNec, validNel}
import cats.effect.kernel.{Async, Clock}
import cats.effect.{Concurrent, ExitCode, Resource, Sync}
import cats.syntax.applicative.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import cats.syntax.traverse.*
import cats.{Applicative, Monad}
import com.monovore.decline.{Command, Opts}
import fs2.io.file.{Files, Path}
import scribe.Logging

import scala.concurrent.ExecutionContext
import scala.scalajs.js

object ScriptOpts extends Logging {

  val ScriptAqua = "aqua/script.aqua"

  val AddFuncName = "schedule"
  val RemoveFuncName = "remove"
  val ListFuncName = "list"

  case class FuncWithLiteralArgs(func: CliFunc, args: List[LiteralRaw])

  // Func with only literal arguments (strings, booleans or numbers)
  def funcWithLiteralsOpt[F[_]: Files: Concurrent]
    : Opts[F[ValidatedNec[String, FuncWithLiteralArgs]]] = {
    (dataFileOrStringOpt[F], funcOpt).mapN { case (dataF, func) =>
      dataF.map { dataV =>
        dataV.andThen { data =>
          resolveOnlyLiteralsFromData(func.args, data).map { literals =>
            FuncWithLiteralArgs(func, literals)
          }
        }
      }
    }
  }

  private def resolveOnlyLiteralsFromData(
    args: List[ValueRaw],
    data: Option[js.Dynamic]
  ): ValidatedNec[String, List[LiteralRaw]] = {
    val literals = args.map {
      case l: LiteralRaw => validNec(l) // TODO handle CollectionRaw?
      case v @ VarRaw(name, _) =>
        data.map { d =>
          val arg = d.selectDynamic(name)
          js.typeOf(arg) match {
            case "number" => validNec(LiteralRaw(arg.toString, LiteralType.number))
            case "string" => validNec(LiteralRaw(arg.toString, LiteralType.string))
            case "boolean" => validNec(LiteralRaw(arg.toString, LiteralType.bool))
            case t =>
              invalidNec(
                s"Scheduled script functions support 'string', 'boolean' and 'number' argument types only"
              )
          }
        }.getOrElse(invalidNec(s"There is no '$name' argument in data"))
      case _ =>
        invalidNec(
          s"Scheduled script functions support 'string', 'boolean' and 'number' argument types only"
        )
    }

    literals.traverse(identity)
  }

  def scriptOpt[F[_]: Async: AquaIO]: Command[F[ValidatedNec[String, Unit]]] =
    CommandBuilder(
      name = "script",
      header = "Manage scheduled scripts",
      NonEmptyList(add, list :: remove :: Nil)
    ).command

  def intervalOpt: Opts[Option[Int]] =
    AppOpts.wrapWithOption(
      Opts
        .option[Int]("interval", "Indicating how often the script will run in seconds", "n")
    )

  def scriptIdOpt: Opts[String] =
    Opts
      .option[String]("script-id", "Script id to remove", "c")

  def generateAir(callable: FuncArrow, transformConfig: TransformConfig): String = {
    val funcRes = Transform.funcRes(callable, transformConfig).value
    AirGen(funcRes.body).generate.show
  }

  private def commonScriptOpts = GeneralOpts.commonOpt(false, true, true)

  private def compileAir[F[_]: Async: AquaIO](
    input: Path,
    imports: List[Path],
    funcWithArgs: FuncWithLiteralArgs
  ): F[ValidatedNec[String, String]] = {
    val tConfig = TransformConfig(relayVarName = None, wrapWithXor = false)
    val funcCompiler =
      new FuncCompiler[F](
        Option(RelativePath(input)),
        imports,
        tConfig
      )

    val funcName = funcWithArgs.func.name

    for {
      prelude <- Prelude.init[F](true)
      contextV <- funcCompiler.compile(prelude.importPaths)
      wrappedBody = CallArrowRawTag.func(funcName, Call(funcWithArgs.func.args, Nil)).leaf
      result = contextV
        .andThen(context => FuncCompiler.findFunction(context, funcWithArgs.func))
        .map { callable =>
          generateAir(
            FuncArrow(
              funcName + "_scheduled",
              wrappedBody,
              ArrowType(NilType, NilType),
              Nil,
              Map(funcName -> callable),
              Map.empty,
              None
            ),
            tConfig
          )
        }
    } yield result
  }

  def add[F[_]: Async: AquaIO]: SubCommandBuilder[F] =
    SubCommandBuilder.applyF(
      name = "add",
      header = "Upload aqua function as a scheduled script.",
      (
        commonScriptOpts,
        scheduleOptsCompose[F],
        intervalOpt
      ).mapN { (common, optionsF, intervalOp) =>
        val res: F[ValidatedNec[String, RunInfo]] = optionsF
          .flatMap(
            _.map { case (input, imports, funcWithArgs) =>
              val intervalArg =
                intervalOp
                  .map(i => LiteralRaw(i.toString, LiteralType.number))
                  .getOrElse(ValueRaw.Nil)

              val someRes: F[ValidatedNec[String, RunInfo]] = for {
                scriptV <- compileAir(input, imports, funcWithArgs)
                result: ValidatedNec[String, RunInfo] = scriptV.map { script =>
                  val scriptVar = VarRaw("script", ScalarType.string)
                  RunInfo(
                    common,
                    CliFunc(AddFuncName, scriptVar :: intervalArg :: Nil),
                    Option(PackagePath(ScriptAqua)),
                    Nil,
                    Map(
                      "script" -> VarJson(
                        scriptVar,
                        // hack, cannot create unnamed Dynamic
                        // TODO: fix it
                        scalajs.js.Dynamic.literal("script" -> script).selectDynamic("script")
                      )
                    )
                  )
                }
              } yield {
                result
              }

              someRes
            }.fold(
              errs => Validated.Invalid[NonEmptyChain[String]](errs).pure[F],
              identity
            )
          )
        res
      }
    )

  def scheduleOptsCompose[F[_]: Files: Async]
    : Opts[F[ValidatedNec[String, (Path, List[Path], FuncWithLiteralArgs)]]] = {
    (AppOpts.inputOpts[F], AppOpts.importOpts[F], funcWithLiteralsOpt[F]).mapN {
      case (inputF, importF, funcWithLiteralsF) =>
        for {
          inputV <- inputF
          importV <- importF
          funcWithLiteralsV <- funcWithLiteralsF
        } yield {
          (inputV, importV, funcWithLiteralsV).mapN { case (i, im, f) =>
            (i, im, f)
          }
        }
    }
  }

  // Removes scheduled script from a node
  def remove[F[_]: Async]: SubCommandBuilder[F] =
    SubCommandBuilder.valid[F](
      "remove",
      "Remove a script from a remote peer",
      (
        commonScriptOpts,
        scriptIdOpt
      ).mapN { (common, scriptId) =>
        RunInfo(
          common,
          CliFunc(RemoveFuncName, LiteralRaw.quote(scriptId) :: Nil),
          Option(PackagePath(ScriptAqua))
        )
      }
    )

  // Print all scheduled scripts
  def list[F[_]: Async]: SubCommandBuilder[F] =
    SubCommandBuilder
      .simple[F]("list", "Print all scheduled scripts", PackagePath(ScriptAqua), ListFuncName)
}
