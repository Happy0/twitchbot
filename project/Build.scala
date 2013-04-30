import sbt._
import Keys._

trait Resolvers {
  val typesafe = "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
}

trait Dependencies {
  val akka = "com.typesafe.akka" %% "akka-actor" % "2.1.2"

}

object ApplicationBuild extends Build with Resolvers with Dependencies {

  private val buildSettings = Project.defaultSettings ++ Seq(
    scalaVersion := "2.10.1",
    version := "1",
    resolvers := Seq(typesafe),
    libraryDependencies := Seq(akka),
    scalacOptions := Seq("-deprecation", "-unchecked", "-feature", "-language:_"))

  lazy val main = Project("twitchbot", file("."), settings = buildSettings)
}
