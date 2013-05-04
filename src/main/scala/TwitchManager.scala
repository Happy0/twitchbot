package twitchbot

import akka.actor.Actor
import scala.io.Source
import akka.actor.ActorRef
import akka.actor.FSM
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import net.liftweb.json.parse
import net.liftweb.json.JField
import net.liftweb.json.JString
import net.liftweb.json.JObject

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
    if (subscribers.find(p => p.channel == sub.channel).isDefined) {
      sub.actor ! AlreadySubscribed(name)
      this
    } else {
      copy(subscribers = sub :: subscribers)
    }

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
      if (followed.twitchUsers.isEmpty) stay using followed else {

        val commaList = followed.twitchUsers.foldLeft("") {
          case (str, (key, value)) =>
            str + key + ","
        }.dropRight(1)
        val url = "http://api.justin.tv/api/stream/list.json?channel="
        println("url is" + url)
        val json = Source.fromURL(url + commaList).mkString
        val parsed = parse(json)
        val removed =  parsed remove {
          case JField(channel, _) => channel == "channel"
          case _ => false
        }
        
        println(parsed)

        val streaming = for {

          JField("login", JString(login)) <- parsed
          JField("up_time", JString(date)) <- parsed
          
          // work out how to extract the title (there is an inner title also)
          
        } yield (login.toString(), date.toString(), " ")

        println("Streaming: " + streaming)

        val updated = streaming.map {
          case (login, date, title) =>
            val twitchuser = followed.twitchUsers.get(login).get
            if (!(twitchuser.lastAnnounced == date)) {
              twitchuser.subscribers.foreach(a => a.actor ! Streaming(a.channel, login, title)) // Please don't kill me, Ornicar
              twitchuser.name -> twitchuser.copy(lastAnnounced = date)
            } else {
              twitchuser.name -> twitchuser
            }
        } toMap

        stay using followed.copy(twitchUsers = updated) // placeholder
      }

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