ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.17"

lazy val root = (project in file("."))
  .settings(
    name := "podcasts1"
  )

val AkkaVersion = "2.7.0"
val AkkaHttpVersion = "10.5.0"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
)

libraryDependencies += "io.spray" %% "spray-json" % "1.3.+" // current: 1.3.6

val junixSocket = "2.6.2"
libraryDependencies += "com.github.jnr" % "jnr-unixsocket" % "0.38.19"
libraryDependencies += "com.kohlschutter.junixsocket" % "junixsocket-common" % junixSocket
libraryDependencies += "com.kohlschutter.junixsocket" % "junixsocket-core" % junixSocket pomOnly()

libraryDependencies += "org.postgresql" % "postgresql" % "42.5.+" // current: 42.5.4

val sparkVersion = "3.3.1"
libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-graphx" % sparkVersion
)
