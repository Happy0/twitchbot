package twitchbot;
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.IO
import akka.actor.IOManager
import akka.util.ByteString
import akka.actor.FSM
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

case object Reconnect

// A message from the IRC Server
sealed trait IRCServerMessage

// A channel message
case class ChannelMessage(user: String, channel: String, message: String) extends IRCServerMessage
case class MalformedMesssage(explanation: String, msg: ChannelMessage) extends IRCServerMessage
case class AddStream(username: String, msg: ChannelMessage) extends IRCServerMessage

case class PING(number: String) extends IRCServerMessage
case object Registered extends IRCServerMessage
case object NoInterest extends IRCServerMessage

sealed trait ClientState
case object Connected extends ClientState
case object FullyConnected extends ClientState
case object Disconnected extends ClientState
case object Reconnecting extends ClientState

sealed trait ClientData
case class ResponsibleFor(server: Server, socket: IO.SocketHandle) extends ClientData

class IRCClient(server: Server, ircManager: ActorRef, twitchManager: ActorRef) extends Actor with FSM[ClientState, ClientData] {

  val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)
  val ioManager = IOManager(context.system)

  startWith(Disconnected, ResponsibleFor(server, ioManager.connect(server.address, server.port)))

  when(Disconnected) {
    case Event(Reconnect, responsible: ResponsibleFor) =>
      ioManager.connect(responsible.server.address, responsible.server.port)
      goto(Reconnecting) using responsible

  }

  when(Connected) {
    case Event(Registered, responsible: ResponsibleFor) =>
      println("Actor: Registered!")
      goto(FullyConnected) using responsible

    case Event(AddStream(streamName, msg), responsible: ResponsibleFor) =>
      val channel = responsible.server.channels.find(a => a.name == msg.channel)
      channel.fold(IRCClient.writeMessage(responsible.socket, "Error: Please contact Happy0.")) {
        a =>
          IRCClient.writeMessage(responsible.socket, "Subscribing to" + streamName)
          twitchManager ! Subscribe(self, a, streamName)
      }

      stay using responsible
  }

  when(FullyConnected) {
    case Event(ChannelMessage(user, channel, message), responsible: ResponsibleFor) =>
      stay using responsible

  }

  onTransition {
    case Connected -> FullyConnected =>
      println("Transitioning to FullyConnected")
      stateData match {
        case ResponsibleFor(server, socket) => server.channels.foreach(channel => IRCClient.joinChannel(socket, channel))
      }

    // @TODO: Count the number of times we reconnect
    case _ -> Disconnected =>
      setTimer("reconnect", Reconnect, FiniteDuration(1, TimeUnit.MINUTES), false)

  }

  whenUnhandled {

    case Event(IO.Connected(socket, address), responsable: ResponsibleFor) =>
      val botName = responsable.server.username

      state(socket) flatMap (_ => IRCClient.processMessage(socket, self))
      println("Successfully connected to " + address)

      // Send the bot's nickname to the server
      IRCClient.writeMessage(socket, String.format("NICK %s", botName))
      IRCClient.writeMessage(socket, String.format("USER %s 0 * :%s", botName, botName))

      // Join the channels that the bot is configured to join

      goto(Connected) using responsable.copy(socket = socket)

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
        case "PRIVMSG" =>
          val chanMsg = ChannelMessage(prefix.takeWhile(c => c != '!'), params(0), trailing)
          if (chanMsg.message.startsWith("!watch")) {
            val twitchName = chanMsg.message.drop(6).takeWhile(c => c != ' ')
            AddStream(twitchName, chanMsg)

          } else {
            NoInterest
          }

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