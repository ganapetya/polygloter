ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.shakti"

// Add Akka repository for latest releases
resolvers += "Akka Repository" at "https://repo.akka.io/maven"

lazy val root = (project in file("."))
  .settings(
    name := "shakti-actors",
    
    libraryDependencies ++= Seq(
      // Akka Core (from com.typesafe.akka) - Akka 2.10.6
      "com.typesafe.akka" %% "akka-actor-typed" % "2.10.6",
      "com.typesafe.akka" %% "akka-stream" % "2.10.6",
      "com.typesafe.akka" %% "akka-serialization-jackson" % "2.10.6",
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.10.6" % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.10.6" % Test,
      "com.typesafe.akka" %% "akka-slf4j" % "2.10.6",

      // Akka HTTP (from com.typesafe.akka) - Akka 2.10.6
      "com.typesafe.akka" %% "akka-http" % "10.7.1",
      "com.typesafe.akka" %% "akka-http-testkit" % "10.7.1" % Test,

      // JSON parsing
      "io.spray" %% "spray-json" % "1.3.6",

      // ScalaTest
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.11",
      
      // Micrometer for JVM metrics (works with Prometheus)
      "io.micrometer" % "micrometer-core" % "1.11.5",
      "io.micrometer" % "micrometer-registry-prometheus" % "1.11.5",
      
      // Metrics for JVM and application monitoring
      "io.dropwizard.metrics" % "metrics-core" % "4.2.21",
      "io.dropwizard.metrics" % "metrics-jvm" % "4.2.21",
      "io.dropwizard.metrics" % "metrics-healthchecks" % "4.2.21",
      
      // Prometheus metrics export
      "io.prometheus" % "simpleclient" % "0.16.0",
      "io.prometheus" % "simpleclient_hotspot" % "0.16.0",
      "io.prometheus" % "simpleclient_httpserver" % "0.16.0"
    ),
    
    // Compiler options
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    ),
    
    // Fork tests to avoid conflicts
    Test / fork := true,
    
    // Assembly settings
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.first
      case PathList("reference.conf") => MergeStrategy.concat
      case PathList("application.conf") => MergeStrategy.concat
      case "logback.xml" => MergeStrategy.first
      case x => MergeStrategy.first
    },
    
    // Main class for assembly
    assembly / mainClass := Some("com.shakti.actors.Main")
  ) 