package twitchbot;
import akka.actor.Actor
import akka.actor.IO
import akka.actor.IOManager
import akka.util.ByteString

case class IRCMessage(user: String, channel: String, message: String)

sealed trait ClientState
case object Connected extends ClientState
case object Disconnected extends ClientState

sealed trait ClientData
case class ResponsableFor(server: Server) extends ClientData

class IRCClient(server: Server, ircManager: IRCManager) extends Actor {

  val ioManager = IOManager(context.system)

  val socket = ioManager.connect(server.address, server.port)

  def receive = {

    case IO.Connected(socket, address) =>
      println("Successfully connected to " + address)

    case IO.Closed(socket: IO.SocketHandle, cause) =>
      println("Socket has closed, cause: " + cause)

    case IO.Read(socket, bytes) => println(ascii(bytes))

  }

  def ascii(bytes: ByteString): String = bytes.decodeString("US-ASCII").trim

}