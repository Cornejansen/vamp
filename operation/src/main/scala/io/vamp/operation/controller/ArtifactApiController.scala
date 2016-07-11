package io.vamp.operation.controller

import java.net.URLDecoder

import _root_.io.vamp.common.notification.NotificationProvider
import _root_.io.vamp.operation.gateway.GatewayActor
import _root_.io.vamp.operation.workflow.WorkflowActor
import _root_.io.vamp.operation.workflow.WorkflowActor.Schedule
import akka.pattern.ask
import akka.util.Timeout
import io.vamp.common.akka.IoC._
import io.vamp.common.akka.{ ActorSystemProvider, ExecutionContextProvider }
import io.vamp.model.artifact._
import io.vamp.model.reader.{ YamlReader, _ }
import io.vamp.model.workflow.{ ScheduledWorkflow, Workflow }
import io.vamp.operation.notification.{ InconsistentArtifactName, UnexpectedArtifact }
import io.vamp.persistence.db._
import io.vamp.persistence.notification.PersistenceOperationFailure

import scala.concurrent.Future

trait ArtifactApiController extends ArtifactExpansionSupport {
  this: ExecutionContextProvider with NotificationProvider with ActorSystemProvider ⇒

  def background(artifact: String): Boolean = !crud(artifact)

  def allArtifacts(kind: String, expandReferences: Boolean, onlyReferences: Boolean)(page: Int, perPage: Int)(implicit timeout: Timeout): Future[ArtifactResponseEnvelope] = `type`(kind) match {
    case (t, _) if t == classOf[Deployment] ⇒ Future(ArtifactResponseEnvelope(Nil, 0, 1, ArtifactResponseEnvelope.maxPerPage))
    case (t, _) ⇒
      actorFor[PersistenceActor] ? PersistenceActor.All(t, page, perPage, expandReferences, onlyReferences) map {
        case envelope: ArtifactResponseEnvelope ⇒ envelope
        case other                              ⇒ throwException(PersistenceOperationFailure(other))
      }
  }

  def createArtifact(kind: String, source: String, validateOnly: Boolean)(implicit timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, r) if t == classOf[Gateway] ⇒
      expandGateway(r.read(source).asInstanceOf[Gateway]) flatMap {
        case gateway ⇒ actorFor[GatewayActor] ? GatewayActor.Create(gateway, Option(source), validateOnly)
      }

    case (t, _) if t == classOf[Deployment] ⇒ throwException(UnexpectedArtifact(kind))

    case (t, r) if t == classOf[ScheduledWorkflow] ⇒
      create(r, source, validateOnly).map {
        case list: List[_] ⇒
          list.filter(_.isInstanceOf[ScheduledWorkflow]).foreach(workflow ⇒ actorFor[WorkflowActor] ! Schedule(workflow.asInstanceOf[ScheduledWorkflow]))
          list
        case any ⇒ any
      }

    case (_, r) ⇒ create(r, source, validateOnly)
  }

  def readArtifact(kind: String, name: String, expandReferences: Boolean, onlyReferences: Boolean)(implicit timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, _) if t == classOf[Gateway]    ⇒ actorFor[PersistenceActor] ? PersistenceActor.Read(URLDecoder.decode(name, "UTF-8"), t, expandReferences, onlyReferences)
    case (t, _) if t == classOf[Deployment] ⇒ Future.successful(None)
    case (t, _)                             ⇒ read(t, name, expandReferences, onlyReferences)
  }

  def updateArtifact(kind: String, name: String, source: String, validateOnly: Boolean)(implicit timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, r) if t == classOf[Gateway] ⇒

      expandGateway(r.read(source).asInstanceOf[Gateway]) flatMap {
        case gateway ⇒
          if (name != gateway.name) throwException(InconsistentArtifactName(name, gateway))
          actorFor[GatewayActor] ? GatewayActor.Update(gateway, Option(source), validateOnly, promote = true)
      }

    case (t, r) if t == classOf[Deployment] ⇒ throwException(UnexpectedArtifact(kind))

    case (t, r) if t == classOf[ScheduledWorkflow] ⇒

      update(r, name, source, validateOnly).map {
        case list: List[_] ⇒
          list.filter(_.isInstanceOf[ScheduledWorkflow]).foreach(workflow ⇒ actorFor[WorkflowActor] ! Schedule(workflow.asInstanceOf[ScheduledWorkflow]))
          list
        case any ⇒ any
      }

    case (_, r) ⇒ update(r, name, source, validateOnly)
  }

  def deleteArtifact(kind: String, name: String, source: String, validateOnly: Boolean)(implicit timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, r) if t == classOf[Gateway] ⇒
      actorFor[GatewayActor] ? GatewayActor.Delete(name, validateOnly)

    case (t, r) if t == classOf[Deployment] ⇒ Future.successful(None)

    case (t, r) if t == classOf[ScheduledWorkflow] ⇒
      read(t, name, expandReferences = false, onlyReferences = false) map {
        case Some(workflow: ScheduledWorkflow) ⇒
          delete(t, name, validateOnly).map { result ⇒
            actorFor[WorkflowActor] ! WorkflowActor.Unschedule(workflow)
            result
          }
        case _ ⇒ false
      }

    case (t, r) ⇒
      if (validateOnly) Future(None) else actorFor[PersistenceActor] ? PersistenceActor.Delete(name, t)
  }

  private def read(`type`: Class[_ <: Artifact], name: String, expandReferences: Boolean, onlyReferences: Boolean)(implicit timeout: Timeout) = {
    actorFor[PersistenceActor] ? PersistenceActor.Read(name, `type`, expandReferences, onlyReferences)
  }

  private def create(reader: YamlReader[_ <: Artifact], source: String, validateOnly: Boolean)(implicit timeout: Timeout) = {
    reader.read(source) match {
      case artifact ⇒ if (validateOnly) Future(artifact) else actorFor[PersistenceActor] ? PersistenceActor.Create(artifact, Option(source))
    }
  }

  private def update(reader: YamlReader[_ <: Artifact], name: String, source: String, validateOnly: Boolean)(implicit timeout: Timeout) = {
    reader.read(source) match {
      case artifact ⇒
        if (name != artifact.name)
          throwException(InconsistentArtifactName(name, artifact))

        if (validateOnly) Future(artifact) else actorFor[PersistenceActor] ? PersistenceActor.Update(artifact, Some(source))
    }
  }

  private def delete(`type`: Class[_ <: Artifact], name: String, validateOnly: Boolean)(implicit timeout: Timeout) = {
    if (validateOnly) Future(None) else actorFor[PersistenceActor] ? PersistenceActor.Delete(name, `type`)
  }

  private def crud(kind: String): Boolean = `type`(kind) match {
    case (t, _) if t == classOf[Gateway]           ⇒ false
    case (t, _) if t == classOf[Deployment]        ⇒ false
    case (t, _) if t == classOf[ScheduledWorkflow] ⇒ false
    case _                                         ⇒ true
  }

  private def `type`(kind: String): (Class[_ <: Artifact], YamlReader[_ <: Artifact]) = kind match {
    case "breeds"              ⇒ (classOf[Breed], BreedReader)
    case "blueprints"          ⇒ (classOf[Blueprint], BlueprintReader)
    case "slas"                ⇒ (classOf[Sla], SlaReader)
    case "scales"              ⇒ (classOf[Scale], ScaleReader)
    case "escalations"         ⇒ (classOf[Escalation], EscalationReader)
    case "routes"              ⇒ (classOf[Route], RouteReader)
    case "conditions"          ⇒ (classOf[Condition], ConditionReader)
    case "rewrites"            ⇒ (classOf[Rewrite], RewriteReader)
    case "workflows"           ⇒ (classOf[Workflow], WorkflowReader)
    case "scheduled-workflows" ⇒ (classOf[ScheduledWorkflow], ScheduledWorkflowReader)
    case "gateways"            ⇒ (classOf[Gateway], GatewayReader)
    case "deployments"         ⇒ (classOf[Deployment], DeploymentReader)
    case _                     ⇒ throwException(UnexpectedArtifact(kind))
  }
}
