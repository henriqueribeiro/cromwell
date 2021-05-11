package cromwell.services.metadata.impl

import akka.actor.SupervisorStrategy.{Decider, Directive, Escalate, Resume}
import akka.actor.{Actor, ActorContext, ActorInitializationException, ActorLogging, ActorRef, Cancellable, OneForOneStrategy, Props}
import akka.routing.Listen
import cats.data.NonEmptyList
import com.typesafe.config.Config
import common.exception.AggregatedMessageException
import common.validation.Validation._
import cromwell.core.Dispatcher.ServiceDispatcher
import cromwell.core.{LoadConfig, WorkflowId}
import cromwell.services.MetadataServicesStore
import cromwell.services.metadata.MetadataArchiveStatus
import cromwell.services.metadata.MetadataService._
import cromwell.services.metadata.impl.MetadataSummaryRefreshActor.{MetadataSummaryFailure, MetadataSummarySuccess, SummarizeMetadata}
import cromwell.services.metadata.impl.archiver.{ArchiveMetadataConfig, ArchiveMetadataSchedulerActor}
import cromwell.services.metadata.impl.builder.MetadataBuilderActor
import cromwell.services.metadata.impl.deleter.{DeleteMetadataActor, DeleteMetadataConfig}
import cromwell.util.GracefulShutdownHelper
import cromwell.util.GracefulShutdownHelper.ShutdownCommand
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object MetadataServiceActor {
  val MetadataInstrumentationPrefix = NonEmptyList.of("metadata")

  def props(serviceConfig: Config, globalConfig: Config, serviceRegistryActor: ActorRef) = Props(MetadataServiceActor(serviceConfig, globalConfig, serviceRegistryActor)).withDispatcher(ServiceDispatcher)
}

case class MetadataServiceActor(serviceConfig: Config, globalConfig: Config, serviceRegistryActor: ActorRef)
  extends Actor with ActorLogging with MetadataDatabaseAccess with MetadataServicesStore with GracefulShutdownHelper {

  private val decider: Decider = {
    case _: ActorInitializationException => Escalate
    case _ => Resume
  }

  override val supervisorStrategy = new OneForOneStrategy()(decider) {
    override def logFailure(context: ActorContext, child: ActorRef, cause: Throwable, decision: Directive) = {
      val childName = if (child == readActor) "Read" else "Write"
      log.error(cause, s"The $childName Metadata Actor died unexpectedly, metadata events might have been lost. Restarting it...")
    }
  }

  private val metadataSummaryRefreshInterval: Option[FiniteDuration] = {
    val duration = serviceConfig.getOrElse[Duration]("metadata-summary-refresh-interval", default = 1 second)
    if (duration.isFinite()) Option(duration.asInstanceOf[FiniteDuration]) else None
  }

  private val metadataSummaryRefreshLimit = serviceConfig.getOrElse("metadata-summary-refresh-limit", default = 5000)

  private val metadataReadTimeout: Duration =
    serviceConfig.getOrElse[Duration]("metadata-read-query-timeout", Duration.Inf)
  private val metadataReadRowNumberSafetyThreshold: Int =
    serviceConfig.getOrElse[Int]("metadata-read-row-number-safety-threshold", 3000000)

  def readMetadataWorkerActorProps(): Props =
    ReadDatabaseMetadataWorkerActor
      .props(metadataReadTimeout, metadataReadRowNumberSafetyThreshold)
      .withDispatcher(ServiceDispatcher)

  def metadataBuilderActorProps(): Props = MetadataBuilderActor
    .props(readMetadataWorkerActorProps, metadataReadRowNumberSafetyThreshold)
    .withDispatcher(ServiceDispatcher)

  val readActor = context.actorOf(ReadMetadataRegulatorActor.props(metadataBuilderActorProps, readMetadataWorkerActorProps), "ClassicMSA-ReadMetadataRegulatorActor")

  val dbFlushRate = serviceConfig.getOrElse("db-flush-rate", 5.seconds)
  val dbBatchSize = serviceConfig.getOrElse("db-batch-size", 200)
  val writeActor = context.actorOf(WriteMetadataActor.props(dbBatchSize, dbFlushRate, serviceRegistryActor, LoadConfig.MetadataWriteThreshold), "WriteMetadataActor")

  implicit val ec = context.dispatcher
  //noinspection ActorMutableStateInspection
  private var summaryRefreshCancellable: Option[Cancellable] = None

  private val summaryActor: Option[ActorRef] = buildSummaryActor

  summaryActor foreach { _ => self ! RefreshSummary }

  private val archiveMetadataActor: Option[ActorRef] = buildArchiveMetadataActor

  private val deleteMetadataActor: Option[ActorRef] = buildDeleteMetadataActor

  private def scheduleSummary(): Unit = {
    metadataSummaryRefreshInterval foreach { interval =>
      summaryRefreshCancellable = Option(context.system.scheduler.scheduleOnce(interval, self, RefreshSummary)(context.dispatcher, self))
    }
  }

  override def postStop(): Unit = {
    summaryRefreshCancellable foreach { _.cancel() }
    super.postStop()
  }

  private def buildSummaryActor: Option[ActorRef] = {
    val actor = metadataSummaryRefreshInterval map {
      _ => context.actorOf(MetadataSummaryRefreshActor.props(serviceRegistryActor), "metadata-summary-actor")
    }
    val message = metadataSummaryRefreshInterval match {
      case Some(interval) => s"Metadata summary refreshing every $interval."
      case None => "Metadata summary refresh is off."
    }
    log.info(message)
    actor
  }

  private def buildArchiveMetadataActor: Option[ActorRef] = {
    if (serviceConfig.hasPath("archive-metadata")) {
      log.info("Building metadata archiver from config")
      ArchiveMetadataConfig.parseConfig(serviceConfig.getConfig("archive-metadata"))(context.system) match {
        case Right(config) => Option(context.actorOf(ArchiveMetadataSchedulerActor.props(config, serviceRegistryActor), "archive-metadata-scheduler"))
        case Left(errorList) => throw AggregatedMessageException("Failed to parse the archive-metadata config", errorList.toList)
      }
    } else {
      log.info("No metadata archiver defined in config")
      None
    }
  }

  private def buildDeleteMetadataActor: Option[ActorRef] = {
    if (serviceConfig.hasPath("delete-metadata")) {
      log.info("Building metadata deleter from config")
      DeleteMetadataConfig.parseConfig(serviceConfig.getConfig("delete-metadata")) match {
        case Right(config) => Option(context.actorOf(DeleteMetadataActor.props(config, serviceRegistryActor), "delete-metadata-actor"))
        case Left(errorList) => throw AggregatedMessageException("Failed to parse the archive-metadata config", errorList.toList)
      }
    } else {
      log.info("No metadata deleter defined in config")
      None
    }
  }

  private def validateWorkflowIdInMetadata(possibleWorkflowId: WorkflowId, sender: ActorRef): Unit = {
    workflowWithIdExistsInMetadata(possibleWorkflowId.toString) onComplete {
      case Success(true) => sender ! RecognizedWorkflowId
      case Success(false) => sender ! UnrecognizedWorkflowId
      case Failure(e) => sender ! FailedToCheckWorkflowId(new RuntimeException(s"Failed lookup attempt for workflow ID $possibleWorkflowId", e))
    }
  }

  private def validateWorkflowIdInMetadataSummaries(possibleWorkflowId: WorkflowId, sender: ActorRef): Unit = {
    workflowWithIdExistsInMetadataSummaries(possibleWorkflowId.toString) onComplete {
      case Success(true) => sender ! RecognizedWorkflowId
      case Success(false) => sender ! UnrecognizedWorkflowId
      case Failure(e) => sender ! FailedToCheckWorkflowId(new RuntimeException(s"Failed lookup attempt for workflow ID $possibleWorkflowId", e))
    }
  }

  private def fetchWorkflowMetadataArchiveStatus(workflowId: WorkflowId, sender: ActorRef): Unit = {
    getMetadataArchiveStatus(workflowId) onComplete {
      case Success(status) =>
        MetadataArchiveStatus.fromDatabaseValue(status).toTry match {
          case Success(archiveStatus) => sender ! WorkflowMetadataArchivedStatus(archiveStatus)
          case Failure(e) => sender ! FailedToGetArchiveStatus(new RuntimeException(s"Failed to get metadata archive status for workflow ID $workflowId", e))
        }
      case Failure(e) => sender ! FailedToGetArchiveStatus(new RuntimeException(s"Failed to get metadata archive status for workflow ID $workflowId", e))
    }
  }

  def summarizerReceive: Receive = {
    case RefreshSummary => summaryActor foreach { _ ! SummarizeMetadata(metadataSummaryRefreshLimit, sender()) }
    case MetadataSummarySuccess => scheduleSummary()
    case MetadataSummaryFailure(t) =>
      log.error(t, "Error summarizing metadata")
      scheduleSummary()
  }

  def receive = summarizerReceive orElse {
    case ShutdownCommand => waitForActorsAndShutdown(NonEmptyList.of(writeActor) ++ archiveMetadataActor.toList ++ deleteMetadataActor.toList)
    case action: PutMetadataAction => writeActor forward action
    case action: PutMetadataActionAndRespond => writeActor forward action
    // Assume that listen messages are directed to the write metadata actor
    case listen: Listen => writeActor forward listen
    case v: ValidateWorkflowIdInMetadata => validateWorkflowIdInMetadata(v.possibleWorkflowId, sender())
    case v: ValidateWorkflowIdInMetadataSummaries => validateWorkflowIdInMetadataSummaries(v.possibleWorkflowId, sender())
    case g: FetchWorkflowMetadataArchiveStatus => fetchWorkflowMetadataArchiveStatus(g.workflowId, sender())
    case action: BuildMetadataJsonAction => readActor forward action
    case streamAction: GetMetadataStreamAction => readActor forward streamAction
  }
}
