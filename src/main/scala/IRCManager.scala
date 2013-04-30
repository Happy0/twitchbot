package twitchbot;

import akka.actor.Actor
import akka.actor.IOManager
import akka.actor.ActorSystem

class IRCManager(servers: List[Server]) extends Actor {

  val ioManager = IOManager(context.system)

  //@TODO: State for list of servers, handle read (Iteratee?)
  def receive = {
    case _ => ???
  }

}



