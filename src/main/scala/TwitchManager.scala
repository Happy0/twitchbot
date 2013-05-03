package twitchbot

import akka.actor.Actor
import scala.io.Source
import akka.actor.ActorRef
import akka.actor.FSM
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

case object Start
case class SuccessfullySubscribed(channel: Channel, stream: String)
case class UnSuccessfulSubscribe(channel: Channel, stream: String)

case class Subscribe(actor: ActorRef, channel: Channel, stream: String)
case class UnSubscribe(actor: ActorRef, channel: Channel, stream: String)
private case object PollTwitch

sealed trait TwitchState

case object Uninitiated extends TwitchState
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
class TwitchManager extends Actor with FSM[TwitchState, TwitchData] {

  startWith(Uninitiated, Followed(Map.empty[String, TwitchUser]))

  when(Uninitiated) {
    case Event(Start, followed: Followed) =>
      setTimer("poll", PollTwitch, FiniteDuration(1, TimeUnit.MINUTES), true)
      goto(Open) using followed
  }

  when(Open) {
    case Event(PollTwitch, followed: Followed) =>
      val commaList = followed.twitchUsers.foldLeft("") {
        case (str, (key, value)) =>
          value + ","
      }.dropRight(1)
      val url = "http://api.justin.tv/api/stream/list.json?channel="
      val json = Source.fromURL(url + commaList)
      
      println("json is: " + json)

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
      stay using followed

  }

  when(FullySubscribed) {
    //@TODO: Alert subscriber that we're fully subscribed => Using futures?
    case Event(Subscribe(actor, channel, stream), followed: Followed) =>
      actor ! UnSuccessfulSubscribe(channel, stream)
      stay using followed

    case Event(UnSubscribe(actor, channel, stream), followed: Followed) =>
      val newFollowed = unSubscribe(actor, channel, stream, followed)
      if (newFollowed.twitchUsers.size < 100) goto(Open) using newFollowed else stay using newFollowed

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