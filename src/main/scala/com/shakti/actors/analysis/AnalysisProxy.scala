package com.shakti.actors.analysis

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

object AnalysisProxy {
  sealed trait Command
  
  case class Analyze(
    request: AnalysisRequest
  ) extends Command
  
  private case class AnalysisResult(
    response: AnalysisResponse,
    originalRequest: AnalysisRequest
  ) extends Command
  
  private case class AnalysisFailed(
    error: String,
    originalRequest: AnalysisRequest
  ) extends Command
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new AnalysisProxy(context)
  }
}

class AnalysisProxy(context: ActorContext[AnalysisProxy.Command]) 
  extends AbstractBehavior[AnalysisProxy.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = context.executionContext
  private implicit val materializer: Materializer = Materializer(context.system)
  
  private val analysisApiUrl = sys.env.getOrElse("ANALYSIS_API_URL", "http://localhost:8001")
  
  import AnalysisProxy._
  
  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case Analyze(request) =>
      logger.info(s"Analyzing text: '${request.textToAnalyze}' in context of '${request.contextText.take(50)}...'")
      
      // Make HTTP request to analysis API
      val apiRequest = createApiRequest(request)
      val futureResponse = Http(context.system).singleRequest(apiRequest)
      
      futureResponse.onComplete {
        case Success(response) =>
          handleApiResponse(response, request)
        case Failure(ex) =>
          logger.error("Analysis API request failed", ex)
          context.self ! AnalysisFailed(ex.getMessage, request)
      }
      
      Behaviors.same
      
    case AnalysisResult(response, originalRequest) =>
      logger.info(s"Analysis completed successfully for: '${originalRequest.textToAnalyze}'")
      originalRequest.replyTo ! response
      Behaviors.same
      
    case AnalysisFailed(error, originalRequest) =>
      logger.error(s"Analysis failed: $error")
      originalRequest.replyTo ! AnalysisResponse(
        contextText = originalRequest.contextText,
        textToAnalyze = originalRequest.textToAnalyze,
        inputLanguage = originalRequest.inputLanguage,
        outputLanguage = originalRequest.outputLanguage,
        analysisHtml = s"<div class='analysis'><h3>Error</h3><p>Analysis failed: $error</p></div>",
        success = false,
        error = Some(error)
      )
      Behaviors.same
  }
  
  private def createApiRequest(request: AnalysisRequest): HttpRequest = {
    val jsonBody = s"""{
      "context_text": "${escapeJson(request.contextText)}",
      "text_to_analyze": "${escapeJson(request.textToAnalyze)}",
      "input_lang": "${Language.toString(request.inputLanguage)}",
      "output_lang": "${Language.toString(request.outputLanguage)}"
    }"""
    
    HttpRequest(
      method = HttpMethods.POST,
      uri = s"$analysisApiUrl/analyze",
      entity = HttpEntity(ContentTypes.`application/json`, jsonBody)
    )
  }
  
  private def handleApiResponse(response: HttpResponse, originalRequest: AnalysisRequest): Unit = {
    if (response.status.isSuccess()) {
      Unmarshal(response.entity).to[String].onComplete {
        case Success(jsonString) =>
          try {
            val json = jsonString.parseJson.asJsObject
            val analysisHtml = json.fields("analysis_html").convertTo[String]
            val success = json.fields("success").convertTo[Boolean]
            
            val apiResponse = AnalysisResponse(
              contextText = originalRequest.contextText,
              textToAnalyze = originalRequest.textToAnalyze,
              inputLanguage = originalRequest.inputLanguage,
              outputLanguage = originalRequest.outputLanguage,
              analysisHtml = analysisHtml,
              success = success
            )
            
            context.self ! AnalysisResult(apiResponse, originalRequest)
          } catch {
            case ex: Exception =>
              logger.error("Failed to parse analysis response", ex)
              context.self ! AnalysisFailed(s"Failed to parse response: ${ex.getMessage}", originalRequest)
          }
        case Failure(ex) =>
          logger.error("Failed to unmarshal response", ex)
          context.self ! AnalysisFailed(s"Failed to read response: ${ex.getMessage}", originalRequest)
      }
    } else {
      logger.error(s"Analysis API returned error status: ${response.status}")
      context.self ! AnalysisFailed(s"API error: ${response.status}", originalRequest)
    }
  }
  
  private def escapeJson(str: String): String = {
    str.replace("\\", "\\\\")
       .replace("\"", "\\\"")
       .replace("\n", "\\n")
       .replace("\r", "\\r")
       .replace("\t", "\\t")
  }
} 