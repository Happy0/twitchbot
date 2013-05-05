package twitchbot;
import akka.actor.ActorSystem
import akka.actor.Props
import java.io.File

case class TwitchBot(
  servers: List[Server],
  configFile: File) {

  val system = ActorSystem("twitchbot")

  val twitchManager = system.actorOf(Props(new TwitchManager))
  val ircManager = system.actorOf(Props(new IRCManager(servers, twitchManager, configFile)))

  twitchManager ! Start
  ircManager ! Initialise

}

object TwitchBot {
  //@TODO: Check for invalid hostname/IP. Persist the configuration.

  def main(args: Array[String]) {
    val fileDir = new File(getClass.getClassLoader.getResource("config/").getPath.toString())
    val file = new File(fileDir + "config.txt")

    if (true || !file.exists() || args.length > 0) {
      println("config file doesn't exist, creating config file")
      file.createNewFile()

      val username = args.find(str => str.startsWith("nick=")).fold("TwitchBotx0rz")(a => a.drop(5))
      val server = args.find(str => str.startsWith("server=")).fold("irc.quakenet.org")(a => a.drop(7))
      val port = toInt(args.find(str => str.startsWith("port=")).fold("6667")(a => a.drop(5)))
      val chan = args.find(str => str.startsWith("chan=")).fold("#redditeutests")(a => a.drop(5))

      validateInput(username, server, port, chan) match {
        case None =>
          TwitchBot(List(Server(server, server, port getOrElse 6667, Map(chan -> Channel(chan, List.empty[String])), username)), file)
        case Some(str) =>
          println(str + ", exitting.")
          System.exit(1)
      }

    } else {
      // Read the config from the configuration file...

    }

  }

  /** Returns a Some with an explanation string if invalid input, otherwise returns None*/
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