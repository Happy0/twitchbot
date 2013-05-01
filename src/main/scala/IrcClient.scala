package twitchbot;
import akka.actor.Actor
import akka.actor.IO
import akka.actor.IOManager
import akka.util.ByteString
import akka.actor.FSM

sealed trait IRCServerMessage
case class ChannelMessage(user: String, channel: String, message: String) extends IRCServerMessage
case class PING(number: String) extends IRCServerMessage
case object Registered extends IRCServerMessage
case object NoInterest extends IRCServerMessage

sealed trait ClientState
case object Connected extends ClientState
case object FullyConnected extends ClientState
case object Disconnected extends ClientState

sealed trait ClientData
case class ResponsibleFor(server: Server, socket: IO.SocketHandle) extends ClientData

class IRCClient(server: Server, ircManager: IRCManager) extends Actor with FSM[ClientState, ClientData] {

  val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)
  val ioManager = IOManager(context.system)

  startWith(Disconnected, ResponsibleFor(server, ioManager.connect(server.address, server.port)))

  when(Disconnected) {

    case Event(IO.Connected(socket, address), responsable: ResponsibleFor) =>
      val botName = responsable.server.username

      state(socket) flatMap (_ => IRCClient.processMessage(socket, self))
      println("Successfully connected to " + address)

      // Send the bot's nickname to the server
      IRCClient.writeMessage(socket, String.format("NICK %s", botName))
      IRCClient.writeMessage(socket, String.format("USER %s 0 * :%s", botName, botName))

      // Join the channels that the bot is configured to join

      goto(Connected) using responsable.copy(socket = socket)
  }

  when(Connected) {
    case Event(Registered, responsible: ResponsibleFor) =>
      println("Actor: Registered!")
      goto(FullyConnected) using responsible
  }

  onTransition {
    case Connected -> FullyConnected =>
      println("Transitioning to FullyConnected")
      stateData match {
        case ResponsibleFor(server, socket) => server.channels.foreach(channel => IRCClient.joinChannel(socket, channel))
      }

  }

  when(FullyConnected) {
    case Event(ChannelMessage(user, channel, message), responsible: ResponsibleFor) =>
      stay using responsible

  }

  whenUnhandled {
    case Event(IO.Read(socket, bytes), server: ResponsibleFor) =>
      state(socket)(IO Chunk bytes)
      stay using server

    case Event(IO.Closed(socket: IO.SocketHandle, cause), server: ResponsibleFor) =>
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

      //@TODO: Parse for '001' code => Completed registration with server. This will allow for transition into next state.
    } else if (line.startsWith(":")) {
      val commandLine = line.drop(1).takeWhile(c => c != ':')
      val commandSplit = commandLine.split(" ").toList

      val prefix = commandSplit(0)
      val command = commandSplit(1)
      val params = commandSplit.drop(2)

      val trailing = line.drop(commandLine.length() + 1)

      command match {
        case "001" => Registered
        case "PRIVMSG" => ChannelMessage(prefix.takeWhile(c => c != '!'), params(0), trailing)
        case _ => NoInterest
      }

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
        val message = IRCMessageParser.parse(line)

        message match {
          case PING(number) => writeMessage(socket, String.format("PONG :%s", number))
          case NoInterest =>
          case anyThingElse => actor ! anyThingElse
        }

      }

    }

  }
  def writeMessage(socket: IO.SocketHandle, out: String) = socket.write(ByteString(out + "\r\n"))

  def joinChannel(socket: IO.SocketHandle, channel: Channel) = {
    writeMessage(socket, "JOIN " + channel.name)
  }

}