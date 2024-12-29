package cromwell.backend.impl.aws

import cromwell.backend.ReadLikeFunctions
//import cromwell.core.io.AsyncIoFunctions
//import cromwell.core.path.PathFactory
import scala.concurrent.Future
import scala.util.Try
import cromwell.core.path.{DefaultPathBuilder, PathBuilder, PathFactory}
//import cromwell.backend.impl.aws.AwsBatchBackendInitializationData


trait AwsReadLikeFunctions extends ReadLikeFunctions {
  
  override def readFile(path: String, maxBytes: Option[Int], failOnOverflow: Boolean): Future[String] = {
    // get backend config
    //val sp = stardardParams
    //val ec = sp.executionContext
    //println(s"standardParams: $ec")
    //val initializationData = BackendInitializationData.as[AwsBatchBackendInitializationData](standardParams.backendInitializationDataOption)
    //val backendConfig = initializationData.configuration
    // Implement your custom logic here
    println("in AwsReadLikeFunctions.readFile")
    println(s"raw path : $path")
    val pathBuilders: List[PathBuilder] = List(DefaultPathBuilder)
    val AwsPath = PathFactory.buildPath(path, pathBuilders)
    println(s"aws path : $AwsPath")
    Future.fromTry(Try(AwsPath)) flatMap { p => asyncIo.contentAsStringAsync(p, maxBytes, failOnOverflow) }
  }
}