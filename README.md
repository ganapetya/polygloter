# polygloter

basic frontend + basic actors structure for full featured language learner application 

## Features

- **Akka Actor Typed**: Modern typed actor system
- **Akka Streams**: Reactive streams processing
- **Akka HTTP**: HTTP server and client capabilities
- **Akka Serialization**: Jackson-based serialization
- **Comprehensive Testing**: Akka TestKit integration
- **Logging**: Structured logging with Logback

## Prerequisites

- Java 11 or higher
- SBT 1.9.7 or higher

## Getting Started

### Running the Complete System

The Shakti Actors application requires three services to be running for full functionality (translation and text-to-speech):

1. **Terminal 1 - Translator API:**
   ```bash
   cd /home/peter/work/shakti/shakti-translator-api
   export GOOGLE_APPLICATION_CREDENTIALS="/home/peter/work/shakti/shakti-translator-api/shakti-463508-2e024286394c.json"
   python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
   ```

2. **Terminal 2 - Sound API:**
   ```bash
   cd /home/peter/work/shakti/shakti-sound-api
   export GOOGLE_APPLICATION_CREDENTIALS="/home/peter/work/shakti/shakti-sound-api/shakti-463508-2e024286394c.json"
   python -m uvicorn app.main:app --host 0.0.0.0 --port 8003 --reload
   ```

3. **Terminal 3 - Shakti Actors:**
   ```bash
   cd /home/peter/work/shakti/shakti-actors
   sbt run
   ```

### Service URLs

- **Web Interface**: http://localhost:8080
- **Translator API**: http://localhost:8000
- **Sound API**: http://localhost:8003

### Development Commands

1. **Run tests:**
   ```bash
   sbt test
   ```

2. **Compile:**
   ```bash
   sbt compile
   ```

3. **Stop all services:**
   ```bash
   pkill -f "sbt" && pkill -f "uvicorn"
   ```

## Project Structure

```
shakti-actors/
├── src/
│   ├── main/
│   │   ├── scala/
│   │   │   └── com/shakti/actors/
│   │   │       ├── Main.scala              # Application entry point
│   │   │       └── worker/
│   │   │           └── WorkerActor.scala   # Sample typed actor
│   │   └── resources/
│   │       └── logback.xml                 # Logging configuration
│   └── test/
│       └── scala/
│           └── com/shakti/actors/
│               └── WorkerActorSpec.scala   # Actor tests
├── build.sbt                               # SBT build configuration
├── project/
│   ├── build.properties                    # SBT version
│   └── plugins.sbt                         # SBT plugins
└── README.md                               # This file
```

## Dependencies

- **Akka Actor Typed**: 2.8.8
- **Akka Streams**: 2.8.8
- **Akka HTTP**: 10.5.3
- **Akka Serialization Jackson**: 2.8.8
- **Scala**: 2.13.12
- **ScalaTest**: 3.2.17
- **Logback**: 1.4.11

## SBT Plugins

- **sbt-scalafmt**: Code formatting
- **sbt-native-packager**: Application packaging
- **sbt-assembly**: Fat JAR creation

## Development

The project includes a sample `WorkerActor` that demonstrates:
- Typed actor behavior
- Message handling
- State management
- Graceful shutdown

## Testing

Tests use Akka TestKit for actor testing:
- Actor spawning and messaging
- Test probes for message verification
- Behavior testing

## Next Steps

This is a foundation project. You can extend it by:
- Adding more complex actor hierarchies
- Implementing Akka Streams for data processing
- Adding Akka HTTP endpoints
- Implementing clustering and persistence 
