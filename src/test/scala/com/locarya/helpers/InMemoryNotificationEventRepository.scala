package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.NotificationEventRepository

final class InMemoryNotificationEventRepository[F[_]: Async] private (
  state: Ref[F, Map[NotificationEventId, NotificationEvent]]
) extends NotificationEventRepository[F]:

  def create(event: NotificationEvent): F[NotificationEvent] =
    state.modify { store =>
      if store.contains(event.id) then
        (store, new RuntimeException(s"NotificationEvent ${event.id.value} already exists").raiseError[F, NotificationEvent])
      else
        (store + (event.id -> event), event.pure[F])
    }.flatten

  def findById(id: NotificationEventId): F[Option[NotificationEvent]] =
    state.get.map(_.get(id))

  def update(event: NotificationEvent): F[NotificationEvent] =
    state.modify(store => (store + (event.id -> event)) -> event)

  def all: F[List[NotificationEvent]] =
    state.get.map(_.values.toList)

object InMemoryNotificationEventRepository:
  def make[F[_]: Async]: F[InMemoryNotificationEventRepository[F]] =
    Ref.of[F, Map[NotificationEventId, NotificationEvent]](Map.empty).map(new InMemoryNotificationEventRepository(_))
