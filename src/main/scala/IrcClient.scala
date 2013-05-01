package twitchbot;
import akka.actor.Actor
import akka.actor.IO
import akka.actor.IOManager
import akka.util.ByteString
import akka.actor.FSM

case class IRCMessage(user: String, channel: String, message: String)

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

    case Event(IO.Connected(socket, address), server: ResponsableFor) =>
      state(socket) flatMap (_ => IRCClient.processMessage(socket, self))
      println("Successfully connected to " + address)

      goto(Connected) using server.copy(socket = socket)

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

  def parse(bytes: ByteString): IRCMessage = {
    ???

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
        val theLine = line
        println(IRCMessageParser.ascii(theLine))

      }

    }

  }

}