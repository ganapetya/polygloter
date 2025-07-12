package com.shakti.actors.session

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import com.shakti.actors.messages._
import com.shakti.actors.tracker.WordTracker
import org.slf4j.LoggerFactory
import scala.collection.mutable
import java.time.LocalDateTime
import java.util.UUID

object SessionManager {
  sealed trait Command
  
  case class StartSession(
    request: com.shakti.actors.messages.StartSession
  ) extends Command
  
  case class EndSession(
    request: com.shakti.actors.messages.EndSession
  ) extends Command
  
  case class ProcessTranslation(
    sessionId: String,
    text: String,
    sourceLanguage: Language,
    targetLanguages: List[Language],
    replyTo: ActorRef[TranslationResponse]
  ) extends Command
  
  private case class WordTrackingResult(
    result: WordsTracked,
    originalRequest: ProcessTranslation
  ) extends Command
  
  def apply(wordTracker: ActorRef[WordTracker.Command]): Behavior[Command] = Behaviors.setup { context =>
    new SessionManager(context, wordTracker)
  }
}

class SessionManager(
  context: ActorContext[SessionManager.Command],
  wordTracker: ActorRef[WordTracker.Command]
) extends AbstractBehavior[SessionManager.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // In-memory session storage (in production, this would be a database)
  private val sessions = mutable.Map[String, SessionData]()
  
  case class SessionData(
    sessionId: String,
    userId: Option[String],
    createdAt: LocalDateTime,
    lastActivity: LocalDateTime,
    isActive: Boolean = true
  )
  
  import SessionManager._
  
  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case StartSession(request) =>
      logger.info(s"Starting session: ${request.sessionId}")
      
      val sessionData = SessionData(
        sessionId = request.sessionId,
        userId = request.userId,
        createdAt = LocalDateTime.now(),
        lastActivity = LocalDateTime.now()
      )
      
      sessions(request.sessionId) = sessionData
      
      request.replyTo ! SessionStarted(
        sessionId = request.sessionId,
        success = true
      )
      
      Behaviors.same
      
    case EndSession(request) =>
      logger.info(s"Ending session: ${request.sessionId}")
      
      sessions.get(request.sessionId) match {
        case Some(sessionData) =>
          sessions(request.sessionId) = sessionData.copy(
            isActive = false,
            lastActivity = LocalDateTime.now()
          )
          request.replyTo ! SessionEnded(
            sessionId = request.sessionId,
            success = true
          )
        case None =>
          request.replyTo ! SessionEnded(
            sessionId = request.sessionId,
            success = false
          )
      }
      
      Behaviors.same
      
    case ProcessTranslation(sessionId, text, sourceLanguage, targetLanguages, replyTo) =>
      logger.info(s"Processing translation for session: $sessionId")
      
      // Check if session exists and is active
      sessions.get(sessionId) match {
        case Some(sessionData) if sessionData.isActive =>
          // Update session activity
          sessions(sessionId) = sessionData.copy(lastActivity = LocalDateTime.now())
          
          // Track words first
          val trackWordsRequest = TrackWords(
            sessionId = sessionId,
            text = text,
            sourceLanguage = sourceLanguage,
            replyTo = context.messageAdapter[WordsTracked] { result =>
              WordTrackingResult(result, ProcessTranslation(sessionId, text, sourceLanguage, targetLanguages, replyTo))
            }
          )
          
          wordTracker ! WordTracker.TrackWords(trackWordsRequest)
          
        case Some(_) =>
          // Session exists but is inactive
          replyTo ! TranslationResponse(
            originalText = text,
            translations = Map.empty,
            success = false,
            error = Some("Session is inactive")
          )
          
        case None =>
          // Session doesn't exist
          replyTo ! TranslationResponse(
            originalText = text,
            translations = Map.empty,
            success = false,
            error = Some("Session not found")
          )
      }
      
      Behaviors.same
      
    case WordTrackingResult(result, originalRequest) =>
      if (result.success) {
        logger.info(s"Words tracked successfully for session: ${result.sessionId}")
        // Now proceed with translation (this would be handled by UIActor)
        // For now, just acknowledge the word tracking
      } else {
        logger.error(s"Word tracking failed for session: ${result.sessionId}")
      }
      
      Behaviors.same
  }
  
  def generateSessionId(): String = UUID.randomUUID().toString
} 