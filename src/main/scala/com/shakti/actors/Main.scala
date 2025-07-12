package com.shakti.actors

import akka.actor.typed.ActorSystem
import com.shakti.actors.ui.UIActor
import com.shakti.actors.monitoring.MetricsService
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.duration._

object Main extends App {
  private val logger = LoggerFactory.getLogger(getClass)
  
  logger.info("Starting Shakti Actors application...")
  
  // Create the actor system with UIActor as the guardian
  val system = ActorSystem[UIActor.Command](
    UIActor(),
    "shakti-actors-system"
  )
  
  // Initialize monitoring service
  val metricsService = MetricsService(system.settings.config)(system)
  metricsService.initialize()
  
  // Start the HTTP server
  system ! UIActor.StartServer()
  
  logger.info("Shakti Actors system started successfully")
  
  // Add shutdown hook to gracefully stop the system
  sys.addShutdownHook {
    logger.info("Shutdown signal received, stopping services...")
    metricsService.shutdown()
    system.terminate()
  }
  
  // Keep the system running indefinitely
  logger.info("Application is running. Press Ctrl+C to stop.")
  
  // Wait for the system to terminate
  Await.result(system.whenTerminated, Duration.Inf)
  
  logger.info("Shakti Actors application terminated")
} 