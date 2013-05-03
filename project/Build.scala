import sbt._
import Keys._

trait Resolvers {
  val sonatype = "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  val typesafe = "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
}

trait Dependencies {
  val akka = "com.typesafe.akka" %% "akka-actor" % "2.1.2"
  val lift_json = "net.liftweb" %% "lift-json" % "2.5-M4"
}

object ApplicationBuild extends Build with Resolvers with Dependencies {

  private val buildSettings = Project.defaultSettings ++ Seq(
    scalaVersion := "2.10.1",
    version := "1",
    resolvers := Seq(typesafe, sonatype),
    libraryDependencies := Seq(akka, lift_json),
    scalacOptions := Seq("-deprecation", "-unchecked", "-feature", "-language:_"))

  lazy val main = Project("twitchbot", file("."), settings = buildSettings)
}
