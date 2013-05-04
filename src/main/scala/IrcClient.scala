package twitchbot;
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.IO
import akka.actor.IOManager
import akka.util.ByteString
import akka.actor.FSM
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import akka.actor.Props
import collection.immutable.Queue

case object Reconnect
case object SendQueueMessage

case class Streaming(channel: Channel, user: String, title: String)
case class AlreadySubscribed(channel: Channel, user: String)
case class NotATwitchUser(channel: Channel, user: String)

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
case class ResponsibleFor(server: Server, socket: IO.SocketHandle, messageQueue: Queue[String]) extends ClientData

class IRCClient(server: Server, ircManager: ActorRef, twitchManager: ActorRef) extends Actor with FSM[ClientState, ClientData] {

  val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)
  val ioManager = IOManager(context.system)

  startWith(Disconnected, ResponsibleFor(server, ioManager.connect(server.address, server.port), Queue.empty[String]))

  when(Disconnected) {
    case Event(Reconnect, responsible: ResponsibleFor) =>
      goto(Reconnecting) using responsible.copy(messageQueue = Queue.empty[String])
  }

  when(Connected) {

    case Event(Registered, responsible: ResponsibleFor) =>
      val queue = responsible.server.channels.foldLeft(responsible.messageQueue) {
        case (queue: Queue[String], (key, channel)) =>
          IRCClient.joinChannel(queue, channel)
      }

      // Subscribe to the feeds for each channel
      responsible.server.channels.foreach {
        case (key, channel) =>
          channel.registeredStreams.foreach(a => twitchManager ! Subscribe(self, channel, a))
      }

      goto(FullyConnected) using responsible.copy(messageQueue = queue)
  }

  when(FullyConnected) {
    case Event(AddStream(streamName, msg), responsible: ResponsibleFor) =>
      val channel = responsible.server.channels.get(msg.channel)
      val newQueue = channel.fold(
        IRCClient.writeToChannel(responsible.messageQueue, msg.channel, "Unlikely error")) {
          a =>
            twitchManager ! Subscribe(self, a, streamName)
            responsible.messageQueue
        }

      stay using responsible.copy(messageQueue = newQueue)

    case Event(NotATwitchUser(channel, stream), responsible: ResponsibleFor) =>
      val queue = IRCClient.writeToChannel(responsible.messageQueue, channel.name, stream + " is not a twitch login.")
      stay using responsible.copy(messageQueue = queue)

    case Event(SuccessfullySubscribed(channel, stream), responsible: ResponsibleFor) =>
      val server = responsible.server.addSubscription(channel, stream)

      val newresponsible = server.fold {
        responsible.copy(messageQueue = IRCClient.writeToChannel(
          responsible.messageQueue, channel.name, "This shouldn't happen. Contact Happy0 ;x"))
      }(f =>
        responsible.copy(server = f, messageQueue = IRCClient.writeToChannel(
          responsible.messageQueue, channel.name, "Subscribed to stream notifications for " + stream)))
      stay using newresponsible

    case Event(Streaming(channel, user, title), responsible: ResponsibleFor) =>
      val queue =
        IRCClient.writeToChannel(responsible.messageQueue, channel.name, user + " has began streaming [ " + title + " ]")
      stay using responsible.copy(messageQueue = queue)

  }

  onTransition {
    // @TODO: Count the number of times we reconnect
    case _ -> Disconnected =>
      setTimer("reconnect", Reconnect, FiniteDuration(1, TimeUnit.MINUTES), false)
      cancelTimer("message")

    case Disconnected -> _ =>
      setTimer("message", SendQueueMessage, FiniteDuration(300, TimeUnit.MILLISECONDS), true)

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

    case Event(SendQueueMessage, responsible: ResponsibleFor) =>
      val queue = responsible.messageQueue

      if (!queue.isEmpty) {
        val (msg, newQueue) = queue.dequeue
        IRCClient.writeMessage(responsible.socket, msg)
        stay using responsible.copy(messageQueue = newQueue)
      } else {
        stay using responsible
      }
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

      val trailing = line.drop(commandLine.length() + 2)

      command match {
        case "001" => Registered
        case "PRIVMSG" =>
          val chanMsg = ChannelMessage(prefix.takeWhile(c => c != '!'), params(0), trailing)
          println("chanMsg" + chanMsg)
          if (chanMsg.message.startsWith("!watch")) {
            val twitchName = chanMsg.message.drop(7).takeWhile(c => c != ' ') // @TODO: Check for invalid format
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

  def writeToChannel(queue: Queue[String], channelName: String, message: String): Queue[String] = {
    queue.enqueue("PRIVMSG " + channelName + " :" + message)
  }

  def writeMessage(socket: IO.SocketHandle, out: String) = socket.write(ByteString(out + "\r\n"))

  def joinChannel(queue: Queue[String], channel: Channel): Queue[String] = {
    queue.enqueue("JOIN " + channel.name)
  }

}