# Speaking Rate Feature

This document describes how to use the new `speaking_rate` feature in the TTS API.

## Overview

The `speaking_rate` parameter controls the speed of speech synthesis in Google TTS. It accepts 4 discrete values:
- `0.25` - Very slow speech
- `0.5` - Slow speech
- `0.75` - Normal speech (default)
- `1.0` - Fast speech

## API Usage

### TTS Request with Speaking Rate

```json
POST /api/tts
Content-Type: application/json

{
  "text": "Hello world, this is a test of the speaking rate feature",
  "language": "en",
  "voiceId": "en-AU-Chirp-HD-O",
  "speakingRate": 0.5
}
```

### Response

```json
{
  "status": "processing",
  "requestId": "123456789",
  "message": "TTS request sent"
}
```

### Get TTS Result

```json
GET /api/tts/result/123456789

{
  "success": true,
  "originalText": "Hello world, this is a test of the speaking rate feature",
  "audioUrl": "data:audio/mp3;base64,//audio_content_here//",
  "error": null
}
```

## Default Behavior

- If `speakingRate` is not provided, it defaults to `0.75`
- If an invalid value is provided (not one of 0.25, 0.5, 0.75, 1.0), it will default to `0.75` and log a warning

## Integration with Google TTS

The `speakingRate` parameter is passed directly to Google Cloud TTS AudioConfig as `speaking_rate`, which controls the speed of the synthesized speech.

## UI Integration

In the frontend, you can add a select component with these options:
- "Very Slow (0.25x)"
- "Slow (0.5x)" 
- "Normal (0.75x)" - Default
- "Fast (1.0x)" 