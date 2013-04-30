package twitchbot;

abstract class ClientCommand(fromServer: Server, fromChannel: Channel, fromUser: String)

case class Connect(fromServer: Server, fromChannel: Channel, fromUser: String, connectTo: Server, joinChannel: Channel)
  extends ClientCommand(fromServer, fromChannel, fromUser: String)

case class Join(fromServer: Server, fromChannel: Channel, fromUser: String, join: Channel)
  extends ClientCommand(fromServer, fromChannel, fromUser)