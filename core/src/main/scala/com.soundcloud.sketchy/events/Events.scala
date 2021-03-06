package com.soundcloud.sketchy.events

import java.util.Date
import play.api.libs.json._

/**
 * Itra-actor (agent) message unit
 */
trait Event {
  def id: Option[Long]
  def getName: String = getClass.getName.split('.').last
  def kind: String = getName
}

/**
 * User action interface
 * Abstract representation of a user's actions on a site
 */
trait Action

case object UserEvent {
  case object Create  extends Action
  case object Destroy extends Action
  case object Update  extends Action
}

trait SpamCheck {
  def noSpamCheck: Boolean
}

trait UserEvent extends Event with SpamCheck {
  def key = UserEventKey(kind, id.getOrElse(0))

  var action: Action = UserEvent.Create
  def wasCreated: Boolean = (action == UserEvent.Create)
  def wasDeleted: Boolean = (action == UserEvent.Destroy)
  def wasUpdated: Boolean = (action == UserEvent.Update)

  def recipientId: Option[Long]
  def senderId: Option[Long]
}

trait DeleteOnUpdate {
  val deletedAt: Option[Date]
}

trait Trustable extends UserEvent {
  // trusted by policy, e.g. trusted user
  var trusted: Option[Boolean] = None
  var paying: Option[Boolean] = None
  var age: Option[Long] = None
}

/**
 * Something like a private message on the site
 */
trait MessageLike extends UserEvent with Trustable {
  def senderId: Option[Long]
  def recipientId: Option[Long]
  def content: String
  def key: UserEventKey
  def toMyself = senderId.isDefined && senderId == recipientId

  // 2-way sender-recipient interaction
  var interaction: Option[Boolean] = None

  val public: Option[Boolean]
}

/**
 * Relationship between two entities
 *
 * The history of edge changes will be separated by graph id. For example
 * a link from nodes 1 to 7, followed by a link from 7 to 1 will only be
 * counted as a backlink if the graph id is identical.
 */
trait EdgeLike extends Event {
  val sourceId: Long
  val sinkId: Long
  val edgeKind: String

  val graphId: Option[Long]
  val createdAt: Date
  val isBidirectional: Boolean

  // overlapping with UserEvent
  def senderId: Option[Long]

  def wasCreated: Boolean
}

/**
 * The user(s) did something
 */
case class UserAction(userIds: List[Long]) extends Event {
  val id = None
}

/**
 * Timer
 */
case class Tick(lastTick: Date) extends Event {
  val id = None
}

/**
 * Detected sketchy behavior
 */
case class SketchySignal(
  userId: Long,
  override val kind: String,
  items: List[Long],
  detector: String,
  strength: Double, // signal strength, [0, 1]
  createdAt: Date) extends Event {
  val id = None
}

/**
 * Reported sketchy behavior
 */
case class SpamReport(
  id: Option[Long],
  reporterId: Long,
  spammerId: Long,
  originId: Option[Long],
  originType: String,
  spamPublishedAt: Date,
  lastSignaledAt: Option[Date],
  createdAt: Date,
  updatedAt: Date) extends UserEvent {

  def senderId = Some(reporterId)
  def recipientId = Some(spammerId)

  def noSpamCheck = false
}


/**
 * Sketchy item (e.g. a page)
 */
case class SketchyItem(
  id: Long,
  kind: String,
  createdAt: Date)

/**
 * Scoring of a user
 * An aggregate view of sketchy signals over time
 */
case class SketchyScore(
  userId: Long,
  override val kind: String,
  signals: Int,
  state: Int,
  score: Double,
  probability: Double,
  lastSignaledAt: Date,
  createdAt: Date) extends Event {

  val id = None

  // prior probability of being an abusive user
  val prior: Double = 0.1

  // half-life in days
  val halfLife: Int = 30

  def decayed(date: Date): SketchyScore = {
    val newState = decayedState(date)
    val newScore = decayedScore(date)

    SketchyScore(
      userId,
      kind,
      signals,
      newState,
      newScore,
      prob(newScore),
      lastSignaledAt,
      createdAt)
  }

  def update(signal: SketchySignal): SketchyScore = {
    val newState = if (signals < 2) 0 else decayedState(signal.createdAt) + 1
    val newScore = decayedScore(signal.createdAt) + signal.strength

    SketchyScore(
      userId,
      kind,
      signals + 1,
      newState,
      newScore,
      prob(newScore),
      signal.createdAt,
      createdAt)
  }

  // linearly decayed state
  def decayedState(date: Date): Int =
    scala.math.max(0, state - daysPassed(date).toInt / halfLife)

  // exponentially decayed score
  def decayedScore(date: Date): Double =
    score * scala.math.pow(2.0, -daysPassed(date) / halfLife)

  def prob(score: Double): Double = {
    val posterior = score + prior / (1.0 - prior)
    posterior / (1.0 + posterior)
  }

  def daysPassed(date: Date): Double =
    (date.getTime - lastSignaledAt.getTime) / (24 * 60 * 60 * 1000.0)
}

case class UserEventKey(kind: String, id: Long) {
  def marshalled: String = id.toString + ":" + kind
}

object UserEventKey {
  def unmarshal(key: String): UserEventKey = {
    key.split(':') match {
      case Array(id, kind) => UserEventKey(kind, id.toLong)
      case _ => throw new NullPointerException("UserEventKey parsing error")
    }
  }
}


/**
 * Transformation classes
 * Events that are the result of transformation on user action primitives
 *
 * EdgeChanges encode links, unlinks, relinks and backlinks between objects.
 * EdgeChanges can be applied to EdgeLike objects, and are currently used
 * only as input to the BurstAgent.
 *
 * @param sourceId source id of edge object
 * @param destId sink id of edge object
 * @param actionKind the actormessage kind, e.g. "Affiliation"
 * @param edgeType an EdgeChange.Type
 * @param createdAt the creation time of the event
 */
case class EdgeChange(
  sourceId: Long,
  sinkId: Long,
  ownerId: Option[Long],
  actionKind: String,
  edgeType: EdgeChange.Type,
  createdAt: Date) extends UserEvent {

  // Event level
  def id = None
  override def kind = actionKind

  // UserEvent level
  def senderId = ownerId
  def recipientId = Some(sinkId)

  // the edge change agent should filter out actions that shouldn't be checked
  def noSpamCheck = false
}

case object EdgeChange {
  sealed abstract class Type
  case object Link     extends Type with Action
  case object Relink   extends Type with Action
  case object Unlink   extends Type with Action
  case object Backlink extends Type with Action
}


/**
 * User action primitive classes
 *
 * These include the information encoded in a user action. There are two main
 * types: EdgeLike actions and MessageLike actions.
 */
abstract class AbstractMessageLike extends UserEvent with MessageLike
abstract class AbstractEdgeLike extends UserEvent with EdgeLike


abstract class AbstractAffiliation extends UserEvent with EdgeLike {
  val followeeId: Option[Long]
}

abstract class AbstractComment extends UserEvent with MessageLike {
  val body: Option[String]
}

abstract class AbstractFavoriting extends UserEvent with EdgeLike {
  val itemId: Option[Long]
  val itemKind: Option[String]
}

abstract class AbstractMessage extends UserEvent with MessageLike {
  val subject: Option[String]
  val body: Option[String]
}

abstract class AbstractPost extends UserEvent with MessageLike {
  val title: Option[String]
  val body: Option[String]
}

abstract class AbstractUser extends UserEvent with MessageLike {
  val username: Option[String]
  val permalink: Option[String]
}

package object readers {
  import com.soundcloud.sketchy.util.readers._
  implicit val userActionReader    = Json.reads[UserAction]
  implicit val sketchySignalReader = Json.reads[SketchySignal]
  implicit val spamReportReader    = Json.reads[SpamReport]
  implicit val sketchyItemReader   = Json.reads[SketchyItem]
  implicit val sketchyScoreReader  = Json.reads[SketchyScore]
  implicit val userEventKeyReader  = Json.reads[UserEventKey]
}


package object writers {
  import com.soundcloud.sketchy.util.writers._
  implicit val userActionWriter    = Json.writes[UserAction]
  implicit val sketchySignalWriter = Json.writes[SketchySignal]
  implicit val spamReportWriter    = Json.writes[SpamReport]
  implicit val sketchyItemWriter   = Json.writes[SketchyItem]
  implicit val sketchyScoreWriter  = Json.writes[SketchyScore]
  implicit val userEventKeyWriter  = Json.writes[UserEventKey]

  def serialize(e: Event): String = e match {
    case i: UserAction    => JSON.json(i)
    case i: SketchySignal => JSON.json(i)
    case i: SpamReport    => JSON.json(i)
    case i: SketchyItem   => JSON.json(i)
    case i: SketchyScore  => JSON.json(i)
    case i: UserEventKey  => JSON.json(i)
  }
}
