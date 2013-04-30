package twitchbot;
import akka.actor.ActorSystem
import akka.actor.Props

case class TwitchBot(username: String,
  server: String,
  port: Int,
  chan: String) {

  val system = ActorSystem("twitchbot")
  
  val ircClient = system.actorOf(Props(new IrcClient(system)))
  
  ircClient ! Connect(Server(server,server, port, List(chan), username))
  
  val twitchActor = ???

}

object TwitchBot {
  //@TODO: Check for invalid hostname/IP. Persist the configuration.

  def main(args: Array[String]) {
    val username = args.find(str => str.startsWith("nick=")).fold("[Reu]TwitchBot")(a => a.dropWhile(_ == ' ').drop(5))
    val server = args.find(str => str.startsWith("server=")).fold("irc.quakenet.org")(a => a.dropWhile(_ == ' ').drop(7))
    val port = toInt(args.find(str => str.startsWith("port=")).fold("6667")(a => a.dropWhile(_ == ' ').drop(5)))
    val chan = args.find(str => str.startsWith("chan=")).fold("#redditeux")(a => a.dropWhile(_ == ' ').drop(5))

    validateInput(username, server, port, chan) match {
      case None =>
        val bot = TwitchBot(username, server, port getOrElse 6667, chan)
      case Some(str) =>
        println(str + ", exitting.")
        System.exit(1)
    }
  }

  /** Returns a Some with an explanation string if invalid input, otherwise returns Null*/
  def validateInput(username: String, server: String, port: Option[Int], chan: String): Option[String] = {
    val validUsername = if (username.head.isLetter && username.forall(x => x.isLetter || x.isDigit)) None else Some("Invalid username format")
    val validPort = if (port.isDefined) None else Some("Invalid port")
    val validChan = if (chan.startsWith("#") && chan.forall(x => x != '_')) None else Some("Invalid channel format")

    Seq(validUsername, validPort, validChan).flatten.headOption
  }

  def toInt(s: String): Option[Int] = {
    try {
      Some(s.toInt)
    } catch {
      case e: Exception => None
    }
  }
}