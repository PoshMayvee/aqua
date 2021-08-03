package aqua

import aqua.backend.ts.TypeScriptBackend
import aqua.files.AquaFilesIO
import aqua.model.transform.GenerationConfig
import cats.data.Validated
import cats.effect.{IO, IOApp, Sync}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.file.Paths

object Test extends IOApp.Simple {

  implicit def logger[F[_]: Sync]: SelfAwareStructuredLogger[F] =
    Slf4jLogger.getLogger[F]

  implicit val aio: AquaIO[IO] = new AquaFilesIO[IO]

  override def run: IO[Unit] =
    AquaPathCompiler
      .compileFilesTo[IO](
        Paths.get("./aqua-src"),
        List(Paths.get("./aqua")),
        Paths.get("./target"),
        TypeScriptBackend,
        GenerationConfig()
      )
      .map {
        case Validated.Invalid(errs) =>
          errs.map(System.err.println)
        case Validated.Valid(res) =>
          res.map(println)
      }

}
