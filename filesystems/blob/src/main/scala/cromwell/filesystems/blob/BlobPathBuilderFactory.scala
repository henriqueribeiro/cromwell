package cromwell.filesystems.blob

import akka.actor.ActorSystem
import com.typesafe.config.Config
import cromwell.core.WorkflowOptions
import cromwell.core.path.PathBuilderFactory
import cromwell.core.path.PathBuilderFactory.PriorityBlob

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

final case class SubscriptionId(value: UUID) {override def toString: String = value.toString}
final case class BlobContainerName(value: String) {override def toString: String = value}
final case class StorageAccountName(value: String) {override def toString: String = value}
final case class EndpointURL(value: String) {override def toString: String = value}
final case class WorkspaceId(value: UUID) {override def toString: String = value.toString}
final case class ContainerResourceId(value: UUID) {override def toString: String = value.toString}
final case class WorkspaceManagerURL(value: String) {override def toString: String = value}

final case class BlobPathBuilderFactory(globalConfig: Config, instanceConfig: Config, fsm: BlobFileSystemManager) extends PathBuilderFactory {

  override def withOptions(options: WorkflowOptions)(implicit as: ActorSystem, ec: ExecutionContext): Future[BlobPathBuilder] = {
    Future {
      new BlobPathBuilder(fsm.container, fsm.endpoint)(fsm)
    }
  }

  override def priority: Int = PriorityBlob
}

sealed trait BlobTokenGenerator {
  def getAccessToken: String
}

object BlobTokenGenerator {
  def createBlobTokenGenerator(container: String, endpoint: String): BlobTokenGenerator = {
    createBlobTokenGenerator(container, endpoint, None, None)
  }
  def createBlobTokenGenerator(container: String, endpoint: String, workspaceId: Option[String], workspaceManagerURL: Option[String]): BlobTokenGenerator = {
     (container: String, endpoint: String, workspaceId, workspaceManagerURL) match {
       case (container, endpoint, None, None) =>
         NativeBlobTokenGenerator(container, endpoint)
       case (container, endpoint, Some(workspaceId), Some(workspaceManagerURL)) =>
         WSMBlobTokenGenerator(container, endpoint, workspaceId, workspaceManagerURL)
       case _ =>
         throw new Exception("Arguments provided do not match any available BlobTokenGenerator implementation.")
     }
  }
}

case class WSMBlobTokenGenerator(container: String, endpoint: String, workspaceId: String, workspaceManagerURL: String) extends BlobTokenGenerator {
  def getAccessToken: String = {
    throw new NotImplementedError
  }
}

case class NativeBlobTokenGenerator(container: String, endpoint: String) extends BlobTokenGenerator {
  def getAccessToken: String = {
    val storageAccountName = BlobPathBuilder.parseStorageAccount(BlobPathBuilder.parseURI(endpoint)) match {
      case Some(storageAccountName) => storageAccountName
      case _ => throw new Exception("Storage account could not be parsed from endpoint")
    }

    val profile = new AzureProfile(AzureEnvironment.AZURE)
    val azureCredential = new DefaultAzureCredentialBuilder()
      .authorityHost(profile.getEnvironment.getActiveDirectoryEndpoint)
      .build
    val azure = AzureResourceManager.authenticate(azureCredential, profile).withDefaultSubscription

    val storageAccounts = azure.storageAccounts()
    val storageAccount = storageAccounts
      .list()
      .asScala
      .find(_.name == storageAccountName)

    val storageAccountKeys = storageAccount match {
      case Some(value) => value.getKeys.asScala.map(_.value())
      case _ => throw new Exception("Storage Account not found")
    }

    val storageAccountKey = storageAccountKeys.headOption match {
      case Some(value) => value
      case _ => throw new Exception("Storage Account has no keys")
    }

    val keyCredential = new StorageSharedKeyCredential(
      storageAccountName,
      storageAccountKey
    )
    val blobContainerClient = new BlobContainerClientBuilder()
      .credential(keyCredential)
      .endpoint(endpoint)
      .containerName(container)
      .buildClient()

    val blobContainerSasPermission = new BlobContainerSasPermission()
      .setReadPermission(true)
      .setCreatePermission(true)
      .setListPermission(true)
    val blobServiceSasSignatureValues = new BlobServiceSasSignatureValues(
      OffsetDateTime.now.plusDays(1),
      blobContainerSasPermission
    )

    blobContainerClient.generateSas(blobServiceSasSignatureValues)
  }
}
