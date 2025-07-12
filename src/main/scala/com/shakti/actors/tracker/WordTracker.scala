package com.shakti.actors.tracker

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import com.shakti.actors.messages._
import org.slf4j.LoggerFactory
import scala.collection.mutable
import java.time.LocalDateTime

object WordTracker {
  sealed trait Command
  
  case class TrackWords(
    request: com.shakti.actors.messages.TrackWords
  ) extends Command
  
  case class GetSessionStats(
    sessionId: String,
    replyTo: ActorRef[SessionStats]
  ) extends Command
  
  case class SessionStats(
    sessionId: String,
    wordCount: Int,
    sentenceCount: Int,
    lastActivity: LocalDateTime
  )
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new WordTracker(context)
  }
}

class WordTracker(context: ActorContext[WordTracker.Command]) 
  extends AbstractBehavior[WordTracker.Command](context) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // In-memory storage (in production, this would be a database)
  private val sessions = mutable.Map[String, SessionData]()
  
  case class SessionData(
    sessionId: String,
    words: mutable.Set[String] = mutable.Set.empty,
    sentences: mutable.ListBuffer[String] = mutable.ListBuffer.empty,
    var lastActivity: LocalDateTime = LocalDateTime.now()
  )
  
  import WordTracker._
  
  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case TrackWords(request) =>
      logger.info(s"Tracking words for session: ${request.sessionId}")
      
      val sessionData = sessions.getOrElseUpdate(request.sessionId, SessionData(request.sessionId))
      
      // Extract words from text (simple word splitting)
      val words = request.text.split("\\s+").filter(_.nonEmpty).map(_.toLowerCase)
      sessionData.words ++= words
      sessionData.sentences += request.text
      sessionData.lastActivity = LocalDateTime.now()
      
      logger.info(s"Tracked ${words.length} words for session ${request.sessionId}. Total words: ${sessionData.words.size}")
      
      // In a real implementation, you would save to database here
      saveToDatabase(request.sessionId, words, request.text, request.sourceLanguage)
      
      request.replyTo ! WordsTracked(
        sessionId = request.sessionId,
        wordCount = sessionData.words.size,
        success = true
      )
      
      Behaviors.same
      
    case GetSessionStats(sessionId, replyTo) =>
      sessions.get(sessionId) match {
        case Some(data) =>
          replyTo ! SessionStats(
            sessionId = sessionId,
            wordCount = data.words.size,
            sentenceCount = data.sentences.size,
            lastActivity = data.lastActivity
          )
        case None =>
          replyTo ! SessionStats(
            sessionId = sessionId,
            wordCount = 0,
            sentenceCount = 0,
            lastActivity = LocalDateTime.now()
          )
      }
      Behaviors.same
  }
  
  private def saveToDatabase(sessionId: String, words: Array[String], sentence: String, language: Language): Unit = {
    // TODO: Implement actual database persistence
    // For now, just log the data that would be saved
    logger.debug(s"Would save to DB - Session: $sessionId, Words: ${words.mkString(", ")}, Sentence: $sentence, Language: $language")
    
    // Example database operations:
    // - Save session if not exists
    // - Save words with frequency counts
    // - Save sentence with metadata
    // - Update user progress
  }
} 