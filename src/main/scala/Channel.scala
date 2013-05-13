package twitchbot;

//@TODO: Create a class representing a Stream
case class Channel(name: String,
  registeredStreams: List[String]) {

  /** Returns None if the channel is not in the list of watched streams. Otherwise, returns a new channel with the strea removed */
  def removeStream(streamName: String): Option[Channel] = {
    lazy val newChan = copy(registeredStreams = registeredStreams.filter(p => p != streamName))

    registeredStreams.find(c => c == streamName).fold[Option[Channel]](None)(
      a => Some(newChan))
  }
}

