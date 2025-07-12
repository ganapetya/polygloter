package com.shakti.actors.tts

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import com.shakti.actors.messages._
import org.slf4j.LoggerFactory
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import spray.json._
import DefaultJsonProtocol._
import scala.collection.mutable
import java.time.Instant

// JSON format definitions for TTS API
object TTSJsonProtocol extends DefaultJsonProtocol {
  case class VoiceConfig(language_code: String, voice_name: Option[String] = None)
  case class AudioConfig(speaking_rate: Option[Double] = None)
  case class TTSApiRequest(text: String, voice_config: VoiceConfig, audio_config: Option[AudioConfig] = None)
  case class TTSApiResponse(success: Boolean, audio_content: Option[String], audio_format: Option[String], error: Option[String])
  
  implicit val voiceConfigFormat: spray.json.RootJsonFormat[VoiceConfig] = jsonFormat2(VoiceConfig)
  implicit val audioConfigFormat: spray.json.RootJsonFormat[AudioConfig] = jsonFormat1(AudioConfig)
  implicit val ttsApiRequestFormat: spray.json.RootJsonFormat[TTSApiRequest] = jsonFormat3(TTSApiRequest)
  implicit val ttsApiResponseFormat: spray.json.RootJsonFormat[TTSApiResponse] = jsonFormat4(TTSApiResponse)
}

// Cache implementation for TTS responses
object TTSCache {
  case class CacheKey(textHash: Int, speakingRate: Double, sessionId: String, language: String)
  case class CacheEntry(response: TTSResponse, timestamp: Instant)
  
  private val cache = mutable.Map[CacheKey, CacheEntry]()
  private val TTL_MINUTES = 10
  
  def get(text: String, speakingRate: Option[Double], sessionId: String, language: Language): Option[TTSResponse] = {
    val key = CacheKey(text.hashCode, speakingRate.getOrElse(0.75), sessionId, Language.toString(language))
    cache.get(key) match {
      case Some(entry) =>
        if (isExpired(entry.timestamp)) {
          cache.remove(key)
          None
        } else {
          Some(entry.response)
        }
      case None => None
    }
  }
  
  def put(text: String, speakingRate: Option[Double], sessionId: String, language: Language, response: TTSResponse): Unit = {
    val key = CacheKey(text.hashCode, speakingRate.getOrElse(0.75), sessionId, Language.toString(language))
    cache.put(key, CacheEntry(response, Instant.now()))
  }
  
  private def isExpired(timestamp: Instant): Boolean = {
    val now = Instant.now()
    val expiry = timestamp.plusSeconds(TTL_MINUTES * 60)
    now.isAfter(expiry)
  }
  
  def getCacheStats(): String = {
    val totalEntries = cache.size
    val expiredEntries = cache.count { case (_, entry) => isExpired(entry.timestamp) }
    s"Cache stats: total=$totalEntries, expired=$expiredEntries"
  }
}

object TTSProxy {
  sealed trait Command
  
  case class TextToSpeech(
    request: TTSRequest
  ) extends Command
  
  private case class TTSResult(
    response: TTSResponse,
    originalRequest: TTSRequest
  ) extends Command
  
  private case class TTSFailed(
    error: String,
    originalRequest: TTSRequest
  ) extends Command
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new TTSProxy(context)
  }
}

class TTSProxy(context: ActorContext[TTSProxy.Command]) 
  extends AbstractBehavior[TTSProxy.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = context.executionContext
  private implicit val materializer: Materializer = Materializer(context.system)
  
  private val ttsApiUrl = sys.env.getOrElse("TTS_API_URL", "http://localhost:8003")
  
  // Track pending requests to avoid duplicates
  private val pendingRequests = mutable.Map[TTSCache.CacheKey, List[TTSRequest]]()
  
  import TTSProxy._
  import TTSJsonProtocol._
  
  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case TextToSpeech(request) =>
      val cacheKeyObj = TTSCache.CacheKey(request.text.hashCode, request.speakingRate.getOrElse(0.75), request.sessionId, Language.toString(request.language))
      val cacheKeyStr = s"text.hashCode=${request.text.hashCode}, speakingRate=${request.speakingRate.getOrElse(0.75)}, sessionId=${request.sessionId}, language=${Language.toString(request.language)}"
      logger.info(s"Converting text to speech: '${request.text}' in language: ${Language.toString(request.language)} with speaking rate: ${request.speakingRate} (cache key: $cacheKeyStr)")
      
      // Check cache first
      TTSCache.get(request.text, request.speakingRate, request.sessionId, request.language) match {
        case Some(cachedResponse) =>
          logger.info(s"Cache HIT for $cacheKeyStr - returning cached audio")
          request.replyTo ! cachedResponse
          Behaviors.same
          
        case None =>
          // Check if there's already a pending request for this exact same combination
          pendingRequests.get(cacheKeyObj) match {
            case Some(existingRequests) =>
              // Add this request to the queue for the same cache key
              logger.info(s"Request DEDUPLICATION for $cacheKeyStr - queueing request (${existingRequests.length + 1} total waiting)")
              pendingRequests(cacheKeyObj) = request :: existingRequests
              Behaviors.same
              
            case None =>
              logger.info(s"Cache MISS for $cacheKeyStr - making API request")
              // Start tracking this request
              pendingRequests(cacheKeyObj) = List(request)
              
              // Make HTTP request to TTS API
              val apiRequest = createApiRequest(request)
              val futureResponse = Http(context.system).singleRequest(apiRequest)
              
              futureResponse.onComplete {
                case Success(response) =>
                  handleApiResponse(response, request)
                case Failure(ex) =>
                  logger.error("TTS API request failed", ex)
                  context.self ! TTSFailed(ex.getMessage, request)
              }
              
              Behaviors.same
          }
      }
      
    case TTSResult(response, originalRequest) =>
      logger.info(s"TTS completed successfully for: '${originalRequest.text}'")
      
      val cacheKeyObj = TTSCache.CacheKey(originalRequest.text.hashCode, originalRequest.speakingRate.getOrElse(0.75), originalRequest.sessionId, Language.toString(originalRequest.language))
      
      // Store successful response in cache
      if (response.success) {
        TTSCache.put(originalRequest.text, originalRequest.speakingRate, originalRequest.sessionId, originalRequest.language, response)
        val cacheKeyStr = s"text.hashCode=${originalRequest.text.hashCode}, speakingRate=${originalRequest.speakingRate.getOrElse(0.75)}, sessionId=${originalRequest.sessionId}, language=${Language.toString(originalRequest.language)}"
        logger.info(s"Cached TTS response for $cacheKeyStr. ${TTSCache.getCacheStats()}")
      }
      
      // Send response to all pending requests for this cache key
      pendingRequests.get(cacheKeyObj) match {
        case Some(requests) =>
          logger.info(s"Notifying ${requests.length} pending requests for completed TTS")
          requests.foreach(_.replyTo ! response)
          pendingRequests.remove(cacheKeyObj)
        case None =>
          // This shouldn't happen, but handle gracefully
          logger.warn("No pending requests found for completed TTS")
          originalRequest.replyTo ! response
      }
      
      Behaviors.same
      
    case TTSFailed(error, originalRequest) =>
      logger.error(s"TTS failed: $error")
      
      val cacheKeyObj = TTSCache.CacheKey(originalRequest.text.hashCode, originalRequest.speakingRate.getOrElse(0.75), originalRequest.sessionId, Language.toString(originalRequest.language))
      val errorResponse = TTSResponse(
        originalText = originalRequest.text,
        audioUrl = None,
        success = false,
        error = Some(error)
      )
      
      // Send error response to all pending requests for this cache key
      pendingRequests.get(cacheKeyObj) match {
        case Some(requests) =>
          logger.info(s"Notifying ${requests.length} pending requests of TTS failure")
          requests.foreach(_.replyTo ! errorResponse)
          pendingRequests.remove(cacheKeyObj)
        case None =>
          // This shouldn't happen, but handle gracefully
          logger.warn("No pending requests found for failed TTS")
          originalRequest.replyTo ! errorResponse
      }
      
      Behaviors.same
  }
  
  private def createApiRequest(request: TTSRequest): HttpRequest = {
    val voiceConfig = VoiceConfig(
      language_code = Language.toString(request.language),
      voice_name = request.voiceId
    )
    
    // Include audio config with speaking rate if provided
    val audioConfig = request.speakingRate match {
      case Some(rate) => Some(AudioConfig(speaking_rate = Some(rate)))
      case None => None
    }
    
    val apiRequest = TTSApiRequest(
      text = request.text,
      voice_config = voiceConfig,
      audio_config = audioConfig
    )
    
    val jsonBody = apiRequest.toJson.compactPrint
    
    logger.info(s"Sending TTS API request: $jsonBody")
    
    HttpRequest(
      method = HttpMethods.POST,
      uri = s"$ttsApiUrl/tts/synthesize",
      entity = HttpEntity(ContentTypes.`application/json`, jsonBody)
    )
  }
  
  private def handleApiResponse(response: HttpResponse, originalRequest: TTSRequest): Unit = {
    if (response.status.isSuccess()) {
      Unmarshal(response.entity).to[String].onComplete {
        case Success(jsonString) =>
          try {
            val apiResponse = jsonString.parseJson.convertTo[TTSApiResponse]
            
            // Create a data URL for the audio content with compound key identifier
            val audioUrl = apiResponse.audio_content.map(content => {
              val format = apiResponse.audio_format.getOrElse("MP3").toLowerCase
              val textHash = originalRequest.text.hashCode
              val speakingRate = originalRequest.speakingRate.getOrElse(0.75)
              val sessionId = originalRequest.sessionId
              val language = Language.toString(originalRequest.language)
              // Use compound key for consistent caching behavior
              s"data:audio/$format;base64,$content#textHash=$textHash&rate=$speakingRate&sessionId=$sessionId&lang=$language"
            })
            
            val ttsResponse = TTSResponse(
              originalText = originalRequest.text,
              audioUrl = audioUrl,
              success = apiResponse.success,
              error = apiResponse.error
            )
            
            context.self ! TTSResult(ttsResponse, originalRequest)
          } catch {
            case ex: Exception =>
              logger.error("Failed to parse TTS response", ex)
              context.self ! TTSFailed(s"Failed to parse response: ${ex.getMessage}", originalRequest)
          }
        case Failure(ex) =>
          logger.error("Failed to unmarshal TTS response", ex)
          context.self ! TTSFailed(s"Failed to read response: ${ex.getMessage}", originalRequest)
      }
    } else {
      logger.error(s"TTS API returned error status: ${response.status}")
      context.self ! TTSFailed(s"API error: ${response.status}", originalRequest)
    }
  }
} 