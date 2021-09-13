package aqua.backend.js

import aqua.backend.air.FuncAirGen
import aqua.model.transform.res.FuncRes
import aqua.types.*
import cats.syntax.show.*
import aqua.model.transform.res.ServiceRes

case class JavaScriptService(srv: ServiceRes) {

  import JavaScriptCommon._

  def fnHandler(arrow: ArrowType, memberName: String) = {
    s"""
       | if (req.fnName === '${memberName}') {
       |     ${callBackExprBody(arrow, "service." + memberName)}
       | }
    """.stripMargin
  }

  def generate: String =
    val fnHandlers = srv.members
      .map{ case (name, arrow) =>
        fnHandler(arrow, name)
      }
      .mkString("\n\n")

    val registerName = s"register${srv.name}"

    val defaultServiceIdBranch = srv.defaultId.fold("")(x => 
      s""" 
      | else {
      |     serviceId = ${x}
      |}""".stripMargin
    )

    s"""
      | export function ${registerName}(...args) {
      |    let peer;
      |    let serviceId;
      |    let service;
      |    if (FluencePeer.isInstance(args[0])) {
      |        peer = args[0];
      |    } else {
      |        peer = Fluence.getPeer();
      |    }
      |
      |    if (typeof args[0] === 'string') {
      |        serviceId = args[0];
      |    } else if (typeof args[1] === 'string') {
      |        serviceId = args[1];
      |    } ${defaultServiceIdBranch}
      |    
      |    // Figuring out which overload is the service.
      |    // If the first argument is not Fluence Peer and it is an object, then it can only be the service def
      |    // If the first argument is peer, we are checking further. The second argument might either be
      |    // an object, that it must be the service object
      |    // or a string, which is the service id. In that case the service is the third argument
      |    if (!(FluencePeer.isInstance(args[0])) && typeof args[0] === 'object') {
      |        service = args[0];
      |    } else if (typeof args[1] === 'object') {
      |        service = args[1];
      |    } else {
      |        service = args[2];
      |    }
      |
      |      peer.internals.callServiceHandler.use((req, resp, next) => {
      |          if (req.serviceId !== serviceId) {
      |              next();
      |              return;
      |          }
      |  
      |          ${fnHandlers}
      |  
      |          next();
      |      });
      | }
      """.stripMargin
}