package twitchbot

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.FSM

case class Subscribe(stream: String)

sealed trait TwitchState
case object Open extends TwitchState
case object FullySubscribed extends TwitchState

case class TwitchUser(name: String, lastAnnounced: String)

sealed trait TwitchData
case class Followed(twitchUsers: List[(TwitchUser)]) extends TwitchData

// http://api.justin.tv/api/stream/list.json?channel=wcs_europe
class TwitchManager extends Actor with FSM[TwitchState, TwitchData] {

}