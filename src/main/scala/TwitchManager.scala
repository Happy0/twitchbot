package twitchbot

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.FSM
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

case class SuccessfullySubscribed(channel: Channel, stream: String)
case class UnSuccessfulSubscribe(channel: Channel, stream: String)

case class Subscribe(actor: ActorRef, channel: Channel, stream: String)
case class UnSubscribe(actor: ActorRef, channel: Channel, stream: String)
private case object PollTwitch

sealed trait TwitchState

case object Open extends TwitchState
case object FullySubscribed extends TwitchState

case class TwitchUser(name: String, lastAnnounced: String, subscribers: List[Subscriber]) {
  def addSubscriber(sub: Subscriber): TwitchUser = {
    copy(subscribers = sub :: subscribers)
  }

  def updateLastAnnounced(lastAnnouncedShow: String): TwitchUser = {
    copy(lastAnnounced = lastAnnouncedShow)
  }
}
case class Subscriber(actor: ActorRef, channel: Channel, stream: String)

sealed trait TwitchData
case class Followed(twitchUsers: Map[String, TwitchUser]) extends TwitchData

// http://api.justin.tv/api/stream/list.json?channel=wcs_europe

//@TODO: Initialise with a persisted configuration
case class TwitchManager(capacity: Int) extends Actor with FSM[TwitchState, TwitchData] {

  setTimer("poll", PollTwitch, FiniteDuration(1, TimeUnit.MINUTES), true)

  startWith(Open, Followed(Map.empty[String, TwitchUser]))

  when(Open) {
    case Event(PollTwitch, followed: Followed) =>
      stay using followed // placeholder

    case Event(Subscribe(actor: ActorRef, channel: Channel, stream: String), followed: Followed) =>
      val subscriber = Subscriber(actor, channel, stream)
      val map = followed.twitchUsers
      val entry = map.get(stream)
      val newMap: Map[String, TwitchUser] =
        entry.fold(map + (stream -> TwitchUser(stream, "", List(subscriber))))(
          x => map.updated(stream, x.addSubscriber(subscriber)))

      val newFollowed = followed.copy(twitchUsers = newMap)
      actor ! SuccessfullySubscribed(channel, stream)
      if (newMap.size < 100) stay using newFollowed else goto(FullySubscribed) using (newFollowed)

    case Event(UnSubscribe(actor, channel, stream), followed: Followed) =>
      val newFollowed = unSubscribe(actor, channel, stream, followed)
      stay using followed // placeholder

  }

  when(FullySubscribed) {
    //@TODO: Alert subscriber that we're fully subscribed => Using futures?
    case Event(Subscribe(actor, channel, stream), followed: Followed) =>
      actor ! UnSuccessfulSubscribe(channel, stream)
      stay using followed

    case Event(UnSubscribe(actor, channel, stream), followed: Followed) =>
      val newFollowed = unSubscribe(actor, channel, stream, followed)
      if (newFollowed.twitchUsers.size < capacity) goto(Open) using newFollowed else stay using newFollowed

  }

  whenUnhandled {
    case Event(PollTwitch, followed: Followed) =>
      stay using followed
  }

  def unSubscribe(actor: ActorRef, channel: Channel, stream: String, followed: Followed): Followed = {
    val map = followed.twitchUsers
    val entry = map.get(stream)
    entry.fold(followed) { a =>
      val subscribers = a.subscribers
      val updatedSubscribers = subscribers.filterNot(a => a.channel == channel && a.stream == stream)
      val newMap = if (updatedSubscribers.isEmpty) map - stream else map.updated(stream, a.copy(subscribers = updatedSubscribers))

      followed.copy(twitchUsers = newMap)
    }
  }

}