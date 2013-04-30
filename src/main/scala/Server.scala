package twitchbot;

case class Server(servername: String,
  address: String,
  port: Int,
  channels: List[Channel],
  username: String) {

  def addChannel(channel: Channel): Server = {
    copy(channels = channel :: channels)
  }

}
