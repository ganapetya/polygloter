package com.shakti.actors.monitoring

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.exporter.HTTPServer
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

/**
 * Service for initializing and managing application metrics and monitoring.
 */
class MetricsService(config: Config)(implicit system: ActorSystem[_]) {
  private val logger = LoggerFactory.getLogger(classOf[MetricsService])
  
  private val monitoringConfig = config.getConfig("monitoring")
  private val prometheusConfig = monitoringConfig.getConfig("prometheus")
  private val appConfig = monitoringConfig.getConfig("application")
  
  // Application info
  private val appName = appConfig.getString("name")
  private val appVersion = appConfig.getString("version")
  private val environment = appConfig.getString("environment")
  
  // Prometheus metrics
  private val registry = CollectorRegistry.defaultRegistry
  
  // Application metrics
  val httpRequestsTotal: Counter = Counter.build()
    .name("http_requests_total")
    .help("Total number of HTTP requests")
    .labelNames("method", "endpoint", "status")
    .register(registry)
  
  val httpRequestDuration: Histogram = Histogram.build()
    .name("http_request_duration_seconds")
    .help("HTTP request duration in seconds")
    .labelNames("method", "endpoint")
    .register(registry)
  
  val activeSessionsGauge: Gauge = Gauge.build()
    .name("active_sessions_total")
    .help("Number of active user sessions")
    .register(registry)
  
  val translationRequestsTotal: Counter = Counter.build()
    .name("translation_requests_total")
    .help("Total number of translation requests")
    .labelNames("source_lang", "target_lang", "status")
    .register(registry)
  
  val ttsRequestsTotal: Counter = Counter.build()
    .name("tts_requests_total")
    .help("Total number of text-to-speech requests")
    .labelNames("language", "status")
    .register(registry)
  
  val analysisRequestsTotal: Counter = Counter.build()
    .name("analysis_requests_total")
    .help("Total number of analysis requests")
    .labelNames("source_lang", "target_lang", "status")
    .register(registry)
  
  val actorMessageProcessingTime: Histogram = Histogram.build()
    .name("actor_message_processing_seconds")
    .help("Time taken to process actor messages")
    .labelNames("actor_type", "message_type")
    .register(registry)
  
  val applicationInfo: Gauge = Gauge.build()
    .name("application_info")
    .help("Application information")
    .labelNames("name", "version", "environment")
    .register(registry)
  
  // HTTP server for metrics endpoint
  private var metricsServer: Option[HTTPServer] = None
  
  /**
   * Initialize the metrics service
   */
  def initialize(): Unit = {
    logger.info("Initializing metrics service...")
    
    try {
      // Register JVM metrics
      if (monitoringConfig.getBoolean("jvm.enabled")) {
        logger.info("Registering JVM metrics...")
        DefaultExports.initialize()
        logger.info("JVM metrics registered successfully")
      }
      
      // Set application info
      applicationInfo.labels(appName, appVersion, environment).set(1)
      
      // Start Prometheus HTTP server
      if (prometheusConfig.getBoolean("enabled")) {
        val port = prometheusConfig.getInt("port")
        val host = prometheusConfig.getString("host")
        
        logger.info(s"Starting Prometheus metrics server on $host:$port...")
        
        Try {
          new HTTPServer(port)
        } match {
          case Success(server) =>
            metricsServer = Some(server)
            logger.info(s"Prometheus metrics server started successfully on port $port")
            logger.info(s"Metrics endpoint available at: http://localhost:$port/metrics")
          case Failure(exception) =>
            logger.error(s"Failed to start Prometheus metrics server: ${exception.getMessage}", exception)
        }
      }
      
      logger.info("Metrics service initialized successfully")
      
    } catch {
      case ex: Exception =>
        logger.error("Failed to initialize metrics service", ex)
    }
  }
  
  /**
   * Shutdown the metrics service
   */
  def shutdown(): Unit = {
    logger.info("Shutting down metrics service...")
    
    metricsServer.foreach { server =>
      try {
        server.close()
        logger.info("Prometheus metrics server stopped")
      } catch {
        case ex: Exception =>
          logger.error("Error stopping Prometheus metrics server", ex)
      }
    }
    
    logger.info("Metrics service shutdown complete")
  }
  
  /**
   * Record HTTP request metrics
   */
  def recordHttpRequest(method: String, endpoint: String, status: String, duration: Double): Unit = {
    httpRequestsTotal.labels(method, endpoint, status).inc()
    httpRequestDuration.labels(method, endpoint).observe(duration)
  }
  
  /**
   * Update active sessions count
   */
  def updateActiveSessions(count: Int): Unit = {
    activeSessionsGauge.set(count)
  }
  
  /**
   * Record translation request metrics
   */
  def recordTranslationRequest(sourceLanguage: String, targetLanguage: String, status: String): Unit = {
    translationRequestsTotal.labels(sourceLanguage, targetLanguage, status).inc()
  }
  
  /**
   * Record TTS request metrics
   */
  def recordTTSRequest(language: String, status: String): Unit = {
    ttsRequestsTotal.labels(language, status).inc()
  }
  
  /**
   * Record analysis request metrics
   */
  def recordAnalysisRequest(sourceLanguage: String, targetLanguage: String, status: String): Unit = {
    analysisRequestsTotal.labels(sourceLanguage, targetLanguage, status).inc()
  }
  
  /**
   * Record actor message processing time
   */
  def recordActorMessageProcessing(actorType: String, messageType: String, duration: Double): Unit = {
    actorMessageProcessingTime.labels(actorType, messageType).observe(duration)
  }
}

object MetricsService {
  def apply(config: Config)(implicit system: ActorSystem[_]): MetricsService = {
    new MetricsService(config)
  }
} 