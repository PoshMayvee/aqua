package aqua.builder

import aqua.backend.*
import aqua.io.OutputPrinter
import aqua.js.{CallJsFunction, FluencePeer, ServiceHandler}
import aqua.raw.ops.{Call, CallArrowRawTag}
import aqua.raw.value.{LiteralRaw, VarRaw}
import aqua.definitions.*
import aqua.types.ScalarType
import cats.data.NonEmptyList

import scala.scalajs.js
import scala.scalajs.js.{Dynamic, JSON}

// Function to print any variables that passed as arguments
abstract class ResultPrinter(serviceId: String, functions: NonEmptyList[AquaFunction])
    extends Service(serviceId, functions) {

  def callTag(variables: List[VarRaw]): CallArrowRawTag
}

object ResultPrinter {

  private def resultPrinterFunc(funcName: String, resultNames: List[String]) = new AquaFunction {
    override def fnName: String = funcName

    override def handler: ServiceHandler = varArgs => {
      // drop last argument (tetraplets)
      val args: Seq[js.Any] = varArgs.init
      val toPrint = args.toList match {
        case arg :: Nil => JSON.stringify(arg, space = 2)
        case _ => args.map(a => JSON.stringify(a, space = 2)).mkString("[\n", ",\n", "\n]")
      }

      // if an input function returns a result, our success will be after it is printed
      // otherwise finish after JS SDK will finish sending a request
      OutputPrinter.print(toPrint)
      // empty JS object
      js.Promise.resolve(Service.emptyObject)
    }

    def arrow: ArrowTypeDef = ArrowTypeDef(
      LabeledProductTypeDef(resultNames.map(n => (n, TopTypeDef))),
      NilTypeDef
    )
  }

  def apply(serviceId: String, fnName: String, resultNames: List[String]): ResultPrinter = {
    val funcs = NonEmptyList.one(resultPrinterFunc(fnName, resultNames))
    new ResultPrinter(serviceId, funcs) {
      def callTag(variables: List[VarRaw]): CallArrowRawTag =
        CallArrowRawTag.service(
          LiteralRaw.quote(serviceId),
          fnName,
          Call(variables, Nil)
        )
    }
  }

}
