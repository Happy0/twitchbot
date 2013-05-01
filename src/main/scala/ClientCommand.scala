package twitchbot;

abstract class ClientCommand(fromServer: Server, fromChannel: Channel, fromUser: String)

/** Instructs the bot to connect to a server */
case class Connect(fromServer: Server, fromChannel: Channel, fromUser: String, connectTo: Server, joinChannel: Channel)
  extends ClientCommand(fromServer, fromChannel, fromUser: String)

/** Instructs the bot to join a channel on the current server */
case class Join(fromServer: Server, fromChannel: Channel, fromUser: String, join: Channel)
  extends ClientCommand(fromServer, fromChannel, fromUser)