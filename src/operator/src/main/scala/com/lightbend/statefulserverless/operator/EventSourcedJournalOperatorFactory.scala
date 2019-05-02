package com.lightbend.statefulserverless.operator

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import akka.stream.Materializer
import com.lightbend.statefulserverless.operator.EventSourcedJournal.Resource
import skuber.LabelSelector.dsl._
import skuber._
import skuber.api.client.KubernetesClient

import scala.concurrent.{ExecutionContext, Future}
import skuber.apps.v1.DeploymentList
import skuber.apps.v1.Deployment

class EventSourcedJournalOperatorFactory(implicit mat: Materializer, ec: ExecutionContext) extends
  OperatorFactory[EventSourcedJournal.Status, EventSourcedJournal.Resource] {

  import OperatorConstants._

  val CassandraJournalImage = sys.env.getOrElse("CASSANDRA_JOURNAL_IMAGE", "lightbend-docker-registry.bintray.io/octo/stateful-serverless-cassandra-backend:latest")

  override def apply(client: KubernetesClient): Operator = new EventSourcedJournalOperator(client)

  class EventSourcedJournalOperator(client: KubernetesClient) extends Operator {

    override def hasAnythingChanged(resource: Resource): Boolean = {
      (for {
        status <- resource.status
        specHash <- status.specHash
      } yield {
        if (hashOf(resource.spec) != specHash) {
          true
        } else {
          if (status.reason.isDefined &&
            status.lastApplied.getOrElse(Instant.EPOCH).plus(1, ChronoUnit.MINUTES).isBefore(Instant.now())) {
            true
          } else {
            false
          }
        }
      }).getOrElse(true)
    }

    private def errorStatus(reason: String, resource: Option[Resource]) = EventSourcedJournal.Status(
      specHash = None,
      image = None,
      sidecarEnv = None,
      reason = Some(reason),
      lastApplied = Some(Instant.now())
    )

    private def updateStatus(resource: Resource, status: EventSourcedJournal.Status): Future[Done] = {
      client.updateStatus(resource.withStatus(status))
        .map(_ => Done)
    }

    override def handleChanged(resource: Resource): Future[Option[EventSourcedJournal.Status]] = {
      val status = resource.spec.`type` match {
        case `CassandraJournalType` =>
          resource.spec.deployment match {
            case `UnmanagedJournalDeployment` =>
              (resource.spec.config \ "service").asOpt[String] match {
                case Some(contactPoints) =>
                  EventSourcedJournal.Status(
                    specHash = Some(hashOf(resource.spec)),
                    image = Some(CassandraJournalImage),
                    sidecarEnv = Some(List(
                      EnvVar("CASSANDRA_CONTACT_POINTS", contactPoints)
                    )),
                    reason = None,
                    lastApplied = Some(Instant.now())
                  )
                case None => errorStatus("No service name declared in unmanaged Cassandra journal", Some(resource))
              }
            case unknown => errorStatus(s"Unknown Cassandra deployment type: $unknown", Some(resource))
          }
        case unknown => errorStatus(s"Unknown journal type: $unknown", Some(resource))
      }

      if (status.reason.isEmpty && status.specHash.isDefined) {
        // We have to first update our own status before we update our dependents, since they depend on our updated status
        for {
          _ <- updateStatus(resource, status)
          _ <- updateDependents(resource.name, _.copy(journalConfigHash = status.specHash))
        } yield None
      } else {
        Future.successful(Some(status))
      }
    }

    override def handleDeleted(resource: Resource): Future[Done] = {
      updateDependents(resource.name, _.copy(journalConfigHash = None))
    }

    private def updateDependents(name: String, update: EventSourcedService.Status => EventSourcedService.Status) = {

      (for {
        deployments <- client.listSelected[DeploymentList](LabelSelector(
          JournalLabel is name
        ))
        _ <- Future.sequence(deployments.map(deployment => updateServiceForDeployment(deployment, update)))
      } yield Done).recover {
        case error =>
          println("Error while attempting to update dependent service configuration resource, ignoring")
          error.printStackTrace()
          Done
      }
    }

    private def updateServiceForDeployment(deployment: Deployment,
      update: EventSourcedService.Status => EventSourcedService.Status): Future[Done] = {

      for {
        maybeService <- deployment.metadata.labels.get(EventSourcedLabel).map { serviceName =>
          client.getOption[EventSourcedService.Resource](serviceName)
        }.getOrElse(Future.successful(None))
        _ <- maybeService match {
          case Some(service) =>
            val status = service.status.getOrElse(
              EventSourcedService.Status(None, None, None, None, None)
            )
            client.updateStatus(service.withStatus(update(status)))
          case None =>
            Future.successful(Done)
        }
      } yield Done
    }

    override def statusFromError(error: Throwable, existing: Option[Resource]): EventSourcedJournal.Status = {
      errorStatus("Unknown operator error: " + error, existing)
    }
  }
}