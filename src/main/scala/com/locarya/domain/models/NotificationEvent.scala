package com.locarya.domain.models

import java.time.Instant

enum NotificationEventStatus:
  case Pending, Processed, Failed

final case class NotificationEvent private (
  id:          NotificationEventId,
  eventType:   String,
  payload:     String,
  status:      NotificationEventStatus,
  retryCount:  Int,
  createdAt:   Instant,
  processedAt: Option[Instant]
)

object NotificationEvent:
  def create(
    id:        NotificationEventId,
    eventType: String,
    payload:   String,
    createdAt: Instant
  ): NotificationEvent =
    NotificationEvent(id, eventType, payload, NotificationEventStatus.Pending, 0, createdAt, None)

  def fromDb(
    id:          NotificationEventId,
    eventType:   String,
    payload:     String,
    status:      NotificationEventStatus,
    retryCount:  Int,
    createdAt:   Instant,
    processedAt: Option[Instant]
  ): NotificationEvent =
    NotificationEvent(id, eventType, payload, status, retryCount, createdAt, processedAt)
