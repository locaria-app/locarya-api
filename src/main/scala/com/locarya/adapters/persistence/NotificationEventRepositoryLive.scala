package com.locarya.adapters.persistence

import cats.effect.Async
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.NotificationEventRepository
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

final class NotificationEventRepositoryLive[F[_]: Async] private (xa: Transactor[F])
    extends NotificationEventRepository[F]:

  private case class EventRow(
    id:          UUID,
    eventType:   String,
    payload:     String,
    status:      String,
    retryCount:  Int,
    createdAt:   LocalDateTime,
    processedAt: Option[LocalDateTime]
  ) derives Read

  private val selectBase = fr"""
    SELECT id, event_type, payload, status, retry_count, created_at, processed_at
    FROM notification_events
  """

  private def decodeStatus(s: String): F[NotificationEventStatus] = s match
    case "pending"   => NotificationEventStatus.Pending.pure[F]
    case "processed" => NotificationEventStatus.Processed.pure[F]
    case "failed"    => NotificationEventStatus.Failed.pure[F]
    case other       => Async[F].raiseError(new RuntimeException(s"Unknown notification status: $other"))

  private def rowToEvent(row: EventRow): F[NotificationEvent] =
    for
      id          <- NotificationEventId.fromString(row.id.toString)
                       .fold(e => Async[F].raiseError(new RuntimeException(e.toString)), _.pure[F])
      status      <- decodeStatus(row.status)
      createdAt    = row.createdAt.toInstant(ZoneOffset.UTC)
      processedAt  = row.processedAt.map(_.toInstant(ZoneOffset.UTC))
    yield NotificationEvent.fromDb(id, row.eventType, row.payload, status, row.retryCount, createdAt, processedAt)

  private def encodeStatus(s: NotificationEventStatus): String = s match
    case NotificationEventStatus.Pending   => "pending"
    case NotificationEventStatus.Processed => "processed"
    case NotificationEventStatus.Failed    => "failed"

  override def findPending(limit: Int): F[List[NotificationEvent]] =
    (selectBase ++
      fr"WHERE status = 'pending' ORDER BY created_at ASC LIMIT $limit")
      .query[EventRow].to[List].transact(xa)
      .flatMap(_.traverse(rowToEvent))

  override def create(event: NotificationEvent): F[NotificationEvent] =
    val createdAtLdt = event.createdAt.atZone(ZoneOffset.UTC).toLocalDateTime
    val payloadFr    = fr"${event.payload}::jsonb"
    (fr"""INSERT INTO notification_events
            (id, event_type, payload, status, retry_count, created_at)
          VALUES (${UUID.fromString(event.id.value)}, ${event.eventType},""" ++
      payloadFr ++
      fr""", ${encodeStatus(event.status)}, ${event.retryCount}, $createdAtLdt)""")
      .update.run.transact(xa) >> event.pure[F]

  override def findById(id: NotificationEventId): F[Option[NotificationEvent]] =
    (selectBase ++ fr"WHERE id = ${UUID.fromString(id.value)}")
      .query[EventRow].option.transact(xa)
      .flatMap(_.traverse(rowToEvent))

  override def update(event: NotificationEvent): F[NotificationEvent] =
    val processedAtLdt = event.processedAt.map(_.atZone(ZoneOffset.UTC).toLocalDateTime)
    sql"""UPDATE notification_events SET
            status       = ${encodeStatus(event.status)},
            retry_count  = ${event.retryCount},
            processed_at = $processedAtLdt
          WHERE id = ${UUID.fromString(event.id.value)}"""
      .update.run.transact(xa) >> event.pure[F]

object NotificationEventRepositoryLive:
  def make[F[_]: Async](xa: Transactor[F]): NotificationEventRepository[F] =
    new NotificationEventRepositoryLive[F](xa)
