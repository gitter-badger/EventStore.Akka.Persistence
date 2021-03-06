package akka.persistence.eventstore.snapshot

import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{ SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import akka.persistence.eventstore.Helpers._
import akka.persistence.eventstore.{ UrlEncoder, EventStorePlugin }
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.Config
import eventstore._
import eventstore.ReadDirection.Backward

class EventStoreSnapshotStore extends SnapshotStore with EventStorePlugin {
  import EventStoreSnapshotStore._
  import EventStoreSnapshotStore.SnapshotEvent._
  import context.dispatcher

  val config: Config = context.system.settings.config.getConfig("eventstore.persistence.snapshot-store")
  val deleteAwait: FiniteDuration = config.getDuration("delete-await", TimeUnit.MILLISECONDS).millis
  val readBatchSize: Int = config.getInt("read-batch-size")

  def loadAsync(persistenceId: PersistenceId, criteria: SnapshotSelectionCriteria) = async {
    import Selection._
    def fold(deletes: Deletes, event: Event): Selection = {
      val eventType = event.data.eventType
      ClassMap.get(eventType) match {
        case None =>
          logNoClassFoundFor(eventType)
          deletes

        case Some(SnapshotClass) =>
          val metadata = deserialize(event.data.metadata, classOf[SnapshotMetadata])

          val seqNr = metadata.sequenceNr
          val timestamp = metadata.timestamp

          val deleted = seqNr <= deletes.minSequenceNr ||
            timestamp <= deletes.minTimestamp ||
            (deletes.deleted contains seqNr)

          val acceptable = seqNr <= criteria.maxSequenceNr && timestamp <= criteria.maxTimestamp

          if (deleted || !acceptable) deletes
          else {
            val snapshot = deserialize(event.data.data, SnapshotClass)
            Selected(SelectedSnapshot(metadata, snapshot.data))
          }

        case Some(clazz) => deserialize(event.data.data, clazz) match {
          case Snapshot(_)      => deletes // should not happen
          case Delete(seqNr, _) => deletes.copy(deleted = deletes.deleted + seqNr)
          case DeleteCriteria(maxSeqNr, maxTimestamp) => deletes.copy(
            minSequenceNr = math.max(deletes.minSequenceNr, maxSeqNr),
            minTimestamp = math.max(deletes.minTimestamp, maxTimestamp))
        }
      }
    }

    val streamId = eventStream(persistenceId)
    val req = ReadStreamEvents(streamId, EventNumber.Last, maxCount = readBatchSize, direction = Backward)
    connection.foldLeft(req, Empty) {
      case (deletes: Deletes, event) => fold(deletes, event)
    }.map(_.selected)
  }

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any) = asyncUnit {
    val streamId = eventStream(metadata.persistenceId)
    connection.future(WriteEvents(streamId, List(eventData(metadata, snapshot))))
  }

  def saved(metadata: SnapshotMetadata) = {}

  def delete(metadata: SnapshotMetadata) = {
    delete(metadata.persistenceId, Delete(metadata.sequenceNr, timestamp = metadata.timestamp))
  }

  def delete(persistenceId: PersistenceId, criteria: SnapshotSelectionCriteria) = {
    delete(persistenceId, SnapshotEvent.DeleteCriteria(
      maxSequenceNr = criteria.maxSequenceNr,
      maxTimestamp = criteria.maxTimestamp))
  }

  def eventData(metadata: SnapshotMetadata, snapshot: Any): EventData = EventData(
    eventType = EventTypeMap(SnapshotClass),
    data = serialize(Snapshot(snapshot)),
    metadata = serialize(metadata))

  def eventData(x: SnapshotEvent): EventData = EventData(
    eventType = EventTypeMap(x.getClass),
    data = serialize(x))

  def eventStream(x: PersistenceId): EventStream.Id = EventStream.Id(UrlEncoder(x) + "-snapshots")

  def delete(persistenceId: PersistenceId, se: DeleteEvent): Unit = {
    val streamId = eventStream(persistenceId)
    val future = connection.future(WriteEvents(streamId, List(eventData(se))))
    Await.result(future, deleteAwait)
  }
}

object EventStoreSnapshotStore {
  sealed trait SnapshotEvent

  object SnapshotEvent {
    val SnapshotClass: Class[Snapshot] = classOf[Snapshot]
    val ClassMap: Map[String, Class[_ <: SnapshotEvent]] = Map(
      "snapshot" -> SnapshotClass,
      "delete" -> classOf[Delete],
      "deleteCriteria" -> classOf[DeleteCriteria])

    val EventTypeMap: Map[Class[_ <: SnapshotEvent], String] = ClassMap.map(_.swap)

    @SerialVersionUID(0)
    case class Snapshot(data: Any) extends SnapshotEvent

    sealed trait DeleteEvent extends SnapshotEvent

    @SerialVersionUID(0)
    case class Delete(sequenceNr: SequenceNr, timestamp: Timestamp) extends DeleteEvent

    @SerialVersionUID(0)
    case class DeleteCriteria(maxSequenceNr: SequenceNr, maxTimestamp: Timestamp) extends DeleteEvent
  }

  sealed trait Selection {
    def selected: Option[SelectedSnapshot]
  }

  object Selection {
    val Empty: Selection = Deletes(Set.empty, -1L, -1L)

    case class Deletes(
        deleted: Set[SequenceNr],
        minSequenceNr: SequenceNr,
        minTimestamp: Timestamp) extends Selection {
      def selected = None
    }

    case class Selected(value: SelectedSnapshot) extends Selection {
      def selected = Some(value)
    }
  }
}