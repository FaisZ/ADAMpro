package ch.unibas.dmi.dbis.adam.client

import ch.unibas.dmi.dbis.adam.client.grpc.RPCClient
import ch.unibas.dmi.dbis.adam.client.web.{AdamController, WebServer}

/**
  * adamtwo
  *
  * Ivan Giangreco
  * March 2016
  */
object ClientStartupMain {
  val httpPort = 9099

  val grpcHost = "localhost"
  val grpcPort = 5890

  def main(args: Array[String]): Unit = {
    val grpc = RPCClient(grpcHost, grpcPort)
    val web = new WebServer(httpPort, new AdamController(grpc)).main(Array[String]())
  }
}
