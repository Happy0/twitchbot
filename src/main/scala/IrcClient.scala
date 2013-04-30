package twitchbot;

import akka.actor.Actor
import akka.actor.IOManager
import akka.actor.ActorSystem
import akka.actor.IO

class IrcClient(actorSystem: ActorSystem) extends Actor {

  //@TODO: State for list of servers, handle read (Iteratee?)
  
  def receive = {
    
    case Connect(server) => IOManager(actorSystem).connect(server.address, server.port)

    case IO.Connected(socket, address) =>
      println("Successfully connected to " + address)

    case IO.Closed(socket: IO.SocketHandle, cause) =>
      println("Socket has closed, cause: " + cause)

    case IO.Read(socket, bytes) =>
      println("Received incoming data from socket")

  }
}



