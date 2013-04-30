package twitchbot;
import akka.actor.Actor
import akka.actor.IO
import akka.actor.IOManager



class IRCClient(server: Server) extends Actor {
  
  val ioManager = IOManager(context.system)
  
  def receive = {

    case IO.Connected(socket, address) =>
      println("Successfully connected to " + address)

    case IO.Closed(socket: IO.SocketHandle, cause) =>
      println("Socket has closed, cause: " + cause)

    case IO.Read(socket, bytes) =>
      println("Received incoming data from socket")

  }

}