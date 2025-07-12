package com.shakti.actors.translator

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

object TranslatorProxy {
  sealed trait Command
  
  case class Translate(
    request: TranslationRequest
  ) extends Command
  
  private case class TranslationResult(
    response: TranslationResponse,
    originalRequest: TranslationRequest
  ) extends Command
  
  private case class TranslationFailed(
    error: String,
    originalRequest: TranslationRequest
  ) extends Command
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new TranslatorProxy(context)
  }
}

class TranslatorProxy(context: ActorContext[TranslatorProxy.Command]) 
  extends AbstractBehavior[TranslatorProxy.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = context.executionContext
  private implicit val materializer: Materializer = Materializer(context.system)
  
  private val translatorApiUrl = sys.env.getOrElse("TRANSLATOR_API_URL", "http://localhost:8000")
  
  import TranslatorProxy._
  
  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case Translate(request) =>
      logger.info(s"Translating text: '${request.text}' to languages: ${request.targetLanguages.map(Language.toString)}")
      
      // Make HTTP request to translator API
      val apiRequest = createApiRequest(request)
      val futureResponse = Http(context.system).singleRequest(apiRequest)
      
      futureResponse.onComplete {
        case Success(response) =>
          handleApiResponse(response, request)
        case Failure(ex) =>
          logger.error("Translation API request failed", ex)
          context.self ! TranslationFailed(ex.getMessage, request)
      }
      
      Behaviors.same
      
    case TranslationResult(response, originalRequest) =>
      logger.info(s"Translation completed successfully for: '${originalRequest.text}'")
      originalRequest.replyTo ! response
      Behaviors.same
      
    case TranslationFailed(error, originalRequest) =>
      logger.error(s"Translation failed: $error")
      originalRequest.replyTo ! TranslationResponse(
        originalText = originalRequest.text,
        translations = Map.empty,
        success = false,
        error = Some(error)
      )
      Behaviors.same
  }
  
  private def createApiRequest(request: TranslationRequest): HttpRequest = {
    // Use proper JSON construction instead of string interpolation to handle special characters
    val requestJson = JsObject(
      "sentence" -> JsString(request.text),
      "input_lang" -> JsString(Language.toString(request.sourceLanguage)),
      "target_lang" -> JsArray(request.targetLanguages.map(lang => JsString(Language.toString(lang))): _*),
      "translator" -> JsString("google_api")
    )
    
    val jsonBody = requestJson.compactPrint
    
    logger.debug(s"Created JSON request body: $jsonBody")
    
    HttpRequest(
      method = HttpMethods.POST,
      uri = s"$translatorApiUrl/translate",
      entity = HttpEntity(ContentTypes.`application/json`, jsonBody)
    )
  }
  
  private def handleApiResponse(response: HttpResponse, originalRequest: TranslationRequest): Unit = {
    if (response.status.isSuccess()) {
      Unmarshal(response.entity).to[String].onComplete {
        case Success(jsonString) =>
          try {
            val json = jsonString.parseJson.asJsObject
            val translations = json.fields("translations").convertTo[List[List[String]]]
            
            val translationMap = translations.collect {
              case List(langCode, text) => Language.fromString(langCode) -> text
            }.toMap
            
            val apiResponse = TranslationResponse(
              originalText = originalRequest.text,
              translations = translationMap,
              success = true
            )
            
            context.self ! TranslationResult(apiResponse, originalRequest)
          } catch {
            case ex: Exception =>
              logger.error("Failed to parse translation response", ex)
              context.self ! TranslationFailed(s"Failed to parse response: ${ex.getMessage}", originalRequest)
          }
        case Failure(ex) =>
          logger.error("Failed to unmarshal response", ex)
          context.self ! TranslationFailed(s"Failed to read response: ${ex.getMessage}", originalRequest)
      }
    } else {
      logger.error(s"Translation API returned error status: ${response.status}")
      context.self ! TranslationFailed(s"API error: ${response.status}", originalRequest)
    }
  }
} 