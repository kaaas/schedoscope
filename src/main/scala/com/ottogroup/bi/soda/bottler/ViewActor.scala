package com.ottogroup.bi.soda.bottler

import java.security.PrivilegedAction
import java.lang.Math.max
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import com.ottogroup.bi.soda.SettingsImpl
import com.ottogroup.bi.soda.dsl.NoOp
import com.ottogroup.bi.soda.dsl.View
import com.ottogroup.bi.soda.dsl.transformations.filesystem.Delete
import com.ottogroup.bi.soda.dsl.transformations.filesystem.Touch
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSelection.toScala
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import com.ottogroup.bi.soda.dsl.transformations.filesystem.FilesystemTransformation

class ViewActor(view: View, settings: SettingsImpl, viewManagerActor: ActorRef, actionsManagerActor: ActorRef, schemaActor: ActorRef) extends Actor {
  import context._

  val log = Logging(system, this)
  implicit val timeout = new Timeout(settings.dependencyTimout)

  val listenersWaitingForMaterialize = collection.mutable.HashSet[ActorRef]()
  val dependenciesMaterializing = collection.mutable.HashSet[View]()

  var lastTransformationTimestamp = 0l

  var oneDependencyReturnedData = false

  // state variables
  // one of the dependencies was not available (no data)
  var incomplete = false

  // maximum transformation timestamp of dependencies
  var dependenciesFreshness = 0l

  // one of the dependencies' transformations failed
  var withErrors = false

  override def postRestart(reason: Throwable) {
    self ! MaterializeView()
  }

  // State: default
  // transitions: defaultForNoOpView, defaultForViewWithoutDependencies, defaultForViewWithDependencies
  def receive = if (view.transformation() == NoOp())
    defaultForNoOpView
  else if (view.dependencies.isEmpty)
    defaultForViewWithoutDependencies
  else
    defaultForViewWithDependencies

  // State: default for NoOp views
  // transitions: materialized
  def defaultForNoOpView: Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse("receive", view)

    case MaterializeView() => {
      if (successFlagExists(view)) {
        log.debug("no dependencies for " + view + ", success flag exists, and no transformation specified")

        addPartition(view)
        setVersion(view)

        sender ! ViewMaterialized(view, false, getOrLogTransformationTimestamp(view), withErrors)

        become(materialized)

        log.debug("STATE CHANGE:receive->materialized")
      } else {
        log.debug("no data and no dependencies for " + view)

        sender ! NoDataAvailable(view)
      }
    }
  })

  // State: default for non-NoOp views without dependencies
  // transitions: transforming
  def defaultForViewWithoutDependencies: Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse("receive", view)

    case MaterializeView() => {
      log.debug("no dependencies for " + view + " and transformation specified")

      listenersWaitingForMaterialize.add(sender)

      transform(0)
    }
  })

  // State: default for views with dependencies
  // transitions: waiting
  def defaultForViewWithDependencies: Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse("receive", view)

    case MaterializeView() => {
      listenersWaitingForMaterialize.add(sender)

      view.dependencies.foreach { d =>
        {
          dependenciesMaterializing.add(d)

          log.debug("querying dependency " + d)

          val dependencyActor = Await.result((viewManagerActor ? d).mapTo[ActorRef], timeout.duration)

          dependencyActor ! MaterializeView()
        }
      }

      become(waiting)

      log.debug("STATE CHANGE:receive -> waiting")
    }
  })

  // State: view actor waiting for dependencies to materialize
  // transitions: transforming, materialized, default
  def waiting: Receive = LoggingReceive {
    case _: GetStatus => sender ! ViewStatusResponse("waiting", view)

    case MaterializeView() => listenersWaitingForMaterialize.add(sender)

    case NoDataAvailable(dependency) => {
      log.debug("Nodata from " + dependency)
      incomplete = true
      dependencyAnswered(dependency)
    }

    case Failed(dependency) => {
      log.debug("Failed from " + dependency)
      incomplete = true
      withErrors = true
      dependencyAnswered(dependency)
    }

    case ViewMaterialized(dependency, dependencyIncomplete, dependencyTransformationTimestamp, dependencyWithErrors) => {
      log.debug(s"View materialized from ${dependency}: incomplete=${dependencyIncomplete} transformationTimestamp=${dependencyTransformationTimestamp} withErrors=${dependencyWithErrors}")
      oneDependencyReturnedData = true
      incomplete |= dependencyIncomplete
      withErrors |= dependencyWithErrors
      dependenciesFreshness = max(dependenciesFreshness, dependencyTransformationTimestamp)
      dependencyAnswered(dependency)
    }
  }

  // State: transforming, view actor in process of applying transformation
  // transitions: materialized,retrying
  def transforming(retries: Int): Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse(if (0.equals(retries)) "transforming" else "retrying", view)

    case _: ActionSuccess[_] => {
      log.info("SUCCESS")

      touchSuccessFlag(view)
      val transformationTimestamp = logTransformationTimestamp(view)

      unbecome()
      become(materialized)

      log.debug("STATE CHANGE:transforming->materialized")

      listenersWaitingForMaterialize.foreach(s => { log.debug(s"sending View materialized message to ${s}"); s ! ViewMaterialized(view, incomplete, transformationTimestamp, withErrors) })
      listenersWaitingForMaterialize.clear
    }

    case _: ActionFailure[_] => retry(retries)

    case MaterializeView() => listenersWaitingForMaterialize.add(sender)
  })

  // State: materialized, view has been computed and materialized
  // transitions: default,transforming
  def materialized: Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse("materialized", view)

    case MaterializeView() => sender ! ViewMaterialized(view, incomplete, lastTransformationTimestamp, withErrors)

    case Invalidate() => {
      unbecome()
      become(receive)

      lastTransformationTimestamp = 0l
      dependenciesFreshness = 0l

      log.debug("STATE CHANGE:receive")
    }

    case NewDataAvailable(view) => if (dependenciesMaterializing.contains(view)) reload()
  })

  // State: retrying
  // transitions: failed, transforming
  def retrying(retries: Int): Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse("retrying", view)

    case MaterializeView() => listenersWaitingForMaterialize.add(sender)

    case Retry() => if (retries <= settings.retries)
      transform(retries + 1)
    else {
      unbecome()
      become(failed)

      listenersWaitingForMaterialize.foreach(_ ! Failed(view))
      listenersWaitingForMaterialize.clear()

      log.warning("STATE CHANGE:retrying-> failed")
    }
  })

  // State: failed, view actor failed to materialize
  // transitions:  default, transforming
  def failed: Receive = LoggingReceive({
    case _: GetStatus => sender ! ViewStatusResponse("failed", view)

    case NewDataAvailable(view) => if (dependenciesMaterializing.contains(view)) reload()

    case Invalidate() => {
      unbecome()
      become(receive)

      lastTransformationTimestamp = 0l
      dependenciesFreshness = 0l

      log.debug("STATE CHANGE:failed->receive")
    }

    case MaterializeView() => sender ! Failed(view)

    case _ => sender ! FatalError(view, "not recoverable")
  })

  def reload() = {
    unbecome()
    become(waiting)

    log.debug("STATE CHANGE:waiting")

    Await.result(actionsManagerActor ? Delete(view.fullPath + "/_SUCCESS", false), settings.fileActionTimeout)
    transform(1)

    // tell everyone that new data is avaiable
    viewManagerActor ! NewDataAvailable(view)
  }

  def retry(retries: Int): akka.actor.Cancellable = {
    unbecome()
    become(retrying(retries))

    log.warning("STATE CHANGE: transforming->retrying")

    // exponential backoff
    system.scheduler.scheduleOnce(Duration.create(Math.pow(2, retries).toLong, "seconds"))(self ! Retry())
  }

  def transform(retries: Int) {
    addPartition(view)
    setVersion(view)
    if (!view.transformation().isInstanceOf[FilesystemTransformation])
      deletePartitionData(view)

    actionsManagerActor ! view

    unbecome()
    become(transforming(retries))

    log.debug("STATE CHANGE:transforming")
  }

  def dependencyAnswered(dependency: com.ottogroup.bi.soda.dsl.View) {
    dependenciesMaterializing.remove(dependency)

    if (!dependenciesMaterializing.isEmpty) {
      log.debug(s"This actor is still waiting for ${dependenciesMaterializing.size} dependencies, dependencyFreshness=${dependenciesFreshness}, incomplete=${incomplete}, dependencies with data=${oneDependencyReturnedData}")
      return
    }

    if (oneDependencyReturnedData) {
      if ((lastTransformationTimestamp <= dependenciesFreshness) || hasVersionMismatch(view))
        transform(0)
      else {
        listenersWaitingForMaterialize.foreach(s => s ! ViewMaterialized(view, incomplete, lastTransformationTimestamp, withErrors))
        listenersWaitingForMaterialize.clear
        oneDependencyReturnedData = false
        dependenciesFreshness = 0l

        unbecome
        become(materialized)

        log.debug("STATE CHANGE:waiting->materialized")
      }
    } else {
      listenersWaitingForMaterialize.foreach(s => { log.debug(s"sending NoDataAvailable to ${s}"); s ! NoDataAvailable(view) })
      listenersWaitingForMaterialize.clear
      oneDependencyReturnedData = false
      dependenciesFreshness = 0l

      unbecome
      become(receive)

      log.debug("STATE CHANGE:waiting->receive")
    }
  }

  def successFlagExists(view: View): Boolean = {
    settings.userGroupInformation.doAs(new PrivilegedAction[Boolean]() {
      def run() = {
        val pathWithSuccessFlag = new Path(view.fullPath + "/_SUCCESS")

        FileSystem.get(settings.hadoopConf).exists(pathWithSuccessFlag)
      }
    })
  }

  def touchSuccessFlag(view: View) {
    Await.result(actionsManagerActor ? Touch(view.fullPath + "/_SUCCESS"), settings.fileActionTimeout)
  }

  def hasVersionMismatch(view: View) = {
    val versionInfo = Await.result(schemaActor ? CheckViewVersion(view), settings.schemaActionTimeout)

    log.debug(versionInfo.toString)

    versionInfo match {
      case SchemaActionFailure() => {
        log.warning("got error from versioncheck, assuming version mismatch")
        true
      }

      case ViewVersionMismatch(v, version) => {
        log.debug("version mismatch")
        true
      }

      case ViewVersionOk(v) => false
    }
  }

  def logTransformationTimestamp(view: View) = {
    Await.result(schemaActor ? LogTransformationTimestamp(view), settings.schemaActionTimeout)
    lastTransformationTimestamp = Await.result(schemaActor ? GetTransformationTimestamp(view), settings.schemaActionTimeout) match {
      case TransformationTimestamp(_, ts) => ts
      case _ => 0l
    }

    lastTransformationTimestamp
  }

  def getOrLogTransformationTimestamp(view: View) =
    if (lastTransformationTimestamp > 0l)
      lastTransformationTimestamp
    else
      logTransformationTimestamp(view)

  def addPartition(view: View) {
    Await.result(schemaActor ? AddPartition(view), settings.schemaActionTimeout)
  }

  def deletePartitionData(view: View) {
    Await.result(actionsManagerActor ? Delete(view.fullPath, true), settings.fileActionTimeout)
  }

  def setVersion(view: View) {
    Await.result(schemaActor ? SetViewVersion(view), settings.schemaActionTimeout)
  }

}

object ViewActor {
  def props(view: View, settings: SettingsImpl, viewManagerActor: ActorRef, actionsManagerActor: ActorRef, schemaActor: ActorRef): Props = Props(classOf[ViewActor], view, settings, viewManagerActor, actionsManagerActor, schemaActor)
}
