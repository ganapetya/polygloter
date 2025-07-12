package com.shakti.actors.messages

import akka.actor.typed.ActorRef

// Language codes
sealed trait Language
case object Russian extends Language
case object English extends Language
case object German extends Language
case object Norwegian extends Language
case object Hebrew extends Language
case object Ukrainian extends Language

object Language {
  def fromString(code: String): Language = code.toLowerCase match {
    case "ru" | "russian" => Russian
    case "en" | "english" => English
    case "de" | "german" => German
    case "no" | "norwegian" => Norwegian
    case "he" | "hebrew" => Hebrew
    case "uk" | "ukrainian" => Ukrainian
    case _ => Norwegian // default to Norwegian
  }
  
  def toString(lang: Language): String = lang match {
    case Russian => "ru"
    case English => "en"
    case German => "de"
    case Norwegian => "no"
    case Hebrew => "he"
    case Ukrainian => "uk"
  }
}

// Translation request/response messages
case class TranslationRequest(
  text: String,
  sourceLanguage: Language,
  targetLanguages: List[Language],
  replyTo: ActorRef[TranslationResponse]
)

case class TranslationResponse(
  originalText: String,
  translations: Map[Language, String],
  success: Boolean,
  error: Option[String] = None
)

// Session management messages
case class StartSession(
  sessionId: String,
  userId: Option[String] = None,
  replyTo: ActorRef[SessionStarted]
)

case class SessionStarted(
  sessionId: String,
  success: Boolean,
  error: Option[String] = None
)

case class EndSession(
  sessionId: String,
  replyTo: ActorRef[SessionEnded]
)

case class SessionEnded(
  sessionId: String,
  success: Boolean
)

// Word tracking messages
case class TrackWords(
  sessionId: String,
  text: String,
  sourceLanguage: Language,
  replyTo: ActorRef[WordsTracked]
)

case class WordsTracked(
  sessionId: String,
  wordCount: Int,
  success: Boolean,
  error: Option[String] = None
)

// Text-to-Speech messages
case class TTSRequest(
  text: String,
  language: Language,
  sessionId: String,
  voiceId: Option[String] = None,
  speakingRate: Option[Double] = Some(0.75), // Default speaking rate 0.75
  replyTo: ActorRef[TTSResponse]
)

case class TTSResponse(
  originalText: String,
  audioUrl: Option[String],
  success: Boolean,
  error: Option[String] = None
)

// Analysis messages
case class AnalysisRequest(
  contextText: String,
  textToAnalyze: String,
  inputLanguage: Language,
  outputLanguage: Language,
  sessionId: String,
  replyTo: ActorRef[AnalysisResponse]
)

case class AnalysisResponse(
  contextText: String,
  textToAnalyze: String,
  inputLanguage: Language,
  outputLanguage: Language,
  analysisHtml: String,
  success: Boolean,
  error: Option[String] = None
) 