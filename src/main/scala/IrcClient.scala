package twitchbot;
import akka.actor.Actor
import akka.actor.IO
import akka.actor.IOManager
import akka.util.ByteString
import akka.actor.FSM

sealed trait IRCServerMessage
case class ChannelMessage(user: String, channel: String, message: String) extends IRCServerMessage
case class PING(number: String) extends IRCServerMessage
case object NoInterest extends IRCServerMessage

sealed trait ClientState
case object Connected extends ClientState
case object Disconnected extends ClientState

sealed trait ClientData
case class ResponsableFor(server: Server, socket: IO.SocketHandle) extends ClientData

class IRCClient(server: Server, ircManager: IRCManager) extends Actor with FSM[ClientState, ClientData] {

  val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)
  val ioManager = IOManager(context.system)
  val scket = ioManager.connect(server.address, server.port)

  startWith(Disconnected, ResponsableFor(server, scket))

  when(Disconnected) {

    case Event(IO.Connected(socket, address), responsable: ResponsableFor) =>
      val botName = responsable.server.username

      state(socket) flatMap (_ => IRCClient.processMessage(socket, self))
      println("Successfully connected to " + address)

      // Send the bot's nickname to the server
      IRCClient.writeMessage(socket, String.format("NICK %s", botName))
      IRCClient.writeMessage(socket, String.format("USER %s 0 * :%s", botName, botName))

      goto(Connected) using responsable.copy(socket = socket)
  }

  when(Connected) {

    case Event(IO.Read(socket, bytes), server: ResponsableFor) =>

      state(socket)(IO Chunk bytes)
      stay using server

    case Event(IO.Closed(socket: IO.SocketHandle, cause), server: ResponsableFor) =>
      //@TODO: Attempt to reconnect => Using a timer?
      state(socket)(IO EOF)
      state -= socket
      println("Socket has closed, cause: " + cause)
      goto(Disconnected) using server

  }

}

object IRCMessageParser {

  def ascii(bytes: ByteString): String = bytes.decodeString("US-ASCII").trim

  def parse(bytes: ByteString): IRCServerMessage = {
    val line = ascii(bytes)

    if (line.startsWith("PING :")) {
      val number = line.drop(6)
      PING(number)
    } else {
      NoInterest
    }

  }
}

object IRCClient {
  import akka.actor.ActorRef

  /** @actorRef : The actor to send a parsed IRCMessage to when available */
  def processMessage(socket: IO.SocketHandle, actor: ActorRef): IO.Iteratee[Unit] = {
    IO repeat {
      for {
        line <- IO takeUntil ByteString("\r\n")
      } yield {
        println(IRCMessageParser.ascii(line))

        val theLine = line
        val message = IRCMessageParser.parse(theLine)

        message match {
          case PING(number) => writeMessage(socket, String.format("PONG :%s", number))
          case ChannelMessage(user, string, message) =>
          case NoInterest =>
        }

      }

    }

  }
  def writeMessage(socket: IO.SocketHandle, out: String) = socket.write(ByteString(out + "\r\n"))

}