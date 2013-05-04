package twitchbot;
import net.liftweb.json.JsonDSL._

case class Server(servername: String,
  address: String,
  port: Int,
  channels: Map[String, Channel],
  username: String) {

  def addChannel(channel: Channel): Server = {
    copy(channels = channels + (channel.name -> channel))
  }

  def addSubscription(channel: Channel, subscription: String): Option[Server] = {
    val chan = channels.get(channel.name)

    chan.fold[Option[Server]](None) { a =>
      val updatedSub = a.copy(registeredStreams = subscription :: a.registeredStreams)
      val newServer = copy(channels = channels.updated(channel.name, updatedSub))
      Some(newServer)

    }

  }

  val toJson = {
    ("servername" -> servername) ~
      ("port" -> port) ~
      ("address" -> address) ~
      ("username" -> username) ~
      ("channels" ->
        ("name" -> channels.map {
          case (string, channel) =>
            channel.registeredStreams
        }))
  }

}
