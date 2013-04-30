package twitchbot;

abstract class ClientCommand

case class Connect(server: Server) extends ClientCommand
  
case class Join(server: Server, channel: String) extends ClientCommand