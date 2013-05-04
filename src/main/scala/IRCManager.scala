package twitchbot;

import akka.actor.Actor
import akka.actor.IOManager
import akka.actor.ActorSystem
import akka.actor.FSM
import akka.actor.Props
import akka.actor.ActorRef
import net.liftweb.json.JObject
import java.io.File
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

// Commands
case object Initialise
case class JoinChannel(server: Server, channel: Channel)

case class UpdateConfiguration(server: Server)
case object WriteConfiguration

sealed trait State
case object Uninitialised extends State
case object Active extends State
case object Full extends State

sealed trait Data
case object Empty extends Data
case class IRCData(serverList: Map[String, ActorRef], configurations: Map[String, JObject]) extends Data

class IRCManager(servers: List[Server], twitch: ActorRef, configFile: File) extends Actor with FSM[State, Data] {

  startWith(Uninitialised, Empty)

  when(Uninitialised) {
    case Event(Initialise, Empty) =>
      println("Initialising!")
      val initialised = servers.map(server => server.servername -> context.actorOf(Props(new IRCClient(server, self, twitch)),
        name = server.servername))
      goto(Active) using IRCData(initialised.toMap, servers.map(a => a.servername -> a.toJson) toMap)
  }

  when(Active) {
    case Event(UpdateConfiguration(server), data: IRCData) =>
      val newConfigs = data.configurations.updated(server.servername, server.toJson)
      stay using data.copy(configurations = newConfigs)

    case Event(WriteConfiguration, data: IRCData) =>
      val configurations = data.configurations
      val text = configurations.map {
        case (key, value) => value
      } mkString

      val p = new java.io.PrintWriter(configFile)
      p.write(text)
      p.flush()
      p.close()

      stay using data

  }

  onTransition {
    case Uninitialised -> Active =>
      setTimer("writeConfig", WriteConfiguration, FiniteDuration(3, TimeUnit.MINUTES), true)

  }
}



