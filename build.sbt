name := "ContribsGH2-P-C"

version := "0.1"

organization := "com.avargasv"

scalaVersion := "2.13.10"

scalacOptions ++= Seq("-deprecation", "-unchecked")

val AkkaVersion       = "2.6.19"
val AkkaHttpVersion   = "10.2.9"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"     % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream"          % AkkaVersion,
  "com.typesafe.akka" %% "akka-http"            % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "ch.qos.logback"    %  "logback-classic"      % "1.2.11",
  "com.github.kstyrc" %  "embedded-redis"       % "0.6",
  "redis.clients"     % "jedis"                 % "3.2.0"
)
