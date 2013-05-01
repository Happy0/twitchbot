package twitchbot;

import akka.actor.Actor
import akka.actor.IOManager
import akka.actor.ActorSystem
import akka.actor.FSM
import akka.actor.Props
import akka.actor.ActorRef

// Commands
case object Initialise
case class JoinChannel(server: Server, channel: Channel)

sealed trait State
case object Uninitialised extends State
case object Active extends State
case object Full extends State

sealed trait Data
case object Empty extends Data
case class ServerHandlers(serverList: Map[String, ActorRef]) extends Data

class IRCManager(servers: List[Server], twitch: ActorRef) extends Actor with FSM[State, Data] {

  startWith(Uninitialised, Empty)

  when(Uninitialised) {
    case Event(Initialise, Empty) =>
      println("Initialising!")
      val initialised = servers.map(server => server.servername -> context.actorOf(Props(new IRCClient(server, self, twitch)),
        name = server.servername))
      goto(Active) using ServerHandlers(initialised.toMap)
  }

  when(Active) {
    case Event(_, handlers: ServerHandlers) => stay using handlers
  }
}



