package aqua

import aqua.js.{LogLevel, FluenceJSLogLevel}
import fs2.io.file.Path
import scribe.Level

import scala.util.Try

object LogLevelTransformer {

  def logLevelToAvm(logLevel: Level): LogLevel = {
    logLevel match {
      case Level.Trace => "trace"
      case Level.Debug => "debug"
      case Level.Info => "info"
      case Level.Warn => "warn"
      case Level.Error => "error"
      case Level.Fatal => "off"
      case _ => "info"
    }
  }

  def logLevelToFluenceJS(logLevel: Level): FluenceJSLogLevel = {
    logLevel match {
      case Level.Trace => "trace"
      case Level.Debug => "debug"
      case Level.Info => "info"
      case Level.Warn => "warn"
      case Level.Error => "error"
      case Level.Fatal => "silent"
      case _ => "info"
    }
  }
}
