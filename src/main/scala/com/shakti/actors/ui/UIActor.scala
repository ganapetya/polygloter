package com.shakti.actors.ui

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.shakti.actors.messages._
import com.shakti.actors.session.SessionManager
import com.shakti.actors.translator.TranslatorProxy
import com.shakti.actors.tts.TTSProxy
import com.shakti.actors.tracker.WordTracker
import com.shakti.actors.analysis.AnalysisProxy
import org.slf4j.LoggerFactory
import spray.json._
import DefaultJsonProtocol._
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}
import java.util.UUID
import akka.stream.Materializer
import scala.collection.mutable

object UIActor {
  sealed trait Command
  
  case class StartServer() extends Command
  case class ServerStarted() extends Command
  case class ServerFailed(error: String) extends Command
  
  // Internal messages for handling responses
  private case class SessionStartedResponse(response: com.shakti.actors.messages.SessionStarted) extends Command
  private case class TranslationResponseReceived(response: com.shakti.actors.messages.TranslationResponse) extends Command
  private case class TTSResponseReceived(response: com.shakti.actors.messages.TTSResponse) extends Command
  private case class AnalysisResponseReceived(response: com.shakti.actors.messages.AnalysisResponse) extends Command
  private case class SessionStatsReceived(stats: WordTracker.SessionStats) extends Command
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new UIActor(context)
  }
}

class UIActor(context: ActorContext[UIActor.Command]) 
  extends AbstractBehavior[UIActor.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = context.executionContext
  
  // Create child actors
  private val wordTracker = context.spawn(WordTracker(), "word-tracker")
  private val sessionManager = context.spawn(SessionManager(wordTracker), "session-manager")
  private val translatorProxy = context.spawn(TranslatorProxy(), "translator-proxy")
  private val ttsProxy = context.spawn(TTSProxy(), "tts-proxy")
  private val analysisProxy = context.spawn(AnalysisProxy(), "analysis-proxy")
  
  // Store HTTP binding to keep it alive
  private var httpBinding: Option[Http.ServerBinding] = None
  
  // Store translation results by session ID and request ID
  private val translationResults = mutable.Map[String, TranslationResponse]()
  
  // Store TTS results by request ID
  private val ttsResults = mutable.Map[String, TTSResponse]()
  
  // Store analysis results by request ID
  private val analysisResults = mutable.Map[String, AnalysisResponse]()
  
  // Store pending TTS requests to map responses back to compound keys
  private val pendingTTSRequests = mutable.Map[String, String]()
  
  // Create message adapters once during initialization
  private val sessionStartedAdapter = context.messageAdapter[com.shakti.actors.messages.SessionStarted] { response =>
    UIActor.SessionStartedResponse(response)
  }
  
  private val translationResponseAdapter = context.messageAdapter[com.shakti.actors.messages.TranslationResponse] { response =>
    UIActor.TranslationResponseReceived(response)
  }
  
  private val sessionStatsAdapter = context.messageAdapter[WordTracker.SessionStats] { stats =>
    UIActor.SessionStatsReceived(stats)
  }
  
  private val ttsResponseAdapter = context.messageAdapter[com.shakti.actors.messages.TTSResponse] { response =>
    UIActor.TTSResponseReceived(response)
  }
  
  private val analysisResponseAdapter = context.messageAdapter[com.shakti.actors.messages.AnalysisResponse] { response =>
    UIActor.AnalysisResponseReceived(response)
  }
  
  import UIActor._
  
  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case StartServer() =>
      logger.info("Starting HTTP server...")
      
      val route = createRoutes()
      implicit val mat: Materializer = Materializer(context.system)
      val bindingFuture = Http(context.system).newServerAt("0.0.0.0", 8080).bindFlow(Route.toFlow(route)(context.system))
      
      bindingFuture.onComplete {
        case Success(binding) =>
          logger.info(s"Server started at http://0.0.0.0:${binding.localAddress.getPort}")
          httpBinding = Some(binding)
          context.self ! ServerStarted()
        case Failure(ex) =>
          logger.error("Failed to start server", ex)
          context.self ! ServerFailed(ex.getMessage)
      }
      
      Behaviors.same
      
    case ServerStarted() =>
      logger.info("HTTP server is ready to accept requests")
      // Keep the actor running by returning the same behavior
      Behaviors.same
      
    case ServerFailed(error) =>
      logger.error(s"Server failed to start: $error")
      // Even if server fails, keep the actor running for debugging
      Behaviors.same
      
    case SessionStartedResponse(response) =>
      logger.info(s"Session started: ${response.sessionId}")
      Behaviors.same
      
    case TranslationResponseReceived(response) =>
      logger.info(s"Translation response received for: ${response.originalText}")
      // Store the translation result with a key based on text AND target languages
      val targetLangCodes = response.translations.keys.map(Language.toString).toList.sorted.mkString(",")
      val resultKey = s"${response.originalText.hashCode}_${targetLangCodes.hashCode}"
      translationResults(resultKey) = response
      Behaviors.same
      
    case TTSResponseReceived(response) =>
      logger.info(s"TTS response received for: ${response.originalText}")
      // Store the TTS result using the compound key from pending requests
      pendingTTSRequests.get(response.originalText) match {
        case Some(requestId) =>
          ttsResults(requestId) = response
          pendingTTSRequests.remove(response.originalText)
        case None =>
          // Fallback to old behavior if no pending request found
          val resultKey = s"${response.originalText.hashCode}"
          ttsResults(resultKey) = response
      }
      Behaviors.same
      
    case AnalysisResponseReceived(response) =>
      logger.info(s"Analysis response received for: ${response.textToAnalyze}")
      logger.info(s"Analysis HTML length: ${response.analysisHtml.length} characters")
      // Store the analysis result with a key based on text to analyze
      val resultKey = s"${response.textToAnalyze.hashCode}"
      analysisResults(resultKey) = response
      Behaviors.same
      
    case SessionStatsReceived(stats) =>
      logger.info(s"Session stats received for: ${stats.sessionId}")
      Behaviors.same
  }
  
  override def onSignal: PartialFunction[akka.actor.typed.Signal, Behavior[Command]] = {
    case akka.actor.typed.PostStop =>
      logger.info("UIActor stopping, unbinding HTTP server...")
      httpBinding.foreach { binding =>
        binding.unbind()
        logger.info("HTTP server unbound")
      }
      Behaviors.same
  }
  
  private def createRoutes(): Route = {
    path("") {
      get {
        getFromResource("pages/index.html")
      }
    } ~
    path("styles.css") {
      get {
        getFromResource("pages/styles.css")
      }
    } ~
    path("app.js") {
      get {
        getFromResource("pages/app.js")
      }
    } ~
    path("debug_speaking_rate.html") {
      get {
        getFromFile("debug_speaking_rate.html")
      }
    } ~
    path("api" / "session" / "start") {
      post {
        entity(as[String]) { body =>
          complete {
            val sessionId = UUID.randomUUID().toString
            val startRequest = StartSession(
              sessionId = sessionId,
              userId = None,
              replyTo = sessionStartedAdapter
            )
            
            sessionManager ! SessionManager.StartSession(startRequest)
            
            s"""{"sessionId": "$sessionId", "success": true}"""
          }
        }
      }
    } ~
    path("api" / "translate") {
      post {
        entity(as[String]) { body =>
          complete {
            try {
              val json = body.parseJson.asJsObject
              val text = json.fields("text").convertTo[String]
              val sourceLang = Language.fromString(json.fields("sourceLanguage").convertTo[String])
              val targetLangs = json.fields("targetLanguages").convertTo[List[String]].map(Language.fromString)
              val sessionId = json.fields("sessionId").convertTo[String]
              
              val translationRequest = TranslationRequest(
                text = text,
                sourceLanguage = sourceLang,
                targetLanguages = targetLangs,
                replyTo = translationResponseAdapter
              )
              
              // Send to translator proxy
              translatorProxy ! TranslatorProxy.Translate(translationRequest)
              
              // Return a request ID that includes both text and target languages for uniqueness
              val targetLangCodes = targetLangs.map(Language.toString).sorted.mkString(",")
              val requestId = s"${text.hashCode}_${targetLangCodes.hashCode}"
              s"""{"status": "processing", "requestId": "$requestId", "message": "Translation request sent"}"""
            } catch {
              case ex: Exception =>
                logger.error("Failed to parse translation request", ex)
                s"""{"error": "Invalid request format: ${ex.getMessage}"}"""
            }
          }
        }
      }
    } ~
    path("api" / "translate" / "result" / Segment) { requestId =>
      get {
        complete {
          translationResults.get(requestId) match {
            case Some(response) =>
              // Use proper JSON serialization
              import spray.json._
              import DefaultJsonProtocol._
              
              val translationsJson = response.translations.map { case (lang, text) =>
                JsArray(JsString(Language.toString(lang)), JsString(text))
              }
              
              val responseJson = JsObject(
                "success" -> JsBoolean(response.success),
                "originalText" -> JsString(response.originalText),
                "translations" -> JsArray(translationsJson.toVector),
                "error" -> response.error.map(JsString(_)).getOrElse(JsNull)
              )
              
              responseJson.toString()
            case None =>
              """{"status": "not_found", "message": "Translation result not found"}"""
          }
        }
      }
    } ~
    path("api" / "tts") {
      post {
        entity(as[String]) { body =>
          complete {
            try {
              val json = body.parseJson.asJsObject
              val text = json.fields("text").convertTo[String]
              val language = Language.fromString(json.fields("language").convertTo[String])
              val sessionId = json.fields.get("sessionId").map(_.convertTo[String]).getOrElse("default-session")
              val voiceId = json.fields.get("voiceId").map(_.convertTo[String])
              
              // Parse speaking rate with validation for discrete values
              val speakingRate = json.fields.get("speakingRate") match {
                case Some(rate) =>
                  val rateValue = rate.convertTo[Double]
                  logger.info(s"Received speaking rate: $rateValue")
                  if (List(0.25, 0.5, 0.75, 1.0).contains(rateValue)) {
                    logger.info(s"Valid speaking rate: $rateValue")
                    Some(rateValue)
                  } else {
                    logger.warn(s"Invalid speaking rate: $rateValue, using default 0.75")
                    Some(0.75)
                  }
                case None => 
                  logger.info("No speaking rate provided, using default 0.75")
                  Some(0.75) // Default value
              }
              
              logger.info(s"Creating TTS request with speaking rate: $speakingRate")
              
              val ttsRequest = TTSRequest(
                text = text,
                language = language,
                sessionId = sessionId,
                voiceId = voiceId,
                speakingRate = speakingRate,
                replyTo = ttsResponseAdapter
              )
              
              // Send to TTS proxy
              ttsProxy ! TTSProxy.TextToSpeech(ttsRequest)
              
              // Return a request ID that includes all cache key components
              val requestId = s"${text.hashCode}_${speakingRate.getOrElse(0.75)}_${sessionId}_${Language.toString(language)}"
              
              // Store the pending request mapping
              pendingTTSRequests(text) = requestId
              
              s"""{"status": "processing", "requestId": "$requestId", "message": "TTS request sent"}"""
            } catch {
              case ex: Exception =>
                logger.error("Failed to parse TTS request", ex)
                s"""{"error": "Invalid request format: ${ex.getMessage}"}"""
            }
          }
        }
      }
    } ~
    path("api" / "tts" / "result" / Segment) { requestId =>
      get {
        complete {
          ttsResults.get(requestId) match {
            case Some(response) =>
              // Use proper JSON serialization
              import spray.json._
              import DefaultJsonProtocol._
              
              val responseJson = JsObject(
                "success" -> JsBoolean(response.success),
                "originalText" -> JsString(response.originalText),
                "audioUrl" -> response.audioUrl.map(JsString(_)).getOrElse(JsNull),
                "error" -> response.error.map(JsString(_)).getOrElse(JsNull)
              )
              
              responseJson.toString()
            case None =>
              """{"status": "not_found", "message": "TTS result not found"}"""
          }
        }
      }
    } ~
    path("api" / "analyze") {
      post {
        entity(as[String]) { body =>
          complete {
            try {
              val json = body.parseJson.asJsObject
              val contextText = json.fields("contextText").convertTo[String]
              val textToAnalyze = json.fields("textToAnalyze").convertTo[String]
              val inputLang = Language.fromString(json.fields("inputLanguage").convertTo[String])
              val outputLang = Language.fromString(json.fields("outputLanguage").convertTo[String])
              val sessionId = json.fields("sessionId").convertTo[String]
              
              val analysisRequest = AnalysisRequest(
                contextText = contextText,
                textToAnalyze = textToAnalyze,
                inputLanguage = inputLang,
                outputLanguage = outputLang,
                sessionId = sessionId,
                replyTo = analysisResponseAdapter
              )
              
              // Send to analysis proxy
              analysisProxy ! AnalysisProxy.Analyze(analysisRequest)
              
              // Return a request ID that can be used to poll for results
              val requestId = textToAnalyze.hashCode.toString
              s"""{"status": "processing", "requestId": "$requestId", "message": "Analysis request sent"}"""
            } catch {
              case ex: Exception =>
                logger.error("Failed to parse analysis request", ex)
                s"""{"error": "Invalid request format: ${ex.getMessage}"}"""
            }
          }
        }
      }
    } ~
    path("api" / "analyze" / "result" / Segment) { requestId =>
      get {
        complete {
          analysisResults.get(requestId) match {
            case Some(response) =>
              // Manual JSON construction to avoid escaping HTML content
              val errorJson = response.error.map(e => s""""${e.replace("\"", "\\\"")}"""").getOrElse("null")
              val contextTextEscaped = response.contextText.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
              val textToAnalyzeEscaped = response.textToAnalyze.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
              
              // For analysisHtml, escape all JSON control characters but preserve HTML structure
              val analysisHtmlEscaped = response.analysisHtml
                .replace("\\", "\\\\")   // Escape backslashes first
                .replace("\"", "\\\"")   // Escape quotes
                .replace("\n", "\\n")    // Escape newlines
                .replace("\r", "\\r")    // Escape carriage returns  
                .replace("\t", "\\t")    // Escape tabs
                .replace("\b", "\\b")    // Escape backspace
                .replace("\f", "\\f")    // Escape form feed
              
              logger.info(s"Returning analysis result for requestId: $requestId, HTML length: ${response.analysisHtml.length} characters")
              
              s"""{
                "success": ${response.success},
                "contextText": "$contextTextEscaped",
                "textToAnalyze": "$textToAnalyzeEscaped",
                "inputLanguage": "${Language.toString(response.inputLanguage)}",
                "outputLanguage": "${Language.toString(response.outputLanguage)}",
                "analysisHtml": "$analysisHtmlEscaped",
                "error": $errorJson
              }"""
            case None =>
              """{"status": "not_found", "message": "Analysis result not found"}"""
          }
        }
      }
    } ~
    path("api" / "analysis" / "model") {
      get {
        complete {
          // Forward request to analysis API
          implicit val mat: Materializer = Materializer(context.system)
          val analysisApiUrl = sys.env.getOrElse("ANALYSIS_API_URL", "http://localhost:8001")
          Http(context.system).singleRequest(HttpRequest(uri = s"$analysisApiUrl/model"))
            .flatMap(response => Unmarshal(response.entity).to[String])
            .map(content => HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, content)))
            .recover {
              case ex => 
                logger.error("Failed to get model info from analysis API", ex)
                HttpResponse(
                  StatusCodes.InternalServerError,
                  entity = HttpEntity(ContentTypes.`application/json`, """{"error": "Failed to get model info"}""")
                )
            }
        }
      }
    } ~
    path("api" / "session" / Segment / "stats") { sessionId =>
      get {
        complete {
          // Get session stats
          wordTracker ! WordTracker.GetSessionStats(
            sessionId = sessionId,
            replyTo = sessionStatsAdapter
          )
          
          s"""{"sessionId": "$sessionId", "status": "requested"}"""
        }
      }
    }
  }
  

} 