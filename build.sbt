name := "websokket"

version := "0.1"

scalaVersion := "2.13.3"

val akkaVersion     = "2.6.6"
val akkaHttpVersion = "10.1.12"

scalacOptions ++= Seq(
  "-Xfatal-warnings",
  "-feature",
  "-deprecation",
  "-unchecked"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-http"                % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-testkit"        % akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-stream"              % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-testkit"      % akkaVersion % Test,
  "ch.qos.logback"    % "logback-classic"           % "1.2.3",
  "org.scalatest"     %% "scalatest"                % "3.1.1" % Test
)
