# Multi-stage build for Scala application
FROM sbtscala/scala-sbt:openjdk-17.0.2_1.7.1_3.2.0 as builder

# Set working directory
WORKDIR /app

# Copy build files first (for better caching)
COPY build.sbt .
COPY project/ project/

# Download dependencies (cached layer)
RUN sbt update

# Copy source code and resources
COPY src/ src/

# Create fat JAR with all dependencies
RUN sbt assembly

# Runtime image
FROM eclipse-temurin:17-jre-jammy

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the fat JAR
COPY --from=builder /app/target/scala-2.13/*assembly*.jar ./app.jar

# Create non-root user
RUN useradd --create-home --shell /bin/bash shakti \
    && chown -R shakti:shakti /app
USER shakti

# Expose ports
EXPOSE 8080 9001

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
CMD ["java", "-Djava.awt.headless=true", "-Xmx512m", "-jar", "app.jar"] 