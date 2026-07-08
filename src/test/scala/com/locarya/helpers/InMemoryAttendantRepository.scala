package com.locarya.helpers

import cats.effect.Async
import cats.effect.Ref
import cats.syntax.all.*
import com.locarya.domain.models.*
import com.locarya.domain.ports.AttendantRepository

final class InMemoryAttendantRepository[F[_]: Async] private (
  state:     Ref[F, Map[AttendantId, Attendant]],
  joinState: Ref[F, Map[(BookingId, BookingLineRef), Set[AttendantId]]]
) extends AttendantRepository[F]:

  def create(attendant: Attendant): F[Attendant] =
    state.modify { store =>
      if store.contains(attendant.id) then
        (store, new RuntimeException(s"Attendant ${attendant.id.value} already exists").raiseError[F, Attendant])
      else
        (store + (attendant.id -> attendant), attendant.pure[F])
    }.flatten

  def findById(id: AttendantId): F[Option[Attendant]] =
    state.get.map(_.get(id))

  def update(attendant: Attendant): F[Attendant] =
    state.modify(store => (store + (attendant.id -> attendant)) -> attendant)

  def findByProvider(providerId: ProviderId): F[List[Attendant]] =
    state.get.map(_.values.filter(_.providerId == providerId).toList)

  def findActiveByProvider(providerId: ProviderId): F[List[Attendant]] =
    state.get.map(_.values.filter(a => a.providerId == providerId && a.isActive).toList)

  def assignToBookingLine(bookingId: BookingId, lineRef: BookingLineRef, attendantId: AttendantId): F[Unit] =
    val key = (bookingId, lineRef)
    joinState.update(store =>
      store + (key -> (store.getOrElse(key, Set.empty) + attendantId))
    )

  def removeFromBookingLine(bookingId: BookingId, lineRef: BookingLineRef, attendantId: AttendantId): F[Unit] =
    val key = (bookingId, lineRef)
    joinState.update(store =>
      store + (key -> (store.getOrElse(key, Set.empty) - attendantId))
    )

  def findByBookingGrouped(bookingId: BookingId): F[Map[BookingLineRef, Set[AttendantId]]] =
    joinState.get.map { store =>
      store.collect {
        case ((bid, lineRef), ids) if bid == bookingId && ids.nonEmpty => lineRef -> ids
      }
    }

object InMemoryAttendantRepository:
  def make[F[_]: Async]: F[InMemoryAttendantRepository[F]] =
    for
      state     <- Ref.of[F, Map[AttendantId, Attendant]](Map.empty)
      joinState <- Ref.of[F, Map[(BookingId, BookingLineRef), Set[AttendantId]]](Map.empty)
    yield new InMemoryAttendantRepository(state, joinState)
