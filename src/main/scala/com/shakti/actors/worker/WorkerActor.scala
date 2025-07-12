package com.shakti.actors.worker

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import org.slf4j.LoggerFactory

object WorkerActor {
  
  // Commands that this actor can handle
  sealed trait Command
  final case class ProcessMessage(message: String) extends Command
  final case class GetStatus(replyTo: ActorRef[Status]) extends Command
  final case class Stop() extends Command
  
  // Response messages
  final case class Status(message: String, processedCount: Int)
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new WorkerActor(context)
  }
}

class WorkerActor(context: ActorContext[WorkerActor.Command]) 
  extends AbstractBehavior[WorkerActor.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private var processedCount = 0
  
  logger.info("WorkerActor started")
  
  override def onMessage(msg: WorkerActor.Command): Behavior[WorkerActor.Command] = {
    msg match {
      case WorkerActor.ProcessMessage(message) =>
        processedCount += 1
        logger.info(s"Processing message: $message (count: $processedCount)")
        this
        
      case WorkerActor.GetStatus(replyTo) =>
        replyTo ! WorkerActor.Status("Worker is running", processedCount)
        this
        
      case WorkerActor.Stop() =>
        logger.info("WorkerActor stopping...")
        Behaviors.stopped
    }
  }
  
  override def onSignal: PartialFunction[akka.actor.typed.Signal, Behavior[WorkerActor.Command]] = {
    case akka.actor.typed.PostStop =>
      logger.info("WorkerActor stopped")
      this
  }
} 